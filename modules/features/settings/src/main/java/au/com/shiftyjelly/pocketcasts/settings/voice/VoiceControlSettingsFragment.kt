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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackend
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackendSelector
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.CanaryFlashBackend
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.ModelSpec
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
import javax.inject.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class VoiceControlSettingsFragment : BaseFragment() {

    @Inject lateinit var settings: Settings

    @Inject lateinit var modelManager: ModelManager

    @Inject lateinit var wakeWordDetector: Provider<WakeWordDetector>

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
            // The row must name the backend the product actually selects for this locale.
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
            // Recomputed on resume so a backend that finishes installing shows as ready.
            var readinessRevision by remember { mutableIntStateOf(0) }
            DisposableEffect(this) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) readinessRevision++
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            val recognitionRows = remember(selectedBackend, modelState, readinessRevision) {
                recognitionRowsFor(
                    selectedBackend = selectedBackend,
                    isModelReady = modelManager::isModelReady,
                    downloadState = modelState,
                )
            }
            // The wake word is a different component from the recognition backend: its model is
            // bundled with the app and its readiness is the detector's own load result. Reading the
            // recognition model's absence as wake readiness contradicted the status row directly
            // above it (owner-observed: "Not downloaded" next to "Active (wake word required)").
            // Defer detector construction and native model loading to IO.
            // Until loading completes, readiness remains unconfirmed.
            var detectorReady by remember { mutableStateOf(false) }
            var detectorRevision by remember { mutableIntStateOf(0) }
            LaunchedEffect(detectorRevision) {
                detectorReady = readWakeWordReadiness(wakeWordDetector)
            }
            // Recomputed on resume so a detector that initialized after first composition is shown
            // as it actually is, rather than as it looked once.
            DisposableEffect(this) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) detectorRevision++
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
                recognitionRows = recognitionRows,
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
    recognitionRows: RecognitionRows,
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
                    secondaryText = recognitionRowSecondaryText(recognitionRows),
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
            recognitionRows = RecognitionRows(
                recognitionText = "SenseVoice ready",
                recognitionProgressText = "",
            ),
            wakeWordReady = true,
            statusText = "Active (wake word required)",
            onEnabledChange = {},
            onBackPress = {},
            bottomInset = 0.dp,
        )
    }
}

/** Each row names the component it measures. */
internal const val WAKE_WORD_MODEL_LABEL = "Wake Word Model"
internal const val RECOGNITION_MODEL_LABEL = "Speech Recognition Model"

/**
 * The transcription backends the product selects between, mirroring `AsrBackendSelector`.
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

/** The rows the recognition section renders. */
internal data class RecognitionRows(
    val recognitionText: String,
    val recognitionProgressText: String,
)

internal fun recognitionRowSecondaryText(rows: RecognitionRows): String = rows.recognitionProgressText.ifEmpty { rows.recognitionText }

// Resolve the provider inside IO: eager injection would load native models on the main thread.
internal suspend fun readWakeWordReadiness(detector: Provider<WakeWordDetector>): Boolean = withContext(Dispatchers.IO) { detector.get().isReady }

/**
 * The fragment's own row construction: maps the selected `AsrBackend` to the row enum and hands its
 * `requiredModel` to the resolver. Production calls exactly this, so a control over it governs the
 * fragment's real input rather than a parallel projection.
 */
internal fun recognitionRowsFor(
    selectedBackend: AsrBackend?,
    isModelReady: (ModelSpec) -> Boolean,
    downloadState: ModelDownloadState,
): RecognitionRows {
    val row = when {
        selectedBackend == null -> null

        selectedBackend is CanaryFlashBackend -> RecognitionBackend.CanaryFlash

        // SenseVoice is the product default; any other selectable backend it presents as itself.
        selectedBackend is SenseVoiceBackend -> RecognitionBackend.SenseVoice

        else -> RecognitionBackend.SenseVoice
    }
    return resolveRecognitionRows(
        selectedBackend = row,
        requiredModel = selectedBackend?.requiredModel,
        isModelReady = isModelReady,
        downloadState = downloadState,
    )
}

/**
 * Resolves the recognition rows by querying the SELECTED backend's own required model through the
 * file-readiness provider.
 *
 * Reviewer finding 16d8af35: the earlier version of this function received an already-computed
 * `backendFilesPresent` Boolean and had no production caller, so its tests could not establish
 * which spec production actually queried. This one takes the spec the backend requires and the
 * readiness provider, so a test can assert the queried spec, and production calls it.
 */
internal fun resolveRecognitionRows(
    selectedBackend: RecognitionBackend?,
    requiredModel: ModelSpec?,
    isModelReady: (ModelSpec) -> Boolean,
    downloadState: ModelDownloadState,
): RecognitionRows {
    val backendFilesPresent = requiredModel?.let(isModelReady) ?: false
    val progressText = when (downloadState) {
        is ModelDownloadState.Downloading -> "Downloading: ${downloadState.progressPercent}%"
        is ModelDownloadState.Failed -> "Download failed"
        else -> ""
    }
    return RecognitionRows(
        recognitionText = recognitionModelText(
            backend = selectedBackend,
            backendReady = backendFilesPresent,
        ),
        recognitionProgressText = progressText,
    )
}

/**
 * The recognition row reports the SELECTED backend and that backend's readiness.
 *
 * Reviewer finding cec2f3be: this row previously reported a different component's download state
 * under a generic label, so on a device whose selected backend was SenseVoice it announced that
 * speech recognition was unavailable when only an unselected model was missing. A `null` backend
 * means the OS locale is outside the product language set -- say that, rather than blame a model
 * that was never selected.
 */
internal fun recognitionModelText(
    backend: RecognitionBackend?,
    backendReady: Boolean,
): String {
    if (backend == null) return "Not available for this language"
    return if (backendReady) "${backend.displayName} ready" else "${backend.displayName} model missing"
}
