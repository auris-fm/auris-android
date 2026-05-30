package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import android.content.Context
import android.media.AudioManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackend
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceAudioProcessor
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceSegmenterResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognitionContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Backend-agnostic voice pipeline: Oboe capture -> Silero VAD -> [AsrBackend] -> intent matching.
 *
 * The [AsrBackend] is provided by the [au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackendSelector]
 * and may be whisper.cpp, SenseVoice, or an NPU backend depending on device capabilities.
 */
@Singleton
class VoiceAsrEngine @Inject constructor(
    private val voiceAudioProcessor: VoiceAudioProcessor,
    private val utteranceFilter: UtteranceFilter,
    private val intentRecognizer: VoiceRecognizer,
    @ApplicationContext private val context: Context,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var processingJob: Job? = null
    private var scoStarted = false
    private var savedAudioMode: Int? = null
    private var playbackBufferProvider: (() -> FloatArray)? = null

    private var backend: AsrBackend? = null
    private var onIntent: ((VoiceIntent) -> Unit)? = null

    fun start(
        backend: AsrBackend,
        audioRoute: AudioRoute,
        playbackBufferProvider: () -> FloatArray,
        contextProvider: () -> VoiceRecognitionContext,
        onIntent: (VoiceIntent) -> Unit,
    ) {
        this.backend = backend
        this.playbackBufferProvider = playbackBufferProvider
        this.onIntent = onIntent
        utteranceFilter.reset()
        openBluetoothSco()

        processingJob = scope.launch {
            try {
                voiceAudioProcessor.startProcessing().collect { result ->
                    when (result) {
                        is VoiceSegmenterResult.SpeechStarted ->
                            Timber.i("VAD: speech started")

                        is VoiceSegmenterResult.SpeechContinuing -> { /* accumulating frames */ }

                        is VoiceSegmenterResult.SpeechEnded -> {
                            val frameCount = result.frames.size
                            val durationMs = frameCount * 64L
                            Timber.i("VAD: speech ended (%d frames, ~%dms)", frameCount, durationMs)
                            transcribeSegment(result, contextProvider())
                        }

                        is VoiceSegmenterResult.Rejected ->
                            Timber.w("VAD: rejected - %s", result.reason)

                        VoiceSegmenterResult.Silence -> { /* no speech */ }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Voice audio processing failed")
            }
        }
        Timber.i("VoiceAsrEngine started (backend=%s)", backend::class.simpleName)
    }

    private suspend fun transcribeSegment(
        segment: VoiceSegmenterResult.SpeechEnded,
        context: VoiceRecognitionContext,
    ) {
        val b = backend ?: return
        val frameCount = segment.frames.size
        val sampleRateHz = segment.frames.firstOrNull()?.sampleRateHz ?: 16000

        // Concatenate all frames into one float buffer for batch ASR
        val totalSamples = segment.frames.sumOf { it.samples.size }
        val floatSamples = FloatArray(totalSamples)
        var offset = 0
        for (frame in segment.frames) {
            for (i in frame.samples.indices) {
                floatSamples[offset + i] = frame.samples[i].toFloat() / 32768f
            }
            offset += frame.samples.size
        }

        // Filter out playback bleed before transcribing
        val playbackBuffer = playbackBufferProvider?.invoke() ?: FloatArray(0)
        if (!utteranceFilter.shouldProcess(floatSamples, false, -1, playbackBuffer)) {
            Timber.i("Utterance filtered out (playback bleed)")
            return
        }

        val t0 = System.currentTimeMillis()
        val result = b.transcribe(floatSamples, sampleRateHz)
        val elapsedMs = System.currentTimeMillis() - t0

        if (result.text.isBlank()) {
            Timber.i("ASR returned empty text (%dms)", elapsedMs)
            return
        }

        Timber.i("ASR: '%s' (lang=%s, %dms)", result.text, result.detectedLanguage, elapsedMs)
        processUtterance(result)
    }

    private suspend fun processUtterance(result: AsrResult) {
        val recognizer = intentRecognizer
        val handler = onIntent ?: return

        val t0 = System.currentTimeMillis()
        // Build a minimal recognition context — the full context is set by the caller's provider
        val ctx = au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognitionContext(
            listeningMode = au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningMode.Off,
            micExposure = au.com.shiftyjelly.pocketcasts.voicecontrol.route.MicExposure.Exposed,
        )
        val intent = recognizer.recognize(result.text, ctx)
        val elapsedMs = System.currentTimeMillis() - t0

        if (intent != null) {
            Timber.i("Intent: %s (%dms)", intent::class.simpleName, elapsedMs)
            handler(intent)
        } else {
            Timber.i("No intent (%dms)", elapsedMs)
        }
    }

    fun stop() {
        processingJob?.cancel()
        processingJob = null
        voiceAudioProcessor.stopProcessing()
        backend?.release()
        backend = null
        onIntent = null
        closeBluetoothSco()
        Timber.i("VoiceAsrEngine stopped")
    }

    @Suppress("DEPRECATION")
    private fun openBluetoothSco() {
        if (!audioManager.isBluetoothScoAvailableOffCall) {
            Timber.i("Bluetooth SCO not available off-call, skipping")
            return
        }
        try {
            savedAudioMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.startBluetoothSco()
            scoStarted = true
            Timber.i("Bluetooth SCO started (mode: %d -> MODE_IN_COMMUNICATION)", savedAudioMode)
        } catch (e: Exception) {
            Timber.w(e, "Failed to start Bluetooth SCO")
        }
    }

    @Suppress("DEPRECATION")
    private fun closeBluetoothSco() {
        if (!scoStarted) return
        try {
            audioManager.stopBluetoothSco()
            scoStarted = false
            savedAudioMode?.let { audioManager.mode = it }
            savedAudioMode = null
            Timber.i("Bluetooth SCO stopped, mode restored to %d", savedAudioMode)
        } catch (e: Exception) {
            Timber.w(e, "Failed to stop Bluetooth SCO")
        }
    }
}
