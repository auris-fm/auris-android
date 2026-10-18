package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import java.util.ArrayDeque
import java.util.UUID

/**
 * Observes the ACTUAL segment path — start / incremental / cancel / completed — under ONE
 * serialized lifecycle. Every event (pipeline events AND external revocation/closure signals)
 * is applied synchronously under a single lock, so an external invalidation can never interleave
 * between a pipeline decision and its handoff, and stop-cancel is not "outside" the collect loop:
 * both go through the same lock. The context is frozen SYNCHRONOUSLY at START before any
 * allocation or await; the SAME immutable context value travels to the detector, ASR, routing
 * and execution call sites, which report outcomes joined by issued capture ID.
 *
 * Exactly ONE terminal per open capture is defined and emitted before it exits the observer:
 * Completed (carrying DetectorFailed when the wake detector threw), or Cancelled. Late events
 * for a closed capture are refused — never joined onto a newer capture.
 */
class SegmentObserver(
    private val gate: CollectionGate,
    private val sink: SegmentSink,
    private val authority: () -> AuthorityDecision,
    /** Freezes the ORIGINAL authority at start; NO renewal source — reads current, once. */
    private val issueAuthority: () -> IssuedAuthority = { IssuedAuthority(null, 0L, 0L, null, null) },
    /**
     * One-transaction completion handoff (owner→observer→sink). Production composes a real
     * coordinator over the credential owner; the default refuses EVERY handoff (fail-closed),
     * so an uncomposed observer retains nothing at completion.
     */
    private val handoff: CompletionHandoff = RefuseAllCompletionHandoff,
) : ObserverHandoffScope {

    // All fields below are guarded by [lock]; external callers and the pipeline thread contend
    // on the same monitor, which serializes the lifecycle without a dispatcher dependency.
    private val lock = Any()
    private var open: CaptureContext? = null
    private var retainedForOpen = false
    private var cumulativeAccounted = 0
    private var totalRetainedBytes = 0L

    /** Cap on retained collection audio (per-clip bound of plan Task 5 enforced early). */
    var maxRetainedBytes: Long = DEFAULT_MAX_RETAINED_BYTES

    /** Context issued for this SpeechStarted iff admission passed; null when refused/closed. */
    // [firstChunk] is the production segment's current accumulated audio; when retention starts
    // the sink ADOPTS that array (ownership transfer, no extra copy). Pass [ShortArray] of the
    // live buffer — never a fabricated size.
    // ADMISSION BEFORE ALLOCATION (reviewer 1d27bcd6 blocker 3): [producerSize] is the native
    // buffer's CURRENT length — a counter read that allocates nothing. The observer freezes the
    // coherent authority, consults the gate, and RESERVES exactly those bytes in the sink FIRST;
    // only then does the caller drain the native buffer into an array whose ownership transfers
    // to the sink ([adoptFirstChunk], called iff this returns non-null). Refusal leaves the
    // producer untouched: recognition keeps its own buffer as before.
    fun speechStartedAdmitting(captureSession: Long, routeClass: String, producerSize: Int): CaptureContext? = synchronized(lock) {
        val context = CaptureContext(UUID.randomUUID(), captureSession, routeClass, issueAuthority())
        open = context
        cumulativeAccounted = 0
        retainedForOpen = gate.accepts(authority()) && sink.admit(SegmentObservation.Admission(context, producerSize))
        if (!retainedForOpen) return null
        context
    }

    /** Transfers the drained first chunk into the reserved prefix; must follow a successful admit. */
    fun adoptFirstChunk(context: CaptureContext, firstChunk: ShortArray): Unit = synchronized(lock) {
        if (open?.captureId != context.captureId || !retainedForOpen) return
        sink.adopted(SegmentObservation.Started(context, firstChunk))
    }

    fun speechStarted(captureSession: Long, routeClass: String, firstChunk: ShortArray): CaptureContext? = synchronized(lock) {
        val admitted = speechStartedAdmitting(captureSession, routeClass, firstChunk.size)
        if (admitted != null) adoptFirstChunk(admitted, firstChunk)
        admitted
    }

    /** Real sample delta produced by the production producer since the previous accounting point. */
    fun speechIncremental(context: CaptureContext?, samples: ShortArray): Unit = synchronized(lock) {
        val current = open ?: return
        if (context == null || current.captureId != context.captureId) return // stale/unknown: refuse
        if (!retainedForOpen || samples.isEmpty()) return
        cumulativeAccounted += samples.size
        sink.incremental(SegmentObservation.Incremental(current, samples.size, retainedForOpen), samples)
    }

    /**
     * VAD closed [context] (or the detector failed for it). [framesProvider] is invoked AT MOST
     * ONCE and ONLY after the live revalidation passes — with a dead gate it is NEVER called, so
     * zero upload-side bytes are allocated. It hands over the PRODUCTION frames plus sample rate.
     */
    // Completion handoff DELEGATES to [CompletionHandoff]: production runs the whole sequence —
    // original-authority validation, live re-decision, frames materialization, sink adoption,
    // closure — inside ONE owner transaction (owner→observer→sink order). The default refuses
    // everything (fail-closed). No check-then-act seam exists at this boundary anymore.
    fun wakeCompleted(
        context: CaptureContext?,
        speechOnsetSample: Int,
        speechEndSample: Int,
        wakeState: SegmentObservation.WakeState,
        framesProvider: () -> Pair<List<ShortArray>, Int>,
    ): Unit = synchronized(lock) {
        handoff.handoff(context, speechOnsetSample, speechEndSample, wakeState, framesProvider, this)
    }

    /**
     * Observer-side completion body, invoked ONLY through [HandoffCoordinator] while the CALLER
     * holds the owner lock (order owner→observer). Late/unknown contexts are refused without
     * running [framesProvider]; the live decision is re-read; refusal allocates nothing.
     */
    override fun completeUnderOwner(
        context: CaptureContext?,
        speechOnsetSample: Int,
        speechEndSample: Int,
        wakeState: SegmentObservation.WakeState,
        framesProvider: () -> Pair<List<ShortArray>, Int>,
        admit: Boolean,
    ): Boolean = synchronized(lock) {
        val current = open ?: return false
        if (context == null || current.captureId != context.captureId) return false // late: refuse join
        if (!admit) {
            // ORIGINAL-authority validation failed under the owner lock: close the capture with
            // zero retention — provider never runs, prefix charge released via cancellation path.
            sink.cancelled(SegmentObservation.Cancelled(current, "handoff-authority-refused"))
            closeLocked()
            return false
        }
        val decision = authority()
        if (!gate.accepts(decision)) {
            closeLocked()
            return false
        }
        val (frames, rate) = framesProvider()
        val adopted = sink.completed(
            SegmentObservation.Completed(current, frames, rate, speechOnsetSample, speechEndSample, wakeState),
        )
        if (adopted) totalRetainedBytes += frames.sumOf { it.size } * BYTES_PER_SAMPLE
        closeLocked()
        adopted
    }

    /** Terminal cancellation of the OPEN capture identified by [context] (late ones are no-ops). */
    fun cancelled(context: CaptureContext?, reason: String): Unit = synchronized(lock) {
        val current = open ?: return
        if (context != null && current.captureId != context.captureId) return // already terminal
        sink.cancelled(SegmentObservation.Cancelled(current, reason))
        closeLocked()
    }

    /** Listening closure: discard ONLY the current partial; retained completions survive. */
    fun listeningClosed(reason: String): Unit = cancelled(null, reason)

    /**
     * External FULL REVOCATION signal (withdrawal/disable/expiry/account change): purges ALL
     * collection PCM including the open partial, immediately, under the same lifecycle lock.
     */
    fun revoked(reason: String): Boolean = synchronized(lock) {
        val decision = authority()
        if (decision.collectionPermitted && !decision.fullRevocation) return false
        val purge = sink as? RevocationAware
        purge?.purgeAllForRevocation(reason)
        closeLocked()
        totalRetainedBytes = 0L
        true
    }

    // True while the SINK still owns retention for the open capture (budget may have ended it).
    fun isRetaining(captureId: UUID): Boolean = synchronized(lock) { open?.captureId == captureId && retainedForOpen }

    /** The sink ended retention externally (budget/cap): stop forwarding without a new terminal. */
    fun noteRetentionEnded(context: CaptureContext): Unit = synchronized(lock) {
        if (open?.captureId == context.captureId) retainedForOpen = false
    }

    fun isOpen(captureId: UUID): Boolean = synchronized(lock) { open?.captureId == captureId }

    /** Bytes currently attributed to retained collection audio (test seam for the cap). */
    fun retainedBytes(): Long = synchronized(lock) { totalRetainedBytes }

    private fun closeLocked() {
        open = null
        retainedForOpen = false
    }

    private fun flatten(frames: List<ShortArray>): ShortArray {
        val out = ShortArray(frames.sumOf { it.size })
        var offset = 0
        for (f in frames) {
            System.arraycopy(f, 0, out, offset, f.size)
            offset += f.size
        }
        return out
    }

    companion object {
        const val DEFAULT_MAX_RETAINED_BYTES: Long = 8L * 1024 * 1024
        private const val BYTES_PER_SAMPLE = 2L
    }
}

/** Sinks that own retention clear everything on full revocation, partial buffers included. */
interface RevocationAware {
    fun purgeAllForRevocation(reason: String)
}
