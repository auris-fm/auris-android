package au.com.shiftyjelly.pocketcasts.settings.voice

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.ui.FailedOperation
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.ui.PendingOperation
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.ui.SettingsOperationStatus
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.ui.VoiceSharingSettingsUiState

/**
 * The sharing rows' secondary copy, mapped from the reviewed ViewModel's durable state.
 *
 * Extracted as a pure function over a string resolver so the BINDING's mapping is unit-testable
 * without Robolectric (reviewer 578bb62a: VM-only controls cannot prove the fragment's mapping).
 * The composable layer supplies `stringResource`; this object owns only the decision.
 */
object VoiceSharingSettingsRowCopy {

    /** The switch row's secondary copy: pending names the in-flight op; failures stay visible. */
    fun switchSecondary(
        state: VoiceSharingSettingsUiState,
        benefits: String,
        strings: (Int) -> String,
    ): String {
        if (state.pending) {
            return strings(
                when (state.pendingOperation) {
                    PendingOperation.ENROLL -> au.com.shiftyjelly.pocketcasts.localization.R.string.voice_sharing_enroll_pending
                    PendingOperation.WITHDRAW -> au.com.shiftyjelly.pocketcasts.localization.R.string.voice_sharing_withdraw_pending
                    PendingOperation.NONE -> au.com.shiftyjelly.pocketcasts.localization.R.string.voice_sharing_withdraw_pending
                },
            )
        }
        if (state.status == SettingsOperationStatus.RELEASE_FAILED) {
            return strings(au.com.shiftyjelly.pocketcasts.localization.R.string.voice_sharing_local_stop_failed)
        }
        if (state.status == SettingsOperationStatus.CONFLICT) {
            return strings(au.com.shiftyjelly.pocketcasts.localization.R.string.voice_sharing_conflict)
        }
        return when (state.failedOperation) {
            FailedOperation.ENROLL -> strings(au.com.shiftyjelly.pocketcasts.localization.R.string.voice_sharing_enroll_failed)
            FailedOperation.WITHDRAW -> strings(au.com.shiftyjelly.pocketcasts.localization.R.string.voice_sharing_withdraw_failed)
            FailedOperation.NONE -> benefits
        }
    }

    /**
     * The delete row's secondary copy. Three honest states (reviewer 578bb62a): the REQUEST in
     * flight, the accepted-but-processing 202 receipt, and a visible failure — none means deleted.
     */
    fun deleteSecondary(
        state: VoiceSharingSettingsUiState,
        strings: (Int) -> String,
    ): String = when {
        state.deletionRequested -> strings(au.com.shiftyjelly.pocketcasts.localization.R.string.voice_sharing_delete_pending)
        state.deletionReceiptPending -> strings(au.com.shiftyjelly.pocketcasts.localization.R.string.voice_sharing_delete_pending)
        state.deletionFailed -> strings(au.com.shiftyjelly.pocketcasts.localization.R.string.voice_sharing_delete_failed)
        else -> ""
    }
}
