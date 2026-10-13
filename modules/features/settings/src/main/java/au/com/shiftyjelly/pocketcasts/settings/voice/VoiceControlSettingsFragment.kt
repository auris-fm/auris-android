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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import au.com.shiftyjelly.pocketcasts.ui.theme.Theme
import au.com.shiftyjelly.pocketcasts.utils.extensions.pxToDp
import au.com.shiftyjelly.pocketcasts.views.fragments.BaseFragment
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningModePolicy
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.ModelDownloadState
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.ModelManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRouteMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.toMicExposure
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class VoiceControlSettingsFragment : BaseFragment() {

    @Inject lateinit var settings: Settings

    @Inject lateinit var modelManager: ModelManager

    @Inject lateinit var gate: VoiceControlGate

    @Inject lateinit var audioRouteMonitor: AudioRouteMonitor

    @Inject lateinit var listeningModePolicy: ListeningModePolicy

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

            VoiceControlSettingsPage(
                enabled = !enabled,
                modelReady = modelReady,
                modelState = modelState,
                statusText = voiceControlStatus(
                    mode = mode,
                    gate = gateState,
                    micExposure = route.toMicExposure(),
                    permissionGranted = permissionGranted,
                ),
                onEnabledChange = { checked ->
                    settings.voiceControlUserDisabled.set(!checked, updateModifiedAt = false)
                },
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
    onEnabledChange: (Boolean) -> Unit,
    onBackPress: () -> Unit,
    bottomInset: Dp,
) {
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
            onEnabledChange = {},
            onBackPress = {},
            bottomInset = 0.dp,
        )
    }
}
