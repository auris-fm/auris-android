package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.annotation.RequiresPermission
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackend
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.TranslationStage
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.OboeConfig
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceAudioProcessor
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceSegmenterResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.captureTimeOfSegmentStartMs
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconId
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.SpokenLine
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.SpokenTemplateResolver
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.PredictedIntentName
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
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.WakeOnlyRoutingEmission
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
    // Constructor parameter (Kotlin defaults apply only at Kotlin call sites), and the real resolver
    // Comes from VoiceControlModule.provideSpokenTemplateResolver.
    private val currentLocale: () -> Locale = { Locale.getDefault() },
    private val translationStage: TranslationStage,
    // Upload observation of the ACTUAL segment path. Optional only for Kotlin
    // Test call sites; Dagger supplies the production pair. The live [collectionAuthority] is
    // Consulted at EVERY retention decision — start admission, revalidation before handoff —
    // So a start-time yes can never authorize retention after withdrawal/expiry/account change.
    private val collectionAuthority: () -> AuthorityDecision,
    private val retentionSink: ContributionRetentionSink,
    private val segmentObserver: SegmentObserver,
    // Upload observation is OFF unless a composition module binds all three collaborators;
    // Dagger never applies Kotlin defaults, so an unbound constructor fails at build time.
    @ApplicationContext private val context: Context,
) {

    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var processingJob: Job? = null

    // EVERY processing job stop() cancelled that has not yet finished, exposed so service teardown
    // Can await them all before releasing the ASR backend. These run on this engine's own
    // `SupervisorJob + IO` scope, so joining the service scope's root does not cover them
    // A single slot is also not enough: with stop/start/stop the older native
    // Call can still be running while a newer job exists, and dropping the older one would let
    // Teardown release the backend underneath it.

    /**
     * The processing jobs this engine cancelled, so a caller can tell an expected cancellation from
     * a genuine failure: the service's teardown cancels them deliberately, and treating every
     * cancelled job as abnormal would report a normal shutdown as a held failure.
     */
    internal val cancelledProcessingJobs: Set<Job> get() = cancelledJobs.toSet()

    /** The completion cause recorded for [job], or null when it finished cleanly or is still running. */
    internal fun recordedOutcome(job: Job): Throwable? = outcomes[job]?.takeIf { it !== CLEAN_COMPLETION }

    /** Jobs this engine cancelled because a stop was requested, i.e. EXPECTED cancellation. */
    internal val cancelledByThisTeardown: Set<Job> get() = cancelledJobs.toSet()

    /**
     * Drop bookkeeping for jobs that are finished AND already judged.
     *
     * Without this a long-lived service accumulates one entry per stop, so consumed outcomes and
     * cancellation markers are pruned once they can no longer influence a verdict.
     */
    internal fun pruneConsumedBookkeeping(onlyKeep: Set<Job>) {
        // Scan the OWNERSHIP set, not the unfinished list. The completion
        // Callback removes a job from `unfinished` the moment it finishes, so searching that list
        // For completed jobs found the ones already gone and retained the maps forever: outcomes,
        // Cancellation markers and ownership all leaked one entry per utterance.
        val finished = ownedProcessingJobs.keys.filter { it.isCompleted && it !in onlyKeep }
        finished.forEach {
            outcomes.remove(it)
            cancelledJobs.remove(it)
            ownedProcessingJobs.remove(it)
        }
        unfinished.removeAll(finished.toSet())
    }

    private val cancelledJobs = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()

    // The cause each tracked processing job completed with, captured when it finished. Immutable
    // Once recorded, so judging a job never depends on when the snapshot ran.
    private val outcomes = java.util.concurrent.ConcurrentHashMap<Job, Throwable>()

    /** Every processing job this engine created, so a pre-snapshot failure still has an owner. */
    private val ownedProcessingJobs = java.util.concurrent.ConcurrentHashMap<Job, Unit>()

    internal val unfinishedProcessingJobs: List<Job>
        get() = unfinished.toList()

    /**
     * EVERY processing job this engine ever created, running or finished.
     *
     * [unfinishedProcessingJobs] is not enough for a verdict: a job that was removed from the
     * unfinished list — cancelled, or finished before the snapshot ran — vanished from quiescence's
     * view entirely, so a failure that happened before the snapshot was never judged. Ownership outlives
     * the unfinished list; this is the set quiescence must scan.
     */
    internal val ownedJobs: Set<Job> get() = ownedProcessingJobs.keys.toSet()

    private val unfinished = java.util.concurrent.CopyOnWriteArrayList<Job>()
    private var playbackBufferProvider: (() -> PlaybackReference)? = null

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
     * one dispatch's identity. Reports are addressed by this token, never by the intent value:
     * identical intents dispatched concurrently are different invocations with different captures.
     */
    data class InvocationToken(val id: Long, val captureId: java.util.UUID)

    /**
     * One dispatch's identity plus its per-action observations, append-only:
     * every reported fact is preserved in arrival order. Settlement aggregates — the supported
     * schema can carry one outcome, so full agreement publishes it and disagreement publishes the
     * explicit unknown rather than an invented single outcome.
     */
    internal class InvocationRecord(val token: InvocationToken) {
        val observations = mutableListOf<ExecutionOutcome>()
    }

    /** Open invocations awaiting finalization; guarded by [invocationLock]. */
    private val openInvocations = java.util.concurrent.ConcurrentHashMap<Long, InvocationRecord>()

    /** Serializes one-shot token consumption against settle/stop. */
    private val invocationLock = Any()

    private val nextInvocationId = java.util.concurrent.atomic.AtomicLong()

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(
        backend: AsrBackend,
        audioRoute: AudioRoute,
        listeningMode: ListeningMode,
        playbackBufferProvider: () -> PlaybackReference,
        micExposureProvider: () -> MicExposure,
        // The dispatch handler receives the INVOCATION token for this specific dispatch, or null when
        // The intent belongs to no retained capture. Results are reported back against that token.
        onIntent: (VoiceIntent, InvocationToken?) -> Unit,
        // Invoked when the processing job ends for any reason, so the service can clear capture
        // State the pipeline no longer backs. The job ends on an intentional stop too; the caller
        // Scopes the reaction to its start generation so only an unexpected end is acted on.
        onTerminated: () -> Unit = {},
    ): CaptureAttachment {
        this.backend = backend
        this.currentMode = listeningMode
        // The handler receives the invocation token for THIS dispatch; the binding lives in
        // InvokeHandler at the dispatch sites.
        // The token binding happens inside invokeHandler at the dispatch sites, not by wrapping
        // Here: the internal dispatch sites call invokeHandler directly, and a wrapper here would
        // Run it twice — the inner call would see pendingExecutionContext already consumed and
        // Hand the caller a null token (found by the overlapping-dispatch control).
        this.onIntent = onIntent
        this.currentRouteClass = audioRoute::class.simpleName ?: AudioRoute.Unknown::class.simpleName!!
        captureSessionIncarnation += 1
        this.playbackBufferProvider = playbackBufferProvider
        this.micExposureProvider = micExposureProvider
        utteranceFilter.reset()

        val attached = CompletableDeferred<Boolean>()
        lateinit var started: Job
        started = scope.launch {
            Timber.i("[VoicePipeline] start route=%s bluetoothCandidate=%b", audioRoute, audioRoute is AudioRoute.BluetoothA2dpOnly)
            // Prepare the backend on every start. Not because a pause releases it — stop()
            // Deliberately keeps it warm — but because the engine can be handed a backend that
            // Was never prepared, and because a start that cannot be prepared must not run a
            // Pipeline: an unprepared backend returns an empty result for every utterance
            // Instead of failing, which is indistinguishable from speech it could not read.
            val backendReady = backend.ensureReady()
            if (backendReady.isFailure) {
                Timber.e(backendReady.exceptionOrNull(), "[VoicePipeline] ASR backend not ready — not listening")
                return@launch
            }
            // Attach the native VAD pipeline to the confirmed stream before reporting the start
            // As attached. The caller hands the stream over only on a true signal, so a start
            // That ends here leaves the stream owned by the caller and closed, never live with
            // No consumer.
            //
            // Sco ownership: a Bluetooth candidate is acquired before the engine starts, and the
            // Stream opened by acquisition is the one this pipeline attaches to. Opening SCO here
            // Would deadlock under the isolated-microphone rule (voice-control-core.md > Route
            // Acquisition), so the engine does not perform its own SCO setup even when the route
            // Is BluetoothA2dpOnly.
            if (!voiceAudioProcessor.attachCapture()) {
                Timber.e("[VoicePipeline] capture attach failed — not listening")
                return@launch
            }
            attached.complete(true)
            // A Bluetooth candidate is acquired before the engine starts; the stream opened
            // By acquisition is the one this pipeline attaches to. Opening SCO here would
            // Deadlock under the isolated-microphone rule (voice-control-core.md > Route
            // Acquisition).
            try {
                var openContext: CaptureContext? = null
                var accountedSamples = 0
                voiceAudioProcessor.startProcessing().collect { result ->
                    when (result) {
                        is VoiceSegmenterResult.SpeechStarted -> {
                            // Freeze the capture context SYNCHRONOUSLY here — before any await
                            // Boundary — and keep the issued value for every later stage of this
                            // Capture. Refused captures yield null and are excluded downstream.
                            // Admission before allocation: read only the native size counter, let
                            // The observer reserve exact bytes; drain into an admitted array whose
                            // Ownership then transfers to the sink. Refusal allocates NOTHING and
                            // Leaves the recognition buffer untouched.
                            val initialSize = voiceAudioProcessor.speechPcmSize()
                            // Per-capture model provenance: the
                            // ACTUAL selected backend's model identity, frozen with the context.
                            // The VERSION is the model's own manifest identity, never a filename.
                            val spec = backend.requiredModel
                            val asrRef = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef.Named(
                                spec.targetDir,
                                spec.files.firstOrNull { it.manifestId.isNotEmpty() }?.manifestId ?: "",
                            )
                            // Translation provenance is NOT knowable at capture start (the stage may
                            // Or may not run for this utterance); it is recorded when observed.
                            // ONE admission attempt, gated by consent/policy and the per-clip
                            // Ceiling only (core c74d7e30). There is no allowance to provision,
                            // No prepaid token to refill and no server round trip on this path:
                            // The daily limit is metered server-side at acceptance of actual audio
                            // Bytes. A refusal here is therefore FINAL for this utterance and
                            // Means no eligible collection, never "waiting for a batch".
                            val admission = segmentObserver.speechStartedAdmitting(
                                captureSessionIncarnation,
                                currentRouteClass,
                                initialSize,
                                asrRef,
                                // Real grace state frozen at segment start (spec phase 2):
                                // Wake word required unless the follow-up grace window is active.
                                wakeRequired = !gracePeriodSignal.isActive.value,
                            )
                            openContext = admission?.context
                            if (admission != null) {
                                // Lease-protected initial allocation (
                                // release is addressed by the immutable handle —
                                // Valid even if revocation closes this capture or a successor
                                // Opens mid-copy. The admit token's charge leaves exactly once:
                                // Transferred into the prefix on success, or returned here.
                                var transferred = false
                                try {
                                    val firstChunk = ShortArray(initialSize)
                                    val copied = if (initialSize > 0) voiceAudioProcessor.drainSpeechPcm(firstChunk) else 0
                                    // Actual sink transfer result; drained valid count passed
                                    // Separately from array capacity.
                                    transferred = segmentObserver.adoptFirstChunk(admission, firstChunk, copied)
                                    if (transferred) accountedSamples = copied
                                } finally {
                                    if (!transferred) segmentObserver.releaseInitialChunk(admission)
                                }
                            }
                        }

                        is VoiceSegmenterResult.SpeechContinuing -> {
                            // Reserve-before-copy (blocker b3): read only the native size counter;
                            // Admit the delta into collection ownership FIRST; allocate + drain the
                            // Tail ONLY for an admitted delta; any failure releases the reservation.
                            // Not retaining (gate off / cap-ended / refused capture): ZERO collection
                            // Work — no peek array, no copy — recognition continues untouched.
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
                                            // Advance accounting only when the sink accepted the
                                            // Commit.
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
                            // Already exist (recognition owns them); collection copies ONLY after
                            // Admitting the exact tail size, and releases on refusal/failure.
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
        // Cancellation before the body ran), report that the stream was never taken. Completing
        // An already-completed true is a no-op.
        processingJob = started
        started.invokeOnCompletion {
            attached.complete(false)
            onTerminated()
        }
        // Ownership and outcome at creation: registering only in stop() missed
        // Work that finished and was pruned before the teardown ever looked, and gave a pre-snapshot
        // Failure nowhere to live. Every processing job is owned and its terminal cause recorded the
        // Moment it exists; stop() only decides which are cancelled by us.
        ownedProcessingJobs[started] = Unit
        started.invokeOnCompletion { cause -> outcomes[started] = cause ?: CLEAN_COMPLETION }
        Timber.i("[VoicePipeline] engine start requested backend=%s mode=%s", backend::class.simpleName, listeningMode)
        return CaptureAttachment(attached)
    }

    // The executor's actual result for a dispatched intent (spec audit): called by whoever ran the
    // Action — the service, not the engine — with what the executor really reported. Anything the
    // Executor never reports stays unobserved; a token that is no longer open is refused.

    /**
     * the executor's actual result for one dispatch, addressed by its token
     * One-shot consumption under the invocation lock: the first
     * report consumes the token, so a later report (including the service's throw-fallback) can
     * neither duplicate nor overwrite it, and a token that is no longer open — settled, abandoned
     * or from a restarted lifecycle — is refused rather than attributed to some other capture.
     */
    // Test/diagnostic seam: opens the same invocation record a real dispatch would, so a control
    // Can drive report/settle against the engine's ACTUAL structures. Never used in production.
    internal fun openInvocationForTest(captureId: java.util.UUID, id: Long): InvocationToken {
        val token = InvocationToken(id = id, captureId = captureId)
        synchronized(invocationLock) { openInvocations[token.id] = InvocationRecord(token) }
        return token
    }

    internal fun reportExecutionResult(token: InvocationToken, outcome: ExecutionOutcome): Boolean = synchronized(invocationLock) {
        // The fact is recorded; the invocation stays open. Publication happens once, at explicit
        // Settlement — a first fact no longer pre-empts a later observed failure, and the
        // Finalize boundary remains the single publication point.
        val record = openInvocations[token.id] ?: return false
        if (record.token != token) return false
        record.observations += outcome
        true
    }

    /**
     * the result-or-unknown finalization boundary: the Executed join —
     * which arms publication and release — happens HERE, never at dispatch. A capture therefore
     * publishes only after its invocation is over, carrying its observed result or, if none was
     * reported, the honest schema unknown.
     */
    fun settleInvocation(token: InvocationToken): Boolean = settleAndReport(token)

    /**
     * Returns true when THIS call consumed the invocation (mismatched or already-settled tokens
     * return false and change nothing).
     */
    private fun settleAndReport(token: InvocationToken): Boolean {
        // Validate and consume in one lock interval: a remove-then-restore
        // Two-step window let a concurrent report/settle lose a fact or resurrect a stopped
        // Invocation. Here the stored record must match the FULL token before it is consumed.
        val record: InvocationRecord? = synchronized(invocationLock) {
            val candidate = openInvocations[token.id]
            if (candidate != null && candidate.token == token) openInvocations.remove(token.id) else null
        }
        record?.let {
            finalizePublication(it.token, aggregateObservations(it.observations))
            return true
        }
        return false
    }

    private fun finalizePublication(token: InvocationToken, outcome: ExecutionOutcome?) {
        if (outcome != null) segmentObserver.noteExecutionOutcome(token.captureId, outcome)
        joinOutcome(CollectionOutcome.Executed(token.captureId, dispatchedWithoutThrowing = true))
    }

    /**
     * restart ownership: a stop ends every open invocation. Each is finalized
     * with an explicit unknown — the dispatch DID happen, only its result was never observed — so
     * an abandoned capture still publishes instead of leaking retained.
     */
    private fun clearInvocationOwnership() {
        val abandoned: List<InvocationRecord> = synchronized(invocationLock) {
            val open = openInvocations.values.toList()
            openInvocations.clear()
            open
        }
        // An abandoned invocation publishes its supported aggregate — the agreed fact, or the
        // Explicit unknown when observations disagree — never an invented claim over known facts
        // never silence over a loss.
        abandoned.forEach { finalizePublication(it.token, aggregateObservations(it.observations)) }
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
        if (token != null) synchronized(invocationLock) { openInvocations[token.id] = InvocationRecord(token) }
        try {
            handler(intent, token)
        } catch (t: Throwable) {
            // A SYNCHRONOUS handler throw must not strand the token: settle it here so the
            // Invocation finalizes (recorded fact or explicit unknown) exactly once.
            if (token != null) settleInvocation(token)
            throw t
        }
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
     * grace, and forwards the complete VAD segment to ASR. Wake-positive
     * time-band trim happens on timed ASR tokens after ASR, not by cutting audio.
     * - Negative outside grace (WakeWord): drops the segment.
     * - Negative during grace (Continuous): forwards the full segment.
     * - Wake-only is decided after ASR: an empty leftover after trim is dropped before
     * routing, so it stays silent rather than spending the grace window.
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
        /**
         * minted at capture admission: the shared utterance identity for this
         * logical turn. Created when the segment is admitted for transcription,
         * before ASR/trim/translation/routing, so every diagnostic and log from
         * this point forward can name the same request. Carried through to the
         * wire request_id unchanged.
         */
        val admittedId: String = java.util.UUID.randomUUID().toString(),
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

    /**
     * True when [text] carries at least one Unicode letter or number (any script).
     * Iterates code points rather than UTF-16 Char units so supplementary letters
     * and numbers (e.g. U+20000 𠀀, U+1D7DF 𝟟) are recognised correctly.
     */
    private fun hasLetterOrNumber(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            if (Character.isLetter(cp) || Character.isDigit(cp)) return true
            val cat = Character.getType(cp)
            if (cat == Character.LETTER_NUMBER.toInt() || cat == Character.OTHER_NUMBER.toInt()) return true
            i += Character.charCount(cp)
        }
        return false
    }

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
        // Every utterance so wake-word audio is always stripped and every
        // Detection is acknowledged.
        val wwResult = runCatching {
            wakeWordDetector.detect(
                segment = floatSamples,
                sampleRateHz = segment.frames.firstOrNull()?.sampleRateHz ?: 16000,
                speechOnsetSample = segment.speechOnsetSample,
            )
        }.getOrElse { e ->
            Timber.w(e, "[VoicePipeline] wake detection failed, dropping segment")
            // Exactly one terminal before exit: the capture completes with DetectorFailed —
            // Not cancelled — so the wake observation state is recorded for this capture.
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
            // Measured wake facts (spec phase 2): recorded whenever the detector measured them.
            wakeMeasurement = SegmentObservation.WakeMeasurement(
                score = wwResult.confidence.takeUnless { it.isNaN() }?.toDouble(),
                threshold = wwResult.threshold.takeUnless { it.isNaN() }?.toDouble(),
            ),
            framesProvider = { segment.frames.map { it.samples } to (segment.frames.firstOrNull()?.sampleRateHz ?: 16000) },
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
                // Outside grace: drop — wake word is required.
                // P1: the known wake/bleed decision that skips ASR is a
                // RECORDED fact (existing vocabulary `rejected_by_wake`), not a wait-expiry unknown.
                capture?.let { joinOutcome(CollectionOutcome.NotCollected(it.captureId, "rejected_by_wake")) }
                    ?: Timber.i("voice-capture publish: refused reason=no-capture-context stage=wake-drop")
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
        // Own timeline position, and the segment carries the capture clock, so the correlator can
        // Compare the two at a SHARED instant instead of at raw buffer offsets. When the stream
        // Reports no timestamp, the capture time stays unknown and the correlator falls back to
        // Offset matching, which is the behaviour from before a clock existed.
        val playbackReference = playbackBufferProvider?.invoke()
            ?: PlaybackReference(FloatArray(0), sampleRateHz, startPositionMs = null)
        // Named `Ms` because the correlator's clock is in milliseconds: the capture observation is
        // Nanoseconds and the conversion happens here, so a reader cannot pair the two domains by
        // Accident. The comment on the helper says the same thing at the point of the division.
        // The anchor describes SAMPLE 0 of the buffer the filter receives (pre-roll included):
        // The correlator aligns the whole buffer against the reference window, so the anchor must
        // Be the buffer's own start, not the speech onset inside it.
        val micPositionMs = segment.captureTimeOfSegmentStartMs()
        if (!utteranceFilter.shouldProcessReference(floatSamples, false, 0, playbackReference, micPositionMs)) {
            Timber.i("[VoicePipeline] → drop (bleed filter)")
            joinOutcome(CollectionOutcome.NotCollected(request.capture?.captureId ?: return, "bleed"))
            return
        }

        // P1: freeze the invoked backend's verified loaded identity at the
        // Actual invocation — carried independently of the result, so failed/empty invocations
        // Keep provenance and a replacement between SpeechStarted and here cannot misattribute.
        val invokedIdentity = verifiedLoadedIdentity(b)
        request.capture?.let { segmentObserver.noteAsrInvocation(it, invokedIdentity) }
        // ASR
        val asrStartedAt = System.currentTimeMillis()
        val asrResult = try {
            b.transcribe(floatSamples, sampleRateHz)
        } catch (ce: java.util.concurrent.CancellationException) {
            // Cancellation is not a provider failure: record the known cancelled fact and rethrow
            // — nonterminal, no early publication.
            request.capture?.let { joinOutcome(CollectionOutcome.AsrCancelled(it.captureId)) }
            throw ce
        } catch (t: Throwable) {
            // A known provider failure is a RECORDED fact: failed/provider_error,
            // Never a wait-expiry unknown. The AsrFailed join is a publishable terminal.
            request.capture?.let { joinOutcome(CollectionOutcome.AsrFailed(it.captureId)) }
            Timber.w(t, "[VoicePipeline] asr provider failed")
            return
        }
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
            // P1: command trimming may remove recognized wake text, so a
            // Wake-only drop is not necessarily an empty recognition. Only a genuinely empty
            // Raw result is completed/empty_result; otherwise the ACTUAL transcript is
            // Preserved as the recognized fact (normal trimming stays a pipeline behavior,
            // Never an erasure of the observed output).
            if (asrResult.text.isNotBlank()) {
                request.capture?.let {
                    // The observed wake-positive
                    // Trim consumed everything — raw text existed and the trimmed command
                    // Transcript is empty — so routing never ran: not_run/wake_only. The
                    // Time-based isWakeOnly arm of this drop (short speech, command text intact)
                    // Must NOT be typed wake_only; that gate is the transcript emptiness itself.
                    // Joined FIRST so Recognized stays the candidate outcome: the transcript fact
                    // Outranks the routing absence, and provenance reads both from the history.
                    if (transcript.isBlank() && WakeOnlyRoutingEmission.enabled) {
                        joinOutcome(CollectionOutcome.RoutingNone(it.captureId, "wake_only"))
                    }
                    joinOutcome(CollectionOutcome.Recognized(it.captureId, asrResult.text, asrResult.detectedLanguage))
                }
            } else {
                val captureId = request.capture?.captureId ?: run {
                    Timber.i("voice-capture publish: refused reason=no-capture-context stage=asr-empty")
                    return
                }
                // The backend MEASURED a language on this result even though it produced no text.
                // The empty-result observation is what the stage records, so the measurement rides
                // With it: a measured fact dies here otherwise.
                joinOutcome(CollectionOutcome.NotCollected(captureId, "empty", asrResult.detectedLanguage))
            }
            return
        }
        // Task #78: a transcript with no content -- punctuation, symbols or whitespace only,
        // After the wake trim -- is not a user request. Discard it silently BEFORE translation,
        // Routing or any feedback: no dialog consumption, no grace extension, no error cue.
        // Content is any Unicode letter or number, so CJK particles (停, 嗯) and digit
        // Lookalikes (², ½) count as content while ".", "…" and "?!" do not.
        if (!hasLetterOrNumber(transcript)) {
            Timber.i(
                "[VoicePipeline] asr %s %dms lang=%s id=%s '%s'%s → drop (non-content)",
                b::class.simpleName,
                asrMs,
                asrResult.detectedLanguage ?: "?",
                request.admittedId,
                transcript,
                trimNote?.let { " $it" } ?: "",
            )
            // No usable command text, but the backend did observe this transcript and did measure a
            // Language. Both are facts: the command is discarded, the observation is not, so they
            // Travel with the non-content outcome instead of dying here. The text recorded is the
            // RAW result — the observation is what ASR produced, and a trim is a pipeline behaviour,
            // never an erasure of the output.
            request.capture?.let {
                joinOutcome(
                    CollectionOutcome.NotCollected(
                        it.captureId,
                        "non_content",
                        asrResult.detectedLanguage,
                        observedText = asrResult.text,
                    ),
                )
            } ?: Timber.i("voice-capture publish: refused reason=no-capture-context stage=non-content")
            return
        }
        // Recognized: the actual trimmed transcript + detected language of this capture.
        request.capture?.let { joinOutcome(CollectionOutcome.Recognized(it.captureId, transcript, asrResult.detectedLanguage)) }
            ?: Timber.i("voice-capture publish: refused reason=no-capture-context stage=recognized")
        // Translate to English when the ASR backend did not already translate and
        // The detected language is not English (the SenseVoice CJK path).
        // Use the wake-trimmed transcript from LFM's WakeTranscriptTrimmer.
        val trimmedResult = asrResult.copy(text = transcript)
        val routePrep = prepareRoutingInput(trimmedResult, b)
        // OBSERVED translation provenance travels with the capture (never claimed when absent).
        routePrep.translation?.let(request.noteTranslation)
        // P1: a known translation-skip is a RECORDED fact, not unknown.
        routePrep.translationSkipReason?.let { reason ->
            request.capture?.let { ctx ->
                segmentObserver.noteTranslation(
                    ctx,
                    au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.TranslationProvenance(
                        model = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef.None,
                        producedResult = false,
                        translatedText = null,
                        targetLanguage = null,
                        skipReason = reason,
                    ),
                )
            }
        }
        // P1: a run-and-fail is a recorded FAILURE fact (state failed), not a skip or unknown.
        if (routePrep.translationFailed) {
            request.capture?.let { ctx ->
                segmentObserver.noteTranslation(
                    ctx,
                    au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.TranslationProvenance(
                        model = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef.None,
                        producedResult = false,
                        translatedText = null,
                        targetLanguage = null,
                        failedWith = "provider_error",
                    ),
                )
            }
        }
        // Source-first: lang + first quote are ASR; translate note carries the English result.
        // Printing post-translate fields first made lines like
        // Lang=en 'Play.' translate=yue→en '播放。'
        // Read as English ASR that somehow translated into Chinese.
        Timber.i(
            "[VoicePipeline] asr %s %dms lang=%s id=%s '%s'%s%s",
            b::class.simpleName,
            asrMs,
            trimmedResult.detectedLanguage ?: "?",
            request.admittedId,
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
        // Segment that we could not route gets the spoken line, a NotDetected one keeps the earcon.
        // The grace window is true for both, so it cannot make that distinction.
        processUtterance(routePrep.input!!, addressed = request.wakePositive, capture = request.capture, admittedId = request.admittedId)
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

    /**
     * The name this intent publishes as `routing.predicted_intent`.
     *
     * The upload spec fixes the value as `<tool>.<action>` from the Voice Intents tool schema, or
     * `cloud_route` alone, derived from the intent's type and action and never from its arguments.
     */
    internal fun intentCategory(intent: VoiceIntent): String? = PredictedIntentName.of(intent)

    private suspend fun processUtterance(input: IntentRoutingInput, addressed: Boolean, capture: CaptureContext?, admittedId: String) {
        val recognizer = intentRecognizer
        val handler = onIntent ?: run {
            // No handler is wired, so routing cannot happen for this capture. Recorded as its own
            // cause: leaving the record's routing unreported would let a misconfigured build look
            // like a clip that simply had nothing to route.
            capture?.let { joinOutcome(CollectionOutcome.RoutingFailed(it.captureId, ROUTER_NO_HANDLER)) }
            return
        }
        fun routed(category: String?, fields: Map<String, String> = emptyMap()) {
            // A null name means the schema defines no action for this intent. Routing still
            // happened -- the app decided -- so the stage is recorded; the name is simply absent
            // rather than invented.
            if (capture != null) joinOutcome(CollectionOutcome.Routed(capture.captureId, category, fields))
        }

        val ready = recognizer.ensureReady()
        if (ready.isFailure) {
            Timber.e(ready.exceptionOrNull(), "[VoicePipeline] intent not ready")
            // The router was attempted and could not be used. A fault, and the one fact that
            // explains why nothing was routed, so it is joined before returning.
            capture?.let { joinOutcome(CollectionOutcome.RoutingFailed(it.captureId, ROUTER_UNAVAILABLE)) }
            // The router never ran, which is the same class as the router's own
            // `model_not_loaded`: a turn happened and nothing an answer it. Say
            // So — silence here is the complaint this change exists to remove.
            // Deliberately not escalated: a capability failure would turn a
            // Broken install into cloud traffic (see CloudEscalationPolicy).
            audioFeedbackRenderer.submitEarcon(EarconId.ERROR)
            return
        }

        // P1: the router identity is claimed only when routing
        // Actually runs, bound to the recognizer actually called (`recognizer`, not another
        // Component's load state) — its own verified loader evidence.
        capture?.let { ctxCap ->
            recognizer.loadedModelRef()?.let { ref ->
                segmentObserver.noteRouterInvocation(ctxCap, ref)
            }
        }

        val t0 = System.currentTimeMillis()
        val ctx = VoiceRecognitionContext(
            listeningMode = currentMode,
            micExposure = micExposureProvider?.invoke() ?: MicExposure.Exposed,
        )
        // Captured before the recognition suspends. Recognition can take seconds, and a wake that
        // Arrives while it runs opens a NEW window: stamping the intent afterwards would attach it to
        // The window that opened during the wait, so a request issued before a successor wake would
        // Act as though it belonged to the successor.
        val windowAtRequest = gracePeriodSignal.capturedGeneration()
        // The admitted identity arrives from capture admission (TranscribeRequest.admittedId),
        // Minted BEFORE ASR/trim/translation so every diagnostic from that point forward names
        // The same request. A retry of the same request reuses this value; two separate user acts
        // That happen to say the same words do not. The dialog path mints its own for
        // Clarification flows; this is the producer for a plain utterance.
        val outcome = recognizer.recognize(input, ctx, admittedId)
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
            // That window's identity and cannot extend a later one.
            routed(intentCategory(intent))
            pendingExecutionContext = capture
            invokeHandler(
                handler,
                // Bind the immutable input for this recognition, not model-authored cloud text.
                // Local intents keep their English routing protocol unchanged.
                (
                    if (intent is VoiceIntent.CloudRoute) {
                        intent.copy(
                            request = input.sourceTranscript ?: input.routerTranscript,
                            sourceLanguage = input.sourceLanguage?.takeIf { it.isNotBlank() },
                        )
                    } else {
                        intent
                    }
                    )
                    .stampedForWindow(windowAtRequest)
                    // Only a CLOUD turn carries a logical identity: it is the one dispatch that is
                    // Prepared once and may be retried. A local intent has no wire identity to reuse.
                    .let { it.withLogicalRequest(admittedId) },
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

        // P1: the recognizer's OWN reported decision travels — a null
        // Intent is NOT always no_candidates (it may be validation or a model failure), and
        // The observation stream is NOT closed: a later cloud escalation can still supply
        // Its normal outcome. NONTERMINAL fact.
        capture?.let { joinOutcome(CollectionOutcome.RoutingNone(it.captureId, diagnostic?.reason)) }

        // No usable intent: three outcomes, decided in one place
        // (see [CloudEscalationPolicy] for which reason lands where).
        when (CloudEscalationPolicy.decide(reason, addressed)) {
            // A rejection, not a failure. A wake-negative capture lands here — a follow-up the wake
            // Did not cover in its own segment, or speech caught from the room.
            // Heard something we could not route and the user was not addressing us: a tone is
            // All that is warranted.
            CloudEscalation.EARCON -> audioFeedbackRenderer.submitEarcon(EarconId.ERROR)

            CloudEscalation.SILENT -> Unit

            // They addressed us and we could not place it: say so in their language, falling back
            // To the earcon in a locale this build has no line for.
            CloudEscalation.SPEAK_UNROUTED -> {
                val line = SpokenLine.forKey(
                    KEY_CLOUD_UNROUTED,
                    templateResolver,
                    diagnostic?.sourceLanguage,
                )
                if (line.isBlank()) {
                    audioFeedbackRenderer.submitEarcon(EarconId.ERROR)
                } else {
                    audioFeedbackRenderer.render(VoiceResponse.Spoken(line))
                }
            }

            // Nothing was sent, but a turn happened: the earcon table already
            // Requires a tone for this case.
            CloudEscalation.SPEAK_ERROR -> audioFeedbackRenderer.submitEarcon(EarconId.ERROR)

            // The client has no answer of its own, so the service gets asked —
            // Through the same handler a chosen `cloud_route` uses, so
            // Superseded-turn cancellation and the auto-pause obligation apply.
            CloudEscalation.DISPATCH -> {
                // The origin is the one captured at registration, not the window that happens to be
                // Live now. Recognition can take seconds, and a wake arriving meanwhile opens a NEW
                // Window: escalating under that window would let OLD recognition consume its
                // Successor's conversation. A stale origin is rejected before dispatch.
                if (!gracePeriodSignal.isCurrent(windowAtRequest)) {
                    Timber.i(
                        "[VoicePipeline] cloud escalation skipped: origin %d is no longer live",
                        windowAtRequest,
                    )
                    audioFeedbackRenderer.submitEarcon(EarconId.ERROR)
                    return
                }
                routed("cloud_escalation")
                pendingExecutionContext = capture
                Timber.i("[VoicePipeline] cloud escalation ← '%s' (reason=%s)", input.routerTranscript, reason)
                invokeHandler(
                    handler,
                    VoiceIntent.CloudRoute(
                        request = input.sourceTranscript ?: input.routerTranscript,
                        sourceLanguage = input.sourceLanguage?.takeIf { it.isNotBlank() },
                        tier = VoiceIntent.CloudTier.Unknown,
                        // Stamped with the captured origin, matching the window this recognition was
                        // Issued under -- the same value the chosen-route path uses.
                        windowGeneration = windowAtRequest,
                        origin = VoiceIntent.CloudRouteOrigin.RoutingFailure,
                        // The registered identity, not a second one: this escalation belongs to the same
                        // Logical request as the recognition it follows, so a retry of that request
                        // Names one identity rather than two.
                        logicalRequestId = admittedId,
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
                // P1: the SEPARATE translation stage is skipped because the
                // Invoked backend produced the English text in the SAME transcribe call (observed
                // Output above). Capability alone never asserts a produced translation — but the
                // Backend's actual English output for this invocation is the observed fact, and
                // The skip of the separate stage is a known fact in the owning vocabulary.
                translationSkipReason = "backend_handles_translation",
            )
        }
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
                // P1: known skip — source and target are the same language; no stage ran.
                translationSkipReason = "source_language_target_language",
            )
        }

        val ready = translationStage.ensureReady(detected)
        if (ready.isFailure) {
            // P1: the translator could not run at all — known SKIP, owning vocabulary.
            return RoutePrep(
                input = null,
                translateNote = "translate=fail($detected)",
                dropWithError = true,
                translationSkipReason = "translator_unavailable",
            )
        }
        val translated = translationStage.translate(trimmed.text, detected).getOrElse {
            // P1: the stage ran and failed — a known failure fact.
            return RoutePrep(
                input = null,
                translateNote = "translate=fail($detected)",
                dropWithError = true,
                translationFailed = true,
            )
        }
        if (translated.isBlank()) {
            // P1: the stage ran and returned invalid (blank) output — known failure.
            return RoutePrep(
                input = null,
                translateNote = "translate=blank($detected)",
                dropWithError = true,
                translationFailed = true,
            )
        }
        // Reject no-op "translations" that would mislabel CJK as English.
        if (translated == trimmed.text) {
            // P1: a no-op string does not establish source/target equality — the stage ran
            // And produced invalid output: known failure.
            return RoutePrep(
                input = null,
                translateNote = "translate=noop($detected)",
                dropWithError = true,
                translationFailed = true,
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
            // Observable here, so the model is honest absence rather than a fixed label.
            translation = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.TranslationProvenance(
                model = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef.None,
                producedResult = true,
                // The observed result text and the target language travel as separate fields
                // the record schema has translated_text AND target_language.
                translatedText = translated,
                targetLanguage = "en",
            ),
        )
    }

    /**
     * P1: the backend's EXISTING verified loaded identity — the loader's own
     * manifest identity for the artifact it verified. When the loader cannot establish a name
     * AND version, the result is [ModelRef.None]; an empty version on a Named ref is never minted.
     */
    private fun verifiedLoadedIdentity(backend: AsrBackend): au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef = // P1: only the backend's own successful-load evidence counts; a
        // Configured requiredModel is a requirement, not evidence. Absence → None.
        backend.loadedModelRef() ?: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef.None

    /** The selected backend's manifest identity, when it is the component translating. */
    private fun requestTranslationProvenance(
        backend: AsrBackend,
        translatedText: String,
    ) = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.TranslationProvenance(
        model = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef.Named(
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
        /** OBSERVED translation provenance for this capture; null = none ran.*/
        val translation: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.TranslationProvenance? = null,
        /**
         * P1: a KNOWN skip of the separate translation stage, in the
         * existing owning vocabulary. A skip is a recorded fact, not unknown.
         */
        val translationSkipReason: String? = null,
        /** P1: the translation stage RAN and failed / returned invalid output. */
        val translationFailed: Boolean = false,
    )

    fun stop() {
        // Listening closure: discard ONLY the current partial through the same serialized
        // Observer lifecycle (one monitor guards pipeline events and external signals alike);
        // Never resume capture to complete an example. Completed contributions survive.
        segmentObserver.listeningClosed("pipeline-stopped")
        // Every open invocation ends with the lifecycle: no result can cross a stop/restart.
        clearInvocationOwnership()
        // Keep the processing job so the SERVICE teardown can await it: this job runs on the
        // Engine's own SupervisorJob+io scope, so cancelling serviceScope's root does not wait for
        // It. A noncooperative ASR/native call or its `finally` can otherwise
        // Outlive stop() and overlap VoiceControlService's backend release.
        processingJob?.let { cancelled ->
            // Prune finished entries so a long-lived service does not accumulate them, then track
            // This one until it actually finishes.
            unfinished.removeAll { it.isCompleted }
            unfinished.add(cancelled)
            // Only a job that was still running counts as cancelled by us: a job
            // That had already failed or been cancelled elsewhere keeps its own outcome, and
            // Relabelling it as expected shutdown would hide a real failure.
            if (cancelled.isActive) {
                cancelledJobs.add(cancelled)
            }
            // Capture the outcome at registration. Waiting until the shutdown snapshot misses jobs
            // That finished (and were pruned) before it, and would replay the outcome later.
            cancelled.invokeOnCompletion { cause ->
                // ConcurrentHashMap rejects null values, and a clean completion has no cause, so the
                // Entries are typed nullable and store a sentinel for "finished cleanly".
                outcomes[cancelled] = cause ?: CLEAN_COMPLETION
                unfinished.remove(cancelled)
            }
        }
        processingJob?.cancel()
        processingJob = null
        // Tear down native capture directly too: the processing job's flow `finally` only runs
        // When the event loop was collected, and a capture attached but never collected must
        // Still be stopped. Idempotent with the flow's own teardown.
        voiceAudioProcessor.stopProcessing()
        // Listening pauses are frequent (gate conflicts, route changes), so the backend is
        // Deliberately kept warm here rather than released: releasing it would force a full
        // Model reload on the next start, and everything spoken during that reload is lost.
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
        /**
         * Marks a tracked job that finished with no cause, so the outcome map never stores null
         * (ConcurrentHashMap rejects null values).
         */
        private val CLEAN_COMPLETION: Throwable = java.util.concurrent.CancellationException("finished cleanly")

        /** Client-owned wording, resolved through [SpokenLine] so a foreign locale hears the earcon. */
        private const val KEY_CLOUD_UNROUTED = "pipeline.unclear_command"

        /** The router could not be loaded, so no attempt at routing was possible. */
        private const val ROUTER_UNAVAILABLE = "router_unavailable"

        /** Nothing is wired to receive an intent, so a routed intent would have had no destination. */
        private const val ROUTER_NO_HANDLER = "no_handler"

        /**
         * the supported aggregate over one invocation's per-action observations:
         * full agreement publishes that outcome; ANY disagreement publishes null — the explicit
         * schema unknown (observation_unavailable) — because the schema carries one outcome and
         * inventing a single one over conflicting facts is the defect this channel exists to
         * prevent. Empty observations (nothing observed) also aggregate to null.
         */
        internal fun aggregateObservations(observations: List<ExecutionOutcome>): ExecutionOutcome? {
            val distinct = observations.toSet()
            if (distinct.size > 1) {
                Timber.w("[VoicePipeline] execution observations disagree (%s); recording unknown", distinct)
            }
            return if (distinct.size == 1) distinct.single() else null
        }
    }
}
