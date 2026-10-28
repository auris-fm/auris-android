package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.ui

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.WithdrawalState
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentController
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentDisclosure
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentState
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.LocalWithdrawalIntentStore
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.PolicySnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The settings shell's UI state. Its truth rules (plan Task 7 UI bullet):
 *
 *  - [VoiceSharingSettingsUiState.enrolled] is the SERVER projection read through the LOCAL
 *    withdrawal intent — never a tap, and never a local queue change dressed up as server
 *    completion. A withdrawal tap stops collection immediately (the user decided), which makes
 *    `enrolled` false BEFORE the server answers, and it stays false if the withdrawal fails.
 *  - [VoiceSharingSettingsUiState.pending] is true exactly while a membership mutation is in
 *    flight. Pending is never rendered as enrolled.
 *  - [VoiceSharingSettingsUiState.status] surfaces conflict and failure HONESTLY and is cleared
 *    only by the next outcome.
 *  - The disclosure copy is the exact build constants whose version the client accepts — the UI
 *    cannot render wording the consent version does not name.
 */
data class VoiceSharingSettingsUiState(
    /** False while the policy is disabled: the setting must hide, even for an enrolled account. */
    val settingAvailable: Boolean,
    /** Server projection through the local intent. A tap alone never flips this. */
    val enrolled: Boolean,
    /** A membership mutation is in flight; the server has not confirmed anything. */
    val pending: Boolean,
    /** The last settled outcome, honest: conflicts and failures stay visible until the next op. */
    val status: SettingsOperationStatus,
    /** WHICH operation failed, so the status line can say what is (and is not) true now. */
    val failedOperation: FailedOperation = FailedOperation.NONE,
    /** WHICH operation is in flight; the pending copy names it, it is not hard-coded withdrawal. */
    val pendingOperation: PendingOperation = PendingOperation.NONE,
    val disclosureVersion: String,
    val disclosureTitle: String,
    val disclosureSupporting: String,
    val disclosureDetail: String,
)

enum class SettingsOperationStatus { NONE, CONFLICT, FAILED }

/** The operation a visible failure refers to; the copy differs per operation's honest state. */
enum class FailedOperation { NONE, ENROLL, WITHDRAW }

/** The operation currently in flight, so pending copy names what is actually pending. */
enum class PendingOperation { NONE, ENROLL, WITHDRAW }

/**
 * Composes the LIVE policy snapshot, the account projection and the local withdrawal intent into
 * the settings shell's state, and drives the [EnrollmentController] the same way the production
 * Settings row does: a withdrawal records the LOCAL intent FIRST — before any request — so
 * collection stops while the server has not answered yet (reviewer-verified boundary).
 */
class VoiceSharingSettingsViewModel(
    private val policy: StateFlow<PolicySnapshot>,
    private val projection: StateFlow<VoiceSharingAccountProjection?>,
    private val enrollmentController: EnrollmentController,
    private val withdrawalIntent: LocalWithdrawalIntentStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {

    // DURABLE UI STATE, not transient render inputs (reviewer ae7922f9): the combine collector
    // re-renders on every policy/projection emission, and a default parameter there silently
    // erased the failure copy between an action and the next outcome. The failure identity lasts
    // until the NEXT action; the pending label lasts until the outcome settles.
    private var lastFailedOperation = FailedOperation.NONE
    private var pendingOperation = PendingOperation.NONE

    // Declaration order matters: these initializers run render(), so the durable fields above
    // must already be initialized (reviewer-turned-self-inflicted NPE: Kotlin initializes in
    // declaration order, and a null field under a non-null type fails the intrinsic check).
    private val mutableState = MutableStateFlow(render())
    val state: StateFlow<VoiceSharingSettingsUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            kotlinx.coroutines.flow.combine(policy, projection, enrollmentController.state) { _, _, _ -> }
                .collect { mutableState.value = render() }
        }
    }

    /** Enrolls with the exact disclosure version whose copy this screen renders. */
    fun enroll() {
        // A new action retires the previous failure copy (reviewer ae7922f9: durable until the
        // next action, not wiped by an unrelated emission but not carried into a fresh attempt).
        lastFailedOperation = FailedOperation.NONE
        pendingOperation = PendingOperation.ENROLL
        mutableState.value = render()
        scope.launch {
            consume(enrollmentController.enroll(EnrollmentDisclosure.VERSION), FailedOperation.ENROLL)
        }
    }

    /** Records the local stop FIRST, then withdraws; a failure keeps the stop. */
    fun withdraw() {
        lastFailedOperation = FailedOperation.NONE
        pendingOperation = PendingOperation.WITHDRAW
        withdrawalIntent.recordWithdrawalRequested()
        mutableState.value = render()
        scope.launch {
            consume(enrollmentController.withdraw(), FailedOperation.WITHDRAW)
        }
    }

    private suspend fun consume(outcome: EnrollmentOutcome, operation: FailedOperation) {
        when (outcome) {
            is EnrollmentOutcome.Applied -> {
                // A SUCCESSFUL re-enrollment releases the durable stop, atomically with the
                // outcome: the clear runs inside the fence/owner transaction via applyForOutcome,
                // so a superseded or cross-account outcome releases nothing (reviewer ae7922f9).
                // A failed, refused or superseded enrollment keeps the stop. A withdrawal's
                // Applied deliberately does NOT clear: opting out must stay opted out.
                if (operation == FailedOperation.ENROLL) {
                    enrollmentController.applyForOutcome(outcome) {
                        withdrawalIntent.clearOnUserEnrollment()
                    }
                }
                pendingOperation = PendingOperation.NONE
                mutableState.value = render(SettingsOperationStatus.NONE)
            }

            is EnrollmentOutcome.Conflict -> {
                pendingOperation = PendingOperation.NONE
                mutableState.value = render(SettingsOperationStatus.CONFLICT)
            }

            is EnrollmentOutcome.Failed -> {
                lastFailedOperation = operation
                pendingOperation = PendingOperation.NONE
                mutableState.value = render(SettingsOperationStatus.FAILED)
            }

            is EnrollmentOutcome.DeletionRequested -> {
                pendingOperation = PendingOperation.NONE
                mutableState.value = render(SettingsOperationStatus.NONE)
            }

            is EnrollmentOutcome.Refused -> {
                lastFailedOperation = operation
                pendingOperation = PendingOperation.NONE
                mutableState.value = render(SettingsOperationStatus.FAILED)
            }

            EnrollmentOutcome.Superseded -> {
                // Nothing settled; the superseding action's own rendering takes over.
                pendingOperation = PendingOperation.NONE
                mutableState.value = render()
            }
        }
    }

    private fun render(status: SettingsOperationStatus = currentStatus()) = VoiceSharingSettingsUiState(
        settingAvailable = policy.value.enabled,
        enrolled = isEnrolled(),
        pending = enrollmentController.state.value == EnrollmentState.Pending,
        status = status,
        failedOperation = lastFailedOperation,
        pendingOperation = pendingOperation,
        disclosureVersion = EnrollmentDisclosure.VERSION,
        disclosureTitle = EnrollmentDisclosure.TITLE,
        disclosureSupporting = EnrollmentDisclosure.SUPPORTING,
        disclosureDetail = EnrollmentDisclosure.DETAIL,
    )

    /**
     * The server projection read through the local intent: enrolled requires a projection that
     * says enrolled AND no recorded withdrawal intent. The intent store's own collection gate
     * already encodes the fail-closed combination; the UI reads the same rule so a failed
     * withdrawal cannot render as enrolled.
     */
    private fun isEnrolled(): Boolean {
        val p = projection.value ?: return false
        return p.voiceSharingEnrolled &&
            p.withdrawalState == WithdrawalState.ACTIVE &&
            withdrawalIntent.collectionPermittedByLocalIntent(p)
    }

    private fun currentStatus(): SettingsOperationStatus = when (enrollmentController.state.value) {
        EnrollmentState.Conflict -> SettingsOperationStatus.CONFLICT
        EnrollmentState.Failed -> SettingsOperationStatus.FAILED
        else -> SettingsOperationStatus.NONE
    }
}
