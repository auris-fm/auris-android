package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.annotation.RequiresPermission
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackend
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.TranslationStage
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceAudioProcessor
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceSegmenterResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconId
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningMode
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.IntentRoutingInput
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.TranslationKind
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognitionContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.MicExposure
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeTranscriptTrimmer
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordDetector
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordSegmentCapture
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

@Singleton
class VoiceAsrEngine @Inject constructor(
    private val voiceAudioProcessor: VoiceAudioProcessor,
    private val utteranceFilter: UtteranceFilter,
    private val intentRecognizer: VoiceRecognizer,
    private val wakeWordDetector: WakeWordDetector,
    private val gracePeriodSignal: GracePeriodSignal,
    private val audioFeedbackRenderer: AudioFeedbackRenderer,
    private val translationStage: TranslationStage,
    @ApplicationContext private val context: Context,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var processingJob: Job? = null
    private var scoStarted = false
    private var savedAudioMode: Int? = null
    private var playbackBufferProvider: (() -> FloatArray)? = null

    private var backend: AsrBackend? = null
    private var onIntent: ((VoiceIntent) -> Unit)? = null
    private var micExposureProvider: (() -> MicExposure)? = null

    @Volatile
    private var currentMode: ListeningMode = ListeningMode.Off

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(
        backend: AsrBackend,
        audioRoute: AudioRoute,
        listeningMode: ListeningMode,
        playbackBufferProvider: () -> FloatArray,
        micExposureProvider: () -> MicExposure,
        onIntent: (VoiceIntent) -> Unit,
    ) {
        this.backend = backend
        this.currentMode = listeningMode
        this.playbackBufferProvider = playbackBufferProvider
        this.micExposureProvider = micExposureProvider
        this.onIntent = onIntent
        utteranceFilter.reset()

        processingJob = scope.launch {
            Timber.i("[VoicePipeline] start route=%s sco=%b", audioRoute, audioRoute is AudioRoute.BluetoothA2dpOnly)
            if (audioRoute is AudioRoute.BluetoothA2dpOnly) {
                awaitBluetoothSco()
            }
            try {
                voiceAudioProcessor.startProcessing().collect { result ->
                    when (result) {
                        is VoiceSegmenterResult.SpeechStarted -> { /* utterance started */ }

                        is VoiceSegmenterResult.SpeechContinuing -> { /* accumulating frames */ }

                        is VoiceSegmenterResult.SpeechEnded -> {
                            val totalSamples = result.frames.sumOf { it.samples.size }
                            val durationMs = totalSamples * 1000L / 16000
                            Timber.i("[VoicePipeline] vad ~%dms (%d samples)", durationMs, totalSamples)

                            val request = shouldTranscribe(result)
                            if (request != null) {
                                transcribeSegment(result, request)
                            }
                        }

                        is VoiceSegmenterResult.Rejected ->
                            Timber.w("[VoicePipeline] vad rejected - %s", result.reason)

                        VoiceSegmenterResult.Silence -> { /* no speech */ }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "[VoicePipeline] audio processing failed")
            }
        }
        Timber.i("[VoicePipeline] engine started backend=%s mode=%s", backend::class.simpleName, listeningMode)
    }

    fun updateListeningMode(mode: ListeningMode) {
        currentMode = mode
        Timber.i("[VoicePipeline] mode updated to %s", mode)
    }

    /**
     * Runs wake-word detection on every utterance in both listening modes,
     * then decides whether to transcribe. Per spec:
     *
     * - Positive detection (either mode): emits WAKE_WORD earcon, opens/resets
     *   grace, and forwards the complete VAD segment to ASR. Wake-positive
     *   time-band trim happens on timed ASR tokens after ASR, not by cutting audio.
     * - Negative outside grace (WakeWord): drops the segment.
     * - Negative during grace (Continuous): forwards the full segment.
     * - Wake-only is decided after ASR: empty leftover after time-band trim plays ERROR.
     */
    private data class TranscribeRequest(
        val samples: FloatArray,
        val wakePositive: Boolean,
        val completionSample: Int = 0,
    )

    private suspend fun shouldTranscribe(segment: VoiceSegmenterResult.SpeechEnded): TranscribeRequest? {
        // Build float samples from the segment
        val totalSamples = segment.frames.sumOf { it.samples.size }
        val floatSamples = FloatArray(totalSamples)
        var offset = 0
        for (frame in segment.frames) {
            for (i in frame.samples.indices) {
                floatSamples[offset + i] = frame.samples[i].toFloat() / 32768f
            }
            offset += frame.samples.size
        }

        // Always run the wake-word detector — it's lightweight and must observe
        // every utterance so wake-word audio is always stripped and every
        // detection is acknowledged.
        val wwResult = runCatching {
            wakeWordDetector.detect(
                segment = floatSamples,
                sampleRateHz = segment.frames.firstOrNull()?.sampleRateHz ?: 16000,
                speechOnsetSample = segment.speechOnsetSample,
            )
        }.getOrElse { e ->
            Timber.w(e, "[VoicePipeline] wake detection failed, dropping segment")
            return null
        }

        // Debug instrumentation: behind WAKE_WORD_DEBUG_CAPTURE, dump every
        // VAD segment (raw WAV + log-Mel PNG) named by timestamp + score.
        WakeWordSegmentCapture.capture(
            context,
            floatSamples,
            segment.frames.firstOrNull()?.sampleRateHz ?: 16000,
            wwResult.confidence,
        )

        val mode = currentMode
        val wakeCmp = when {
            wwResult.threshold.isNaN() -> if (wwResult.detected) "hit" else "miss"
            wwResult.detected -> "%.3f >= %.3f".format(wwResult.confidence, wwResult.threshold)
            else -> "%.3f < %.3f".format(wwResult.confidence, wwResult.threshold)
        }

        if (wwResult.detected) {
            // Open or reset the conversation grace period. This causes
            // ListeningModePolicy to switch to Continuous for subsequent utterances.
            gracePeriodSignal.onWakeWordDetected()

            // Always acknowledge detection with the WAKE_WORD earcon
            audioFeedbackRenderer.playEarcon(EarconId.WAKE_WORD)

            Timber.i("[VoicePipeline] wake %s → ASR (hit, mode=%s)", wakeCmp, mode)
            return TranscribeRequest(
                samples = floatSamples,
                wakePositive = true,
                completionSample = wwResult.completionSample,
            )
        }

        // Negative detection
        return when (mode) {
            ListeningMode.Continuous -> {
                Timber.i("[VoicePipeline] wake %s → ASR (grace/continuous)", wakeCmp)
                TranscribeRequest(samples = floatSamples, wakePositive = false)
            }

            ListeningMode.WakeWord -> {
                // Outside grace: drop — wake word is required
                Timber.i("[VoicePipeline] wake %s → drop (no grace)", wakeCmp)
                null
            }

            ListeningMode.Off -> null
        }
    }

    private suspend fun transcribeSegment(
        segment: VoiceSegmenterResult.SpeechEnded,
        request: TranscribeRequest,
    ) {
        val b = backend ?: return
        val sampleRateHz = segment.frames.firstOrNull()?.sampleRateHz ?: 16000
        val floatSamples = request.samples

        // Filter out playback bleed before transcribing
        val playbackBuffer = playbackBufferProvider?.invoke() ?: FloatArray(0)
        if (!utteranceFilter.shouldProcess(floatSamples, false, 0, playbackBuffer)) {
            Timber.i("[VoicePipeline] → drop (bleed filter)")
            return
        }

        // ASR
        val asrStartedAt = System.currentTimeMillis()
        val asrResult = b.transcribe(floatSamples, sampleRateHz)
        val asrMs = System.currentTimeMillis() - asrStartedAt
        val durationMs = (floatSamples.size * 1000L / sampleRateHz).toInt()
        val transcript = WakeTranscriptTrimmer.commandText(
            result = asrResult,
            wakePositive = request.wakePositive,
            completionSample = request.completionSample,
            sampleRateHz = sampleRateHz,
            utteranceDurationMs = durationMs,
        )
        val trimNote = when {
            !request.wakePositive -> null
            asrResult.text != transcript -> "trim '${asrResult.text}' → '$transcript'"
            else -> null
        }
        if (transcript.isBlank()) {
            Timber.i(
                "[VoicePipeline] asr %s %dms lang=%s '%s'%s → drop (%s)",
                b::class.simpleName,
                asrMs,
                asrResult.detectedLanguage ?: "?",
                asrResult.text,
                trimNote?.let { " $it" } ?: "",
                if (request.wakePositive) "wake-only" else "empty",
            )
            if (request.wakePositive) {
                audioFeedbackRenderer.playEarcon(EarconId.ERROR)
            }
            return
        }
        // Translate to English when the ASR backend did not already translate and
        // the detected language is not English (the SenseVoice CJK path).
        // Use the wake-trimmed transcript from LFM's WakeTranscriptTrimmer.
        val trimmedResult = asrResult.copy(text = transcript)
        val routePrep = prepareRoutingInput(trimmedResult, b)
        // Source-first: lang + first quote are ASR; translate note carries the English result.
        // Printing post-translate fields first made lines like
        //   lang=en 'Play.' translate=yue→en '播放。'
        // read as English ASR that somehow translated into Chinese.
        Timber.i(
            "[VoicePipeline] asr %s %dms lang=%s '%s'%s%s",
            b::class.simpleName,
            asrMs,
            trimmedResult.detectedLanguage ?: "?",
            trimmedResult.text,
            trimNote?.let { " $it" } ?: "",
            routePrep.translateNote?.let { " $it" } ?: "",
        )
        if (routePrep.dropWithError) {
            // Don't feed untranslated CJK into the English-only intent model.
            Timber.i("[VoicePipeline] → drop (%s)", routePrep.translateNote)
            audioFeedbackRenderer.playEarcon(EarconId.ERROR)
            return
        }
        processUtterance(routePrep.input!!)
    }

    private suspend fun processUtterance(input: IntentRoutingInput) {
        val recognizer = intentRecognizer
        val handler = onIntent ?: return

        val ready = recognizer.ensureReady()
        if (ready.isFailure) {
            Timber.e(ready.exceptionOrNull(), "[VoicePipeline] intent not ready")
            return
        }

        val t0 = System.currentTimeMillis()
        val ctx = VoiceRecognitionContext(
            listeningMode = currentMode,
            micExposure = micExposureProvider?.invoke() ?: MicExposure.Exposed,
        )
        val outcome = recognizer.recognize(input, ctx)
        val elapsedMs = System.currentTimeMillis() - t0
        val intent = outcome.intent
        val diagnostic = outcome.diagnostic

        if (intent != null) {
            Timber.i(
                "[VoicePipeline] intent %s %dms ← '%s'",
                intent,
                elapsedMs,
                input.routerTranscript,
            )
            handler(intent)
        } else {
            val stage = diagnostic?.failedStage ?: "unknown"
            val reason = diagnostic?.reason ?: "none"
            Timber.i(
                "[VoicePipeline] intent none %dms stage=%s reason=%s ← '%s'",
                elapsedMs,
                stage,
                reason,
                input.routerTranscript,
            )
        }
    }

    /**
     * Build [IntentRoutingInput] from the wake-trimmed ASR result.
     * Translation never overwrites source evidence; failures drop before routing.
     */
    private suspend fun prepareRoutingInput(
        trimmed: AsrResult,
        backend: AsrBackend,
    ): RoutePrep {
        val detected = trimmed.detectedLanguage?.lowercase()
        if (detected == null) {
            return RoutePrep(
                input = IntentRoutingInput(
                    sourceTranscript = trimmed.text,
                    sourceLanguage = null,
                    routerTranscript = trimmed.text,
                    translationKind = TranslationKind.NONE,
                ),
                translateNote = "translate=skip(no lang)",
                dropWithError = false,
            )
        }
        // Backend-native English (Canary de/es/fr→en) before the native-English branch:
        // Canary reports configured source language with English text.
        if (backend.capabilities.canTranslateToEnglish) {
            return RoutePrep(
                input = IntentRoutingInput(
                    sourceTranscript = null,
                    sourceLanguage = detected,
                    routerTranscript = trimmed.text,
                    translationKind = TranslationKind.BACKEND,
                ),
                translateNote = "translate=skip(backend)",
                dropWithError = false,
            )
        }
        if (detected == "en") {
            return RoutePrep(
                input = IntentRoutingInput(
                    sourceTranscript = trimmed.text,
                    sourceLanguage = "en",
                    routerTranscript = trimmed.text,
                    translationKind = TranslationKind.NONE,
                ),
                translateNote = null,
                dropWithError = false,
            )
        }

        val ready = translationStage.ensureReady(detected)
        if (ready.isFailure) {
            return RoutePrep(
                input = null,
                translateNote = "translate=fail($detected)",
                dropWithError = true,
            )
        }
        val translated = translationStage.translate(trimmed.text, detected).getOrElse {
            return RoutePrep(
                input = null,
                translateNote = "translate=fail($detected)",
                dropWithError = true,
            )
        }
        if (translated.isBlank()) {
            return RoutePrep(
                input = null,
                translateNote = "translate=blank($detected)",
                dropWithError = true,
            )
        }
        // Reject no-op "translations" that would mislabel CJK as English.
        if (translated == trimmed.text) {
            return RoutePrep(
                input = null,
                translateNote = "translate=noop($detected)",
                dropWithError = true,
            )
        }
        return RoutePrep(
            input = IntentRoutingInput(
                sourceTranscript = trimmed.text,
                sourceLanguage = detected,
                routerTranscript = translated,
                translationKind = TranslationKind.PLATFORM,
            ),
            translateNote = "translate=$detected→en '$translated'",
            dropWithError = false,
        )
    }

    private data class RoutePrep(
        val input: IntentRoutingInput?,
        val translateNote: String?,
        val dropWithError: Boolean,
    )

    fun stop() {
        processingJob?.cancel()
        processingJob = null
        backend?.release()
        backend = null
        closeBluetoothSco()
        Timber.i("[VoicePipeline] engine stopped")
    }

    @Suppress("DEPRECATION") // startBluetoothSco + SCO broadcast deprecated in API 33; no replacement
    private suspend fun awaitBluetoothSco() {
        if (scoStarted) return
        var registeredReceiver: BroadcastReceiver? = null
        var modeCaptured = false
        try {
            val connected = withTimeoutOrNull(SCO_CONNECT_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context, intent: Intent) {
                            val state = intent.getIntExtra(
                                AudioManager.EXTRA_SCO_AUDIO_STATE,
                                AudioManager.SCO_AUDIO_STATE_ERROR,
                            )
                            Timber.i("[VoicePipeline] sco state=%d", state)
                            if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED ||
                                state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED
                            ) {
                                context.unregisterReceiver(this)
                                if (cont.isActive) cont.resumeWith(Result.success(state == AudioManager.SCO_AUDIO_STATE_CONNECTED))
                            }
                        }
                    }
                    context.registerReceiver(
                        receiver,
                        IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                    )
                    registeredReceiver = receiver
                    savedAudioMode = audioManager.mode
                    modeCaptured = true
                    audioManager.mode = AudioManager.MODE_NORMAL
                    audioManager.startBluetoothSco()
                    scoStarted = true
                    Timber.i("[VoicePipeline] sco requested, waiting")

                    cont.invokeOnCancellation {
                        try {
                            context.unregisterReceiver(receiver)
                        } catch (_: Exception) {
                        }
                        registeredReceiver = null
                    }
                }
            }
            if (connected != true) {
                Timber.w("[VoicePipeline] sco timeout/fallback — proceeding without confirmed SCO")
                // Leave scoStarted as set if startBluetoothSco was issued; closeBluetoothSco
                // still cleans up on stop. Capture continues on the best available input.
            }
        } catch (e: CancellationException) {
            // Cancellation can land after register/mode change but before scoStarted —
            // rollback restores mode / clears savedAudioMode (unregister is idempotent).
            rollbackFailedScoSetup(registeredReceiver, modeCaptured)
            throw e
        } catch (e: Exception) {
            Timber.w(e, "[VoicePipeline] sco setup failed — falling back to phone mic")
            rollbackFailedScoSetup(registeredReceiver, modeCaptured)
        }
    }

    private fun rollbackFailedScoSetup(receiver: BroadcastReceiver?, modeCaptured: Boolean) {
        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
        if (modeCaptured) {
            try {
                savedAudioMode?.let { audioManager.mode = it }
            } catch (_: Exception) {
            }
            savedAudioMode = null
        }
        scoStarted = false
    }

    @Suppress("DEPRECATION") // stopBluetoothSco deprecated in API 33; no replacement
    private fun closeBluetoothSco() {
        if (!scoStarted) return
        try {
            audioManager.stopBluetoothSco()
            savedAudioMode?.let { audioManager.mode = it }
        } catch (e: Exception) {
            Timber.w(e, "Failed to stop Bluetooth SCO")
        } finally {
            scoStarted = false
        }
    }

    companion object {
        private const val SCO_CONNECT_TIMEOUT_MS = 3_000L
    }
}
