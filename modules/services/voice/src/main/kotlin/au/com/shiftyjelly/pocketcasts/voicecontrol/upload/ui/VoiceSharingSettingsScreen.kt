package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import au.com.shiftyjelly.pocketcasts.localization.R

/**
 * The enrollment/collection settings row (plan Task 7 UI shell). THIN by design: every decision —
 * what the toggle means, when the setting hides, whether a failure is shown — lives in
 * [VoiceSharingSettingsViewModel] and is controlled there. This composable renders state and
 * forwards taps, nothing else.
 *
 * COPY: the strings come from the localization module and MUST stay identical to the
 * [au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentDisclosure] constants
 * whose version the client sends as accepted consent (the disclosure test asserts the invariant).
 * Status lines are HONEST: a failed withdrawal says sharing is already stopped on this device; a
 * failed enrollment says nothing is being shared; pending never claims completion.
 */
@Composable
fun VoiceSharingSettingsScreen(
    viewModel: VoiceSharingSettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    if (!state.settingAvailable) return

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.voice_sharing_title),
                    style = MaterialTheme.typography.body1,
                )
                Text(
                    text = stringResource(R.string.voice_sharing_disclosure_supporting),
                    style = MaterialTheme.typography.body2,
                )
            }
            Switch(
                checked = state.enrolled,
                enabled = !state.pending,
                onCheckedChange = { checked ->
                    if (checked) viewModel.enroll() else viewModel.withdraw()
                },
            )
        }
        Text(
            text = stringResource(R.string.voice_sharing_disclosure_detail),
            style = MaterialTheme.typography.body2,
        )
        if (state.pending) {
            Text(
                text = stringResource(R.string.voice_sharing_withdraw_pending),
                style = MaterialTheme.typography.caption,
            )
        }
        when (state.failedOperation) {
            FailedOperation.ENROLL -> Text(
                text = stringResource(R.string.voice_sharing_enroll_failed),
                style = MaterialTheme.typography.caption,
            )

            FailedOperation.WITHDRAW -> Text(
                text = stringResource(R.string.voice_sharing_withdraw_failed),
                style = MaterialTheme.typography.caption,
            )

            FailedOperation.NONE -> Unit
        }
        if (state.status == SettingsOperationStatus.CONFLICT) {
            Text(
                text = stringResource(R.string.voice_sharing_conflict),
                style = MaterialTheme.typography.caption,
            )
        }
    }
}
