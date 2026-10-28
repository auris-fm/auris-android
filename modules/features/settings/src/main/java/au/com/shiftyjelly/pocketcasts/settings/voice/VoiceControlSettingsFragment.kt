package au.com.shiftyjelly.pocketcasts.settings.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import au.com.shiftyjelly.pocketcasts.compose.AppTheme
import au.com.shiftyjelly.pocketcasts.compose.AppThemeWithBackground
import au.com.shiftyjelly.pocketcasts.compose.bars.ThemedTopAppBar
import au.com.shiftyjelly.pocketcasts.compose.components.SettingRow
import au.com.shiftyjelly.pocketcasts.compose.components.SettingRowToggle
import au.com.shiftyjelly.pocketcasts.compose.extensions.contentWithoutConsumedInsets
import au.com.shiftyjelly.pocketcasts.compose.preview.ThemePreviewParameterProvider
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.settings.rowModifier
import au.com.shiftyjelly.pocketcasts.ui.theme.Theme
import au.com.shiftyjelly.pocketcasts.utils.extensions.pxToDp
import au.com.shiftyjelly.pocketcasts.views.fragments.BaseFragment
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningModePolicy
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.ModelDownloadState
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.ModelManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRouteMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.toMicExposure
import au.com.shiftyjelly.pocketcasts.voicecontrol.service.VoiceControlCaptureStateHolder
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.ui.VoiceSharingSettingsUiState
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.ui.VoiceSharingSettingsViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import au.com.shiftyjelly.pocketcasts.localization.R as LR

@AndroidEntryPoint
class VoiceControlSettingsFragment : BaseFragment() {

    @Inject lateinit var settings: Settings

    @Inject lateinit var modelManager: ModelManager

    @Inject lateinit var gate: VoiceControlGate

    @Inject lateinit var audioRouteMonitor: AudioRouteMonitor

    @Inject lateinit var listeningModePolicy: ListeningModePolicy

    @Inject lateinit var captureStateHolder: VoiceControlCaptureStateHolder

    @Inject lateinit var voiceSharingSettings: VoiceSharingSettingsViewModel

    private fun Context.isRecordAudioGranted() = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = contentWithoutConsumedInsets {
        AppThemeWithBackground(theme.activeTheme) {
            val context = LocalContext.current
            val enabled by settings.voiceControlUserDisabled.flow
                .collectAsState(false)
            val modelState by modelManager.downloadState
                .collectAsState(ModelDownloadState.NotStarted)
            val modelReady = modelManager.isMoonshineModelReady()
            val gateState by gate.state
                .collectAsStateWithLifecycle()
            val route by audioRouteMonitor.route
                .collectAsStateWithLifecycle()
            val mode by listeningModePolicy.mode
                .collectAsStateWithLifecycle()
            val capturing by captureStateHolder.capturing
                .collectAsStateWithLifecycle(false)
            val bottomInset = settings.bottomInset
                .collectAsStateWithLifecycle(0)

            var permissionGranted by remember { mutableStateOf(context.isRecordAudioGranted()) }
            DisposableEffect(this) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        permissionGranted = context.isRecordAudioGranted()
                    }
                }
                lifecycle.addObserver(observer)
                onDispose {
                    lifecycle.removeObserver(observer)
                }
            }

            // ONE SHARING SURFACE (spec directive): the reachable Settings section binds to the
            // reviewed ViewModel — durable presentation, tap-time session capture, fenced outcome
            // effects and the local-stop-first withdrawal all live there now.
            val sharingState by voiceSharingSettings.state.collectAsStateWithLifecycle()
            // The section only renders when it can be truthful: policy available AND an account
            // projection actually observed (no switch without a projection to read it against).
            val sharing = if (sharingState.settingAvailable && sharingState.projectionObserved) sharingState else null
            var confirmDelete by remember { mutableStateOf(false) }
            if (confirmDelete) {
                AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text(stringResource(LR.string.voice_sharing_delete_confirm_title)) },
                    text = { Text(stringResource(LR.string.voice_sharing_delete_confirm_body)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                confirmDelete = false
                                voiceSharingSettings.deleteSharedData()
                            },
                        ) { Text(stringResource(LR.string.voice_sharing_delete_action)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                    },
                )
            }

            VoiceControlSettingsPage(
                enabled = !enabled,
                modelReady = modelReady,
                modelState = modelState,
                statusText = voiceControlStatus(
                    mode = mode,
                    gate = gateState,
                    micExposure = route.toMicExposure(),
                    permissionGranted = permissionGranted,
                    capturing = capturing,
                ),
                onEnabledChange = { checked ->
                    settings.voiceControlUserDisabled.set(!checked, updateModifiedAt = false)
                },
                sharing = sharing,
                onSharingChange = { checked ->
                    // The ViewModel owns the ordering: withdraw records the LOCAL stop at the tap
                    // (before any request) and enrolls release the stop only inside the fence.
                    if (checked) voiceSharingSettings.enroll() else voiceSharingSettings.withdraw()
                },
                onDeleteSharedData = { confirmDelete = true },
                onBackPress = { activity?.onBackPressedDispatcher?.onBackPressed() },
                bottomInset = bottomInset.value.pxToDp(LocalContext.current).dp,
            )
        }
    }
}

@Composable
private fun VoiceControlSettingsPage(
    enabled: Boolean,
    modelReady: Boolean,
    modelState: ModelDownloadState,
    statusText: String,
    sharing: VoiceSharingSettingsUiState?,
    onEnabledChange: (Boolean) -> Unit,
    onSharingChange: (Boolean) -> Unit,
    onDeleteSharedData: () -> Unit,
    onBackPress: () -> Unit,
    bottomInset: Dp,
) {
    // Strings resolved eagerly in composition; the sharing copy mapping itself stays a pure,
    // unit-tested function (VoiceSharingSettingsRowCopyTest).
    val rowStrings = mapOf(
        LR.string.voice_sharing_enroll_pending to stringResource(LR.string.voice_sharing_enroll_pending),
        LR.string.voice_sharing_withdraw_pending to stringResource(LR.string.voice_sharing_withdraw_pending),
        LR.string.voice_sharing_enroll_failed to stringResource(LR.string.voice_sharing_enroll_failed),
        LR.string.voice_sharing_withdraw_failed to stringResource(LR.string.voice_sharing_withdraw_failed),
        LR.string.voice_sharing_local_stop_failed to stringResource(LR.string.voice_sharing_local_stop_failed),
        LR.string.voice_sharing_conflict to stringResource(LR.string.voice_sharing_conflict),
        LR.string.voice_sharing_delete_pending to stringResource(LR.string.voice_sharing_delete_pending),
        LR.string.voice_sharing_delete_failed to stringResource(LR.string.voice_sharing_delete_failed),
    )
    val copyResolver: (Int) -> String = { rowStrings[it].orEmpty() }
    Column(modifier = Modifier.padding(bottom = bottomInset)) {
        ThemedTopAppBar(
            title = "Voice Control",
            onNavigationClick = { onBackPress() },
        )
        LazyColumn(contentPadding = PaddingValues(vertical = 16.dp)) {
            item {
                SettingRow(
                    primaryText = "Enable Voice Control",
                    secondaryText = "Listen for voice commands during playback",
                    toggle = SettingRowToggle.Switch(checked = enabled),
                    modifier = Modifier.toggleable(
                        value = enabled,
                        role = Role.Switch,
                    ) { onEnabledChange(it) },
                    indent = false,
                )
            }
            item {
                val modelText = when (modelState) {
                    is ModelDownloadState.NotStarted -> if (modelReady) "Ready" else "Not downloaded"
                    is ModelDownloadState.Downloading -> "Downloading: ${modelState.progressPercent}%"
                    is ModelDownloadState.Ready -> "Ready"
                    is ModelDownloadState.Failed -> "Download failed"
                }
                SettingRow(
                    primaryText = "Speech Model",
                    secondaryText = modelText,
                    toggle = SettingRowToggle.None,
                    indent = false,
                )
            }
            item {
                SettingRow(
                    primaryText = "Status",
                    secondaryText = statusText,
                    toggle = SettingRowToggle.None,
                    indent = false,
                )
            }
            // SHARING SURFACE — one surface, bound to the reviewed ViewModel. Only rendered once
            // an account projection has been observed and the policy allows the setting.
            if (sharing != null) {
                item {
                    SettingRow(
                        primaryText = stringResource(LR.string.voice_sharing_title),
                        secondaryText = sharing.disclosureDetail,
                        toggle = SettingRowToggle.None,
                        indent = false,
                    )
                }
                item {
                    val benefits = stringResource(LR.string.voice_sharing_benefits)
                    SettingRow(
                        primaryText = stringResource(LR.string.voice_sharing_setting_share_recordings),
                        secondaryText = VoiceSharingSettingsRowCopy.switchSecondary(sharing, benefits, copyResolver),
                        toggle = SettingRowToggle.Switch(checked = sharing.enrolled),
                        modifier = Modifier.toggleable(
                            value = sharing.enrolled,
                            role = Role.Switch,
                        ) { onSharingChange(it) },
                        indent = false,
                    )
                }
                // Deleting already-accepted data is a separate, available operation while enrolled.
                // Its pending state is 202-processing honesty, never "deleted" (202 ≠ done).
                if (sharing.enrolled) {
                    item {
                        val deleteSecondary = VoiceSharingSettingsRowCopy.deleteSecondary(sharing, copyResolver)
                        SettingRow(
                            primaryText = stringResource(LR.string.voice_sharing_delete_action),
                            secondaryText = deleteSecondary,
                            toggle = SettingRowToggle.None,
                            modifier = Modifier.rowModifier(onDeleteSharedData),
                            indent = false,
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun VoiceControlSettingsPagePreview(
    @PreviewParameter(ThemePreviewParameterProvider::class) themeType: Theme.ThemeType,
) {
    AppTheme(themeType) {
        VoiceControlSettingsPage(
            enabled = true,
            modelReady = true,
            modelState = ModelDownloadState.Ready,
            statusText = "Active (wake word required)",
            sharing = null,
            onEnabledChange = {},
            onSharingChange = {},
            onDeleteSharedData = {},
            onBackPress = {},
            bottomInset = 0.dp,
        )
    }
}
