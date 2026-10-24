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
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.DeletionState
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentController
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.LocalWithdrawalIntentStore
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.ContributionStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import au.com.shiftyjelly.pocketcasts.localization.R as LR

@AndroidEntryPoint
class VoiceControlSettingsFragment : BaseFragment() {

    /**
     * Set when the stop is known to this session but could NOT be made durable. The screen already
     * shows the user as stopped; this records that the on-disk marker did not land, so nothing
     * downstream can read the absence of a marker as permission.
     */
    private var locallyStopped = false

    @Inject lateinit var settings: Settings

    @Inject lateinit var modelManager: ModelManager

    @Inject lateinit var gate: VoiceControlGate

    @Inject lateinit var audioRouteMonitor: AudioRouteMonitor

    @Inject lateinit var listeningModePolicy: ListeningModePolicy

    @Inject lateinit var captureStateHolder: VoiceControlCaptureStateHolder

    @Inject lateinit var voiceSharingAccountStartup: VoiceSharingAccountStartup

    @Inject lateinit var contributionStore: ContributionStore

    @Inject lateinit var localWithdrawalIntent: LocalWithdrawalIntentStore

    private fun Context.isRecordAudioGranted() = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * The ONE place an enrollment outcome may mutate the durable stop.
     *
     * Runs through [EnrollmentController.applyForOutcome], NOT a fragment-owned lock (reviewer
     * 60647af3, a65eef2f). A caller-side lock cannot make check-then-act indivisible against the
     * fence's own mutations: `issue`/`supersede` do not acquire it, so an owner or action change could
     * land between "is this still authoritative?" and "do it". The decision and the effect now run
     * inside the fence's critical section, the same one its mutations take.
     *
     * A refused outcome runs NOTHING - not even the conservative re-record. A stop written on behalf
     * of a superseded or foreign action is indistinguishable from a real one, and would silently
     * re-stop sharing after the user turned it on.
     */
    private fun applyStopEffect(
        checked: Boolean,
        outcome: EnrollmentOutcome,
        controller: EnrollmentController,
        intent: LocalWithdrawalIntentStore,
        onReleased: () -> Unit,
    ) {
        controller.applyForOutcome(outcome) {
            when {
                // A fresh explicit opt-in, applied, releases the local stop. Only an APPLIED
                // enrollment releases: a failed one is not consent to resume collection.
                checked && outcome is EnrollmentOutcome.Applied -> {
                    intent.clearOnUserEnrollment()
                    onReleased()
                }

                // Withdrawal applied: the stop is KEPT (a stale enrolled projection must not resume
                // collection), so this restates the request rather than clearing anything.
                !checked && outcome is EnrollmentOutcome.Applied -> intent.onServerAgrees()

                // A failed withdrawal leaves the stop in place: a network failure is not consent.
                !checked -> intent.onWithdrawalFailed()
            }
        }
    }

    private fun newEnrollmentController() = voiceSharingAccountStartup.enrollmentController {
        // Use the store's single ownership transaction, not a bare file sweep: a withdrawal must
        // both withdraw in-flight launches and delete everything this owner holds, under the same
        // lock every other revocation uses.
        contributionStore.revokeAndPurge("enrollment-withdrawn")
    }

    /**
     * The disclosure version the user is accepting. Sourced from the disclosure this UI actually
     * showed, NOT from server state: the server validates it against its supported set, so sending
     * a version we did not render would be a false consent record.
     */
    private fun localDisclosureVersion(): String = "2026-10-01"

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

            val projection by voiceSharingAccountStartup.state.projection.collectAsStateWithLifecycle()
            // The controller is created per screen visit: it holds only in-flight operation state,
            // and a stale Pending surviving into a new visit would misreport an operation nobody is
            // waiting on any more.
            val enrollmentController = remember { newEnrollmentController() }
            val enrollmentState by enrollmentController.state.collectAsStateWithLifecycle()
            var deletionState by remember { mutableStateOf<DeletionState?>(null) }
            var deletionFailed by remember { mutableStateOf(false) }
            // LOCAL intent, not derived from the server: once the user turns sharing off we stop
            // sending immediately, so the toggle must not wait for a round trip to move.
            //
            // INITIALIZATION must consult the DURABLE stop, not just the server projection. A user who
            // turned sharing off and then relaunched would otherwise be shown "sharing on" by a stale
            // enrolled projection while collection was in fact stopped — the screen disagreeing with
            // the gate about the one thing the user cares about. The local stop wins here for the same
            // reason it wins in CollectionAuthority: the user's decision, not server state.
            var locallySharing by remember(projection?.membershipRevision) {
                mutableStateOf(
                    projection?.voiceSharingEnrolled == true &&
                        localWithdrawalIntent.collectionPermittedByLocalIntent(projection),
                )
            }
            val scope = rememberCoroutineScope()
            val sharingCopy = VoiceSharingCopy(
                supporting = stringResource(LR.string.voice_sharing_disclosure_supporting),
                detail = stringResource(LR.string.voice_sharing_disclosure_detail),
                benefits = stringResource(LR.string.voice_sharing_benefits),
                withdrawPending = stringResource(LR.string.voice_sharing_withdraw_pending),
                withdrawFailed = stringResource(LR.string.voice_sharing_withdraw_failed),
                conflict = stringResource(LR.string.voice_sharing_conflict),
                deleteAction = stringResource(LR.string.voice_sharing_delete_action),
                deletePending = stringResource(LR.string.voice_sharing_delete_pending),
                deleteFailed = stringResource(LR.string.voice_sharing_delete_failed),
            )
            val sharing = VoiceSharingPresentation.forRow(
                projection = projection,
                locallySharing = locallySharing,
                enrollmentState = enrollmentState,
                deletionState = deletionState,
                deletionFailed = deletionFailed,
                copy = sharingCopy,
            )
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
                                scope.launch {
                                    val outcome = enrollmentController.deleteSharedData()
                                    deletionState = (outcome as? EnrollmentOutcome.DeletionRequested)?.state
                                    deletionFailed = outcome is EnrollmentOutcome.Failed
                                }
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
                sharingCopy = sharingCopy,
                onSharingChange = { checked ->
                    // Turning OFF takes effect locally FIRST and unconditionally: the controller
                    // clears everything this device would have sent BEFORE it awaits the server, so
                    // there is no window in which we keep collecting after the user said stop. No
                    // confirmation dialog here, for exactly that reason.
                    if (!checked) locallySharing = false
                    // THE USER'S STOP IS RECORDED HERE, not inside the request. This is the only
                    // place the actual tap reaches durable state, so without it the collection gate
                    // consults a flag nobody ever sets. Written BEFORE the coroutine launches, so no
                    // scheduling window exists in which collection could still admit a capture.
                    // A stop that could not be DURABLY written is still a stop for this session: the
                    // in-memory switch below already refuses to collect, and the failed marker is
                    // reported rather than discarded. Treating the failure as "carry on" would resume
                    // collection for someone who asked us to stop.
                    if (!checked && !localWithdrawalIntent.recordWithdrawalRequested()) locallyStopped = true
                    scope.launch {
                        val outcome = if (checked) {
                            enrollmentController.enroll(acceptedDisclosureVersion = localDisclosureVersion())
                        } else {
                            enrollmentController.withdraw()
                        }
                        // SUPERSEDED RESULTS ARE IGNORED ENTIRELY (reviewer 668882eb, fd258849).
                        // A result the user has already moved past describes an action that no longer
                        // exists: the catchall below used to treat it as a failure and re-record the
                        // stop, so a delayed withdrawal superseded by a SUCCESSFUL new enrollment put
                        // the stop back after the opt-in had cleared it. It can also belong to a
                        // different account, and the durable stop is global.
                        if (outcome is EnrollmentOutcome.Superseded) return@launch
                        // EVERY outcome-driven side effect is fenced, not only the release
                        // (reviewer a65eef2f). The stop-recording branches had no check at all, and
                        // an outcome can go stale between the controller returning and this code
                        // running just as easily for a withdrawal as for an enrollment. The durable
                        // stop is GLOBAL, so an unfenced branch can mutate another account's state or
                        // overwrite a newer decision.
                        //
                        // The check and the mutation run under ONE lock (see applyStopEffect) so a
                        // competing owner/action change cannot interleave between them: checking and
                        // then mutating unlocked would leave the same window one level down.
                        applyStopEffect(
                            checked = checked,
                            outcome = outcome,
                            controller = enrollmentController,
                            intent = localWithdrawalIntent,
                            onReleased = { locallySharing = true },
                        )
                    }
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
    sharing: VoiceSharingRowState?,
    sharingCopy: VoiceSharingCopy?,
    onEnabledChange: (Boolean) -> Unit,
    onSharingChange: (Boolean) -> Unit,
    onDeleteSharedData: () -> Unit,
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
            // SHARING SURFACE. Only rendered once account state has been observed: without a
            // projection we cannot show a truthful switch, and offering a control we cannot honor
            // would be worse than showing nothing.
            if (sharing != null && sharingCopy != null) {
                item {
                    SettingRow(
                        primaryText = stringResource(LR.string.voice_sharing_title),
                        secondaryText = sharingCopy.detail,
                        toggle = SettingRowToggle.None,
                        indent = false,
                    )
                }
                item {
                    SettingRow(
                        primaryText = stringResource(LR.string.voice_sharing_setting_share_recordings),
                        secondaryText = sharing.secondary,
                        toggle = if (sharing.switchAvailable) {
                            SettingRowToggle.Switch(checked = sharing.switchEnabled)
                        } else {
                            SettingRowToggle.None
                        },
                        modifier = if (sharing.switchAvailable) {
                            Modifier.toggleable(
                                value = sharing.switchEnabled,
                                role = Role.Switch,
                            ) { onSharingChange(it) }
                        } else {
                            Modifier
                        },
                        indent = false,
                    )
                }
                item {
                    SettingRow(
                        primaryText = "",
                        secondaryText = sharingCopy.benefits,
                        toggle = SettingRowToggle.None,
                        indent = false,
                    )
                }
                if (sharing.showDeleteAction) {
                    item {
                        SettingRow(
                            primaryText = stringResource(LR.string.voice_sharing_delete_action),
                            // A pending or failed deletion is surfaced here with the SAME honesty
                            // rule as the switch: 202 is "still processing", never "deleted".
                            secondaryText = sharing.deleteSecondary,
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
            sharingCopy = null,
            onEnabledChange = {},
            onSharingChange = {},
            onDeleteSharedData = {},
            onBackPress = {},
            bottomInset = 0.dp,
        )
    }
}
