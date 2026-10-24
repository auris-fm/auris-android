package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.DeletionReceipt
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingMutationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the Settings enrollment surface is allowed to know about an operation in progress or just
 * finished. Deliberately narrow: the UI must never be able to claim a server-side completion it has
 * not observed.
 */
sealed interface EnrollmentState {
    data object Idle : EnrollmentState

    /** A membership mutation is in flight. The server has not confirmed anything yet. */
    data object Pending : EnrollmentState

    /** Last operation was applied by the server. */
    data object Applied : EnrollmentState

    /** The server reported the membership moved under us (409). Requires a fresh user action. */
    data object Conflict : EnrollmentState

    /**
     * The server-side operation did not complete. Reported honestly as failed — NOT as "not sharing".
     * Note this says nothing about local collection: turning sharing off clears local pending work
     * immediately, so collection has already stopped regardless of this outcome.
     */
    data object Failed : EnrollmentState
}

/** The separate deletion workflow's observed status. A 202 is PENDING, never "deleted". */
enum class DeletionState { Pending, }

sealed interface EnrollmentOutcome {
    /**
     * Which action and which account produced this result.
     *
     * Settings applies SIDE EFFECTS on an outcome (recording or releasing the durable stop), and an
     * outcome with no identity cannot be checked against the state at application time. Without this,
     * a delayed result from an action the user has since replaced - or from a previous account - can
     * re-record a stop the newer opt-in already cleared, or clear a stop that belongs to someone else.
     */
    val attempt: MutationFence.Attempt?

    data class Applied(val state: EnrollmentState, override val attempt: MutationFence.Attempt? = null) : EnrollmentOutcome
    data class Conflict(val state: EnrollmentState, override val attempt: MutationFence.Attempt? = null) : EnrollmentOutcome
    data class Failed(val state: EnrollmentState, override val attempt: MutationFence.Attempt? = null) : EnrollmentOutcome
    data class DeletionRequested(val state: DeletionState, override val attempt: MutationFence.Attempt? = null) : EnrollmentOutcome

    /** Refused locally before any network call (e.g. no accepted disclosure). */
    data class Refused(val reason: String, override val attempt: MutationFence.Attempt? = null) : EnrollmentOutcome

    /**
     * A newer action or an account switch made this result irrelevant. NOT an error: the operation
     * may well have succeeded server-side, but reporting it would describe a state the user has since
     * moved past (or does not belong to this account).
     */
    data object Superseded : EnrollmentOutcome {
        override val attempt: MutationFence.Attempt? = null
    }
}

/**
 * The seam between the account operations and the Settings UI
 * (plan `docs/plans/android/voice-recording-uploads.md`, Task 2 + the UI shell item).
 *
 * Two rules this class exists to enforce, both user-facing:
 *
 * 1. **Turning sharing off stops collection at the instant of the tap.** [clearLocalPending] runs
 *    BEFORE the withdrawal request is awaited, because a user who withdraws must not keep
 *    contributing while a round trip completes. The server's answer changes the reported status, not
 *    whether we stopped collecting.
 * 2. **A conflicted opt-in is never replayed.** A 409 means the membership moved underneath us; the
 *    caller surfaces the conflict and requires a fresh user action, because silently retrying would
 *    enroll somebody who did not just ask again.
 *
 * Deletion is a DIFFERENT operation from withdrawal and is never presented as part of it: withdrawal
 * stops future collection, deletion requests removal of data already accepted.
 */
class EnrollmentController(
    private val enrollOperation: suspend (acceptedDisclosureVersion: String) -> VoiceSharingMutationResult,
    private val withdrawOperation: suspend () -> VoiceSharingMutationResult,
    private val deleteOperation: suspend () -> DeletionReceipt?,
    private val clearLocalPending: () -> Unit,
    /** Owner session each operation is issued under; a change drops in-flight results. */
    private val ownerSession: () -> Long,
    private val fence: MutationFence = MutationFence(),
) {
    private val mutableState = MutableStateFlow<EnrollmentState>(EnrollmentState.Idle)
    val state: StateFlow<EnrollmentState> = mutableState.asStateFlow()

    /**
     * Enroll with an ACCEPTED disclosure version. A blank version is refused locally: the contract
     * requires the accepted version to be sent, and enrolling without a recorded disclosure would
     * bypass the consent this whole surface exists to collect.
     */
    suspend fun enroll(acceptedDisclosureVersion: String): EnrollmentOutcome {
        if (acceptedDisclosureVersion.isBlank()) {
            return EnrollmentOutcome.Refused(
                "enrollment requires an accepted disclosure version",
                fence.peek(),
            )
        }
        mutableState.value = EnrollmentState.Pending
        val attempt = fence.issue(ownerSession())
        val result = fence.serialize { enrollOperation(acceptedDisclosureVersion) }
        // A superseded or cross-session result is DROPPED, not applied: applying it would let a slow
        // enrollment flip the UI back to "sharing on" after the user turned sharing off, or write one
        // account's outcome onto another's screen.
        //
        // Compare against the session the attempt was ISSUED under, not a fresh read: re-reading here
        // would return the NEW account and make the check trivially true, which is exactly how this
        // leaked one account's outcome onto another's screen the first time.
        if (!fence.isCurrent(attempt, ownerSession)) return EnrollmentOutcome.Superseded
        val settled = when (result) {
            is VoiceSharingMutationResult.Applied -> EnrollmentState.Applied
            is VoiceSharingMutationResult.MembershipConflict -> EnrollmentState.Conflict
            VoiceSharingMutationResult.Failure -> EnrollmentState.Failed
        }
        mutableState.value = settled
        return when (result) {
            is VoiceSharingMutationResult.Applied -> EnrollmentOutcome.Applied(settled, attempt)
            is VoiceSharingMutationResult.MembershipConflict -> EnrollmentOutcome.Conflict(settled, attempt)
            VoiceSharingMutationResult.Failure -> EnrollmentOutcome.Failed(settled, attempt)
        }
    }

    /**
     * Whether [outcome] still describes the authoritative action for the live account RIGHT NOW.
     *
     * Settings applies durable side effects on an outcome (recording or releasing the global stop),
     * and it must decide that AT APPLICATION TIME, not from what was true when the call returned. A
     * newer action issued in between makes this false, and so does an account switch - the stop is
     * global, so releasing it on behalf of an account that is no longer signed in would resume
     * collection for somebody else.
     */
    fun stillApplies(outcome: EnrollmentOutcome): Boolean {
        if (outcome is EnrollmentOutcome.Superseded) return false
        val attempt = outcome.attempt ?: return false
        return fence.isCurrent(attempt, ownerSession)
    }

    /**
     * Applies a durable side effect for [outcome], or applies NOTHING.
     *
     * Use this instead of [stillApplies] followed by an effect. The two-step form has a gap the
     * reviewer named: `issue`/`supersede`/account change do not hold any caller-side lock, so between
     * the check and the effect the world can move, and the effect then lands on state the decision no
     * longer describes. Here the decision and the effect run inside the fence's own critical section,
     * the same one its mutations take.
     *
     * Returns true if the effect ran.
     */
    fun <T> applyForOutcome(outcome: EnrollmentOutcome, effect: () -> T): T? {
        if (outcome is EnrollmentOutcome.Superseded) return null
        val attempt = outcome.attempt ?: return null
        return fence.applyIfCurrent(attempt, ownerSession, effect)
    }

    /**
     * Withdraw the CURRENT enrollment. The epoch is deliberately not a parameter: the account layer
     * captures it from the fresh projection inside its owner transaction, so the withdrawal always
     * targets the enrollment that was current at issue time rather than one the caller remembered.
     *
     * Local clearing happens FIRST and unconditionally: even if the network call fails, we have
     * already stopped collecting, and the failure is reported as a server-side status rather than as
     * a reason collection might resume.
     */
    suspend fun withdraw(): EnrollmentOutcome {
        // ORDER MATTERS: before the request, not after. A later clear would keep collecting for the
        // duration of the round trip, which is exactly the state the user just asked to leave.
        clearLocalPending()
        // SUPERSEDE BEFORE ANYTHING ELSE. An in-flight enrollment must lose here rather than complete
        // and restore the UI to "on"; waiting for it (or for the serialization lock it holds) would
        // make the user's decision queue behind a request they have already changed their mind about.
        mutableState.value = EnrollmentState.Pending
        // ONE atomic step: kill every earlier attempt and make this withdrawal the authoritative one.
        val attempt = fence.supersedeAndIssue(ownerSession())
        // NOT serialized behind the enrollment lock. Serializing here would make the user's stop wait
        // for the very request they are stopping — the deadlock this control produced. The withdrawal
        // is already the newest intent, and the fence guarantees the older enrollment cannot apply, so
        // the two requests need not be ordered against each other.
        val result = withdrawOperation()
        if (!fence.isCurrent(attempt, ownerSession)) return EnrollmentOutcome.Superseded
        val settled = when (result) {
            is VoiceSharingMutationResult.Applied -> EnrollmentState.Applied
            is VoiceSharingMutationResult.MembershipConflict -> EnrollmentState.Conflict
            VoiceSharingMutationResult.Failure -> EnrollmentState.Failed
        }
        mutableState.value = settled
        return when (result) {
            is VoiceSharingMutationResult.Applied -> EnrollmentOutcome.Applied(settled, attempt)
            is VoiceSharingMutationResult.MembershipConflict -> EnrollmentOutcome.Conflict(settled, attempt)
            VoiceSharingMutationResult.Failure -> EnrollmentOutcome.Failed(settled, attempt)
        }
    }

    /**
     * Request deletion of already-accepted data. Separate from withdrawal, and never described as
     * stopping future sharing. A 202 is PENDING; the absence of a receipt means we do not know the
     * status and it is reported as failed rather than assumed.
     */
    suspend fun deleteSharedData(): EnrollmentOutcome {
        mutableState.value = EnrollmentState.Pending
        val receipt = deleteOperation()
        if (receipt == null) {
            mutableState.value = EnrollmentState.Failed
            return EnrollmentOutcome.Failed(EnrollmentState.Failed)
        }
        mutableState.value = EnrollmentState.Applied
        return EnrollmentOutcome.DeletionRequested(DeletionState.Pending)
    }
}
