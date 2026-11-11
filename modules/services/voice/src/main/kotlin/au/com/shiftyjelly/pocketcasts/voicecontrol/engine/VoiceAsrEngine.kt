package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.annotation.RequiresPermission
import au.com.shiftyjelly.pocketcasts.repositories.cloud.EarlyConnectionOpener
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackend
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.TranslationStage
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.OboeConfig
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.InProgressUtteranceReader
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceAudioProcessor
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceSegmenterResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.captureTimeOfSegmentEndNs
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.captureTimeOfSegmentStartMs
import au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord
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
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.EarlyWakeScore
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeTranscriptTrimmer
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordDetector
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordResult
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
    // Upload observation of the ACTUAL segment path. Optional only for Kotlin
    // test call sites; Dagger supplies the production pair. The live [collectionAuthority] is
    // consulted at EVERY retention decision — start admission, revalidation before handoff —
    // so a START-time YES can never authorize retention after withdrawal/expiry/account change.
    private val collectionAuthority: () -> AuthorityDecision,
    private val retentionSink: ContributionRetentionSink,
    private val segmentObserver: SegmentObserver,
    // Upload observation is OFF unless a composition module binds all three collaborators;
    // Dagger never applies Kotlin defaults, so an unbound constructor fails at build time.
    @ApplicationContext private val context: Context,
    /** One clock for every timing mark, so the table's differences are real quantities. */
    private val elapsedClockMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    /**
     * Opens the route connection early, while the turn is still being recognised.
     *
     * Null disables the optimisation; production supplies it. An early connection only saves a
     * round trip, so every failure here is silent by construction -- the turn dials for itself.
     */
    private val earlyConnectionOpener: EarlyConnectionOpener? = null,
    /**
     * Reads the utterance the native VAD is still accumulating. Null disables early wake scoring:
     * the verdict then only exists at the endpoint, as before.
     */
    private val inProgressUtterance: InProgressUtteranceReader? = null,
) {

    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var processingJob: Job? = null

    // EVERY processing job stop() cancelled that has not yet finished, exposed so service teardown
    // can await them ALL before releasing the ASR backend. These run on this engine's OWN
    // `SupervisorJob + IO` scope, so joining the service scope's root does not cover them
    // A single slot is also not enough: with stop/start/stop the older native
    // call can still be running while a newer job exists, and dropping the older one would let
    // Teardown release the backend underneath it.

    /**
     * The processing jobs THIS ENGINE cancelled, so a caller can tell an expected cancellation from
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
        // callback removes a job from `unfinished` the moment it finishes, so searching that list
        // for completed jobs found the ones already gone and retained the maps forever: outcomes,
        // cancellation markers and ownership all leaked one entry per utterance.
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
    // once recorded, so judging a job never depends on when the snapshot ran.
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
    /** Hands an intent to the executor, with the invocation token for its result attribution
     * and the admitted request id for its timing table. */
    private var onIntent: ((VoiceIntent, InvocationToken?, String?) -> Unit)? = null
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
        // the intent belongs to no retained capture. Results are reported back against that token.
        // The admitted request id rides along so the executor can mark the utterance's timing table.
        onIntent: (VoiceIntent, InvocationToken?, String?) -> Unit,
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
        lateinit var started: Job
        started = scope.launch {
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
                var openContext: CaptureContext? = null
                // Samples of the CURRENT utterance's clip already collected. The clip starts from
                // zero at SpeechStarted (the native counter there is the finished-utterance
                // snapshot), so this is 0 until the segment at SpeechEnded commits the whole
                // utterance; it is kept so the final commit's budget arithmetic stays explicit.
                var accountedSamples = 0
                voiceAudioProcessor.startProcessing().collect { result ->
                    when (result) {
                        is VoiceSegmenterResult.SpeechStarted -> {
                            // Freeze the capture context SYNCHRONOUSLY here — before any await
                            // boundary — and keep the ISSUED value for every later stage of THIS
                            // capture. Refused captures yield null and are excluded downstream.
                            // THE CLIP STARTS FROM ZERO: the native size counter at this moment is
                            // the FINISHED-utterance snapshot (it only fills at segment end), so
                            // reading or draining it here collected the PREVIOUS utterance's audio
                            // as this utterance's prefix. Admission is therefore empty-prefix and
                            // the clip grows from the segment the engine receives at SpeechEnded,
                            // which already carries the whole utterance. (The native buffer itself
                            // is left alone: recognition reads it asynchronously, and clearing it
                            // here could wipe an utterance before ASR copies it.)
                            // A new utterance must not inherit a verdict or an identity scored for
                            // the last one. Both live across the endpoint (SpeechEnded), where the
                            // endpoint path still needs them: the turn must carry the SAME id the
                            // early connection was opened under, or it replaces that connection
                            // with one no turn will ever claim.
                            inFlightUtteranceId = null
                            earlyWakeResult = null
                            earlyUtteranceGeneration = null
                            earlyScoringRetired = false
                            earlyWakeAcknowledged = false
                            // Per-capture model provenance: the
                            // ACTUAL selected backend's model identity, frozen with the context.
                            // The VERSION is the model's own manifest identity, never a filename.
                            val spec = backend.requiredModel
                            val asrRef = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef.Named(
                                spec.targetDir,
                                spec.files.firstOrNull { it.manifestId.isNotEmpty() }?.manifestId ?: "",
                            )
                            // Translation provenance is NOT knowable at capture start (the stage may
                            // or may not run for this utterance); it is recorded when observed.
                            // ONE admission attempt, gated by consent/policy and the per-clip
                            // ceiling only (core c74d7e30). There is no allowance to provision,
                            // no prepaid token to refill and no server round trip on this path:
                            // the daily limit is metered server-side at acceptance of actual audio
                            // bytes. A refusal here is therefore FINAL for this utterance and
                            // means no eligible collection, never "waiting for a batch".
                            val admission = segmentObserver.speechStartedAdmitting(
                                captureSessionIncarnation,
                                currentRouteClass,
                                // Empty-prefix admission: ZERO stays legal and the deltas from the
                                // segment at SpeechEnded grow the clip.
                                0,
                                asrRef,
                                // REAL GRACE STATE FROZEN AT SEGMENT START (spec phase 2):
                                // wake word required unless the follow-up grace window is active.
                                wakeRequired = !gracePeriodSignal.isActive.value,
                            )
                            openContext = admission?.context
                            // PER-UTTERANCE accounting: the clip starts from zero each time, so
                            // the previous utterance's committed size must never leak into this
                            // one's final-tail arithmetic.
                            accountedSamples = 0
                        }

                        is VoiceSegmenterResult.SpeechContinuing -> {
                            // NO COLLECTION HERE: the native size counter this step read is the
                            // FINISHED-utterance snapshot, not the live utterance, so a delta taken
                            // here copied the previous utterance's audio into the current clip.
                            // SpeechEnded already collects the whole utterance from the segment
                            // event, which is the only clip source.
                            // The detector scores only the first EarlyWakeScore.cutoffMillis() after
                            // onset, so for an utterance at least that long the wake answer is fully
                            // determined while the user is still speaking. Score it as soon as that
                            // much audio exists: this is what lets the verdict exist at the trailing-
                            // silence onset, and the connection open there, outside grace too.
                            // A cutoff event carries the generation of the utterance it belongs to.
                            // The event queue is FIFO but the engine is async: a cutoff for utterance
                            // A can be popped after A ended and B started. Acting on it would read
                            // B's snapshot and accept B as A — so the event's generation is matched
                            // BEFORE any score. Where no generation is known yet (no snapshot seen,
                            // segmenters that do not report it), the snapshot check inside the score
                            // remains the guard.
                            val eventGeneration = result.utteranceGeneration
                            val known = earlyUtteranceGeneration
                            if (eventGeneration != null && known != null && eventGeneration != known) {
                                Timber.i(
                                    "[VoicePipeline] stale early-wake cutoff (gen %d, current %d); dropped",
                                    eventGeneration,
                                    known,
                                )
                            } else {
                                scoreWakeEarly(expectedGeneration = eventGeneration)
                            }
                        }

                        is VoiceSegmenterResult.TrailingSilenceBegan -> {
                            // The utterance may be over: this is where the cloud connection starts
                            // opening, so the handshake overlaps the endpoint wait and ASR instead
                            // of following them. Fires once per pause, so an utterance that pauses
                            // and resumes reports it again -- the opener keys on the tag, so a
                            // repeat keeps the connection this utterance already opened rather
                            // than dialling a second one.
                            openEarlyConnectionIfAddressed()
                        }

                        is VoiceSegmenterResult.SpeechEnded -> {
                            // This utterance is over. Its identity is NOT cleared here: the
                            // endpoint path below mints, opens and transcribes under this id, and
                            // the next SpeechStarted clears it for the next utterance.
                            wakePositiveForCurrentUtterance = false
                            // The early-verdict fields are NOT reset here: this event is about to
                            // run the endpoint path, which must still see the early verdict (to
                            // score the detector once and to fire grace and the earcon once). The
                            // next SpeechStarted clears them for the next utterance.
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

                            // The acoustic end is the table's zero point: the instant the user
                            // stopped speaking, after which every cost is the client's. It comes
                            // from the capture sample accounting, not "now": the drain between the
                            // last captured sample and this delivery is CLIENT cost, and stamping
                            // it here would hide exactly the latency the table exists to measure.
                            // Absent accounting stays absent — the table starts at the endpoint.
                            val timing = TurnTimingRecord(
                                requestId = "utt-" + elapsedClockMs(),
                            )
                            // The acoustic end is marked on the record's ONE monotonic clock:
                            // the capture timestamp observation is AudioRecord's nanoTime-domain
                            // instant, the same base as TurnTimingRecord.clockNanos(), so the
                            // endpoint decision's elapsed against it is a real quantity. (The
                            // AudioRecord nanoTime base equals System.nanoTime's base — both are
                            // CLOCK_MONOTONIC.)
                            result.captureTimeOfSegmentEndNs()?.let { captureEndNs ->
                                timing.mark(TurnTimingRecord.Stage.AcousticEnd, captureEndNs)
                            }
                            // The segmenter has already decided the utterance ended by the time
                            // it reports the segment, so the decision is observed here, next to
                            // the acoustic end and distinct from it.
                            timing.mark(TurnTimingRecord.Stage.EndpointDecision, TurnTimingRecord.clockNanos())

                            val request = shouldTranscribe(result, openContext, timing)
                            if (request != null) {
                                // Registered under the turn's ADMITTED id, the one every producer
                                // outside the engine knows: the wire request id on the route socket,
                                // the executor's logical id. CloudRequestSent, LocalActionExecuted
                                // and FirstAudible mark through the registry by that id.
                                TurnTimingRecord.registerFor(request.admittedId, timing)
                                transcribeSegment(result, request, timing)
                            } else {
                                // Nothing ran for this utterance: publish the table so the
                                // dropped turn is still visible with its real stages.
                                timing.publish()
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
        processingJob = started
        started.invokeOnCompletion {
            attached.complete(false)
            onTerminated()
        }
        // Ownership and outcome at creation: registering only in stop() missed
        // work that finished and was pruned before the teardown ever looked, and gave a pre-snapshot
        // failure nowhere to live. Every processing job is owned and its terminal cause recorded the
        // moment it exists; stop() only decides which are cancelled BY US.
        ownedProcessingJobs[started] = Unit
        started.invokeOnCompletion { cause -> outcomes[started] = cause ?: CLEAN_COMPLETION }
        Timber.i("[VoicePipeline] engine start requested backend=%s mode=%s", backend::class.simpleName, listeningMode)
        return CaptureAttachment(attached)
    }

    // THE EXECUTOR'S ACTUAL RESULT for a dispatched intent (spec audit): called by whoever ran the
    // action — the service, not the engine — with what the executor really reported. Anything the
    // executor never reports stays unobserved; a token that is no longer open is refused.

    /**
     * the executor's actual result for one dispatch, addressed by its token
     * One-shot consumption under the invocation lock: the first
     * report consumes the token, so a later report (including the service's throw-fallback) can
     * neither duplicate nor overwrite it, and a token that is no longer open — settled, abandoned
     * or from a restarted lifecycle — is refused rather than attributed to some other capture.
     */
    // Test/diagnostic seam: opens the same invocation record a real dispatch would, so a control
    // can drive report/settle against the engine's ACTUAL structures. Never used in production.
    internal fun openInvocationForTest(captureId: java.util.UUID, id: Long): InvocationToken {
        val token = InvocationToken(id = id, captureId = captureId)
        synchronized(invocationLock) { openInvocations[token.id] = InvocationRecord(token) }
        return token
    }

    internal fun reportExecutionResult(token: InvocationToken, outcome: ExecutionOutcome): Boolean = synchronized(invocationLock) {
        // The FACT is recorded; the invocation stays OPEN. Publication happens once, at explicit
        // settlement — a first fact no longer pre-empts a later observed failure, and the
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
        // two-step window let a concurrent report/settle lose a fact or resurrect a stopped
        // invocation. Here the stored record must match the FULL token before it is consumed.
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
        // explicit unknown when observations disagree — never an invented claim over known facts
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
    private fun invokeHandler(handler: ((VoiceIntent, InvocationToken?, String?) -> Unit), intent: VoiceIntent, admittedId: String?) {
        val capture = pendingExecutionContext
        pendingExecutionContext = null
        val token = capture?.let { InvocationToken(id = nextInvocationId.incrementAndGet(), captureId = it.captureId) }
        if (token != null) synchronized(invocationLock) { openInvocations[token.id] = InvocationRecord(token) }
        try {
            handler(intent, token, admittedId)
        } catch (t: Throwable) {
            // A SYNCHRONOUS handler throw must not strand the token: settle it here so the
            // invocation finalizes (recorded fact or explicit unknown) exactly once.
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
         * MINTED AT CAPTURE ADMISSION: the shared utterance identity for this
         * logical turn. Created when the segment is admitted for transcription,
         * before ASR/trim/translation/routing, so every diagnostic and log from
         * this point forward can name the same request. Carried through to the
         * wire request_id unchanged.
         */
        val admittedId: String,
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

    /**
     * The utterance currently in flight, from trailing silence to its next resume.
     *
     * The connection is opened per UTTERANCE, and the tag must be the same one the turn will later
     * carry, so the identity has to exist before the endpoint decision -- not be minted there.
     * It is cleared when the segment closes, so the next utterance gets its own.
     */
    private var inFlightUtteranceId: String? = null

    /**
     * Opens the route connection early, if this utterance is one the app should answer at all.
     *
     * Addressed means the app is the intended recipient: a positive wake result, or the grace
     * period that follows one. Speech that was never addressed must never open a connection -- the
     * server would learn that someone spoke near the phone.
     *
     * Only the first trailing silence of an utterance opens. Later ones are the same utterance
     * resuming, and the opener keeps the connection it already holds for that tag.
     */
    private fun openEarlyConnectionIfAddressed() {
        val opener = earlyConnectionOpener ?: return
        val addressed = currentMode == ListeningMode.Continuous || wakePositiveForCurrentUtterance
        if (!addressed) return
        val tag = inFlightUtteranceId ?: java.util.UUID.randomUUID().toString().also { inFlightUtteranceId = it }
        opener.openForLater(tag)
    }

    /**
     * Whether the wake result for the utterance in flight is already positive.
     *
     * Trailing silence can arrive before the wake decision is final; when it does, the connection
     * is opened later, at the moment the verdict turns positive.
     */
    private var wakePositiveForCurrentUtterance: Boolean = false

    /** The wake result scored from the in-progress utterance, or null before the cutoff was reached. */
    private var earlyWakeResult: WakeWordResult? = null

    /** The native utterance generation the early state belongs to; null until the first snapshot. */
    private var earlyUtteranceGeneration: Long? = null

    /** Set when a snapshot crossed a generation boundary: no more early scoring this utterance. */
    private var earlyScoringRetired = false

    /** Whether the early hit already fired grace and the earcon, so the finished-segment path fires them once. */
    private var earlyWakeAcknowledged = false

    /**
     * Score the wake word from the utterance in progress, once enough audio exists.
     *
     * The window handed to the detector is exactly the prefix a finished segment would give it
     * (onset-relative, [EarlyWakeScore.CUTOFF_SAMPLES] long), so the early score IS the finished
     * score, not an approximation of it. A negative result is cached too: re-scoring the same
     * window every frame would spend detector time on a decided question.
     */
    private suspend fun scoreWakeEarly(expectedGeneration: Long? = null) {
        if (earlyWakeResult != null || earlyScoringRetired) return
        val reader = inProgressUtterance ?: return
        if (!wakeWordDetector.isReady) return
        val sampleRateHz = reader.sampleRateHz()
        // ONE snapshot call: generation, onset, size and PCM under one lock. A snapshot whose
        // generation does not match this utterance describes audio across a finalization boundary;
        // it is dropped and early scoring is retired for this utterance — no retry, because the
        // endpoint path scores that utterance anyway. An event-supplied expected generation must
        // match too: the snapshot and the event that triggered this score must describe the SAME
        // utterance, not merely both be "current".
        val header = LongArray(3)
        val buffer = ShortArray(EarlyWakeScore.CUTOFF_SAMPLES * 2)
        val copied = reader.readSnapshot(buffer, header)
        if (copied <= 0) return
        val generation = header[0]
        if (expectedGeneration != null && generation != expectedGeneration) {
            Timber.i(
                "[VoicePipeline] snapshot gen %d does not match the cutoff's gen %d; dropping, endpoint will score",
                generation,
                expectedGeneration,
            )
            earlyScoringRetired = true
            return
        }
        val currentGeneration = earlyUtteranceGeneration
        when {
            currentGeneration == null -> earlyUtteranceGeneration = generation

            generation != currentGeneration -> {
                Timber.i("[VoicePipeline] early snapshot crossed an utterance boundary; dropping, endpoint will score")
                earlyScoringRetired = true
                return
            }
        }
        val onset = header[1].toInt()
        val available = header[2].toInt()
        val request = EarlyWakeScore.request(
            speechOnsetSample = onset,
            availableSamples = available,
            sampleRateHz = sampleRateHz,
            isGraceActive = currentMode == ListeningMode.Continuous,
        ) ?: return
        // The detector runs on this collector; scoring blocks the segmenter loop briefly, which the
        // native capture buffer absorbs. Scoring is once per utterance, not per frame.
        val read = copied
        if (read < request.startSample + request.lengthSamples) return
        val prefix = FloatArray(request.lengthSamples) {
            buffer[request.startSample + it].toFloat() / 32768f
        }
        val result = runCatching { wakeWordDetector.detect(prefix, sampleRateHz, 0) }.getOrElse { e ->
            // An early score that cannot be produced is not a failure the user sees: the finished
            // segment still runs the detector at the endpoint, exactly as it always did.
            Timber.w(e, "[VoicePipeline] early wake scoring failed, deferring to endpoint")
            return
        }
        Timber.i("[VoicePipeline] early wake %s", if (result.detected) "hit" else "miss")
        earlyWakeResult = result
        if (result.detected) {
            wakePositiveForCurrentUtterance = true
            acknowledgeEarlyWake()
            // The verdict now exists BEFORE the trailing-silence onset for any utterance longer
            // than the cutoff, so the onset open covers outside grace too.
            openEarlyConnectionIfAddressed()
        }
    }

    /** Fires grace and the wake earcon once per utterance, from the early hit or the endpoint. */
    private fun acknowledgeEarlyWake() {
        if (earlyWakeAcknowledged) return
        earlyWakeAcknowledged = true
        gracePeriodSignal.onWakeWordDetected()
        audioFeedbackRenderer.submitEarcon(EarconId.WAKE_WORD)
    }

    private suspend fun shouldTranscribe(
        segment: VoiceSegmenterResult.SpeechEnded,
        capture: CaptureContext?,
        timing: TurnTimingRecord,
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
            // MEASURED WAKE FACTS (spec phase 2): recorded whenever the detector measured them.
            wakeMeasurement = SegmentObservation.WakeMeasurement(
                score = wwResult.confidence.takeUnless { it.isNaN() }?.toDouble(),
                threshold = wwResult.threshold.takeUnless { it.isNaN() }?.toDouble(),
            ),
            framesProvider = { segment.frames.map { it.samples } to (segment.frames.firstOrNull()?.sampleRateHz ?: 16000) },
        )

        // The final wake result, as the detector reported it. Recorded before any decision that
        // depends on it, so a negative result is still a measured stage.
        timing.mark(TurnTimingRecord.Stage.WakeResult, TurnTimingRecord.clockNanos())

        val mode = currentMode
        val wakeCmp = when {
            wwResult.threshold.isNaN() -> if (wwResult.detected) "hit" else "miss"
            wwResult.detected -> "%.3f >= %.3f".format(wwResult.confidence, wwResult.threshold)
            else -> "%.3f < %.3f".format(wwResult.confidence, wwResult.threshold)
        }

        if (wwResult.detected) {
            // Open or reset the conversation grace period. This causes
            // ListeningModePolicy to switch to Continuous for subsequent utterances.
            // When the early score already fired these for this utterance, they fire only
            // once: the user hears one earcon and grace starts once for one wake word.
            acknowledgeEarlyWake()

            Timber.i("[VoicePipeline] wake %s → ASR (hit, mode=%s)", wakeCmp, mode)
            // The verdict is final here. If the first trailing silence arrived before it (the usual
            // case outside grace), this is the moment the connection may be opened.
            wakePositiveForCurrentUtterance = true
            openEarlyConnectionIfAddressed()
            val addressed = TranscribeRequest(
                capture = capture,
                noteTranslation = { provenance -> capture?.let { segmentObserver.noteTranslation(it, provenance) } },
                samples = floatSamples,
                speechEndSample = segment.speechEndSample,
                sampleRateHz = segment.frames.firstOrNull()?.sampleRateHz ?: 16000,
                wakePositive = true,
                completionSample = wwResult.completionSample,
                // The identity the early connection was opened under. Reusing it here is what lets
                // the turn adopt that connection; a fresh id would open a connection no turn claims.
                admittedId = inFlightUtteranceId ?: java.util.UUID.randomUUID().toString(),
            )
            return addressed
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
                    admittedId = inFlightUtteranceId ?: java.util.UUID.randomUUID().toString(),
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
        timing: TurnTimingRecord,
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
        // The anchor describes SAMPLE 0 of the buffer the filter receives (pre-roll included):
        // the correlator aligns the whole buffer against the reference window, so the anchor must
        // be the buffer's own start, not the speech onset inside it.
        val micPositionMs = segment.captureTimeOfSegmentStartMs()
        if (!utteranceFilter.shouldProcessReference(floatSamples, false, 0, playbackReference, micPositionMs)) {
            Timber.i("[VoicePipeline] → drop (bleed filter)")
            joinOutcome(CollectionOutcome.NotCollected(request.capture?.captureId ?: return, "bleed"))
            return
        }

        // P1: freeze the invoked backend's verified loaded identity at the
        // actual invocation — carried independently of the result, so failed/empty invocations
        // keep provenance and a replacement between SpeechStarted and here cannot misattribute.
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
            // never a wait-expiry unknown. The AsrFailed join is a publishable terminal.
            request.capture?.let { joinOutcome(CollectionOutcome.AsrFailed(it.captureId)) }
            Timber.w(t, "[VoicePipeline] asr provider failed")
            return
        }
        timing.mark(TurnTimingRecord.Stage.AsrDone, TurnTimingRecord.clockNanos())
||||||| parent of 05218e81 (voice: one monotonic clock for the whole timing table)
        val asrResult = b.transcribe(floatSamples, sampleRateHz)
        timing.mark(TurnTimingRecord.Stage.AsrDone, TurnTimingRecord.clockNanos())
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
            // wake-only drop is NOT necessarily an empty recognition. Only a GENUINELY empty
            // raw result is completed/empty_result; otherwise the ACTUAL transcript is
            // preserved as the recognized fact (normal trimming stays a pipeline behavior,
            // never an erasure of the observed output).
            if (asrResult.text.isNotBlank()) {
                request.capture?.let {
                    // The observed wake-positive
                    // trim consumed everything — raw text existed and the trimmed command
                    // transcript is EMPTY — so ROUTING never ran: not_run/wake_only. The
                    // time-based isWakeOnly arm of this drop (short speech, command text intact)
                    // must NOT be typed wake_only; that gate is the transcript emptiness itself.
                    // Joined FIRST so Recognized stays the candidate outcome: the transcript fact
                    // outranks the routing absence, and provenance reads both from the history.
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
            // No leftover command after the wake: the session started and
            // nothing was asked, so nothing is reported. (The WAKE_WORD earcon
            // already acknowledged detection.)
            return
        }
        // Task #78: a transcript with no content -- punctuation, symbols or whitespace only,
        // after the wake trim -- is not a user request. Discard it silently BEFORE translation,
        // routing or any feedback: no dialog consumption, no grace extension, no error cue.
        // Content is any Unicode letter or number, so CJK particles (停, 嗯) and digit
        // lookalikes (², ½) count as content while ".", "…" and "?!" do not.
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
            // No usable command text, but the backend DID observe this transcript and DID measure a
            // language. Both are facts: the command is discarded, the observation is not, so they
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
        // Recognized: the ACTUAL trimmed transcript + detected language of THIS capture.
        request.capture?.let { joinOutcome(CollectionOutcome.Recognized(it.captureId, transcript, asrResult.detectedLanguage)) }
            ?: Timber.i("voice-capture publish: refused reason=no-capture-context stage=recognized")
        // Translate to English when the ASR backend did not already translate and
        // the detected language is not English (the SenseVoice CJK path).
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
        // read as English ASR that somehow translated into Chinese.
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
        // segment that we could not route gets the spoken line, a NotDetected one keeps the earcon.
        // The grace window is true for both, so it cannot make that distinction.
        processUtterance(
            routePrep.input!!,
            addressed = request.wakePositive,
            capture = request.capture,
            admittedId = request.admittedId,
            timing = timing,
        )
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

    private suspend fun processUtterance(
        input: IntentRoutingInput,
        addressed: Boolean,
        capture: CaptureContext?,
        admittedId: String,
        timing: TurnTimingRecord? = null,
    ) {
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
            // so — silence here is the complaint this change exists to remove.
            // Deliberately not escalated: a capability failure would turn a
            // broken install into cloud traffic (see CloudEscalationPolicy).
            audioFeedbackRenderer.submitEarcon(EarconId.ERROR)
            return
        }

        // P1: the router identity is claimed only when routing
        // ACTUALLY RUNS, bound to the recognizer ACTUALLY CALLED (`recognizer`, not another
        // component's load state) — its own verified loader evidence.
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
        // CAPTURED BEFORE the recognition suspends. Recognition can take seconds, and a wake that
        // arrives while it runs opens a NEW window: stamping the intent afterwards would attach it to
        // the window that opened during the wait, so a request issued before a successor wake would
        // act as though it belonged to the successor.
        val windowAtRequest = gracePeriodSignal.capturedGeneration()
        // THE ADMITTED IDENTITY arrives from capture admission (TranscribeRequest.admittedId),
        // minted BEFORE ASR/trim/translation so every diagnostic from that point forward names
        // the same request. A retry of the same request reuses this value; two separate user acts
        // that happen to say the same words do not. The dialog path mints its own for
        // clarification flows; this is the producer for a plain utterance.
        val outcome = recognizer.recognize(input, ctx, admittedId)
        timing?.mark(TurnTimingRecord.Stage.RoutingDone, TurnTimingRecord.clockNanos())
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
                    // prepared once and may be retried. A local intent has no wire identity to reuse.
                    .let { it.withLogicalRequest(admittedId) },
                admittedId,
            )
            timing?.markOutcome(TurnTimingRecord.Outcome.ActionExecuted)
            timing?.publish()
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
        // The router ran and produced no intent. That is not an executed action, and the table
        // says so rather than leaving the reader to infer it from a missing stage.
        timing?.markOutcome(TurnTimingRecord.Outcome.ActionFailed)
        timing?.publish()

        // P1: the recognizer's OWN reported decision travels — a null
        // intent is NOT always no_candidates (it may be validation or a model failure), and
        // the observation stream is NOT closed: a later cloud escalation can still supply
        // its normal outcome. NONTERMINAL fact.
        capture?.let { joinOutcome(CollectionOutcome.RoutingNone(it.captureId, diagnostic?.reason)) }

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
            // requires a tone for this case.
            CloudEscalation.SPEAK_ERROR -> audioFeedbackRenderer.submitEarcon(EarconId.ERROR)

            // The client has no answer of its own, so the service gets asked —
            // through the same handler a chosen `cloud_route` uses, so
            // superseded-turn cancellation and the auto-pause obligation apply.
            CloudEscalation.DISPATCH -> {
                // THE ORIGIN IS THE ONE CAPTURED AT REGISTRATION, not the window that happens to be
                // live now. Recognition can take seconds, and a wake arriving meanwhile opens a NEW
                // window: escalating under that window would let OLD recognition consume its
                // successor's conversation. A stale origin is rejected before dispatch.
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
                        // STAMPED WITH THE CAPTURED ORIGIN, matching the window this recognition was
                        // issued under -- the same value the chosen-route path uses.
                        windowGeneration = windowAtRequest,
                        origin = VoiceIntent.CloudRouteOrigin.RoutingFailure,
                        // THE REGISTERED IDENTITY, not a second one: this escalation belongs to the SAME
                        // logical request as the recognition it follows, so a retry of that request
                        // names one identity rather than two.
                        logicalRequestId = admittedId,
                    ),
                    admittedId,
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
                // invoked backend produced the English text in the SAME transcribe call (observed
                // output above). Capability alone never asserts a produced translation — but the
                // backend's ACTUAL English output for THIS invocation is the observed fact, and
                // the skip of the separate stage is a known fact in the owning vocabulary.
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
            // P1: the stage RAN and returned invalid (blank) output — known FAILURE.
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
            // and produced INVALID output: known FAILURE.
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
            // observable here, so the model is honest absence rather than a fixed label.
            translation = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.TranslationProvenance(
                model = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef.None,
                producedResult = true,
                // The OBSERVED result text and the target language travel as SEPARATE fields
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
        // configured requiredModel is a requirement, not evidence. Absence → None.
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
        // observer lifecycle (one monitor guards pipeline events and external signals alike);
        // never resume capture to complete an example. Completed contributions survive.
        segmentObserver.listeningClosed("pipeline-stopped")
        // Every open invocation ends with the lifecycle: no result can cross a stop/restart.
        clearInvocationOwnership()
        // Keep the processing job so the SERVICE teardown can await it: this job runs on the
        // engine's OWN SupervisorJob+IO scope, so cancelling serviceScope's root does not wait for
        // It. A noncooperative ASR/native call or its `finally` can otherwise
        // outlive stop() and overlap VoiceControlService's backend release.
        processingJob?.let { cancelled ->
            // Prune finished entries so a long-lived service does not accumulate them, then track
            // this one until it actually finishes.
            unfinished.removeAll { it.isCompleted }
            unfinished.add(cancelled)
            // Only a job that was still running counts as cancelled by us: a job
            // that had already failed or been cancelled elsewhere keeps its own outcome, and
            // relabelling it as expected shutdown would hide a real failure.
            if (cancelled.isActive) {
                cancelledJobs.add(cancelled)
            }
            // Capture the outcome AT REGISTRATION. Waiting until the shutdown snapshot misses jobs
            // that finished (and were pruned) before it, and would replay the outcome later.
            cancelled.invokeOnCompletion { cause ->
                // ConcurrentHashMap REJECTS null values, and a CLEAN completion has no cause, so the
                // entries are typed nullable and store a sentinel for "finished cleanly".
                outcomes[cancelled] = cause ?: CLEAN_COMPLETION
                unfinished.remove(cancelled)
            }
        }
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
