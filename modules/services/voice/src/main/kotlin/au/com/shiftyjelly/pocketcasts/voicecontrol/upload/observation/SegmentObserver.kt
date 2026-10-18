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
    /** Collection-fence wrapper supplied by composition; identity when uncomposed (tests). */
    private val fenceGate: FenceGate = IdentityFenceGate,
) : ObserverHandoffScope {

    // All fields below are guarded by [lock]; external callers and the pipeline thread contend
    // on the same monitor, which serializes the lifecycle without a dispatcher dependency.
    private val lock = Any()
    private var open: CaptureContext? = null
    private var retainedForOpen = false
    private var admissionToken: ReservationToken? = null
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
    // Admission path (order collectionFence → owner → observer → sink; blockers b1/b3): the fence
    // is acquired BEFORE this object's monitor, and the EXACT-SIZE reservation completes BEFORE
    // this method returns — the engine allocates its ShortArray only afterwards, so an oversized
    // or unaffordable prefix never exists. Refusal leaves observer state untouched and releases
    // any charge it took.
    // Admission path (order shared → observer → sink; reviewer 2e1d5b0b b2): BOTH authority reads
    // — the frozen ISSUE and the LIVE gate decision — happen under the shared primitive BEFORE
    // this object's monitor is entered. Inside the lock only local state + sink admit run.
    // Admission + its IMMUTABLE initial-reservation handle (reviewer 79b2b5c9 b2): producers
    // adopt/release by THIS handle even after revocation closed the capture or a successor
    // opened — release never depends on "the currently open context".
    data class AdmissionHandle(val context: CaptureContext, val initialToken: ReservationToken?)

    fun speechStartedAdmitting(captureSession: Long, routeClass: String, producerSize: Int): AdmissionHandle? = fenceGate.gate {
        val issued = issueAuthority() // under the shared primitive, no observer lock yet
        val permitted = gate.accepts(authority()) // same: decision read before the monitor
        speechStartedAdmittingLocked(captureSession, routeClass, producerSize, issued, permitted)?.let { AdmissionHandle(it, admissionToken) }
    }

    private fun speechStartedAdmittingLocked(captureSession: Long, routeClass: String, producerSize: Int, issued: IssuedAuthority, permitted: Boolean): CaptureContext? {
        val context = CaptureContext(UUID.randomUUID(), captureSession, routeClass, issued)
        return synchronized(lock) {
            if (open != null || !permitted) return@speechStartedAdmittingLocked null
            cumulativeAccounted = 0
            val token = sink.admit(SegmentObservation.Admission(context, producerSize))
            if (token == null) return@speechStartedAdmittingLocked null
            admissionToken = token // producer-held initial reservation until adopt/release
            open = context
            context
        }
    }

    // Transfers the drained first chunk into the reserved prefix using ITS admit token; a
    // refusal/throw path must call releaseInitialChunk so that token's charge leaves exactly once
    // (reviewer 23cae70e b4). [validSamples] = samples actually drained; array capacity may
    // exceed the valid range and stays fully charged while adopted (reviewer 79b2b5c9 b1/b3).
    // Returns the SINK's actual transfer result — never hard true on refusal.
    fun adoptFirstChunk(admission: AdmissionHandle, firstChunk: ShortArray, validSamples: Int = firstChunk.size): Boolean = synchronized(lock) {
        val context = admission.context
        if (open?.captureId != context.captureId) return false
        val token = admission.initialToken ?: return false
        val transferred = sink.adopted(SegmentObservation.Started(context, firstChunk, validSamples), token)
        if (transferred) {
            admissionToken = null
            // Retention is live once the prefix exists (sink sets it on adoption); mirror for gates.
            retainedForOpen = openPrefixExistsFor(context)
        }
        transferred
    }

    /** True when the sink currently holds an OPEN prefix for this capture. */
    private fun openPrefixExistsFor(context: CaptureContext): Boolean = sink.hasOpenPrefix(context)

    /** Producer drop for an initial allocation that never transferred. Addressed by the HANDLE
     * from [Admission] — valid AFTER revocation closed the capture or a successor opened: the
     * sink releases THAT token, not "the current open capture's" (reviewer 79b2b5c9 b2). */
    fun releaseInitialChunk(admission: AdmissionHandle) {
        val token = admission.initialToken ?: return
        synchronized(lock) { if (admissionToken === token) admissionToken = null }
        sink.releaseInitial(admission.context, token)
    }

    // Entry WITHOUT any monitor: fenceGate acquires the SHARED primitive first; the observer
    // lock is taken only INSIDE admission (order shared → observer), never the reverse.
    fun speechStarted(captureSession: Long, routeClass: String, firstChunk: ShortArray): CaptureContext? {
        val admitted = speechStartedAdmitting(captureSession, routeClass, firstChunk.size) ?: return null
        if (!adoptFirstChunk(admitted, firstChunk)) releaseInitialChunk(admitted)
        return admitted.context
    }

    // --- Reserve-before-copy delta protocol (blocker b3): reservation is a budget admit that
    // allocates NOTHING; commit transfers the caller's drained array (already reserved) into the
    // owned prefix; release returns bytes when the copy failed or was refused. All three take the
    // observer monitor only — the collection fence was acquired at admission and is NOT re-entered
    // upward from here. ---
    fun reserveDelta(context: CaptureContext, samples: Int): ReservationToken? = synchronized(lock) {
        if (open?.captureId != context.captureId) return null
        sink.reserveDelta(context, samples)
    }

    // Producer drop path: even when the capture is already closed/revoked, the sink MUST see the
    // release so a lease-held charge leaves the budget exactly once (reviewer 8273382e b4).
    // Advances accounting ONLY when the sink actually adopted (reviewer 23cae70e b2).
    fun commitDelta(context: CaptureContext, drained: ShortArray, copied: Int, token: ReservationToken?): Boolean = synchronized(lock) {
        if (open?.captureId != context.captureId || token == null) {
            // Refused commit: the producer still owns the array; its charge leaves ONLY through
            // releaseDelta when the producer drops it — do not adopt or double-release here.
            return false
        }
        val adopted = sink.commitDelta(context, drained, copied, token)
        if (adopted) cumulativeAccounted += copied
        adopted
    }

    fun releaseDelta(context: CaptureContext, token: ReservationToken?) {
        sink.releaseDelta(context, token)
    }

    /** Real sample delta produced by the production producer since the previous accounting point. */
    fun speechIncremental(context: CaptureContext?, samples: ShortArray): Unit = synchronized(lock) {
        val current = open ?: return
        if (context == null || current.captureId != context.captureId) return // stale/unknown: refuse
        if (!retainedForOpen || samples.isEmpty()) return
        // Reserve-before-copy even on the compatibility path: growth that cannot be reserved ends
        // retention via the sink's own terminal; nothing is copied uncharged.
        val token = sink.reserveDelta(current, samples.size) ?: return
        try {
            if (sink.commitDelta(current, samples, samples.size, token)) {
                cumulativeAccounted += samples.size
            }
        } finally {
            // If commit refused/threw, THIS token's charge returns on producer drop.
            sink.releaseDelta(current, token)
        }
    }

    // * VAD closed [context] (or the detector failed for it). [framesProvider] is invoked AT MOST
    // * ONCE and ONLY after the live revalidation passes — with a dead gate it is NEVER called, so
    // * zero upload-side bytes are allocated. It hands over the PRODUCTION frames plus sample rate.
    // Completion handoff DELEGATES to [CompletionHandoff]: production runs the whole sequence —
    // original-authority validation, live re-decision, frames materialization, sink adoption,
    // closure — inside ONE owner transaction (owner→observer→sink order). The default refuses
    // everything (fail-closed). No check-then-act seam exists at this boundary anymore.

    /**
     * Entry WITHOUT the observer lock: the coordinator acquires collectionFence first, then
     * re-enters this object via completeUnderOwner which takes the observer lock as the INNERMOST
     * monitor (order collectionFence → owner → observer → sink; blocker a38421f6 b1).
     * [framesProvider] is wrapped SINGLE-SHOT (blocker b3): the first call drains and hands the
     * native buffer back; every later call returns EMPTY — no second copy-out can double-run.
     */
    fun wakeCompleted(
        context: CaptureContext?,
        speechOnsetSample: Int,
        speechEndSample: Int,
        wakeState: SegmentObservation.WakeState,
        framesProvider: () -> Pair<List<ShortArray>, Int>,
    ): Unit = fenceGate.gate {
        // The collection fence is held for the ENTIRE handoff including the single copy-out:
        // reservation vs expiry can never interleave with the drain (blocker b4), and the
        // provider stays single-shot — a second call returns EMPTY (no re-drain of native state).
        var drained = false
        val oneShot: () -> Pair<List<ShortArray>, Int> = {
            if (drained) {
                emptyList<ShortArray>() to 0
            } else {
                drained = true
                framesProvider()
            }
        }
        // Single consulted gate for the handoff: the LIVE authority decision (shared primitive
        // held by fenceGate above). The coordinator re-runs its own validation inside; test and
        // production paths both see exactly ONE gate consultation per handoff.
        val liveAllowed = gate.accepts(authority())
        handoff.handoff(context, speechOnsetSample, speechEndSample, wakeState, oneShot, this, liveAllowed)
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
        liveGateAllowed: Boolean,
    ): Boolean = synchronized(lock) {
        val current = open ?: return false
        if (context != null && current.captureId != context.captureId) return false // late: refuse join
        if (context == null) {
            // No frozen context at all: the handoff cannot be authorized — terminate the OPEN
            // capture with exactly one terminal and release its prefix (blocker b2), never a
            // silent return that leaves live uncharged audio.
            sink.cancelled(SegmentObservation.Cancelled(current, "handoff-without-context"))
            closeLocked()
            return false
        }
        if (!admit) {
            // ORIGINAL-authority validation failed under the fence: FULL release (blocker b2) —
            // purge every collection byte (prefix AND ready candidates), close the capture, zero
            // retention; provider never runs. A refused handoff is a loss event, not a deferral.
            (sink as? RevocationAware)?.purgeAllForRevocation("handoff-authority-refused")
                ?: sink.cancelled(SegmentObservation.Cancelled(current, "handoff-authority-refused"))
            closeLocked()
            totalRetainedBytes = 0L
            return false
        }
        // The coordinator ALREADY revalidated ORIGINAL coherent authority under the shared
        // primitive before entering; the live gate here consumes that SAME decision source via
        // [liveGate] (a plain boolean read of the frozen+live compare result), never by acquiring
        // the owner/policy locks from inside this monitor (reviewer 2e1d5b0b b2).
        if (!liveGateAllowed) {
            // LIVE-gate refusal is ALSO a terminated handoff (blocker b2): provider never runs,
            // exactly one terminal closes the capture, prefix PCM + charge released via cancel.
            sink.cancelled(SegmentObservation.Cancelled(current, "handoff-gate-refused"))
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

    /** True when [captureId] is the CURRENT open capture: stale-guard before any refusal purge. */
    override fun ownsCapture(captureId: java.util.UUID): Boolean = synchronized(lock) { open?.captureId == captureId }

    /** Listening closure: discard ONLY the current partial; retained completions survive. */
    fun listeningClosed(reason: String): Unit = cancelled(null, reason)

    /**
     * External FULL REVOCATION signal (withdrawal/disable/expiry/account change): purges ALL
     * collection PCM including the open partial, immediately, under the same lifecycle lock.
     */
    // Loss fan-out enters THROUGH the collection fence first (authority() acquires it), so revoke
    // serializes against handoff/admission/expiry as ONE authority transaction; only then does it
    // take the observer monitor (order fence → owner → observer → sink, blocker b1/b4).
    fun revoked(reason: String): Boolean = fenceGate.gate {
        // ONE collection transaction covers decide → purge → close: loss fan-out enters through
        // the fence BEFORE any observer monitor (order fence → owner → observer → sink, b1/b4),
        // and an in-flight handoff holding the same fence completes its copy before this purge.
        // Loss causes purge on their OWN signal; expiry does not require the flag to have flipped.
        val decision = authority()
        if (!revocationCauses.contains(reason) && decision.collectionPermitted && !decision.fullRevocation) {
            return@gate false
        }
        synchronized(lock) {
            val purge = sink as? RevocationAware
            purge?.purgeAllForRevocation(reason)
            closeLocked()
            totalRetainedBytes = 0L
            true
        }
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

        /** Causes that MUST purge regardless of a live-but-stale-looking gate (loss-driven). */
        val revocationCauses = setOf(
            "account-change",
            "enrollment-change",
            "policy-disable",
            "lease-deadline",
            "policy-inactive",
        )
    }
}

/** Sinks that own retention clear everything on full revocation, partial buffers included. */
interface RevocationAware {
    fun purgeAllForRevocation(reason: String)
}
