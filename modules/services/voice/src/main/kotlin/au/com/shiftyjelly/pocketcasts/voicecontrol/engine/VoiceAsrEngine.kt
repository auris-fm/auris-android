package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.annotation.RequiresPermission
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackend
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.TranslationStage
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceAudioProcessor
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceSegmenterResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.captureTimeOfSpeechOnsetMs
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconId
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.SpokenLine
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.SpokenTemplateResolver
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm.CloudEscalation
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm.CloudEscalationPolicy
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.withLogicalRequest
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
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber

/** How close the completion band must sit to speech end for a capture to be the wake word alone. */
private const val WAKE_ONLY_TOLERANCE_MS = 120

/**
 * Handle to a [VoiceAsrEngine.start] attempt. [await] returns true once the native capture
 * pipeline is attached to the confirmed stream, or false when the attempt ended before
 * attaching (backend not ready, native attach failure, or cancellation). The caller must not
 * hand the stream to the engine until [await] returns true; until then the stream is still the
 * caller's to close, so a start that never attaches cannot leave a hot microphone behind.
 */
class CaptureAttachment internal constructor(private val attached: CompletableDeferred<Boolean>) {
    suspend fun await(): Boolean = attached.await()
}

@Singleton
class VoiceAsrEngine @Inject constructor(
    private val voiceAudioProcessor: VoiceAudioProcessor,
    private val utteranceFilter: UtteranceFilter,
    private val intentRecognizer: VoiceRecognizer,
    private val wakeWordDetector: WakeWordDetector,
    private val gracePeriodSignal: GracePeriodSignal,
    private val audioFeedbackRenderer: AudioFeedbackRenderer,
    private val templateResolver: SpokenTemplateResolver = SpokenTemplateResolver(emptyMap()),
    // Tests can substitute one. Production does not rely on this default: Dagger supplies every
    // constructor parameter (Kotlin defaults apply only at Kotlin call sites), and the real resolver
    // comes from VoiceControlModule.provideSpokenTemplateResolver.
    private val currentLocale: () -> Locale = { Locale.getDefault() },
    private val translationStage: TranslationStage,
    @ApplicationContext private val context: Context,
) {

    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var processingJob: Job? = null
    private var playbackBufferProvider: (() -> PlaybackReference)? = null

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
        playbackBufferProvider: () -> PlaybackReference,
        micExposureProvider: () -> MicExposure,
        onIntent: (VoiceIntent) -> Unit,
        // Invoked when the processing job ends for any reason, so the service can clear capture
        // state the pipeline no longer backs. The job ends on an intentional stop too; the caller
        // scopes the reaction to its start generation so only an unexpected end is acted on.
        onTerminated: () -> Unit = {},
    ): CaptureAttachment {
        this.backend = backend
        this.currentMode = listeningMode
        this.playbackBufferProvider = playbackBufferProvider
        this.micExposureProvider = micExposureProvider
        this.onIntent = onIntent
        utteranceFilter.reset()

        val attached = CompletableDeferred<Boolean>()
        processingJob = scope.launch {
            Timber.i("[VoicePipeline] start route=%s bluetoothCandidate=%b", audioRoute, audioRoute is AudioRoute.BluetoothA2dpOnly)
            // Prepare the backend on every start. Not because a pause releases it — stop()
            // deliberately keeps it warm — but because the engine can be handed a backend that
            // was never prepared, and because a start that cannot be prepared must not run a
            // pipeline: an unprepared backend returns an empty result for every utterance
            // instead of failing, which is indistinguishable from speech it could not read.
            val backendReady = backend.ensureReady()
            if (backendReady.isFailure) {
                Timber.e(backendReady.exceptionOrNull(), "[VoicePipeline] ASR backend not ready — not listening")
                return@launch
            }
            // Attach the native VAD pipeline to the confirmed stream before reporting the start
            // as attached. The caller hands the stream over only on a true signal, so a start
            // that ends here leaves the stream owned by the caller and closed, never live with
            // no consumer.
            //
            // SCO ownership: a Bluetooth candidate is ACQUIRED before the engine starts, and the
            // stream opened by acquisition is the one this pipeline attaches to. Opening SCO here
            // would deadlock under the isolated-microphone rule (voice-control-core.md > Route
            // acquisition), so the engine does not perform its own SCO setup even when the route
            // is BluetoothA2dpOnly.
            if (!voiceAudioProcessor.attachCapture()) {
                Timber.e("[VoicePipeline] capture attach failed — not listening")
                return@launch
            }
            attached.complete(true)
            // A Bluetooth candidate is acquired before the engine starts; the stream opened
            // by acquisition is the one this pipeline attaches to. Opening SCO here would
            // deadlock under the isolated-microphone rule (voice-control-core.md > Route
            // acquisition).
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
        // If the job ends without ever attaching (backend not ready, native attach failure, or
        // cancellation before the body ran), report that the stream was never taken. Completing
        // an already-completed true is a no-op.
        processingJob?.invokeOnCompletion {
            attached.complete(false)
            onTerminated()
        }
        Timber.i("[VoicePipeline] engine start requested backend=%s mode=%s", backend::class.simpleName, listeningMode)
        return CaptureAttachment(attached)
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
     * - Wake-only is decided after ASR: an empty leftover after trim is dropped before
     *   routing, so it stays silent rather than spending the grace window.
     */
    private data class TranscribeRequest(
        val samples: FloatArray,
        val wakePositive: Boolean,
        val completionSample: Int = 0,
        val speechEndSample: Int = 0,
        val sampleRateHz: Int = 16000,
    )

    /**
     * Whether the utterance is the wake word and nothing else, from the detector's timing rather
     * than from the text: if the completion band covers the speech, nothing was said after the
     * wake. `speechEndSample` is 0 when the segmenter does not report it, and 0 must read as
     * unknown — otherwise every capture would look wake-only and every turn would go silent.
     */
    private fun isWakeOnly(request: TranscribeRequest): Boolean = request.speechEndSample > 0 &&
        request.completionSample + (request.sampleRateHz * WAKE_ONLY_TOLERANCE_MS / 1000) >=
        request.speechEndSample

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
            audioFeedbackRenderer.submitEarcon(EarconId.WAKE_WORD)

            Timber.i("[VoicePipeline] wake %s → ASR (hit, mode=%s)", wakeCmp, mode)
            return TranscribeRequest(
                samples = floatSamples,
                speechEndSample = segment.speechEndSample,
                sampleRateHz = segment.frames.firstOrNull()?.sampleRateHz ?: 16000,
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

        // Filter out playback bleed before transcribing. The player's reference window carries its
        // own timeline position, and the segment carries the capture clock, so the correlator can
        // compare the two at a SHARED instant instead of at raw buffer offsets. When the stream
        // reports no timestamp, the capture time stays unknown and the correlator falls back to
        // offset matching, which is the behaviour from before a clock existed.
        val playbackReference = playbackBufferProvider?.invoke()
            ?: PlaybackReference(FloatArray(0), sampleRateHz, startPositionMs = null)
        // Named `Ms` because the correlator's clock is in milliseconds: the capture observation is
        // nanoseconds and the conversion happens here, so a reader cannot pair the two domains by
        // accident. The comment on the helper says the same thing at the point of the division.
        val micPositionMs = segment.captureTimeOfSpeechOnsetMs()
        if (!utteranceFilter.shouldProcessReference(floatSamples, false, 0, playbackReference, micPositionMs)) {
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
        if (transcript.isBlank() || isWakeOnly(request)) {
            Timber.i(
                "[VoicePipeline] asr %s %dms lang=%s '%s'%s → drop (%s)",
                b::class.simpleName,
                asrMs,
                asrResult.detectedLanguage ?: "?",
                asrResult.text,
                trimNote?.let { " $it" } ?: "",
                if (request.wakePositive) "wake-only" else "empty",
            )
            // No leftover command after the wake: the session started and
            // nothing was asked, so nothing is reported. (The WAKE_WORD earcon
            // already acknowledged detection.)
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
            audioFeedbackRenderer.submitEarcon(EarconId.ERROR)
            return
        }
        // "Addressed" is this segment's own wake detection, per core voice-intents.md: a Detected
        // segment that we could not route gets the spoken line, a NotDetected one keeps the earcon.
        // The grace window is true for both, so it cannot make that distinction.
        processUtterance(routePrep.input!!, addressed = request.wakePositive)
    }

    private suspend fun processUtterance(input: IntentRoutingInput, addressed: Boolean) {
        val recognizer = intentRecognizer
        val handler = onIntent ?: return

        val ready = recognizer.ensureReady()
        if (ready.isFailure) {
            Timber.e(ready.exceptionOrNull(), "[VoicePipeline] intent not ready")
            // The router never ran, which is the same class as the router's own
            // `model_not_loaded`: a turn happened and nothing an answer it. Say
            // so — silence here is the complaint this change exists to remove.
            // Deliberately not escalated: a capability failure would turn a
            // broken install into cloud traffic (see CloudEscalationPolicy).
            audioFeedbackRenderer.submitEarcon(EarconId.ERROR)
            return
        }

        val t0 = System.currentTimeMillis()
        val ctx = VoiceRecognitionContext(
            listeningMode = currentMode,
            micExposure = micExposureProvider?.invoke() ?: MicExposure.Exposed,
        )
        // CAPTURED BEFORE the recognition suspends. Recognition can take seconds, and a wake that
        // arrives while it runs opens a NEW window: stamping the intent afterwards would attach it to
        // the window that opened during the wait, so a request issued before a successor wake would
        // act as though it belonged to the successor.
        val windowAtRequest = gracePeriodSignal.capturedGeneration()
        // THE LOGICAL IDENTITY FOR AN ORDINARY CLOUD REQUEST IS MINTED HERE, at input registration and
        // BEFORE the recognition suspends. It is not derivable from the transcript: a retry of the same
        // request must name the SAME value, and two separate user acts that happen to say the same
        // words must not. The dialog path mints its own for clarification flows; this is the producer
        // for a plain utterance that goes straight to the cloud.
        val logicalRequestId = java.util.UUID.randomUUID().toString()
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
            // A cloud turn outlives the window it was issued in, so it carries
            // that window's identity and cannot extend a later one.
            handler(
                intent
                    .stampedForWindow(windowAtRequest)
                    // Only a CLOUD turn carries a logical identity: it is the one dispatch that is
                    // prepared once and may be retried. A local intent has no wire identity to reuse.
                    .let { it.withLogicalRequest(logicalRequestId) },
            )
            return
        }

        val stage = diagnostic?.failedStage ?: "unknown"
        val reason = diagnostic?.reason ?: "none"
        Timber.i(
            "[VoicePipeline] intent none %dms stage=%s reason=%s ← '%s'",
            elapsedMs,
            stage,
            reason,
            input.routerTranscript,
        )

        // No usable intent: three outcomes, decided in one place
        // (see [CloudEscalationPolicy] for which reason lands where).
        when (CloudEscalationPolicy.decide(reason, addressed)) {
            // A rejection, not a failure. A wake-negative capture lands here — a follow-up the wake
            // did not cover in its own segment, or speech caught from the room.
            // Heard something we could not route and the user was not addressing us: a tone is
            // all that is warranted.
            CloudEscalation.EARCON -> audioFeedbackRenderer.submitEarcon(EarconId.ERROR)

            CloudEscalation.SILENT -> Unit

            // They addressed us and we could not place it: say so in their language, falling back
            // to the earcon in a locale this build has no line for.
            CloudEscalation.SPEAK_UNROUTED -> {
                val line = SpokenLine.forKey(KEY_CLOUD_UNROUTED, templateResolver, currentLocale())
                if (line.isBlank()) {
                    audioFeedbackRenderer.submitEarcon(EarconId.ERROR)
                } else {
                    audioFeedbackRenderer.render(VoiceResponse.Spoken(line))
                }
            }

            // Nothing was sent, but a turn happened: the earcon table already
            // requires a tone for this case.
            CloudEscalation.SPEAK_ERROR -> audioFeedbackRenderer.submitEarcon(EarconId.ERROR)

            // The client has no answer of its own, so the service gets asked —
            // through the same handler a chosen `cloud_route` uses, so
            // superseded-turn cancellation and the auto-pause obligation apply.
            CloudEscalation.DISPATCH -> {
                // THE ORIGIN IS THE ONE CAPTURED AT REGISTRATION, not the window that happens to be
                // live now. Recognition can take seconds, and a wake arriving meanwhile opens a NEW
                // window: escalating under that window would let OLD recognition consume its
                // SUCCESSOR's allowance and dispatch under the successor's generation. A stale origin is
                // rejected BEFORE the allowance is claimed, so it cannot spend it either.
                if (!gracePeriodSignal.isCurrent(windowAtRequest)) {
                    Timber.i(
                        "[VoicePipeline] cloud escalation skipped: origin %d is no longer live",
                        windowAtRequest,
                    )
                    audioFeedbackRenderer.submitEarcon(EarconId.ERROR)
                    return
                }
                // CLAIMED AGAINST THE REQUEST'S OWN WINDOW. The liveness check above refuses a stale
                // origin; this makes the CLAIM itself belong to that window, so a request that is about
                // to be refused cannot spend the successor's allowance on its way out.
                val issuedUnder = gracePeriodSignal.issueEscalation(windowAtRequest)
                if (issuedUnder == null) {
                    // No open window, or this window's one dispatch is spent.
                    Timber.i(
                        "[VoicePipeline] cloud escalation skipped (%s)",
                        gracePeriodSignal.escalationRefusal(),
                    )
                    audioFeedbackRenderer.submitEarcon(EarconId.ERROR)
                    return
                }
                Timber.i("[VoicePipeline] cloud escalation ← '%s' (reason=%s)", input.routerTranscript, reason)
                handler(
                    VoiceIntent.CloudRoute(
                        request = input.routerTranscript,
                        tier = VoiceIntent.CloudTier.Unknown,
                        // STAMPED WITH THE CAPTURED ORIGIN, matching the window this recognition was
                        // issued under -- the same value the chosen-route path uses.
                        windowGeneration = windowAtRequest,
                        origin = VoiceIntent.CloudRouteOrigin.RoutingFailure,
                        // THE REGISTERED IDENTITY, not a second one: this escalation belongs to the SAME
                        // logical request as the recognition it follows, so a retry of that request
                        // names one identity rather than two.
                        logicalRequestId = logicalRequestId,
                    ),
                )
            }
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
        // Tear down native capture directly too: the processing job's flow `finally` only runs
        // when the event loop was collected, and a capture attached but never collected must
        // still be stopped. Idempotent with the flow's own teardown.
        voiceAudioProcessor.stopProcessing()
        // Listening pauses are frequent (gate conflicts, route changes), so the backend is
        // deliberately kept warm here rather than released: releasing it would force a full
        // model reload on the next start, and everything spoken during that reload is lost.
        // The ASR backend is owned by VoiceControlService and released in its teardown.
        backend = null
        Timber.i("[VoicePipeline] engine stopped")
    }

    /**
     * Stamps the window a cloud dispatch belongs to; local commands are left
     * alone, since a locally handled command is itself a fresh act.
     */
    private fun VoiceIntent.stampedForWindow(generation: Long): VoiceIntent = if (this is VoiceIntent.CloudRoute) copy(windowGeneration = generation) else this

    companion object {
        /** Client-owned wording, resolved through [SpokenLine] so a foreign locale hears the earcon. */
        private const val KEY_CLOUD_UNROUTED = "pipeline.unclear_command"
    }
}
