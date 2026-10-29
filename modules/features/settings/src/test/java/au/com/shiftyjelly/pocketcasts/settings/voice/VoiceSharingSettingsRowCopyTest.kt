package au.com.shiftyjelly.pocketcasts.settings.voice

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingPlan
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.WithdrawalState
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentDisclosure
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.ui.FailedOperation
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.ui.PendingOperation
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.ui.SettingsOperationStatus
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.ui.VoiceSharingSettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The FRAGMENT BINDING's copy mapping (reviewer 578bb62a: VM-only controls cannot prove the
 * fragment's mapping). The mapping is extracted as a pure function and pinned here.
 */
class VoiceSharingSettingsRowCopyTest {

    private val strings = mutableMapOf<Int, String>()
    private fun resolver(): (Int) -> String = { id -> strings.getOrPut(id) { "str#$id" } }

    private fun state(
        pending: Boolean = false,
        pendingOperation: PendingOperation = PendingOperation.NONE,
        status: SettingsOperationStatus = SettingsOperationStatus.NONE,
        failedOperation: FailedOperation = FailedOperation.NONE,
        enrolled: Boolean = true,
    ) = VoiceSharingSettingsUiState(
        settingAvailable = true,
        enrolled = enrolled,
        pending = pending,
        status = status,
        failedOperation = failedOperation,
        pendingOperation = pendingOperation,
        projectionObserved = true,
        disclosureVersion = EnrollmentDisclosure.VERSION,
        disclosureTitle = "t",
        disclosureSupporting = "s",
        disclosureDetail = "d",
    )

    @Test
    fun `pending copy names the operation actually in flight`() {
        val r = resolver()
        val enroll = VoiceSharingSettingsRowCopy.switchSecondary(state(pending = true, pendingOperation = PendingOperation.ENROLL), "benefits", r)
        val withdraw = VoiceSharingSettingsRowCopy.switchSecondary(state(pending = true, pendingOperation = PendingOperation.WITHDRAW), "benefits", r)
        assertEquals(
            "an enrollment in flight must not say turning sharing off",
            strings[au.com.shiftyjelly.pocketcasts.localization.R.string.voice_sharing_enroll_pending],
            enroll,
        )
        assertEquals(
            strings[au.com.shiftyjelly.pocketcasts.localization.R.string.voice_sharing_withdraw_pending],
            withdraw,
        )
    }

    @Test
    fun `failures and the local-release failure outrank the idle benefits copy`() {
        val r = resolver()
        val enrollFailed = VoiceSharingSettingsRowCopy.switchSecondary(state(failedOperation = FailedOperation.ENROLL), "benefits", r)
        val withdrawFailed = VoiceSharingSettingsRowCopy.switchSecondary(state(failedOperation = FailedOperation.WITHDRAW), "benefits", r)
        val releaseFailed = VoiceSharingSettingsRowCopy.switchSecondary(state(status = SettingsOperationStatus.RELEASE_FAILED), "benefits", r)
        val conflict = VoiceSharingSettingsRowCopy.switchSecondary(state(status = SettingsOperationStatus.CONFLICT), "benefits", r)
        assertEquals(strings[au.com.shiftyjelly.pocketcasts.localization.R.string.voice_sharing_enroll_failed], enrollFailed)
        assertEquals(strings[au.com.shiftyjelly.pocketcasts.localization.R.string.voice_sharing_withdraw_failed], withdrawFailed)
        assertEquals(strings[au.com.shiftyjelly.pocketcasts.localization.R.string.voice_sharing_local_stop_failed], releaseFailed)
        assertEquals(strings[au.com.shiftyjelly.pocketcasts.localization.R.string.voice_sharing_conflict], conflict)
        assertEquals("an idle honest state shows the benefits copy", "benefits", VoiceSharingSettingsRowCopy.switchSecondary(state(), "benefits", r))
    }

    @Test
    fun `deletion states map honestly and none claim deleted`() {
        val s = resolver()
        val requested = VoiceSharingSettingsRowCopy.deleteSecondary(state().copy(deletionRequested = true), s)
        val receipt = VoiceSharingSettingsRowCopy.deleteSecondary(state().copy(deletionReceiptPending = true), s)
        val failed = VoiceSharingSettingsRowCopy.deleteSecondary(state().copy(deletionFailed = true), s)
        assertEquals("request in flight shows processing copy", requested, receipt)
        assertEquals("a failed deletion shows the failed copy, distinct from processing", failed != requested, true)
        assertEquals("no deletion state means done", "", VoiceSharingSettingsRowCopy.deleteSecondary(state(), s))
    }
}
