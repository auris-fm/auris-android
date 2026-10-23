package au.com.shiftyjelly.pocketcasts.settings.voice

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.WithdrawalState
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.DeletionState
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentState

/**
 * The Share voice recordings row's presentation, derived from account + operation state.
 *
 * Kept as a pure function so the honesty rules are TESTABLE without rendering anything:
 *
 * - The switch reflects the user's LOCAL collection state first. Once they turn sharing off, the
 *   toggle reads off immediately, even if the server has not answered yet — the alternative would
 *   show "on" while we have already stopped collecting, which misstates what is happening.
 * - A server-side withdrawal that has not completed is reported as PENDING or FAILED, never as
 *   "off and done". [secondary] carries that, and a failed withdrawal explicitly says collection has
 *   already stopped on this device, because that is the part the user actually cares about.
 * - Deletion is a separate action with its own status. A 202 is "still processing", never "deleted".
 */
data class VoiceSharingRowState(
    val switchEnabled: Boolean,
    val switchAvailable: Boolean,
    val secondary: String?,
    val showDeleteAction: Boolean,
    val deleteSecondary: String?,
)

/** Every user-visible string this surface can render, resolved by the caller from resources. */
data class VoiceSharingCopy(
    val supporting: String,
    val detail: String,
    val benefits: String,
    val withdrawPending: String,
    val withdrawFailed: String,
    val conflict: String,
    val deleteAction: String,
    val deletePending: String,
    val deleteFailed: String,
)

object VoiceSharingPresentation {

    fun forRow(
        projection: VoiceSharingAccountProjection?,
        locallySharing: Boolean,
        enrollmentState: EnrollmentState,
        deletionState: DeletionState?,
        deletionFailed: Boolean,
        copy: VoiceSharingCopy,
    ): VoiceSharingRowState {
        // LOCAL state wins for the switch: the user's most recent intent is what the control shows.
        val enrolledServerSide = projection?.voiceSharingEnrolled == true &&
            projection.withdrawalState == WithdrawalState.ACTIVE
        val switch = locallySharing && enrolledServerSide

        val status = when {
            // An in-flight withdraw is only pending when the user has already turned it off locally;
            // before that it is the ordinary "on" state and needs no status line.
            enrollmentState is EnrollmentState.Pending && !locallySharing -> copy.withdrawPending

            enrollmentState is EnrollmentState.Failed && !locallySharing -> copy.withdrawFailed

            enrollmentState is EnrollmentState.Conflict -> copy.conflict

            else -> null
        }

        val deletionText = when {
            deletionFailed -> copy.deleteFailed
            deletionState == DeletionState.Pending -> copy.deletePending
            else -> null
        }

        return VoiceSharingRowState(
            switchEnabled = switch,
            // Changing sharing requires an account projection; without one we do not know the
            // current membership revision and must not send a blind mutation.
            switchAvailable = projection != null,
            secondary = status ?: if (switch) copy.supporting else null,
            showDeleteAction = projection?.voiceSharingEnrolled == true || projection?.enrollmentEpoch != null,
            deleteSecondary = deletionText,
        )
    }
}
