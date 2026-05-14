package au.com.shiftyjelly.pocketcasts.settings.voice

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import au.com.shiftyjelly.pocketcasts.compose.AppTheme
import au.com.shiftyjelly.pocketcasts.compose.AppThemeWithBackground
import au.com.shiftyjelly.pocketcasts.compose.bars.ThemedTopAppBar
import au.com.shiftyjelly.pocketcasts.compose.components.SettingRow
import au.com.shiftyjelly.pocketcasts.compose.components.SettingRowToggle
import au.com.shiftyjelly.pocketcasts.compose.extensions.contentWithoutConsumedInsets
import au.com.shiftyjelly.pocketcasts.compose.preview.ThemePreviewParameterProvider
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.preferences.model.VoiceControlAudioRoutePolicy
import au.com.shiftyjelly.pocketcasts.ui.theme.Theme
import au.com.shiftyjelly.pocketcasts.utils.extensions.pxToDp
import au.com.shiftyjelly.pocketcasts.views.fragments.BaseFragment
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.ModelDownloadState
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.ModelManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceEnrollmentManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceEnrollmentState
import au.com.shiftyjelly.pocketcasts.voicecontrol.ui.EnrollmentActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class VoiceControlSettingsFragment : BaseFragment() {

    @Inject lateinit var settings: Settings

    @Inject lateinit var modelManager: ModelManager

    @Inject lateinit var enrollmentManager: VoiceEnrollmentManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = contentWithoutConsumedInsets {
        AppThemeWithBackground(theme.activeTheme) {
            val enabled by settings.voiceControlUserDisabled.flow
                .collectAsState(false)
            val policy by settings.voiceControlAudioRoutePolicy.flow
                .collectAsState(VoiceControlAudioRoutePolicy.HeadsetOnly)
            val modelState by modelManager.downloadState
                .collectAsState(ModelDownloadState.NotStarted)
            val modelReady = modelManager.areModelsReady()
            val enrollmentState by enrollmentManager.state
                .collectAsState(VoiceEnrollmentState.NotEnrolled)
            val bottomInset = settings.bottomInset
                .collectAsStateWithLifecycle(0)
            val context = LocalContext.current

            VoiceControlSettingsPage(
                enabled = !enabled,
                routePolicy = policy,
                modelReady = modelReady,
                modelState = modelState,
                enrollmentState = enrollmentState,
                onEnabledChange = { checked ->
                    settings.voiceControlUserDisabled.set(!checked, updateModifiedAt = false)
                },
                onRoutePolicyChange = { newPolicy ->
                    settings.voiceControlAudioRoutePolicy.set(newPolicy, updateModifiedAt = false)
                },
                onEnrollClick = {
                    context.startActivity(Intent(context, EnrollmentActivity::class.java))
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
    routePolicy: VoiceControlAudioRoutePolicy,
    modelReady: Boolean,
    modelState: ModelDownloadState,
    enrollmentState: VoiceEnrollmentState,
    onEnabledChange: (Boolean) -> Unit,
    onRoutePolicyChange: (VoiceControlAudioRoutePolicy) -> Unit,
    onEnrollClick: () -> Unit,
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
                val policyText = when (routePolicy) {
                    VoiceControlAudioRoutePolicy.HeadsetOnly -> "Headset Only"
                    VoiceControlAudioRoutePolicy.SpeakerExperimental -> "Speaker (Experimental)"
                }
                SettingRow(
                    primaryText = "Audio Route Policy",
                    secondaryText = policyText,
                    toggle = SettingRowToggle.None,
                    modifier = Modifier.toggleable(
                        value = routePolicy == VoiceControlAudioRoutePolicy.SpeakerExperimental,
                        role = Role.Switch,
                    ) {
                        onRoutePolicyChange(
                            if (it) {
                                VoiceControlAudioRoutePolicy.SpeakerExperimental
                            } else {
                                VoiceControlAudioRoutePolicy.HeadsetOnly
                            },
                        )
                    },
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
                val enrollmentText = when (enrollmentState) {
                    is VoiceEnrollmentState.NotEnrolled -> "Not enrolled — tap to enroll your voice"
                    is VoiceEnrollmentState.Enrolling -> "Enrolling..."
                    is VoiceEnrollmentState.Enrolled -> "Enrolled"
                }
                SettingRow(
                    primaryText = "Speaker Verification",
                    secondaryText = enrollmentText,
                    toggle = SettingRowToggle.None,
                    modifier = Modifier.toggleable(
                        value = false,
                        role = Role.Button,
                    ) { onEnrollClick() },
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
            routePolicy = VoiceControlAudioRoutePolicy.HeadsetOnly,
            modelReady = true,
            modelState = ModelDownloadState.Ready,
            enrollmentState = VoiceEnrollmentState.NotEnrolled,
            onEnabledChange = {},
            onRoutePolicyChange = {},
            onEnrollClick = {},
            onBackPress = {},
            bottomInset = 0.dp,
        )
    }
}
