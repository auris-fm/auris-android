package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.annotation.RequiresPermission
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackend
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.TranslationStage
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.OboeConfig
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceAudioProcessor
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceSegmenterResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconId
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.SpokenLine
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.SpokenTemplateResolver
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm.CloudEscalation
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm.CloudEscalationPolicy
import au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningMode
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.IntentRoutingInput
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.TranslationKind
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognitionContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.MicExposure
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CaptureContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ContributionRetentionSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ExecutionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObservation
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObserver
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeTranscriptTrimmer
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordDetector
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordSegmentCapture
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    // Upload observation of the ACTUAL segment path (plan Task 3). Optional only for Kotlin
    // test call sites; Dagger supplies the production pair. The live [collectionAuthority] is
    // consulted at EVERY retention decision — start admission, revalidation before handoff —
    // so a START-time YES can never authorize retention after withdrawal/expiry/account change.
    private val collectionAuthority: () -> AuthorityDecision,
    private val retentionSink: ContributionRetentionSink,
    private val segmentObserver: SegmentObserver,
    // Upload observation is OFF unless a composition module binds all three collaborators;
    // Dagger never applies Kotlin defaults, so an unbound constructor fails at build time.
    @ApplicationContext private val context: Context,
) {
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var processingJob: Job? = null
    private var playbackBufferProvider: (() -> FloatArray)? = null

    private var backend: AsrBackend? = null
    private var onIntent: ((VoiceIntent, InvocationToken?) -> Unit)? = null
    private var micExposureProvider: (() -> MicExposure)? = null

    @Volatile
    private var currentMode: ListeningMode = ListeningMode.Off

    /** Route CLASS frozen into each capture context; set synchronously at start()/stop(). */
    @Volatile
    private var currentRouteClass: String = AudioRoute.Unknown::class.simpleName!!

    /** Capture-session incarnation: bumped synchronously at every pipeline start. */
    @Volatile
    private var captureSessionIncarnation: Long = 0L

    /** Context whose dispatched intent is about to execute; consumed by the wrapped handler. */
    @Volatile
    private var pendingExecutionContext: CaptureContext? = null

    /**
     * ONE DISPATCH's identity. Reports are addressed by this token, never by the intent value:
     * identical intents dispatched concurrently are different invocations with different captures.
     */
    data class InvocationToken(val id: Long, val captureId: java.util.UUID)

    /** Open invocations awaiting finalization; guarded by [invocationLock]. */
    private val openInvocations = java.util.concurrent.ConcurrentHashMap<Long, InvocationToken>()

    /** Serializes one-shot token consumption against settle/stop. */
    private val invocationLock = Any()

    private val nextInvocationId = java.util.concurrent.atomic.AtomicLong()

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(
        backend: AsrBackend,
        audioRoute: AudioRoute,
        listeningMode: ListeningMode,
        playbackBufferProvider: () -> FloatArray,
        micExposureProvider: () -> MicExposure,
        // The dispatch handler receives the INVOCATION token for this specific dispatch, or null when
        // the intent belongs to no retained capture. Results are reported back against that token.
        onIntent: (VoiceIntent, InvocationToken?) -> Unit,
        // Invoked when the processing job ends for any reason, so the service can clear capture
        // state the pipeline no longer backs. The job ends on an intentional stop too; the caller
        // scopes the reaction to its start generation so only an unexpected end is acted on.
        onTerminated: () -> Unit = {},
    ): CaptureAttachment {
        this.backend = backend
        this.currentMode = listeningMode
        // The handler receives the invocation token for THIS dispatch; the binding lives in
        // invokeHandler at the dispatch sites.
        // The token binding happens INSIDE invokeHandler at the dispatch sites, NOT by wrapping
        // here: the internal dispatch sites call invokeHandler directly, and a wrapper here would
        // run it twice — the inner call would see pendingExecutionContext already consumed and
        // hand the caller a null token (found by the overlapping-dispatch control).
        this.onIntent = onIntent
        this.currentRouteClass = audioRoute::class.simpleName ?: AudioRoute.Unknown::class.simpleName!!
        captureSessionIncarnation += 1
        this.playbackBufferProvider = playbackBufferProvider
        this.micExposureProvider = micExposureProvider
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
                var openContext: CaptureContext? = null
                var accountedSamples = 0
                voiceAudioProcessor.startProcessing().collect { result ->
                    when (result) {
                        is VoiceSegmenterResult.SpeechStarted -> {
                            // Freeze the capture context SYNCHRONOUSLY here — before any await
                            // boundary — and keep the ISSUED value for every later stage of THIS
                            // capture. Refused captures yield null and are excluded downstream.
                            // ADMISSION BEFORE ALLOCATION: read only the native SIZE counter, let
                            // the observer reserve exact bytes; drain into an admitted array whose
                            // ownership then transfers to the sink. Refusal allocates NOTHING and
                            // leaves the recognition buffer untouched.
                            val initialSize = voiceAudioProcessor.speechPcmSize()
                            // PER-CAPTURE MODEL PROVENANCE (reviewers 54635caa/8288ac15): the
                            // ACTUAL selected backend's model identity, frozen with the context.
                            // The VERSION is the model's own manifest identity, never a filename.
                            val spec = backend.requiredModel
                            val asrRef = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef.Named(
                                spec.targetDir,
                                spec.files.firstOrNull { it.manifestId.isNotEmpty() }?.manifestId ?: "",
                            )
                            // Translation provenance is NOT knowable at capture start (the stage may
                            // or may not run for this utterance); it is recorded when observed.
                            val admission = segmentObserver.speechStartedAdmitting(captureSessionIncarnation, currentRouteClass, initialSize, asrRef)
                            openContext = admission?.context
                            if (admission != null) {
                                // Lease-protected initial allocation (reviewers 23cae70e b4 /
                                // 79b2b5c9 b2): release is addressed by the IMMUTABLE HANDLE —
                                // valid even if revocation closes this capture or a successor
                                // opens mid-copy. The admit token's charge leaves EXACTLY ONCE:
                                // transferred into the prefix on success, or returned here.
                                var transferred = false
                                try {
                                    val firstChunk = ShortArray(initialSize)
                                    val copied = if (initialSize > 0) voiceAudioProcessor.drainSpeechPcm(firstChunk) else 0
                                    // ACTUAL sink transfer result; drained VALID count passed
                                    // separately from array capacity (reviewer 79b2b5c9 b3).
                                    transferred = segmentObserver.adoptFirstChunk(admission, firstChunk, copied)
                                    if (transferred) accountedSamples = copied
                                } finally {
                                    if (!transferred) segmentObserver.releaseInitialChunk(admission)
                                }
                            }
                        }

                        is VoiceSegmenterResult.SpeechContinuing -> {
                            // RESERVE-BEFORE-COPY (blocker b3): read only the native SIZE counter;
                            // admit the delta into collection ownership FIRST; allocate + drain the
                            // tail ONLY for an admitted delta; any failure releases the reservation.
                            // Not retaining (gate off / cap-ended / refused capture): ZERO collection
                            // work — no peek array, no copy — recognition continues untouched.
                            if (openContext != null && segmentObserver.isRetaining(openContext!!.captureId)) {
                                val producedSize = voiceAudioProcessor.speechPcmSize()
                                val deltaSamples = (producedSize - accountedSamples).coerceAtLeast(0)
                                val token = if (deltaSamples > 0) segmentObserver.reserveDelta(openContext!!, deltaSamples) else null
                                if (token != null) {
                                    var transferred = false
                                    try {
                                        val delta = ShortArray(deltaSamples)
                                        val copied = voiceAudioProcessor.drainSpeechTail(delta, accountedSamples, producedSize)
                                        if (copied > 0) {
                                            // Advance accounting ONLY when the sink ACCEPTED the
                                            // commit (Boolean honored — reviewer 23cae70e b2).
                                            transferred = segmentObserver.commitDelta(openContext!!, delta, copied, token)
                                            if (transferred) accountedSamples += copied
                                        }
                                    } finally {
                                        // Every refusal/throw path returns THIS token exactly once.
                                        if (!transferred) segmentObserver.releaseDelta(openContext!!, token)
                                    }
                                } else if (deltaSamples > 0) {
                                    // Refused growth: retention ended by the observer with its own terminal.
                                }
                                accountedSamples = producedSize.coerceAtMost(accountedSamples) // never regress past drained
                            }
                        }

                        is VoiceSegmenterResult.SpeechEnded -> {
                            val totalSamples = result.frames.sumOf { it.samples.size }
                            val durationMs = totalSamples * 1000L / 16000
                            Timber.i("[VoicePipeline] vad ~%dms (%d samples)", durationMs, totalSamples)
                            // Reserve-before-copy for the final tail too (blocker b3): the frames
                            // already exist (recognition owns them); collection copies ONLY after
                            // admitting the exact tail size, and releases on refusal/failure.
                            if (openContext != null && totalSamples > accountedSamples &&
                                segmentObserver.isRetaining(openContext!!.captureId)
                            ) {
                                val finalToken = segmentObserver.reserveDelta(openContext!!, totalSamples - accountedSamples)
                                if (finalToken != null) {
                                    var transferred = false
                                    try {
                                        val tail = shortTailOf(result.frames, accountedSamples)
                                        transferred = segmentObserver.commitDelta(openContext!!, tail, tail.size, finalToken)
                                        if (transferred) accountedSamples = totalSamples
                                    } finally {
                                        if (!transferred) segmentObserver.releaseDelta(openContext!!, finalToken)
                                    }
                                }
                            }

                            val request = shouldTranscribe(result, openContext)
                            if (request != null) {
                                transcribeSegment(result, request)
                            }
                            openContext = null
                        }

                        is VoiceSegmenterResult.Rejected -> {
                            Timber.w("[VoicePipeline] vad rejected - %s", result.reason)
                            segmentObserver.cancelled(openContext, result.reason.name)
                            openContext = null
                        }

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

    // THE EXECUTOR'S ACTUAL RESULT for a dispatched intent (spec audit): called by whoever ran the
    // action — the service, not the engine — with what the executor really reported. Anything the
    // executor never reports stays unobserved; a token that is no longer open is refused.

    /**
     * THE EXECUTOR'S ACTUAL RESULT for ONE dispatch, addressed by its token (spec 03c96924,
     * reviewer 268d8cf0/3fb01865). ONE-SHOT consumption under the invocation lock: the first
     * report consumes the token, so a later report (including the service's throw-fallback) can
     * neither duplicate nor overwrite it, and a token that is no longer open — settled, abandoned
     * or from a restarted lifecycle — is refused rather than attributed to some other capture.
     */
    fun reportExecutionResult(token: InvocationToken, outcome: ExecutionOutcome): Boolean = synchronized(invocationLock) {
        if (openInvocations.remove(token.id) != token) return false
        finalizePublication(token, outcome)
        true
    }

    /**
     * THE RESULT-OR-UNKNOWN FINALIZATION BOUNDARY (reviewer 268d8cf0/3fb01865): the Executed join —
     * which arms publication and release — happens HERE, never at dispatch. A capture therefore
     * publishes only after its invocation is over, carrying its observed result or, if none was
     * reported, the honest schema unknown (dispatch alone is not a result; spec 3ed49695).
     */
    fun settleInvocation(token: InvocationToken) {
        val open: Boolean = synchronized(invocationLock) { openInvocations.remove(token.id) == token }
        if (open) finalizePublication(token, null)
    }

    private fun finalizePublication(token: InvocationToken, outcome: ExecutionOutcome?) {
        if (outcome != null) segmentObserver.noteExecutionOutcome(token.captureId, outcome)
        joinOutcome(CollectionOutcome.Executed(token.captureId, dispatchedWithoutThrowing = true))
    }

    /**
     * RESTART OWNERSHIP (reviewer 268d8cf0): a stop ends every open invocation. Each is finalized
     * with an explicit unknown — the dispatch DID happen, only its result was never observed — so
     * an abandoned capture still publishes instead of leaking retained.
     */
    private fun clearInvocationOwnership() {
        val abandoned: List<InvocationToken> = synchronized(invocationLock) {
            val open = openInvocations.values.toList()
            openInvocations.clear()
            open
        }
        abandoned.forEach { finalizePublication(it, null) }
    }

    /**
     * Dispatch through the token-binding seam. The token is bound BEFORE the handler runs, so a
     * result can never beat the binding or be attributed by intent value. The Executed join is
     * DEFERRED to the token's finalization (settle/report/stop-time abandonment), because that
     * join arms publication — finalizing before publication is what lets the record carry a
     * result instead of a permanent unknown.
     */
    private fun invokeHandler(handler: ((VoiceIntent, InvocationToken?) -> Unit), intent: VoiceIntent) {
        val capture = pendingExecutionContext
        pendingExecutionContext = null
        val token = capture?.let { InvocationToken(id = nextInvocationId.incrementAndGet(), captureId = it.captureId) }
        if (token != null) synchronized(invocationLock) { openInvocations[token.id] = token }
        handler(intent, token)
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
        val capture: CaptureContext?,
        /** Records the ACTUAL translation provenance when the stage runs for this capture. */
        val noteTranslation: (au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.TranslationProvenance) -> Unit = {},
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

    private suspend fun shouldTranscribe(
        segment: VoiceSegmenterResult.SpeechEnded,
        capture: CaptureContext?,
    ): TranscribeRequest? {
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
            // Exactly ONE terminal before exit: the capture COMPLETES with DetectorFailed —
            // not cancelled — so the wake observation state is recorded for this capture.
            segmentObserver.wakeCompleted(
                context = capture,
                speechOnsetSample = segment.speechOnsetSample,
                speechEndSample = segment.speechEndSample,
                wakeState = SegmentObservation.WakeState.DetectorFailed,
                framesProvider = { segment.frames.map { it.samples } to (segment.frames.firstOrNull()?.sampleRateHz ?: 16000) },
            )
            return null
        }

        // Handover WITHOUT an upload-side copy: the provider runs only if revalidation passes.
        segmentObserver.wakeCompleted(
            context = capture,
            speechOnsetSample = segment.speechOnsetSample,
            speechEndSample = segment.speechEndSample,
            wakeState = if (wwResult.detected) SegmentObservation.WakeState.Detected else SegmentObservation.WakeState.NotDetected,
            framesProvider = { segment.frames.map { it.samples } to (segment.frames.firstOrNull()?.sampleRateHz ?: 16000) },
        )

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
                capture = capture,
                noteTranslation = { provenance -> capture?.let { segmentObserver.noteTranslation(it, provenance) } },
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
                TranscribeRequest(
                    capture = capture,
                    noteTranslation = { provenance -> capture?.let { segmentObserver.noteTranslation(it, provenance) } },
                    samples = floatSamples,
                    wakePositive = false,
                )
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
            joinOutcome(CollectionOutcome.NotCollected(request.capture?.captureId ?: return, "bleed"))
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
            joinOutcome(
                CollectionOutcome.NotCollected(request.capture?.captureId ?: return, if (request.wakePositive) "wake-only" else "empty"),
            )
            return
        }
        // Recognized: the ACTUAL trimmed transcript + detected language of THIS capture.
        request.capture?.let { joinOutcome(CollectionOutcome.Recognized(it.captureId, transcript, asrResult.detectedLanguage)) }
        // Translate to English when the ASR backend did not already translate and
        // the detected language is not English (the SenseVoice CJK path).
        // Use the wake-trimmed transcript from LFM's WakeTranscriptTrimmer.
        val trimmedResult = asrResult.copy(text = transcript)
        val routePrep = prepareRoutingInput(trimmedResult, b)
        // OBSERVED translation provenance travels with the capture (never claimed when absent).
        routePrep.translation?.let(request.noteTranslation)
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
        // "Addressed" is this segment's own wake detection, per core voice-intents.md: a Detected
        // segment that we could not route gets the spoken line, a NotDetected one keeps the earcon.
        // The grace window is true for both, so it cannot make that distinction.
        processUtterance(routePrep.input!!, addressed = request.wakePositive, capture = request.capture)
    }

    /** Samples of [frames] beyond the first [skip] — the exact delta the producer added. */
    private fun shortTailOf(frames: List<au.com.shiftyjelly.pocketcasts.voicecontrol.audio.PcmAudioFrame>, skip: Int): ShortArray {
        var seen = 0
        val out = ShortArray((frames.sumOf { it.samples.size } - skip).coerceAtLeast(0))
        var offset = 0
        for (frame in frames) {
            if (seen + frame.samples.size <= skip) {
                seen += frame.samples.size
                continue
            }
            val from = (skip - seen).coerceIn(0, frame.samples.size)
            val len = frame.samples.size - from
            System.arraycopy(frame.samples, from, out, offset, len)
            offset += len
            seen += frame.samples.size
        }
        return out
    }

    /** Join an outcome for the capture that produced it; refused silently when uncollected. */
    private fun joinOutcome(outcome: CollectionOutcome) {
        retentionSink.joinOutcome(outcome)
    }

    /** Allowlisted intent category (plan Task 4): coarse family only, never raw payloads. */
    private fun intentCategory(intent: VoiceIntent): String = when (intent) {
        is VoiceIntent.Playback -> "playback"
        is VoiceIntent.Effects -> "effects"
        is VoiceIntent.Volume -> "volume"
        is VoiceIntent.Sleep -> "sleep"
        is VoiceIntent.Chapter -> "chapter"
        is VoiceIntent.CloudRoute -> "cloud"
        else -> "other"
    }

    private suspend fun processUtterance(input: IntentRoutingInput, addressed: Boolean, capture: CaptureContext?) {
        val recognizer = intentRecognizer
        val handler = onIntent ?: return
        fun routed(category: String, fields: Map<String, String> = emptyMap()) {
            if (capture != null) joinOutcome(CollectionOutcome.Routed(capture.captureId, category, fields))
        }

        val ready = recognizer.ensureReady()
        if (ready.isFailure) {
            Timber.e(ready.exceptionOrNull(), "[VoicePipeline] intent not ready")
            // The router never ran, which is the same class as the router's own
            // `model_not_loaded`: a turn happened and nothing an answer it. Say
            // so — silence here is the complaint this change exists to remove.
            // Deliberately not escalated: a capability failure would turn a
            // broken install into cloud traffic (see CloudEscalationPolicy).
            audioFeedbackRenderer.playEarcon(EarconId.ERROR)
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
            // A cloud turn outlives the window it was issued in, so it carries
            // that window's identity and cannot extend a later one.
            routed(intentCategory(intent))
            pendingExecutionContext = capture
            invokeHandler(handler, intent.stampedForWindow(gracePeriodSignal.currentGeneration))
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
            CloudEscalation.EARCON -> audioFeedbackRenderer.playEarcon(EarconId.ERROR)

            CloudEscalation.SILENT -> Unit

            // They addressed us and we could not place it: say so in their language, falling back
            // to the earcon in a locale this build has no line for.
            CloudEscalation.SPEAK_UNROUTED -> {
                val line = SpokenLine.forKey(KEY_CLOUD_UNROUTED, templateResolver, currentLocale())
                if (line.isBlank()) {
                    audioFeedbackRenderer.playEarcon(EarconId.ERROR)
                } else {
                    audioFeedbackRenderer.render(VoiceResponse.Spoken(line))
                }
            }

            // Nothing was sent, but a turn happened: the earcon table already
            // requires a tone for this case.
            CloudEscalation.SPEAK_ERROR -> audioFeedbackRenderer.playEarcon(EarconId.ERROR)

            // The client has no answer of its own, so the service gets asked —
            // through the same handler a chosen `cloud_route` uses, so
            // superseded-turn cancellation and the auto-pause obligation apply.
            CloudEscalation.DISPATCH -> {
                val issuedUnder = gracePeriodSignal.issueEscalation()
                if (issuedUnder == null) {
                    // No open window, or this window's one dispatch is spent.
                    Timber.i(
                        "[VoicePipeline] cloud escalation skipped (%s)",
                        gracePeriodSignal.escalationRefusal(),
                    )
                    audioFeedbackRenderer.playEarcon(EarconId.ERROR)
                    return
                }
                routed("cloud_escalation")
                pendingExecutionContext = capture
                Timber.i("[VoicePipeline] cloud escalation ← '%s' (reason=%s)", input.routerTranscript, reason)
                invokeHandler(
                    handler,
                    VoiceIntent.CloudRoute(
                        request = input.routerTranscript,
                        tier = VoiceIntent.CloudTier.Unknown,
                        windowGeneration = issuedUnder,
                        origin = VoiceIntent.CloudRouteOrigin.RoutingFailure,
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
                // The BACKEND itself produced English: its model identity IS the translation
                // provenance, and a result was produced by definition of this path.
                translation = requestTranslationProvenance(backend, translatedText = trimmed.text),
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
            // A translated RESULT was observed; the on-device model's own identity/version is not
            // observable here, so the model is honest absence rather than a fixed label.
            translation = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.TranslationProvenance(
                model = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef.None,
                producedResult = true,
                // The OBSERVED result text and the target language travel as SEPARATE fields
                // (reviewer 57719ad2): the record schema has translated_text AND target_language.
                translatedText = translated,
                targetLanguage = "en",
            ),
        )
    }

    /** The selected backend's manifest identity, when it is the component translating. */
    private fun requestTranslationProvenance(
        backend: AsrBackend,
        translatedText: String,
    ) = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.TranslationProvenance(
        model = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef.Named(
            backend.requiredModel.targetDir,
            backend.requiredModel.files.firstOrNull { it.manifestId.isNotEmpty() }?.manifestId ?: "",
        ),
        producedResult = true,
        // The backend's English output IS the translated text: observed, not assumed.
        translatedText = translatedText,
        targetLanguage = "en",
    )

    private data class RoutePrep(
        val input: IntentRoutingInput?,
        val translateNote: String?,
        val dropWithError: Boolean,
        /** OBSERVED translation provenance for this capture (reviewer 8288ac15); null = none ran. */
        val translation: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.TranslationProvenance? = null,
    )

    fun stop() {
        // Listening closure: discard ONLY the current partial through the same serialized
        // observer lifecycle (one monitor guards pipeline events and external signals alike);
        // never resume capture to complete an example. Completed contributions survive.
        segmentObserver.listeningClosed("pipeline-stopped")
        // Every open invocation ends with the lifecycle: no result can cross a stop/restart.
        clearInvocationOwnership()
        processingJob?.cancel()
        processingJob = null
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
