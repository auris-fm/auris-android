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
import kotlinx.coroutines.ensureActive
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
     * Bound on the synthesis wait (reviewer 4356632247). An engine that ACCEPTS the request but
     * never delivers onDone/onError/onStop (engine process death, an OEM engine dropping
     * callbacks) used to leave speak() suspended forever while speakWithHeartbeat kept reporting
     * emission -- suppressing every later utterance for the rest of the session. Overridable for
     * tests, like [drainWaitMs].
     */
    var synthesisWaitMs: Long = MAX_SYNTHESIS_WAIT_MS

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

    override suspend fun speak(text: String, language: String): Boolean {
        if (released || tts == null || !initialized) return false
        speakCount++
        val engine = tts ?: return false
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
            // The utterance id is UNIQUE PER SYNTHESIS (finding 4236364898): a millisecond-clock id can
            // collide, and every callback below must be attributable to exactly one utterance on this
            // shared engine.
            val utteranceId = "voice-tts-" + UTTERANCE_ID_SEQ.incrementAndGet()
            var ownershipToken = -1
            // BOUNDED WAIT (reviewer 4356632247): a SUCCESS-queued utterance whose callbacks never
            // arrive must fail the utterance rather than suspend speak() forever. Timing out
            // cancels the suspension, whose invokeOnCancellation stops the engine if THIS utterance
            // still owns it -- the same cleanup a successor or an explicit cancel would run.
            val synthesized = try {
                kotlinx.coroutines.withTimeout(synthesisWaitMs) {
                    suspendCancellableCoroutine { continuation ->
                        // ONE-SHOT, ID-MATCHED completion (finding 4236364898). This listener is SHARED: the
                        // next speak() replaces it, so any LATE callback from THIS utterance is delivered into
                        // the SUCCESSOR's listener instance. Two guards follow.
                        // (1) The id check: a late callback carrying this utterance's id is ignored by the
                        // successor's listener, and vice versa -- a callback can only complete its OWN
                        // utterance. (2) The one-shot flag: an immediate refusal and a late callback (or two
                        // late callbacks) can both arrive at the same listener; only the first resumption wins,
                        // and the second is a no-op instead of an IllegalStateException out of a binder thread.
                        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
                        fun completeOnce(value: Boolean) {
                            if (completed.compareAndSet(false, true)) {
                                continuation.resume(value)
                            }
                        }
                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            @Deprecated("Deprecated in Java")
                            override fun onDone(id: String?) {
                                if (id == utteranceId) completeOnce(true)
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onError(id: String?) {
                                if (id == utteranceId) {
                                    Timber.w("TTS synthesis error for utteranceId=$utteranceId")
                                    completeOnce(false)
                                }
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onStart(id: String?) {}
                        })
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
                            // ONE-SHOT: the refusal is this utterance's first completion; the flag makes any
                            // late platform callback for the same id a no-op instead of a double resume.
                            completeOnce(false)
                        }
                        if (!continuation.isActive) {
                            // A cancellation landed between registering the listener and the call, so stop
                            // the queued synthesis rather than leaving it orphaned.
                            engine.stop()
                        }
                    }
                }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                Timber.w("TTS synthesis produced no callback within %d ms; failing the utterance", synthesisWaitMs)
                false
            }
            if (!synthesized) return false
            // The token is the one THIS utterance claimed at synthesis -- carried forward, never
            // re-read. Re-reading at playback start would let an utterance whose successor claimed
            // the engine during synthesis ADOPT the successor's generation and believe itself the
            // owner.
            playSynthesized(wav, ownershipToken = ownershipToken)
            return true
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
        // TRUTHFUL PLAYBACK OUTCOME (reviewer 486205ae): speak() reports delivery to a caller that
        // releases output holds on it, so "synthesized a file but played nothing" must NOT be a
        // silent success. Decode failure, track-creation failure, and a zero-sample clip all throw
        // the typed incomplete exception the renderer already treats as a drop/failure boundary.
        val clip = runCatching {
            wav.inputStream().use { EarconAudio.decodeWavToMono(it) }
        }.onFailure { Timber.w(it, "TTS: failed to decode synthesized audio") }.getOrNull()
            ?: throw TtsPlaybackIncompleteException(framesWritten = 0, framesPlayed = 0)
        if (clip.samples.isEmpty()) throw TtsPlaybackIncompleteException(framesWritten = 0, framesPlayed = 0)

        val track = buildTrack(clip.sampleRateHz) ?: run {
            Timber.w("TTS: could not create AudioTrack")
            throw TtsPlaybackIncompleteException(framesWritten = 0, framesPlayed = 0)
        }
        // Published under the SAME lock that compares-and-clears it: a locked cleanup cannot protect
        // against an unlocked successor write, so the publication and the clear must share one
        // boundary or the successor can be published into a slot an older cleanup is about to null.
        // The utterance's recorder ADMISSION is minted with its track and closed when the track is
        // released -- an owned producer identity, never a null write (@spec).
        val utteranceAdmission = playbackBufferRecorder.openAdmission(TTS_PRODUCER_BASE + mine)
        currentUtteranceAdmission = utteranceAdmission
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
                    clearOwnedTrack(track, utteranceAdmission)
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
                    clearOwnedTrack(track, utteranceAdmission)
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
                    // THE IMMUTABLE ADMISSION CAPTURED WITH THIS UTTERANCE'S LOCAL TRACK (@reviewer):
                    // reading the shared field here would attribute old audio to a successor that has
                    // since published its own -- the write must carry the identity of the stream it is.
                    admission = utteranceAdmission,
                )
                offset += written
            }
            // Let the buffered audio drain before the caller treats the utterance as finished. The
            // outcome is recorded so a test can assert it happened, rather than inferring it from
            // elapsed time — synthesis, startup and waiting can all consume time without draining.
            var waited = 0L
            // The drain observes the LOCAL track through runCatching: engine-level release() STOPS
            // this track from another thread (@reviewer), and a stopped-or-released instance may
            // refuse position reads. A refused read ends the wait as an incomplete outcome below --
            // it never becomes evidence that audio played, and it cannot crash the shutdown path.
            var positionBroken = false
            fun headPosition(): Int = track.runCatching { playbackHeadPosition }.getOrElse {
                positionBroken = true
                offset // end the wait; the shortfall decision uses framesPlayedAtReturn below
            }
            while (headPosition() < offset && waited < drainWaitMs && !positionBroken) {
                delay(10)
                waited += 10
            }
            framesWritten = offset
            // Read FIRST, then consult the flag (@reviewer ca0a39554 review): checking positionBroken
            // before this final read lets a THROWING final read return headPosition()'s fallback and
            // be mistaken for a fully drained utterance. Every failed read -- including this one --
            // must produce the explicit incomplete outcome below.
            val finalPosition = headPosition()
            framesPlayedAtReturn = if (positionBroken) 0 else finalPosition
            // Surface the truncation through the same channel the cloud path uses, so a caller can
            // avoid reporting a completed answer that was actually cut short.
            if (writeRefused) {
                // A caller that already cancelled this utterance has lost it either way: rethrowing
                // the cancellation (not the incomplete failure) keeps the outcome honest for live
                // callers while a cancelled job surfaces as cancellation, not stray noise.
                coroutineContext.ensureActive()
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
                // Same rule as the refused write above: a cancelled caller reports no outcome.
                coroutineContext.ensureActive()
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
            clearOwnedTrack(track, utteranceAdmission)
            runCatching { track.stop() }
            runCatching { track.release() }
            ownedTrackReleasesForTest += 1
        }
    }

    /**
     * Clears [activeTrack] only when it still refers to [track] -- this utterance's own instance --
     * but ALWAYS retires [admission], the immutable admission captured with this utterance's local
     * track (@reviewer/@spec). Ownership of the shared slot may have passed to a successor; the
     * ENDED stream is still this one's, and leaving its admission open would keep its tail matchable
     * with no owner left to close it. Clearing the shared field stays ownership-guarded.
     */
    private fun clearOwnedTrack(track: AudioTrack, admission: au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder.Admission) {
        synthesisLock.withLock {
            if (activeTrack === track) {
                activeTrack = null
                if (currentUtteranceAdmission === admission) currentUtteranceAdmission = null
            }
            playbackBufferRecorder.retireProducer(admission)
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
     * How many utterances RELEASED their own local track (in the utterance's finally). Test
     * visibility for the release-ownership contract (@reviewer 4236364948): releasing the native
     * object belongs to the owner whose loops read it; engine-level release() only silences.
     */
    @Volatile
    var ownedTrackReleasesForTest: Int = 0
        private set

    /** How many times engine-level release() silenced the currently published track. */
    @Volatile
    var silencesByEngineReleaseForTest: Int = 0
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

    @Volatile
    private var currentUtteranceAdmission: au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder.Admission? = null

    private fun recordAccepted(
        samples: ShortArray,
        sampleRateHz: Int,
        startSample: Int,
        count: Int,
        admission: au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder.Admission,
    ) {
        if (count <= 0) return
        val forReference = EarconAudio.resampleRange(
            samples = samples,
            fromRate = sampleRateHz,
            toRate = REFERENCE_RATE_HZ,
            startSample = startSample,
            count = count,
        )
        // OWNED like every other reference writer (@spec): the caller passes the admission minted with
        // THIS utterance's track, so its retained tail gets a deadline and stale callbacks cannot
        // re-enter -- and a held old write can never append under a successor's ticket either.
        playbackBufferRecorder.write(
            FloatArray(forReference.size) { forReference[it] / 32768f },
            admission = admission,
        )
    }

    /**
     * Optional track-construction override for controls (@reviewer ca0a39554 review): a test can
     * supply an AudioTrack subclass that records release() and refuses position reads afterwards,
     * making premature engine-level release observable at the instance boundary. Null uses the real
     * platform construction below. This is a construction seam only -- production behavior is the
     * same code path with the default null.
     */
    internal var trackFactoryForTest: ((Int) -> AudioTrack?)? = null

    // An installed factory OVERRIDES track creation outright: a null it returns IS the failure
    // (reviewer b0a21f8d), never a reason to fall through to the real constructor.
    private fun buildTrack(sampleRateHz: Int): AudioTrack? {
        val factory = trackFactoryForTest
        // The null-check is on the FACTORY's presence, not its result: an installed factory's null
        // return is the failure itself and must not select the platform fallback.
        return if (factory != null) factory.invoke(sampleRateHz) else createPlatformTrack(sampleRateHz)
    }

    private fun createPlatformTrack(sampleRateHz: Int): AudioTrack? = runCatching {
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
        // STOP ONLY (@reviewer 4236364948): this method runs while the utterance coroutine may be
        // blocked in track.write or reading playbackHeadPosition on another thread. Releasing the
        // AudioTrack here frees the native object underneath those reads -- playbackHeadPosition on
        // a released track throws IllegalStateException, turning a clean shutdown into a failure.
        // Ownership of the track's RELEASE stays with the local track's own cleanup: the utterance's
        // finally releases exactly the instance it created, after its loops have exited. Stopping is
        // enough to silence immediately; releasing can wait for the owner.
        owned?.runCatching {
            stop()
            silencesByEngineReleaseForTest += 1
        }
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun localeForLanguageTag(tag: String): Locale = Locale.forLanguageTag(tag)

    private companion object {
        // Producer-id namespace for TTS utterances, disjoint from cloud answers and earcons.
        private const val TTS_PRODUCER_BASE: Long = Long.MIN_VALUE / 8

        /** Source of unique per-synthesis utterance ids (finding 4236364898). */
        private val UTTERANCE_ID_SEQ = java.util.concurrent.atomic.AtomicLong(0)

        /** The rate the shared echo reference and the correlator work at. */
        const val REFERENCE_RATE_HZ = 16_000

        /** Upper bound on waiting for queued audio to drain, so a stuck sink cannot hang speak(). */
        const val MAX_DRAIN_WAIT_MS = 10_000L

        /** Same order as the playback drain bound: no utterance waits unbounded. */
        const val MAX_SYNTHESIS_WAIT_MS = 10_000L
    }
}
