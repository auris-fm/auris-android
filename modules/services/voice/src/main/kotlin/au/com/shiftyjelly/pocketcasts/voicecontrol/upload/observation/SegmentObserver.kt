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
) {

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
    fun speechStarted(captureSession: Long, routeClass: String): CaptureContext? = synchronized(lock) {
        val context = CaptureContext(UUID.randomUUID(), captureSession, routeClass)
        open = context
        cumulativeAccounted = 0
        // Gate BEFORE any allocation: consulted at START with the LIVE authority.
        retainedForOpen = gate.accepts(authority()) && sink.started(SegmentObservation.Started(context))
        context.takeIf { retainedForOpen }
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
    fun wakeCompleted(
        context: CaptureContext?,
        speechOnsetSample: Int,
        speechEndSample: Int,
        wakeState: SegmentObservation.WakeState,
        framesProvider: () -> Pair<List<ShortArray>, Int>,
    ): Unit = synchronized(lock) {
        val current = open ?: return // no open capture: nothing to complete
        if (context == null || current.captureId != context.captureId) return // late: refuse join
        val decision = authority()
        if (!gate.accepts(decision)) {
            // REVALIDATION before handoff: refuse, allocate NOTHING (provider not run).
            closeLocked()
            return
        }
        val (frames, rate) = framesProvider()
        val total = frames.sumOf { it.size }
        totalRetainedBytes += total * BYTES_PER_SAMPLE
        val adopted = sink.completed(
            SegmentObservation.Completed(current, flatten(frames), rate, speechOnsetSample, speechEndSample, wakeState),
        )
        if (!adopted) totalRetainedBytes -= total * BYTES_PER_SAMPLE
        closeLocked()
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
