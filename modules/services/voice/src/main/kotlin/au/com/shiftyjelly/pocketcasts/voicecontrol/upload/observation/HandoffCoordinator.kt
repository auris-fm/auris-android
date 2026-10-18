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
    private val ownership: au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipOwner,
    /** Coherent ORIGINAL-authority validation executed INSIDE the same owner transaction. */
    private val validateIssuedLocked: (IssuedAuthority) -> Boolean,
) : CompletionHandoff {
    override fun handoff(
        context: CaptureContext?,
        speechOnsetSample: Int,
        speechEndSample: Int,
        wakeState: SegmentObservation.WakeState,
        framesProvider: () -> Pair<List<ShortArray>, Int>,
        scope: ObserverHandoffScope,
    ): Unit = ownership.transaction {
        // ONE transaction: validate ORIGINAL coherent authority...
        val issued = context?.issuedAuthority
        if (issued == null || !validateIssuedLocked(issued)) return@transaction
        // ...then EXECUTE the actual handoff closure (provider + sink adoption + close) while the
        // owner lock is still held. Revocation racing here serializes behind this transaction.
        scope.completeUnderOwner(context, speechOnsetSample, speechEndSample, wakeState, framesProvider)
    }
}

/**
 * Narrow observer surface used ONLY while the caller holds the owner lock. Implemented by
 * [SegmentObserver]; the parameter type [HandoffDecisionSource] keeps the dependency direction
 * legal (observer module → coordinator protocol, no cycle).
 */
interface ObserverHandoffScope {
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

/** Coherent authority snapshot frozen under the OWNER lock at issue time (blocker 4). */
data class IssuedAuthorityV2(
    val accountName: String?,
    val sessionGeneration: Long,
    val credentialIncarnation: Long,
    val enrollmentEpoch: String?,
    val acceptedDisclosureVersion: String?,
    /** Policy activation generation + stamp: a NEW policy cannot authorize OLD work. */
    val policyActivationGeneration: Long,
    val policyActivatedAtMonotonicMs: Long,
) {
    val captureIdAtIssue: UUID? = null // filled by the observer when binding to a capture
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
