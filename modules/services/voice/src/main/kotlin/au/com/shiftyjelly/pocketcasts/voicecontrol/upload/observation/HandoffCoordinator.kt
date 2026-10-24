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
    /** LIVE retention decision source, read under the fence BEFORE entering the observer. */
    private val liveAuthority: () -> AuthorityDecision = { AuthorityDecision(collectionPermitted = false, fullRevocation = true) },
) : CompletionHandoff {
    override fun handoff(
        context: CaptureContext?,
        speechOnsetSample: Int,
        speechEndSample: Int,
        wakeState: SegmentObservation.WakeState,
        framesProvider: () -> Pair<List<ShortArray>, Int>,
        scope: ObserverHandoffScope,
        liveGateAllowed: Boolean,
    ): Unit = collectionTx.transaction {
        // ONE transaction on the SHARED outermost primitive (reviewer 8273382e): validation and
        // observer/sink work run under the SAME acquisition — no two-phase boolean seam. Entering
        // the account tier here is order-safe because every owner transition acquires THIS SAME
        // object first; reentrant nesting cannot invert an order with only one outer lock.
        checkFenceHeld()
        val issued = context?.issuedAuthority
        val authorized = issued != null && issued.accountName != null && validateIssuedLocked(issued)
        // Identity check FIRST: a stale/unknown context is a late NO-OP, never a purge of
        // the current capture's audio.
        if (context != null && !scope.ownsCapture(context.captureId)) return@transaction
        // LIVE gate was evaluated by the observer under the shared primitive BEFORE entering
        // here and arrives as data ([liveGateAllowed]); the observer monitor is never held when
        // authority locks are taken, and this object never acquires authority from inside it.
        val liveAllowed = liveGateAllowed
        if (!authorized || !liveAllowed) {
            // Refusal STILL terminates: exactly one terminal, prefix PCM + charge released
            // via the cancel path — never a silent return leaving live uncharged audio.
            scope.completeUnderOwner(
                context,
                speechOnsetSample,
                speechEndSample,
                wakeState,
                framesProvider,
                admit = false,
                liveGateAllowed = liveAllowed,
            )
            return@transaction
        }
        // Execute the handoff closure (provider + sink adoption + close) while the shared
        // primitive is held. A concurrent logout/expiry serializes behind this transaction;
        // completeUnderOwner takes the observer lock internally as an INNER monitor only.
        scope.completeUnderOwner(
            context,
            speechOnsetSample,
            speechEndSample,
            wakeState,
            framesProvider,
            admit = true,
            liveGateAllowed = true,
        )
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
     * Under the observer lock (order shared → observer): the coordinator has ALREADY validated
     * ORIGINAL coherent authority and evaluated the LIVE gate under the shared primitive; those
     * decisions arrive as data ([admit], [liveGateAllowed]) — the observer never acquires any
     * authority lock upward. Runs [framesProvider] at most once, adopts into the sink, closes
     * the capture. Refusals emit exactly one terminal and release charges. Returns retention.
     */
    fun completeUnderOwner(
        context: CaptureContext?,
        speechOnsetSample: Int,
        speechEndSample: Int,
        wakeState: SegmentObservation.WakeState,
        framesProvider: () -> Pair<List<ShortArray>, Int>,
        admit: Boolean = true,
        liveGateAllowed: Boolean = true,
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
        liveGateAllowed: Boolean,
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
        liveGateAllowed: Boolean,
    ) = Unit
}
