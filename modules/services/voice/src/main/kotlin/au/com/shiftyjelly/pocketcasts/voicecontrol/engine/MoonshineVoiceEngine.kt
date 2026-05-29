package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriptEvent
import android.content.Context
import android.media.AudioManager
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
 * Full voice pipeline: Oboe capture → Silero VAD → Moonshine ASR → Intent parser.
 *
 * Silero VAD gates Moonshine inference so the 123M-param encoder only runs when
 * speech is detected, eliminating continuous ONNX GC churn during silence.
 */
@Singleton
class MoonshineVoiceEngine @Inject constructor(
    private val voiceAudioProcessor: VoiceAudioProcessor,
    private val utteranceFilter: UtteranceFilter,
    private val intentRecognizer: VoiceRecognizer,
    @ApplicationContext private val context: Context,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transcriber: Transcriber? = null
    private var processingJob: Job? = null
    private var scoStarted = false
    private var savedAudioMode: Int? = null
    private var playbackBufferProvider: (() -> FloatArray)? = null
    private var engineStartTimeMs: Long = 0
    private var transcribeStartTimeMs: Long = 0

    fun start(
        modelPath: String,
        modelArch: Int,
        audioRoute: AudioRoute,
        playbackBufferProvider: () -> FloatArray,
        contextProvider: () -> VoiceRecognitionContext,
        onIntent: (VoiceIntent) -> Unit,
    ) {
        this.playbackBufferProvider = playbackBufferProvider
        engineStartTimeMs = System.currentTimeMillis()
        utteranceFilter.reset()
        openBluetoothSco()

        try {
            transcriber = Transcriber().apply {
                loadFromFiles(modelPath, modelArch)
                addListener { event ->
                    Timber.i("Moonshine event: %s", event::class.simpleName)
                    if (event is TranscriptEvent.LineCompleted) {
                        val text = event.line.text ?: ""
                        val elapsed = System.currentTimeMillis() - transcribeStartTimeMs
                        val audio = event.line.audioData ?: FloatArray(0)
                        Timber.i("Moonshine ASR: '%s' (elapsed=%dms)", text, elapsed)
                        if (text.isNotBlank()) {
                            processUtterance(text, audio, contextProvider(), onIntent)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load Moonshine transcriber")
            return
        }

        processingJob = scope.launch {
            try {
                voiceAudioProcessor.startProcessing().collect { result ->
                    when (result) {
                        is VoiceSegmenterResult.SpeechStarted -> {
                            Timber.i("VAD: speech started")
                        }

                        is VoiceSegmenterResult.SpeechContinuing -> {
                            // Speech ongoing — VAD accumulates frames internally
                        }

                        is VoiceSegmenterResult.SpeechEnded -> {
                            val frameCount = result.frames.size
                            val durationMs = frameCount * 64L // 1024 samples @ 16kHz = 64ms
                            Timber.i("VAD: speech ended (%d frames, ~%dms)", frameCount, durationMs)
                            transcribeSegment(result)
                        }

                        is VoiceSegmenterResult.Rejected -> {
                            Timber.w("VAD: rejected - %s", result.reason)
                        }

                        VoiceSegmenterResult.Silence -> { /* no speech */ }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Voice audio processing failed")
            }
        }
        Timber.i("MoonshineVoiceEngine started")
    }

    private fun transcribeSegment(segment: VoiceSegmenterResult.SpeechEnded) {
        val t = transcriber ?: run {
            Timber.w("transcribeSegment: transcriber is null, skipping")
            return
        }
        val frameCount = segment.frames.size
        try {
            transcribeStartTimeMs = System.currentTimeMillis()
            val streamId = t.createStream()
            Timber.i("transcribeSegment: stream=$streamId frames=$frameCount")
            t.startStream(streamId)

            for (frame in segment.frames) {
                val floatSamples = FloatArray(frame.samples.size)
                for (i in frame.samples.indices) {
                    floatSamples[i] = frame.samples[i].toFloat() / 32768f
                }
                t.addAudioToStream(streamId, floatSamples, frame.sampleRateHz)
            }

            t.stopStream(streamId)
            Timber.i("transcribeSegment: stream=$streamId done")
        } catch (e: Exception) {
            Timber.e(e, "transcribeSegment failed")
        }
    }

    private fun processUtterance(
        text: String,
        audio: FloatArray,
        context: VoiceRecognitionContext,
        onIntent: (VoiceIntent) -> Unit,
    ) {
        val playbackBuffer = playbackBufferProvider?.invoke() ?: FloatArray(0)
        if (!utteranceFilter.shouldProcess(audio, false, -1, playbackBuffer)) return

        scope.launch {
            val t0 = System.currentTimeMillis()
            val intent = intentRecognizer.recognize(text, context)
            val elapsedMs = System.currentTimeMillis() - t0
            if (intent != null) {
                Timber.i("Intent: %s (%dms)", intent::class.simpleName, elapsedMs)
                onIntent(intent)
            } else {
                Timber.i("No intent (%dms)", elapsedMs)
            }
        }
    }

    fun stop() {
        processingJob?.cancel()
        processingJob = null
        voiceAudioProcessor.stopProcessing()
        transcriber?.stop()
        transcriber = null
        closeBluetoothSco()
        Timber.i("MoonshineVoiceEngine stopped")
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
            Timber.i("Bluetooth SCO started (mode: %d → MODE_IN_COMMUNICATION)", savedAudioMode)
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
