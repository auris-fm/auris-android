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
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackendSelector
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.CanaryFlashBackend
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.SenseVoiceBackend
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningModePolicy
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.ModelDownloadState
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.ModelManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRouteMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.toMicExposure
import au.com.shiftyjelly.pocketcasts.voicecontrol.service.VoiceControlCaptureStateHolder
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordDetector
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class VoiceControlSettingsFragment : BaseFragment() {

    @Inject lateinit var settings: Settings

    @Inject lateinit var modelManager: ModelManager

    @Inject lateinit var wakeWordDetector: WakeWordDetector

    @Inject lateinit var asrBackendSelector: AsrBackendSelector

    @Inject lateinit var gate: VoiceControlGate

    @Inject lateinit var audioRouteMonitor: AudioRouteMonitor

    @Inject lateinit var listeningModePolicy: ListeningModePolicy

    @Inject lateinit var captureStateHolder: VoiceControlCaptureStateHolder

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
            // The row must name the backend the product actually selects for this locale, and the
            // optional Moonshine download is reported separately -- never as recognition readiness.
            val modelState by modelManager.downloadState
                .collectAsState(ModelDownloadState.NotStarted)
            val selectedBackend = asrBackendSelector.select()
            val recognitionBackend = when (selectedBackend) {
                is SenseVoiceBackend -> RecognitionBackend.SenseVoice
                is CanaryFlashBackend -> RecognitionBackend.CanaryFlash
                null -> null
                else -> null
            }
            // Readiness is the selected backend's OWN model files, the same surface
            // VoiceControlService uses. The shared downloadState flow never reports Ready.
            var backendFilesPresent by remember {
                mutableStateOf(selectedBackend?.let { modelManager.isModelReady(it.requiredModel) } ?: false)
            }
            DisposableEffect(this) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        backendFilesPresent = selectedBackend?.let { modelManager.isModelReady(it.requiredModel) } ?: false
                    }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            val moonshineDownloaded = modelManager.isMoonshineModelReady()
            // The wake word is a different component from the recognition backend: its model is
            // bundled with the app and its readiness is the detector's own load result. Reading the
            // recognition model's absence as wake readiness contradicted the status row directly
            // above it (owner-observed: "Not downloaded" next to "Active (wake word required)").
            val wakeWordReady = wakeWordDetector.isReady
            // Recomputed on resume so a detector that initialized after first composition is shown
            // as it actually is, rather than as it looked once.
            var detectorReady by remember { mutableStateOf(wakeWordReady) }
            DisposableEffect(this) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        detectorReady = wakeWordDetector.isReady
                    }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
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

            VoiceControlSettingsPage(
                enabled = !enabled,
                recognitionBackend = recognitionBackend,
                recognitionBackendReady = backendFilesPresent,
                moonshineDownloaded = moonshineDownloaded,
                wakeWordReady = detectorReady,
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
                onBackPress = { activity?.onBackPressedDispatcher?.onBackPressed() },
                bottomInset = bottomInset.value.pxToDp(LocalContext.current).dp,
            )
        }
    }
}

@Composable
private fun VoiceControlSettingsPage(
    enabled: Boolean,
    recognitionBackend: RecognitionBackend?,
    recognitionBackendReady: Boolean,
    moonshineDownloaded: Boolean,
    wakeWordReady: Boolean,
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
                SettingRow(
                    primaryText = WAKE_WORD_MODEL_LABEL,
                    secondaryText = wakeWordModelText(wakeWordReady),
                    toggle = SettingRowToggle.None,
                    indent = false,
                )
            }
            item {
                SettingRow(
                    primaryText = RECOGNITION_MODEL_LABEL,
                    secondaryText = recognitionModelText(recognitionBackend, recognitionBackendReady, moonshineDownloaded),
                    toggle = SettingRowToggle.None,
                    indent = false,
                )
            }
            item {
                SettingRow(
                    primaryText = MOONSHINE_MODEL_LABEL,
                    secondaryText = moonshineModelText(moonshineDownloaded),
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
            recognitionBackend = RecognitionBackend.SenseVoice,
            recognitionBackendReady = true,
            moonshineDownloaded = false,
            wakeWordReady = true,
            statusText = "Active (wake word required)",
            onEnabledChange = {},
            onBackPress = {},
            bottomInset = 0.dp,
        )
    }
}

/** Each row names the component it measures; Moonshine's absence is never presented as wake readiness. */
internal const val WAKE_WORD_MODEL_LABEL = "Wake Word Model"
internal const val RECOGNITION_MODEL_LABEL = "Speech Recognition Model"
internal const val MOONSHINE_MODEL_LABEL = "Moonshine (optional)"

/**
 * The transcription backends the product actually selects between, mirroring `AsrBackendSelector`.
 * Moonshine is deliberately absent: it is not a selectable backend, so it cannot be the thing a
 * "recognition model" row reports.
 */
internal enum class RecognitionBackend(val displayName: String) {
    SenseVoice("SenseVoice"),
    CanaryFlash("Canary Flash"),
}

/**
 * The wake word model's row. Readiness is the detector's own load result -- not an assumption that
 * bundled assets mean Ready, which would hide a detector that failed to initialize.
 */
internal fun wakeWordModelText(detectorReady: Boolean): String = if (detectorReady) "Ready" else "Unavailable"

/** The rows the recognition section renders, computed from real readiness surfaces. */
internal data class RecognitionRows(
    val recognitionText: String,
    val recognitionProgressText: String,
    val moonshineText: String,
)

/**
 * Builds the recognition rows from the SELECTED backend's own model files.
 *
 * Reviewer finding 7e0bf9bf: readiness must come from `ModelManager.isModelReady(backendSpec)` --
 * the per-spec file check `VoiceControlService` itself uses -- not from the shared
 * `downloadState` flow. That flow starts at `NotStarted`, is only ever assigned `Downloading`, and
 * never becomes `Ready`, so deriving readiness from it reported a preinstalled, working backend as
 * "model missing" and let an unrelated model's download influence the row.
 */
internal fun recognitionRowsFor(
    selectedBackend: RecognitionBackend?,
    backendFilesPresent: Boolean,
    downloadState: ModelDownloadState,
    moonshineDownloaded: Boolean,
): RecognitionRows {
    val progressText = when (downloadState) {
        is ModelDownloadState.Downloading -> "Downloading: ${downloadState.progressPercent}%"
        is ModelDownloadState.Failed -> "Download failed"
        else -> ""
    }
    return RecognitionRows(
        recognitionText = recognitionModelText(
            backend = selectedBackend,
            backendReady = backendFilesPresent,
            moonshineDownloaded = moonshineDownloaded,
        ),
        recognitionProgressText = progressText,
        moonshineText = moonshineModelText(moonshineDownloaded),
    )
}

/**
 * The recognition row reports the SELECTED backend and that backend's readiness.
 *
 * Reviewer finding cec2f3be: this row previously reported Moonshine's download state under a
 * generic label, so on a device whose selected backend was SenseVoice it announced that speech
 * recognition was unavailable when only an unselected, optional model was missing. A `null`
 * backend means the OS locale is outside the product language set -- say that, rather than blame
 * a model that was never selected.
 */
internal fun recognitionModelText(
    backend: RecognitionBackend?,
    backendReady: Boolean,
    moonshineDownloaded: Boolean,
): String {
    if (backend == null) return "Not available for this language"
    return if (backendReady) "${backend.displayName} ready" else "${backend.displayName} model missing"
}

/** The optional Moonshine download, reported as its own row and never as recognition readiness. */
internal fun moonshineModelText(moonshineDownloaded: Boolean): String = if (moonshineDownloaded) "Downloaded" else "Not downloaded"
