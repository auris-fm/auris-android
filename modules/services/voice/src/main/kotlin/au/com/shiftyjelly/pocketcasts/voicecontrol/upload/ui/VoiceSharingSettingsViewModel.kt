package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.ui

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingTaggedProjection
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
    /**
     * True only when the CURRENT session's projection has been observed. A projection retained
     * from another account (the drop of an obsolete-account response is asynchronous) is not the
     * current account's state and must not render (reviewer 578bb62a).
     */
    val projectionObserved: Boolean,
    /**
     * Deletion of already-accepted data, in THREE honest states (reviewer 578bb62a): the tap is
     * the REQUEST in flight; a 202 receipt is ACCEPTED-BUT-PROCESSING; neither ever means
     * deleted, and a failure is visible until the next action.
     */
    val deletionRequested: Boolean = false,
    /**
     * Historical-data eligibility, carried separately from [enrolled] (reviewer 20b681f0): a
     * withdrawal clears membership, not the user's right to delete data already accepted.
     */
    val deleteAvailable: Boolean = false,
    /** The destructive-confirmation dialog; bound to the session that opened it. */
    val deleteConfirmationOpen: Boolean = false,
    val deletionReceiptPending: Boolean = false,
    val deletionFailed: Boolean = false,
    val disclosureVersion: String,
    val disclosureTitle: String,
    val disclosureSupporting: String,
    val disclosureDetail: String,
)

enum class SettingsOperationStatus { NONE, CONFLICT, FAILED, RELEASE_FAILED }

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
    /**
     * The projection WITH its authoritative owner session (reviewer 20b681f0): the tag is
     * published under the account fence, never inferred from collection time.
     */
    private val taggedProjection: StateFlow<VoiceSharingTaggedProjection>,
    private val enrollmentController: EnrollmentController,
    private val withdrawalIntent: LocalWithdrawalIntentStore,
    /**
     * THE shared owner-session source (the same authority snapshot the controller fences with).
     * The ViewModel is a process-wide singleton, so operation PRESENTATION must retire at the
     * account boundary too: account B must never see account A's failure copy (reviewer a2136dd9).
     */
    private val ownerSession: () -> Long,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {

    // DURABLE UI STATE, not transient render inputs (reviewer ae7922f9): the combine collector
    // re-renders on every policy/projection emission, and a default parameter there silently
    // erased the failure copy between an action and the next outcome. The failure identity lasts
    // until the NEXT action; the pending label lasts until the outcome settles.
    private var lastFailedOperation = FailedOperation.NONE
    private var pendingOperation = PendingOperation.NONE

    /**
     * The session whose presentation is currently on screen. The session-change retire clears
     * only presentation belonging to a DIFFERENT (dead) session - a fresh tap under the new
     * session that landed before this collector processed the emission must survive (reviewer
     * b7ad7c92, second interleaving).
     */
    private var presentedSession: Long = ownerSession()

    /** Identifies the CURRENT action; a stale (superseded) outcome may not touch presentation. */
    private var actionGeneration = 0L

    // Deletion presentation: durable like the membership presentation, retired at the same
    // owner-session boundary, with its OWN completion fence (reviewer 578bb62a).
    private var deletionRequested = false
    private var deletionReceiptPending = false
    private var deletionFailed = false
    private var deletionGeneration = 0L

    /**
     * The open delete confirmation and the session that opened it: confirming under a different
     * authority would capture THAT session as the tap owner (reviewer 20b681f0).
     */
    private var deleteConfirmationOpen = false
    private var confirmationSession = 0L

    /**
     * THE status the screen shows, as durable UI state. Deriving it from the controller state on
     * every render was the bug: the controller keeps the LAST operation's settled state (Failed,
     * Applied), so a session-boundary retire or a local-release failure was immediately
     * overwritten by the next combine-driven re-render (reviewer a2136dd9).
     */
    private var presentationStatus = SettingsOperationStatus.NONE

    // Declaration order matters: these initializers run render(), so the durable fields above
    // must already be initialized (reviewer-turned-self-inflicted NPE: Kotlin initializes in
    // declaration order, and a null field under a non-null type fails the intrinsic check).
    private val mutableState = MutableStateFlow(render())
    val state: StateFlow<VoiceSharingSettingsUiState> = mutableState.asStateFlow()

    init {
        // The projection's observed session updates ONLY when the projection flow itself emits:
        // an unrelated emission (policy flip, controller state) under the new session must not
        // re-label a retained foreign-session projection as current (reviewer 578bb62a).
        scope.launch {
            taggedProjection.collect {
                retireForeignSessionPresentation()
                mutableState.value = render()
            }
        }
        scope.launch {
            kotlinx.coroutines.flow.combine(policy, enrollmentController.state) { _, _ -> }
                .collect {
                    retireForeignSessionPresentation()
                    mutableState.value = render()
                }
        }
    }

    /**
     * The account boundary retires operation presentation (reviewer a2136dd9): an account switch
     * emits through the flows, so checking the shared session here catches it. An unrelated
     * refresh within the SAME session leaves the failure copy untouched.
     */
    private fun retireForeignSessionPresentation() {
        val session = ownerSession()
        if (session == presentedSession) return
        presentedSession = session
        lastFailedOperation = FailedOperation.NONE
        pendingOperation = PendingOperation.NONE
        presentationStatus = SettingsOperationStatus.NONE
        deletionRequested = false
        deletionReceiptPending = false
        deletionFailed = false
        deleteConfirmationOpen = false
        confirmationSession = 0L
    }

    /** Enrolls with the exact disclosure version whose copy this screen renders. */
    fun enroll() {
        // A new action retires the previous failure copy (reviewer ae7922f9: durable until the
        // next action, not wiped by an unrelated emission but not carried into a fresh attempt).
        lastFailedOperation = FailedOperation.NONE
        pendingOperation = PendingOperation.ENROLL
        presentationStatus = SettingsOperationStatus.NONE
        val generation = ++actionGeneration
        // THE SESSION IS CAPTURED AT THE TAP, not when the queued coroutine starts (reviewer
        // 66ae7752): a tap issued under a session that changed before the dispatcher ran it must
        // never reach the new account's controller.
        val tappedSession = ownerSession()
        presentedSession = tappedSession
        mutableState.value = render()
        scope.launch {
            if (ownerSession() != tappedSession) {
                retireTapPresentation(generation)
                return@launch
            }
            consume(enrollmentController.enroll(EnrollmentDisclosure.VERSION), FailedOperation.ENROLL, generation)
        }
    }

    /** Opens the destructive-confirmation dialog, bound to the CURRENT session. */
    fun requestDelete() {
        // ACTION BOUNDARY: adopt the session FIRST, so a controller emission triggered by the
        // queued action cannot retire the new session's own state (reviewer 278e595f).
        presentedSession = ownerSession()
        deleteConfirmationOpen = true
        confirmationSession = ownerSession()
        mutableState.value = render()
    }

    fun dismissDelete() {
        deleteConfirmationOpen = false
        confirmationSession = 0L
        mutableState.value = render()
    }

    /**
     * Confirming runs the deletion ONLY under the authority that opened the dialog: a
     * confirmation opened under A and confirmed after a switch to B is REJECTED, not silently
     * re-owned by B (reviewer 20b681f0).
     */
    fun confirmDelete() {
        if (!deleteConfirmationOpen) return
        if (ownerSession() != confirmationSession) {
            dismissDelete()
            return
        }
        deleteConfirmationOpen = false
        confirmationSession = 0L
        startDeletion()
    }

    private fun startDeletion() {
        presentedSession = ownerSession()
        deletionFailed = false
        deletionReceiptPending = false
        deletionRequested = true
        val generation = ++deletionGeneration
        val tappedSession = ownerSession()
        mutableState.value = render()
        scope.launch {
            if (ownerSession() != tappedSession) return@launch
            val outcome = enrollmentController.deleteSharedData()
            // COMPLETION FENCE: the outcome arrives AFTER a suspension, so BOTH the session and
            // the deletion generation must be re-checked here - a deletion whose session changed
            // or that was superseded while parked must not touch the screen (reviewer 578bb62a).
            if (generation != deletionGeneration || ownerSession() != tappedSession) return@launch
            when (outcome) {
                is EnrollmentOutcome.DeletionRequested -> {
                    // 202 = accepted but still processing. Never claimed as deleted.
                    deletionRequested = false
                    deletionReceiptPending = true
                    deletionFailed = false
                }

                is EnrollmentOutcome.Failed, is EnrollmentOutcome.Refused -> {
                    deletionRequested = false
                    deletionReceiptPending = false
                    deletionFailed = true
                }

                else -> {}
            }
            mutableState.value = render()
        }
    }

    /** Records the local stop FIRST, then withdraws; a failure keeps the stop. */
    fun withdraw() {
        lastFailedOperation = FailedOperation.NONE
        pendingOperation = PendingOperation.WITHDRAW
        presentationStatus = SettingsOperationStatus.NONE
        val generation = ++actionGeneration
        // Same tap-time session capture as enroll() (reviewer 66ae7752). The LOCAL stop is still
        // recorded first: stopping collection on the old account is correct the instant of the tap.
        val tappedSession = ownerSession()
        presentedSession = tappedSession
        withdrawalIntent.recordWithdrawalRequested()
        mutableState.value = render()
        scope.launch {
            if (ownerSession() != tappedSession) {
                retireTapPresentation(generation)
                return@launch
            }
            consume(enrollmentController.withdraw(), FailedOperation.WITHDRAW, generation)
        }
    }

    private suspend fun consume(outcome: EnrollmentOutcome, operation: FailedOperation, generation: Long) {
        when (outcome) {
            is EnrollmentOutcome.Applied -> {
                if (operation == FailedOperation.WITHDRAW) {
                    // The server AGREED to the withdrawal: reconcile the recorded request (same
                    // fenced call the reachable Settings section performed before the binding).
                    enrollmentController.applyForOutcome(outcome) {
                        withdrawalIntent.onServerAgrees()
                    }
                }
                // A SUCCESSFUL re-enrollment releases the durable stop, atomically with the
                // outcome: the clear runs inside the fence/owner transaction via applyForOutcome,
                // so a superseded or cross-account outcome releases nothing (reviewer ae7922f9).
                // A withdrawal's Applied deliberately does NOT clear: opting out must stay opted
                // out. The DURABLE effect is attempted even for a stale generation - the fence
                // makes the call a no-op there - but PRESENTATION only the current action may
                // touch: a superseded outcome must not clear the newer action's pending label
                // (reviewer a2136dd9).
                if (operation == FailedOperation.ENROLL) {
                    val released = enrollmentController.applyForOutcome(outcome) {
                        withdrawalIntent.clearOnUserEnrollment()
                    }
                    // false = the effect RAN and durable clearing FAILED: collection stays
                    // stopped while the server says enrolled. That is a distinct state from a
                    // server rejection and is surfaced as one (reviewer a2136dd9).
                    if (released == false && generation == actionGeneration) {
                        // The operation SETTLED (server applied it); only the local release
                        // failed - it must not leave the action pending (reviewer b7ad7c92).
                        pendingOperation = PendingOperation.NONE
                        presentationStatus = SettingsOperationStatus.RELEASE_FAILED
                        mutableState.value = render(SettingsOperationStatus.RELEASE_FAILED)
                        return
                    }
                }
                if (generation != actionGeneration) return
                pendingOperation = PendingOperation.NONE
                presentationStatus = SettingsOperationStatus.NONE
                mutableState.value = render(SettingsOperationStatus.NONE)
            }

            is EnrollmentOutcome.Conflict -> {
                if (generation != actionGeneration) return
                if (operation == FailedOperation.WITHDRAW) {
                    // A CONFLICTED withdrawal did not apply either: the user's stop decision
                    // stands and is latched through the same fenced path as a failure
                    // (reviewer 578bb62a).
                    enrollmentController.applyForOutcome(outcome) {
                        withdrawalIntent.onWithdrawalFailed()
                    }
                }
                pendingOperation = PendingOperation.NONE
                presentationStatus = SettingsOperationStatus.CONFLICT
                mutableState.value = render(SettingsOperationStatus.CONFLICT)
            }

            is EnrollmentOutcome.Failed -> {
                if (generation != actionGeneration) return
                if (operation == FailedOperation.WITHDRAW) {
                    // A FAILED withdrawal latches its failure with the intent store, fenced.
                    enrollmentController.applyForOutcome(outcome) {
                        withdrawalIntent.onWithdrawalFailed()
                    }
                }
                lastFailedOperation = operation
                pendingOperation = PendingOperation.NONE
                presentationStatus = SettingsOperationStatus.FAILED
                mutableState.value = render(SettingsOperationStatus.FAILED)
            }

            is EnrollmentOutcome.DeletionRequested -> {
                if (generation != actionGeneration) return
                pendingOperation = PendingOperation.NONE
                presentationStatus = SettingsOperationStatus.NONE
                mutableState.value = render(SettingsOperationStatus.NONE)
            }

            is EnrollmentOutcome.Refused -> {
                if (generation != actionGeneration) return
                if (operation == FailedOperation.WITHDRAW) {
                    enrollmentController.applyForOutcome(outcome) {
                        withdrawalIntent.onWithdrawalFailed()
                    }
                }
                lastFailedOperation = operation
                pendingOperation = PendingOperation.NONE
                presentationStatus = SettingsOperationStatus.FAILED
                mutableState.value = render(SettingsOperationStatus.FAILED)
            }

            EnrollmentOutcome.Superseded -> {
                // Nothing settled; the superseding action owns rendering. The stale outcome does
                // not touch pending/failure/status AT ALL (reviewer a2136dd9).
            }
        }
    }

    /**
     * A retired (dead-session) tap cleans up its OWN pending presentation without settling — but
     * only if it is still the current action (reviewer b7ad7c92): a stale tap must have NO
     * presentation effects on a newer action's state.
     */
    private fun retireTapPresentation(generation: Long) {
        if (generation != actionGeneration) return
        pendingOperation = PendingOperation.NONE
        presentationStatus = SettingsOperationStatus.NONE
        mutableState.value = render()
    }

    private fun render(status: SettingsOperationStatus = presentationStatus) = VoiceSharingSettingsUiState(
        settingAvailable = policy.value.enabled,
        enrolled = isEnrolled(),
        // Pending is PRESENTATION state, not the shared controller's: the controller keeps the
        // last operation's Pending state even after it settles as Superseded (its own lifecycle
        // owns that), and account B must never inherit account A's spinner (reviewer 66ae7752).
        pending = pendingOperation != PendingOperation.NONE,
        projectionObserved = taggedProjection.value.projection != null &&
            taggedProjection.value.ownerSession == ownerSession(),
        deletionRequested = deletionRequested,
        deletionReceiptPending = deletionReceiptPending,
        deletionFailed = deletionFailed,
        deleteAvailable = isDeleteAvailable(),
        deleteConfirmationOpen = deleteConfirmationOpen,
        status = status,
        failedOperation = lastFailedOperation,
        pendingOperation = pendingOperation,
        disclosureVersion = EnrollmentDisclosure.VERSION,
        disclosureTitle = EnrollmentDisclosure.TITLE,
        disclosureSupporting = EnrollmentDisclosure.SUPPORTING,
        disclosureDetail = EnrollmentDisclosure.DETAIL,
    )

    /**
     * The current session's projection, or null when none is observed FOR THIS session. Enrolled
     * requires a projection that says enrolled AND no recorded withdrawal intent: the intent
     * store's own collection gate already encodes the fail-closed combination, and the UI reads
     * the same rule so a failed withdrawal cannot render as enrolled.
     */
    private fun currentProjection(): VoiceSharingAccountProjection? {
        val tagged = taggedProjection.value
        if (tagged.ownerSession != ownerSession()) return null
        return tagged.projection
    }

    private fun isEnrolled(): Boolean {
        val p = currentProjection() ?: return false
        return p.voiceSharingEnrolled &&
            p.withdrawalState == WithdrawalState.ACTIVE &&
            withdrawalIntent.collectionPermittedByLocalIntent(p)
    }

    /**
     * A withdrawal clears MEMBERSHIP, not the right to delete data the server already accepted
     * (reviewer 20b681f0): the historical-data eligibility is the OLD screen's rule — enrolled
     * OR a recorded enrollment epoch — evaluated against the current session's projection.
     */
    private fun isDeleteAvailable(): Boolean {
        val p = currentProjection() ?: return false
        return p.voiceSharingEnrolled || p.enrollmentEpoch != null
    }
}
