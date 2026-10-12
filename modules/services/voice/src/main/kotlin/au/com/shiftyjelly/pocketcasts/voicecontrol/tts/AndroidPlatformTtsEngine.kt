package au.com.shiftyjelly.pocketcasts.voicecontrol.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconAudio
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import kotlin.concurrent.withLock
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Speaks through the platform TTS engine, then plays the synthesized audio through an AudioTrack
 * this app owns so the samples submitted for playback can be recorded into the shared echo
 * reference.
 *
 * `TextToSpeech.speak` renders internally and exposes no PCM, which is why this uses
 * `synthesizeToFile` and plays the result itself. Everything the previous implementation guaranteed
 * is preserved: language selection with the same fallback, one utterance at a time
 * (QUEUE_FLUSH), completion on done or error, and cancellation that stops playback immediately.
 */
class AndroidPlatformTtsEngine @Inject constructor(
    @ApplicationContext context: Context,
    private val playbackBufferRecorder: PlaybackBufferRecorder,
    // Nullable with a default so the engine can be built without Hilt (the unit tests do), and
    // because a nullable function type is not a binding Dagger can match: production supplies the
    // engine through VoiceControlModule's @Provides, which passes a real factory explicitly.
    private val synthesizerFactory: ((onInitialized: (Boolean) -> Unit) -> TextToSpeech)? = null,
) : TtsEngine {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var initialized = false
    private var released = false

    /**
     * How long to wait for queued audio to drain before reporting an incomplete playback. Exposed
     * for tests that must force the timeout; the default is the production bound.
     */
    var drainWaitMs: Long = MAX_DRAIN_WAIT_MS

    /**
     * Queues [text] for synthesis to [file], returning the platform's status code. Defaults to the
     * real engine; a test can substitute one that never completes so the cancellation path is
     * exercised deterministically rather than inferred from a code reading.
     */
    var synthesizeToFile: ((engine: TextToSpeech, text: String, file: java.io.File, utteranceId: String) -> Int)? =
        null

    /** Set while a synthesized file is being played, so cancellation can stop it. */
    @Volatile
    private var activeTrack: AudioTrack? = null

    /**
     * The sink write, injectable so a test can present a refusal.
     *
     * A refused `AudioTrack.write` needs a real failing track, which a unit test cannot construct, and
     * the truncation it causes is otherwise unreachable -- the same reason [CloudAudioPlayer] exposes
     * its own write for the cloud path. Defaults to the real `AudioTrack.write`.
     */
    internal var writeToSink: ((track: AudioTrack, samples: ShortArray, offset: Int, count: Int) -> Int)? = null

    /** Frames submitted by the last utterance and how far playback had advanced when it returned. */
    @Volatile
    private var framesWritten = 0

    /** True while a synthesized utterance is being PLAYED (set once the sink has been fed). */
    @Volatile
    private var playbackActive = false

    /**
     * Which PLAYBACK INSTANCE the shared [playbackActive] flag describes, or null when none.
     *
     * Ownership is by instance, not by synthesis generation: a superseded utterance that never wrote
     * anything has no playback to report, and an old teardown must not clear a real successor's flag.
     * The generation says whose ENGINE claim is current; this says whose AUDIO is playing, and those
     * can differ during a handover.
     */
    private val playbackLock = Any()

    private var playbackOwner: Any? = null

    @Volatile
    private var framesPlayedAtReturn = 0

    /** Set when an utterance returned with queued audio still unplayed (drain timed out). */
    @Volatile
    private var playbackIncomplete = false

    /** Counts entries into speak(), so a caller can prove a failure does not replay the turn. */
    @Volatile
    private var speakCount = 0

    /**
     * Identifies the current synthesis, so a cancellation handler can tell whether a successor has
     * since started on the shared engine. Incremented before each synthesis call; read from the
     * handler, which may run after the next utterance has begun.
     */
    @Volatile
    private var synthesisGeneration = 0

    /**
     * Serialises the cancellation handler against a successor's startup.
     *
     * The handler is a check-then-stop on the shared generation, so without a shared lock a
     * successor can claim the engine between the check and the stop -- the same read-then-act window
     * that let a released claim publish in the route observer.
     */
    private val synthesisLock = java.util.concurrent.locks.ReentrantLock()

    /** Claims the synthesis slot under the boundary, as a starting utterance does. */
    internal fun claimSynthesisForTest() {
        synthesisLock.withLock { synthesisGeneration += 1 }
    }

    /** Runs [block] while holding the synthesis boundary, so a test can arrange the interleaving. */
    internal fun runSerialisedForTest(block: () -> Unit) {
        synthesisLock.lock()
        try {
            block()
        } finally {
            synthesisLock.unlock()
        }
    }

    init {
        val factory = synthesizerFactory
        tts = if (factory != null) {
            factory { ok ->
                initialized = ok
                if (!ok) Timber.w("TTS engine initialization failed")
            }
        } else {
            TextToSpeech(appContext) { status ->
                initialized = (status == TextToSpeech.SUCCESS)
                if (!initialized) {
                    Timber.w("TTS engine initialization failed with status $status")
                }
            }
        }
    }

    override suspend fun warmUp(language: String) {
        withContext(Dispatchers.Main) {
            val locale = localeForLanguageTag(language)
            tts?.language = locale
        }
        val start = System.currentTimeMillis()
        while (!initialized && (System.currentTimeMillis() - start) < 5000) {
            delay(50)
        }
    }

    override suspend fun speak(text: String, language: String) {
        if (released || tts == null || !initialized) return
        speakCount++
        val engine = tts ?: return
        val locale = localeForLanguageTag(language)
        withContext(Dispatchers.Main) {
            val result = engine.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                Timber.w("TTS language $language not available, falling back to default")
                engine.language = Locale.getDefault()
            }
        }

        val wav = File(appContext.cacheDir, "voice_tts_${System.currentTimeMillis()}.wav")
        try {
            // The ownership claim made at synthesis, carried into playback: playback must never
            // re-read the global generation, or an utterance whose successor claimed during synthesis
            // would adopt the successor's generation and believe itself the owner.
            var ownershipToken = -1
            val synthesized = suspendCancellableCoroutine { continuation ->
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onDone(utteranceId: String?) {
                        continuation.resume(true)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Timber.w("TTS synthesis error for utteranceId=$utteranceId")
                        continuation.resume(false)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStart(utteranceId: String?) {}
                })

                val utteranceId = System.currentTimeMillis().toString()
                // Cancellation must stop the queued synthesis, but only while this synthesis is
                // still the current one. This handler is registered before the call and runs
                // asynchronously, so it can execute after a successor has already started on the
                // shared engine -- stopping then silences the successor rather than this utterance.
                // Claim and stop under the same boundary a successor claims under, so a successor
                // cannot start between the ownership check and the stop.
                // The token lives beyond this block: it is carried into playback as the utterance's
                // ownership claim.
                ownershipToken = synthesisLock.withLock { ++synthesisGeneration }
                val mine = ownershipToken
                continuation.invokeOnCancellation {
                    synthesisLock.withLock {
                        if (synthesisGeneration == mine) {
                            engine.stop()
                        }
                    }
                }
                // QUEUE_FLUSH preserves the previous single-utterance behaviour: a superseded reply
                // must not keep talking.
                val queued = synthesizeToFile?.invoke(engine, text, wav, utteranceId)
                    ?: engine.synthesizeToFile(text, null, wav, utteranceId)
                if (queued != TextToSpeech.SUCCESS) {
                    Timber.w("TTS synthesizeToFile failed with $queued")
                    continuation.resume(false)
                }
                if (!continuation.isActive) {
                    // A cancellation landed between registering the listener and the call, so stop
                    // the queued synthesis rather than leaving it orphaned.
                    engine.stop()
                }
            }
            if (!synthesized) return
            // The token is the one THIS utterance claimed at synthesis -- carried forward, never
            // re-read. Re-reading at playback start would let an utterance whose successor claimed
            // the engine during synthesis ADOPT the successor's generation and believe itself the
            // owner.
            playSynthesized(wav, ownershipToken = ownershipToken)
        } finally {
            wav.delete()
        }
    }

    /**
     * Plays the synthesized WAV through an owned AudioTrack and records the accepted prefix into
     * the echo reference, so the answer is part of what the filter correlates against.
     */
    private suspend fun playSynthesized(wav: File, ownershipToken: Int) = withContext(Dispatchers.IO) {
        // Cancelling the caller must stop playback immediately: the render job is cancelled before
        // every response, and an answer that keeps talking is indistinguishable from a foreign app
        // to our own gate.
        val playback = coroutineContext[Job]
        // The token claimed at SYNTHESIS, not a fresh read: a successor that claimed the engine
        // between synthesis and playback must leave this utterance holding the OLDER token, so every
        // guard below correctly sees it as superseded.
        val mine = ownershipToken
        // The write loop checks the generation BETWEEN writes (supersession) and a watcher inside
        // [playSynthesizedInternal] observes the job's cancelling transition (cancellation); both own
        // only THIS utterance's track, which is why the watcher lives there, where the track exists.
        playbackGenerationForTest = mine
        playSynthesizedInternal(wav, mine) { synthesisLock.withLock { synthesisGeneration != mine } }
    }

    private suspend fun playSynthesizedInternal(
        wav: File,
        mine: Int,
        superseded: () -> Boolean,
    ) = withContext(Dispatchers.IO) {
        playbackIncomplete = false
        val clip = runCatching {
            wav.inputStream().use { EarconAudio.decodeWavToMono(it) }
        }.onFailure { Timber.w(it, "TTS: failed to decode synthesized audio") }.getOrNull() ?: return@withContext

        val track = buildTrack(clip.sampleRateHz) ?: run {
            Timber.w("TTS: could not create AudioTrack")
            return@withContext
        }
        // Published under the SAME lock that compares-and-clears it: a locked cleanup cannot protect
        // against an unlocked successor write, so the publication and the clear must share one
        // boundary or the successor can be published into a slot an older cleanup is about to null.
        synthesisLock.withLock { activeTrack = track }
        // Cancellation watcher, owning THIS utterance's LOCAL track. `isActive` flips the moment the
        // job is cancelled -- a write blocked inside withContext(IO) cannot reach the loop's own
        // check, so the stop must be initiated from outside the blocked call. The generation guard
        // protects a successor: a cancelled old utterance whose generation has been superseded must
        // not stop the successor's track or engine. The shared `activeTrack` field is touched ONLY
        // through [clearOwnedTrack], which clears it if -- and only if -- it still points at this
        // utterance's instance.
        val playbackJob = coroutineContext[Job]
        val stopperScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val watcher = if (playbackJob != null) {
            stopperScope.launch {
                while (playbackJob.isActive) kotlinx.coroutines.delay(10L)
                if (playbackJob.isCancelled) {
                    // The LOCAL track is this utterance's own, so cancellation stops it
                    // UNCONDITIONALLY -- guarding this on the generation would leave the old,
                    // still-blocked track playing whenever a successor has already claimed
                    // ownership, which is the case cancellation most needs to interrupt.
                    track.runCatching { stop() }
                    ownedTrackStopsForTest += 1
                    clearOwnedTrack(track)
                    // The SHARED engine is stopped only while this utterance still owns it, and the
                    // comparison happens INSIDE the same lock that stops it: checking first and
                    // stopping after would let a successor claim the engine in between and be
                    // silenced by the old utterance's cleanup.
                    synthesisLock.withLock {
                        if (synthesisGeneration == mine) {
                            tts?.runCatching { stop() }
                        }
                    }
                }
            }
        } else {
            null
        }
        try {
            track.play()
            // This instance owns the flag from here: it has audio actually playing. A superseded
            // utterance that returns before this point never claims the flag at all.
            synchronized(playbackLock) {
                playbackOwner = this
                playbackActive = true
            }
            lastOwnedTrack = track
            var offset = 0
            var writeRefused = false
            while (offset < clip.samples.size) {
                // Supersession check per iteration: when a newer reply has claimed the engine, this
                // one stops HERE -- at the top of the loop and, through the seam's own unblocking,
                // promptly after a blocked write returns. Without this the old reply's remaining
                // writes continue under the successor.
                if (superseded()) {
                    // Stop THIS utterance's track, never the shared field: a successor may have
                    // replaced `activeTrack` already, and stopping the field would silence it. The
                    // shared engine is guarded by the generation for the same reason.
                    track.runCatching { stop() }
                    ownedTrackStopsForTest += 1
                    clearOwnedTrack(track)
                    // Same in-lock comparison as the watcher: the check and the stop are one
                    // critical section, so a successor cannot claim between them.
                    synthesisLock.withLock {
                        if (synthesisGeneration == mine) {
                            tts?.runCatching { stop() }
                        }
                    }
                    return@withContext
                }
                val writer = writeToSink
                val written = if (writer != null) {
                    writer(track, clip.samples, offset, clip.samples.size - offset)
                } else {
                    track.write(clip.samples, offset, clip.samples.size - offset)
                }
                if (written <= 0) {
                    // A refused write means the answer is TRUNCATED, not finished. Leaving it as a
                    // silent break made it indistinguishable from a clean end: `offset` stays at the
                    // count the sink accepted, the drain loop below then waits only for frames that
                    // have already played, `speak()` returns normally and nothing reaches
                    // onPlaybackFailure -- so the user hears a cut-off reply and sees no error.
                    Timber.w("TTS: AudioTrack.write returned $written")
                    writeRefused = true
                    break
                }
                recordAccepted(
                    samples = clip.samples,
                    sampleRateHz = clip.sampleRateHz,
                    startSample = offset,
                    count = written,
                )
                offset += written
            }
            // Let the buffered audio drain before the caller treats the utterance as finished. The
            // outcome is recorded so a test can assert it happened, rather than inferring it from
            // elapsed time — synthesis, startup and waiting can all consume time without draining.
            var waited = 0L
            while (track.playbackHeadPosition < offset && waited < drainWaitMs) {
                delay(10)
                waited += 10
            }
            framesWritten = offset
            framesPlayedAtReturn = track.playbackHeadPosition
            // Surface the truncation through the same channel the cloud path uses, so a caller can
            // avoid reporting a completed answer that was actually cut short.
            if (writeRefused) {
                throw TtsPlaybackIncompleteException(
                    framesWritten = framesWritten,
                    framesPlayed = framesPlayedAtReturn,
                )
            }
            // A bounded wait that expires is an EXPLICIT incomplete-playback outcome, not a
            // successful completion: the caller must not be told the answer finished when queued
            // audio is still outstanding.
            if (framesPlayedAtReturn < framesWritten) {
                playbackIncomplete = true
                // Throw rather than only warn: a diagnostic flag cannot stop a caller reporting
                // success, and this outcome must reach the failure path.
                throw TtsPlaybackIncompleteException(
                    framesWritten = framesWritten,
                    framesPlayed = framesPlayedAtReturn,
                )
            }
        } finally {
            watcher?.cancel()
            stopperScope.cancel()
            // Clear the shared flag only if THIS playback instance owns it: an old render exiting
            // while a successor is audibly playing must not report that nothing is playing, and a
            // superseded utterance that never reached playback never claimed the flag to begin with.
            // Claim and clear share ONE lock: checking ownership outside the boundary that clears
            // it lets a successor claim the flag in between and be cleared by the old utterance's
            // cleanup. The comparison and the clear are one critical section.
            synchronized(playbackLock) {
                if (playbackOwner === this) {
                    playbackActive = false
                    playbackOwner = null
                }
            }
            // Clear the shared field only if it still points at THIS utterance's track: a successor
            // may have replaced it, and nulling the field would orphan the successor's stop path.
            clearOwnedTrack(track)
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    /** Clears [activeTrack] only when it still refers to [track] -- this utterance's own instance. */
    private fun clearOwnedTrack(track: AudioTrack) {
        synthesisLock.withLock {
            if (activeTrack === track) {
                activeTrack = null
            }
        }
    }

    /**
     * How many times an utterance has stopped its OWN track. Test visibility for the ownership
     * contract: the local stop must not be guarded by ownership, so a case can observe it happening
     * after a successor has claimed the engine.
     */
    @Volatile
    var ownedTrackStopsForTest: Int = 0
        private set

    /**
     * The playback state of the most recently owned track, or null when none was created. Test
     * visibility so a case can measure the STOP directly rather than trusting a counter that runs
     * after a best-effort call.
     */
    fun ownedTrackPlaybackStateForTest(): Int? = lastOwnedTrack?.playState

    @Volatile
    private var lastOwnedTrack: AudioTrack? = null

    /** True while a synthesized utterance is playing, so a caller can observe playback in progress. */
    fun isPlayingSynthesizedAudio(): Boolean = synchronized(playbackLock) { playbackActive }

    /** Frames submitted to the sink by the last utterance, or 0 if none played. */
    fun framesWritten(): Int = framesWritten

    /** Playback position at the moment the last utterance completed. */
    fun framesPlayedAtReturn(): Int = framesPlayedAtReturn

    /** True when the last utterance returned with queued audio still unplayed. */
    fun wasPlaybackIncomplete(): Boolean = playbackIncomplete

    /**
     * True when the last utterance actually finished playing. A refusal or an expired wait must leave
     * this false so nothing downstream can read an incomplete reply as a completed one.
     */
    fun wasPlaybackDrained(): Boolean = framesWritten > 0 && framesPlayedAtReturn >= framesWritten

    /** How many times speak() has been entered, so a test can assert a failure does not re-run it. */
    fun speakInvocations(): Int = speakCount

    /**
     * The generation active when the most recent playback loop started, exposed so the blocked-write
     * regression can observe the same comparison the loop makes.
     */
    @Volatile
    var playbackGenerationForTest: Int = -1
        private set

    /** The engine's current ownership generation. Test visibility; see [playbackGenerationForTest]. */
    fun currentGenerationForTest(): Int = synthesisLock.withLock { synthesisGeneration }

    private fun recordAccepted(
        samples: ShortArray,
        sampleRateHz: Int,
        startSample: Int,
        count: Int,
    ) {
        if (count <= 0) return
        val forReference = EarconAudio.resampleRange(
            samples = samples,
            fromRate = sampleRateHz,
            toRate = REFERENCE_RATE_HZ,
            startSample = startSample,
            count = count,
        )
        playbackBufferRecorder.write(FloatArray(forReference.size) { forReference[it] / 32768f })
    }

    private fun buildTrack(sampleRateHz: Int): AudioTrack? = runCatching {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuffer.coerceAtLeast(sampleRateHz * 2))
            .build()
    }.getOrNull()

    override fun release() {
        released = true
        // Take the slot and empty it in ONE critical section, then act on the instance taken: reading
        // the slot, stopping through it and nulling it separately leaves two windows in which a
        // successor's publication is either acted on by this release or discarded by its clear.
        val owned = synthesisLock.withLock {
            val current = activeTrack
            activeTrack = null
            current
        }
        owned?.runCatching {
            stop()
            release()
        }
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun localeForLanguageTag(tag: String): Locale = Locale.forLanguageTag(tag)

    private companion object {
        /** The rate the shared echo reference and the correlator work at. */
        const val REFERENCE_RATE_HZ = 16_000

        /** Upper bound on waiting for queued audio to drain, so a stuck sink cannot hang speak(). */
        const val MAX_DRAIN_WAIT_MS = 10_000L
    }
}
