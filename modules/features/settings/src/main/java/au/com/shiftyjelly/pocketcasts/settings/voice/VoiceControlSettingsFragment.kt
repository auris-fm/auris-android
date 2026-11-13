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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.ui.VoiceSharingSettingsUiState
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.ui.VoiceSharingSettingsViewModel
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordDetector
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import au.com.shiftyjelly.pocketcasts.localization.R as LR

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
            // VoiceControlService uses. The shared downloadState flow reports Ready only
            // once the whole model set (LFM router included) is on disk.
            // Recomputed on resume so a backend that finishes installing shows as ready.
            var readinessRevision by remember { mutableIntStateOf(0) }
            DisposableEffect(this) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) readinessRevision++
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            // Formats resolved in composition: the resolver is a pure function, so the strings it
            // emits come from resources at the call site (same shape as wakeWordModelText).
            val downloadProgressTemplate = stringResource(LR.string.voice_control_model_downloading)
            val downloadFailedText = stringResource(LR.string.voice_control_model_download_failed)
            val backendReadyTemplate = stringResource(LR.string.voice_control_backend_ready)
            val backendMissingTemplate = stringResource(LR.string.voice_control_backend_model_missing)
            val notForLanguageText = stringResource(LR.string.voice_control_model_not_for_language)
            val recognitionRows = remember(selectedBackend, modelState, readinessRevision) {
                recognitionRowsFor(
                    selectedBackend = selectedBackend,
                    isModelReady = modelManager::isModelReady,
                    downloadState = modelState,
                    downloadingTemplate = { percent -> downloadProgressTemplate.format(percent) },
                    downloadFailed = downloadFailedText,
                    readyTemplate = { name -> backendReadyTemplate.format(name) },
                    missingTemplate = { name -> backendMissingTemplate.format(name) },
                    notForLanguage = notForLanguageText,
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

            // ONE SHARING SURFACE (spec directive): the reachable Settings section binds to the
            // reviewed ViewModel — durable presentation, tap-time session capture, fenced outcome
            // effects and the local-stop-first withdrawal all live there now.
            val sharingState by voiceSharingSettings.state.collectAsStateWithLifecycle()
            // The section only renders when it can be truthful: policy available AND a real state
            // observed — a server projection OR the durable choice itself (ONE LOCAL TRUTH: the
            // switch must render from the durable record even before any projection has landed).
            val sharing = if (sharingState.settingAvailable && (sharingState.projectionObserved || sharingState.durableChoiceKnown)) sharingState else null
            // The destructive confirmation is OWNED BY THE VIEWMODEL and bound to the session that
            // opened it (reviewer 20b681f0): confirming after an account switch is rejected, never
            // silently re-owned by the new account.
            if (sharingState.deleteConfirmationOpen) {
                AlertDialog(
                    onDismissRequest = { voiceSharingSettings.dismissDelete() },
                    title = { Text(stringResource(LR.string.voice_sharing_delete_confirm_title)) },
                    text = { Text(stringResource(LR.string.voice_sharing_delete_confirm_body)) },
                    confirmButton = {
                        TextButton(onClick = { voiceSharingSettings.confirmDelete() }) {
                            Text(stringResource(LR.string.voice_sharing_delete_action))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { voiceSharingSettings.dismissDelete() }) {
                            Text(stringResource(LR.string.voice_sharing_delete_dismiss))
                        }
                    },
                )
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
                sharing = sharing,
                onSharingChange = { checked ->
                    // The ViewModel owns the ordering: withdraw records the LOCAL stop at the tap
                    // (before any request) and enrolls release the stop only inside the fence.
                    if (checked) voiceSharingSettings.enroll() else voiceSharingSettings.withdraw()
                },
                onDeleteSharedData = { voiceSharingSettings.requestDelete() },
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
        LR.string.voice_sharing_delete_pending to stringResource(LR.string.voice_sharing_delete_pending),
        LR.string.voice_sharing_delete_failed to stringResource(LR.string.voice_sharing_delete_failed),
    )
    val copyResolver: (Int) -> String = { rowStrings[it].orEmpty() }
    Column(modifier = Modifier.padding(bottom = bottomInset)) {
        ThemedTopAppBar(
            title = stringResource(LR.string.voice_control_title),
            onNavigationClick = { onBackPress() },
        )
        LazyColumn(contentPadding = PaddingValues(vertical = 16.dp)) {
            item {
                SettingRow(
                    primaryText = stringResource(LR.string.voice_control_enable_primary),
                    secondaryText = stringResource(LR.string.voice_control_enable_secondary),
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
                    primaryText = stringResource(WAKE_WORD_MODEL_LABEL),
                    secondaryText = wakeWordModelText(
                        detectorReady = wakeWordReady,
                        ready = stringResource(LR.string.voice_control_model_ready),
                        unavailable = stringResource(LR.string.voice_control_model_unavailable),
                    ),
                    toggle = SettingRowToggle.None,
                    indent = false,
                )
            }
            item {
                SettingRow(
                    primaryText = stringResource(RECOGNITION_MODEL_LABEL),
                    secondaryText = recognitionRowSecondaryText(recognitionRows),
                    toggle = SettingRowToggle.None,
                    indent = false,
                )
            }
            item {
                SettingRow(
                    primaryText = stringResource(LR.string.voice_control_model_status_label),
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
                // Deletion completes synchronously on 204.
                if (sharing.deleteAvailable) {
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
            recognitionBackend = RecognitionBackend.SenseVoice,
            recognitionRows = RecognitionRows(
                recognitionText = "SenseVoice ready",
                recognitionProgressText = "",
            ),
            wakeWordReady = true,
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

/** Each row names the component it measures. */
internal val WAKE_WORD_MODEL_LABEL = LR.string.voice_control_wake_word_model_label
internal val RECOGNITION_MODEL_LABEL = LR.string.voice_control_recognition_model_label

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
internal fun wakeWordModelText(detectorReady: Boolean, ready: String, unavailable: String): String = if (detectorReady) ready else unavailable

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
    downloadingTemplate: (Int) -> String,
    downloadFailed: String,
    readyTemplate: (String) -> String,
    missingTemplate: (String) -> String,
    notForLanguage: String,
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
        downloadingTemplate = downloadingTemplate,
        downloadFailed = downloadFailed,
        readyTemplate = readyTemplate,
        missingTemplate = missingTemplate,
        notForLanguage = notForLanguage,
    )
}

/**
 * Resolves the recognition rows by querying the SELECTED backend's own required model through the
 * file-readiness provider. The progress line's and the readiness line's texts are supplied by the
 * caller, which resolves them from string resources; this function owns only the mapping, so a
 * unit test asserts the mapping without a resource context.
 */
internal fun resolveRecognitionRows(
    selectedBackend: RecognitionBackend?,
    requiredModel: ModelSpec?,
    isModelReady: (ModelSpec) -> Boolean,
    downloadState: ModelDownloadState,
    downloadingTemplate: (Int) -> String,
    downloadFailed: String,
    readyTemplate: (String) -> String,
    missingTemplate: (String) -> String,
    notForLanguage: String,
): RecognitionRows {
    val backendFilesPresent = requiredModel?.let(isModelReady) ?: false
    val progressText = when (downloadState) {
        is ModelDownloadState.Downloading -> downloadingTemplate(downloadState.progressPercent)
        is ModelDownloadState.Failed -> downloadFailed
        else -> ""
    }
    return RecognitionRows(
        recognitionText = recognitionModelText(
            backend = selectedBackend,
            backendReady = backendFilesPresent,
            readyTemplate = readyTemplate,
            missingTemplate = missingTemplate,
            notForLanguage = notForLanguage,
        ),
        recognitionProgressText = progressText,
    )
}

/**
 * The recognition row reports the SELECTED backend and that backend's readiness.
 *
 * This row previously reported a different component's download state
 * under a generic label, so on a device whose selected backend was SenseVoice it announced that
 * speech recognition was unavailable when only an unselected model was missing. A `null` backend
 * means the OS locale is outside the product language set -- say that, rather than blame a model
 * that was never selected.
 */
internal fun recognitionModelText(
    backend: RecognitionBackend?,
    backendReady: Boolean,
    readyTemplate: (String) -> String,
    missingTemplate: (String) -> String,
    notForLanguage: String,
): String {
    if (backend == null) return notForLanguage
    return if (backendReady) readyTemplate(backend.displayName) else missingTemplate(backend.displayName)
}
