package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import java.util.UUID

/**
 * THE completion-handoff trust boundary as ONE owner transaction (reviewer 1d27bcd6 blocker 1):
 * there is no "validator returns Boolean, then enrollment/provider/sink act outside" seam.
 * [handoff] runs INSIDE `ownership.transaction` and stays there for the whole sequence:
 *   validate ORIGINAL coherent authority → revalidate live decision → materialize frames →
 *   sink adoption → observer closure. A concurrent logout/withdrawal serializes against the same
 *   owner lock, so held-completion revocation wins by construction.
 *
 * LOCK ORDER (blocker 2): owner → observer → sink, one direction only. The coordinator acquires
 * the observer lock via [ObserverHandoffScope] WHILE holding the owner lock; the observer never
 * acquires the owner lock while holding its own lock — handoff enters THROUGH this coordinator,
 * and loss fan-out ([SegmentObserver.revoked]) is invoked from owner-hook context or plain
 * observer context, never upward.
 */
class HandoffCoordinator(
    /** THE collection fence: order collectionFence → owner → observer → sink (a38421f6 b1/b4). */
    private val collectionTx: CollectionAuthorityTransaction,
    /** Coherent ORIGINAL-authority validation executed INSIDE the same collection transaction. */
    private val validateIssuedLocked: (IssuedAuthority) -> Boolean,
) : CompletionHandoff {
    override fun handoff(
        context: CaptureContext?,
        speechOnsetSample: Int,
        speechEndSample: Int,
        wakeState: SegmentObservation.WakeState,
        framesProvider: () -> Pair<List<ShortArray>, Int>,
        scope: ObserverHandoffScope,
    ) {
        // PHASE 1 — validation OUTSIDE the fence (reviewer c0c45049): the validator takes ONLY
        // the owner lock for its identity compare (plus a fence-free policy read). Nothing on
        // this lane acquires the fence while holding the owner lock, so no entry inverts order.
        val issued = context?.issuedAuthority
        val authorized = issued != null && issued.accountName != null && validateIssuedLocked(issued)
        // PHASE 2 — observer/sink work under the fence ALONE (fence → observer → sink). The
        // coordinator never touches the owner lock here; refusal termination and retention both
        // serialize against loss fan-out through the same fence.
        collectionTx.transaction {
            checkFenceHeld()
            // Identity check FIRST: a stale/unknown context is a late NO-OP, never a purge of
            // the current capture's audio.
            if (context != null && !scope.ownsCapture(context.captureId)) return@transaction
            if (!authorized) {
                // Refusal STILL terminates: exactly one terminal, prefix PCM + charge released
                // via the cancel path — never a silent return leaving live uncharged audio.
                scope.completeUnderOwner(context, speechOnsetSample, speechEndSample, wakeState, framesProvider, admit = false)
                return@transaction
            }
            // Execute the handoff closure (provider + sink adoption + close) while the fence is
            // held. A concurrent logout/expiry serializes behind this transaction. completeUnderOwner
            // takes the observer lock internally as the innermost monitor (never upward).
            scope.completeUnderOwner(context, speechOnsetSample, speechEndSample, wakeState, framesProvider)
        }
    }

    /** Order guard: observer/sink work runs only while THIS thread holds the collection fence. */
    private fun checkFenceHeld() {
        check(collectionTx.heldByCurrentThread()) {
            "handoff observer work must run inside the collection authority transaction"
        }
    }
}

/**
 * Narrow observer surface used ONLY while the caller holds the owner lock. Implemented by
 * [SegmentObserver]; the parameter type [HandoffDecisionSource] keeps the dependency direction
 * legal (observer module → coordinator protocol, no cycle).
 */
interface ObserverHandoffScope {
    /** True when the scope's observer currently holds THIS capture open (stale-guard first). */
    fun ownsCapture(captureId: java.util.UUID): Boolean

    /**
     * Under the observer lock (order owner→observer): revalidate the LIVE decision plus the
     * original issued authority components against the coherent state read under the owner lock,
     * run [framesProvider] at most once, adopt into the sink, close the capture. Returns whether
     * audio was retained.
     */
    fun completeUnderOwner(
        context: CaptureContext?,
        speechOnsetSample: Int,
        speechEndSample: Int,
        wakeState: SegmentObservation.WakeState,
        framesProvider: () -> Pair<List<ShortArray>, Int>,
    ): Boolean = completeUnderOwner(context, speechOnsetSample, speechEndSample, wakeState, framesProvider, admit = true)

    /** [admit]=false models a REFUSED original-authority validation: close with ZERO retention,
     * provider NEVER runs, exactly one terminal is emitted (closure), charges released. */
    fun completeUnderOwner(
        context: CaptureContext?,
        speechOnsetSample: Int,
        speechEndSample: Int,
        wakeState: SegmentObservation.WakeState,
        framesProvider: () -> Pair<List<ShortArray>, Int>,
        admit: Boolean,
    ): Boolean
}

/**
 * Completion handoff strategy: production composes [HandoffCoordinator]; tests may inject an
 * inline executor. The observer calls this from its own wakeCompleted site and MUST be handed a
 * scope it can enter under the owner lock.
 */
fun interface CompletionHandoff {
    /**
     * Executes (or refuses) the handoff for [context]. Fail-closed implementations never invoke
     * [framesProvider] and never retain.
     */
    fun handoff(
        context: CaptureContext?,
        speechOnsetSample: Int,
        speechEndSample: Int,
        wakeState: SegmentObservation.WakeState,
        framesProvider: () -> Pair<List<ShortArray>, Int>,
        scope: ObserverHandoffScope,
    )
}

/** Default when uncomposed: NO handoff ever retains. Allocation proof: provider not run. */
object RefuseAllCompletionHandoff : CompletionHandoff {
    override fun handoff(
        context: CaptureContext?,
        speechOnsetSample: Int,
        speechEndSample: Int,
        wakeState: SegmentObservation.WakeState,
        framesProvider: () -> Pair<List<ShortArray>, Int>,
        scope: ObserverHandoffScope,
    ) = Unit
}
