package au.com.shiftyjelly.pocketcasts.settings.voice

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingPlan
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.WithdrawalState
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.DeletionState
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Share voice recordings surface's honesty rules, tested as a pure function because these are
 * exactly the claims a user reads. A wrong label here is not a layout bug — it tells someone their
 * voice is or is not being sent when the opposite may be true.
 */
class VoiceSharingPresentationTest {

    private val copy = VoiceSharingCopy(
        supporting = "SEND-SUPPORTING",
        detail = "SEND-DETAIL",
        benefits = "BENEFITS",
        withdrawPending = "WITHDRAW-PENDING",
        withdrawFailed = "WITHDRAW-FAILED",
        conflict = "CONFLICT",
        deleteAction = "DELETE-ACTION",
        deletePending = "DELETE-PENDING",
        deleteFailed = "DELETE-FAILED",
    )

    private fun projection(
        enrolled: Boolean = true,
        withdrawal: WithdrawalState = WithdrawalState.ACTIVE,
        epoch: String? = "epoch-1",
    ) = VoiceSharingAccountProjection(
        membershipRevision = "rev-1",
        plan = VoiceSharingPlan.FREE,
        voiceSharingEnrolled = enrolled,
        enrollmentEpoch = epoch,
        acceptedDisclosureVersion = "2026-10-01",
        withdrawalState = withdrawal,
    )

    private fun row(
        projection: VoiceSharingAccountProjection? = projection(),
        locallySharing: Boolean = true,
        enrollmentState: EnrollmentState = EnrollmentState.Idle,
        deletionState: DeletionState? = null,
        deletionFailed: Boolean = false,
    ) = VoiceSharingPresentation.forRow(projection, locallySharing, enrollmentState, deletionState, deletionFailed, copy)

    @Test
    fun `turning sharing off flips the switch immediately, before the server answers`() {
        val state = row(locallySharing = false, enrollmentState = EnrollmentState.Pending)
        assertFalse(
            "the switch must read off the moment the user turns it off, not when the server confirms",
            state.switchEnabled,
        )
    }

    @Test
    fun `a failed server withdrawal says collection already stopped on this device`() {
        val state = row(locallySharing = false, enrollmentState = EnrollmentState.Failed)
        assertEquals(
            "a failed withdrawal must not imply recordings are still being sent",
            "WITHDRAW-FAILED",
            state.secondary,
        )
    }

    @Test
    fun `an in-flight withdrawal is reported as pending, never as completed`() {
        val state = row(locallySharing = false, enrollmentState = EnrollmentState.Pending)
        assertEquals("WITHDRAW-PENDING", state.secondary)
    }

    @Test
    fun `a conflict asks for a fresh review rather than silently retrying`() {
        val state = row(enrollmentState = EnrollmentState.Conflict)
        assertEquals("CONFLICT", state.secondary)
    }

    @Test
    fun `a pending deletion is reported as still processing and never as deleted`() {
        val state = row(deletionState = DeletionState.Pending)
        assertEquals(
            "a 202 means pending; the surface must not claim the data was deleted",
            "DELETE-PENDING",
            state.deleteSecondary,
        )
    }

    @Test
    fun `a failed deletion is surfaced rather than dropped`() {
        val state = row(deletionFailed = true)
        assertEquals("DELETE-FAILED", state.deleteSecondary)
    }

    @Test
    fun `the delete action is offered even after withdrawal, because deletion is separate`() {
        val withdrawn = projection(enrolled = false, withdrawal = WithdrawalState.WITHDRAWN)
        val state = row(projection = withdrawn, locallySharing = false)
        assertTrue(
            "withdrawal never deletes accepted data, so the separate delete action must remain available",
            state.showDeleteAction,
        )
        assertNull("nothing is pending for deletion, so no status line", state.deleteSecondary)
    }

    @Test
    fun `no delete action when the account has never enrolled`() {
        val never = projection(enrolled = false, withdrawal = WithdrawalState.NOT_ENROLLED, epoch = null)
        assertFalse("nothing was ever shared, so there is nothing to delete", row(projection = never).showDeleteAction)
    }

    @Test
    fun `the switch is unavailable without a projection so no blind mutation is sent`() {
        val state = row(projection = null, locallySharing = false)
        assertFalse(state.switchAvailable)
    }

    @Test
    fun `an enrolled-and-active account shows the disclosure supporting text when sharing is on`() {
        val state = row()
        assertTrue(state.switchEnabled)
        assertEquals(copy.supporting, state.secondary)
    }
}
