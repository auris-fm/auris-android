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
    data class Applied(val state: EnrollmentState) : EnrollmentOutcome
    data class Conflict(val state: EnrollmentState) : EnrollmentOutcome
    data class Failed(val state: EnrollmentState) : EnrollmentOutcome
    data class DeletionRequested(val state: DeletionState) : EnrollmentOutcome

    /** Refused locally before any network call (e.g. no accepted disclosure). */
    data class Refused(val reason: String) : EnrollmentOutcome
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
            return EnrollmentOutcome.Refused("enrollment requires an accepted disclosure version")
        }
        mutableState.value = EnrollmentState.Pending
        val result = enrollOperation(acceptedDisclosureVersion)
        val settled = when (result) {
            is VoiceSharingMutationResult.Applied -> EnrollmentState.Applied
            is VoiceSharingMutationResult.MembershipConflict -> EnrollmentState.Conflict
            VoiceSharingMutationResult.Failure -> EnrollmentState.Failed
        }
        mutableState.value = settled
        return when (result) {
            is VoiceSharingMutationResult.Applied -> EnrollmentOutcome.Applied(settled)
            is VoiceSharingMutationResult.MembershipConflict -> EnrollmentOutcome.Conflict(settled)
            VoiceSharingMutationResult.Failure -> EnrollmentOutcome.Failed(settled)
        }
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
        mutableState.value = EnrollmentState.Pending
        val result = withdrawOperation()
        val settled = when (result) {
            is VoiceSharingMutationResult.Applied -> EnrollmentState.Applied
            is VoiceSharingMutationResult.MembershipConflict -> EnrollmentState.Conflict
            VoiceSharingMutationResult.Failure -> EnrollmentState.Failed
        }
        mutableState.value = settled
        return when (result) {
            is VoiceSharingMutationResult.Applied -> EnrollmentOutcome.Applied(settled)
            is VoiceSharingMutationResult.MembershipConflict -> EnrollmentOutcome.Conflict(settled)
            VoiceSharingMutationResult.Failure -> EnrollmentOutcome.Failed(settled)
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
