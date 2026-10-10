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
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
) : TtsEngine {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var initialized = false
    private var released = false

    /** Set while a synthesized file is being played, so cancellation can stop it. */
    @Volatile
    private var activeTrack: AudioTrack? = null

    /** Frames submitted by the last utterance and how far playback had advanced when it returned. */
    @Volatile
    private var framesWritten = 0

    /** True while a synthesized utterance is being PLAYED (set once the sink has been fed). */
    @Volatile
    private var playbackActive = false

    @Volatile
    private var framesPlayedAtReturn = 0

    init {
        tts = TextToSpeech(appContext) { status ->
            initialized = (status == TextToSpeech.SUCCESS)
            if (!initialized) {
                Timber.w("TTS engine initialization failed with status $status")
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
                // QUEUE_FLUSH preserves the previous single-utterance behaviour: a superseded reply
                // must not keep talking.
                val queued = engine.synthesizeToFile(text, null, wav, utteranceId)
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
            playSynthesized(wav)
        } finally {
            wav.delete()
        }
    }

    /**
     * Plays the synthesized WAV through an owned AudioTrack and records the accepted prefix into
     * the echo reference, so the answer is part of what the filter correlates against.
     */
    private suspend fun playSynthesized(wav: File) = withContext(Dispatchers.IO) {
        // Cancelling the caller must stop playback immediately: the render job is cancelled before
        // every response, and an answer that keeps talking is indistinguishable from a foreign app
        // to our own gate.
        val playback = coroutineContext[Job]
        val stopper = playback?.invokeOnCompletion { cause ->
            if (cause != null) {
                activeTrack?.runCatching { stop() }
                tts?.stop()
            }
        }
        try {
            playSynthesizedInternal(wav)
        } finally {
            stopper?.dispose()
        }
    }

    private suspend fun playSynthesizedInternal(wav: File) = withContext(Dispatchers.IO) {
        val clip = runCatching {
            wav.inputStream().use { EarconAudio.decodeWavToMono(it) }
        }.onFailure { Timber.w(it, "TTS: failed to decode synthesized audio") }.getOrNull() ?: return@withContext

        val track = buildTrack(clip.sampleRateHz) ?: run {
            Timber.w("TTS: could not create AudioTrack")
            return@withContext
        }
        activeTrack = track
        try {
            track.play()
            playbackActive = true
            var offset = 0
            while (offset < clip.samples.size) {
                val written = track.write(clip.samples, offset, clip.samples.size - offset)
                if (written <= 0) {
                    Timber.w("TTS: AudioTrack.write returned $written")
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
            while (track.playbackHeadPosition < offset && waited < MAX_DRAIN_WAIT_MS) {
                delay(10)
                waited += 10
            }
            framesWritten = offset
            framesPlayedAtReturn = track.playbackHeadPosition
        } finally {
            playbackActive = false
            activeTrack = null
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    /** True while a synthesized utterance is playing, so a caller can observe playback in progress. */
    fun isPlayingSynthesizedAudio(): Boolean = playbackActive

    /** Frames submitted to the sink by the last utterance, or 0 if none played. */
    fun framesWritten(): Int = framesWritten

    /** Playback position at the moment the last utterance completed. */
    fun framesPlayedAtReturn(): Int = framesPlayedAtReturn

    private fun recordAccepted(
        samples: ShortArray,
        sampleRateHz: Int,
        startSample: Int,
        count: Int,
    ) {
        if (count <= 0) return
        val slice = samples.copyOfRange(startSample, startSample + count)
        val forReference = if (sampleRateHz == REFERENCE_RATE_HZ) {
            slice
        } else {
            EarconAudio.resampleMono(slice, sampleRateHz, REFERENCE_RATE_HZ)
        }
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
        activeTrack?.runCatching {
            stop()
            release()
        }
        activeTrack = null
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
