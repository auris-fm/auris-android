package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.ui

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.WithdrawalState
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentController
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentDisclosure
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentState
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.LocalWithdrawalIntentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

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
    /** Always true now: there is no remote enable switch; the user's sharing choice is the control. */
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
||||||| /tmp/mfb
     * Deletion of already-accepted data, in THREE honest states: the tap is
     * the request in flight; a 202 receipt is accepted-but-processing; neither ever means
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
    val deletionFailed: Boolean = false,
    val disclosureVersion: String,
    val disclosureTitle: String,
    val disclosureSupporting: String,
    val disclosureDetail: String,
)

enum class SettingsOperationStatus { NONE, FAILED, RELEASE_FAILED }

/** The operation a visible failure refers to; the copy differs per operation's honest state. */
enum class FailedOperation { NONE, ENROLL, WITHDRAW }

/** The operation currently in flight, so pending copy names what is actually pending. */
enum class PendingOperation { NONE, ENROLL, WITHDRAW }

/**
 * Composes the account projection and the local withdrawal intent into
 * the settings shell's state, and drives the [EnrollmentController] the same way the production
 * Settings row does: a withdrawal records the LOCAL intent FIRST — before any request — so
 * collection stops while the server has not answered yet (reviewer-verified boundary).
 */
class VoiceSharingSettingsViewModel(
    /** The account's membership projection; null until the server has answered for this session. */
    private val projection: StateFlow<VoiceSharingAccountProjection?>,
    /**
     * THE PRODUCTION PROJECTION PREFIX (installed-path fix, spec c244661a): the surface's
     * projection is only authoritative if something actually REQUESTS it. Before this, the
     * membership GET ran nowhere in production once the owner had a live session, so the consent
     * section stayed hidden with no request to point at. Null in controls that seed projections.
     */
    private val refreshProjection: (suspend () -> Boolean)? = null,
    private val enrollmentController: EnrollmentController,
    private val withdrawalIntent: LocalWithdrawalIntentStore,
    /**
     * THE shared owner-session source (the same authority snapshot the controller fences with).
     * The ViewModel is a process-wide singleton, so operation PRESENTATION must retire at the
     * account boundary too: account B must never see account A's failure copy (reviewer a2136dd9).
     */
    private val ownerSession: () -> Long,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    /**
     * Abandons a request that is already on the wire.
     *
     * The uploader loop cannot do this itself: while a call is active the loop is suspended inside
     * it and never reaches its own pre-send gate, so a stop that waits for the loop to notice waits
     * for the very request it is meant to stop. The user's action is the only place that runs while
     * the loop is blocked.
     */
    private val cancelInFlightUpload: () -> Unit = {},
    /**
     * Where durable work runs. Turning sharing off writes a marker, cancels a request and deletes
     * the queue, all of which touch storage; doing that on the caller's thread would stall the
     * frame a user is waiting on.
     */
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    /**
     * The durable marker read itself, injectable so a control can park a read's completion while a
     * newer one lands -- the out-of-order acceptance the fence exists for. Null in production,
     * which reads the store directly on [ioDispatcher].
     */
    private val markerRead: (suspend () -> Boolean)? = null,
) {

    // DURABLE UI STATE, not transient render inputs (reviewer ae7922f9): the combine collector
    // re-renders on every projection emission, and a default parameter there silently
    // erased the failure copy between an action and the next outcome. The failure identity lasts
    // until the NEXT action; the pending label lasts until the outcome settles.
    private var lastFailedOperation = FailedOperation.NONE
    private var pendingOperation = PendingOperation.NONE

    /**
     * The session whose presentation is currently on screen. The session-change retire clears
     * only presentation belonging to a DIFFERENT (dead) session - a fresh tap under the new
     * session that landed before this collector processed the emission must survive.
     */
    private var presentedSession: Long = ownerSession()

    /** Identifies the CURRENT action; a stale (superseded) outcome may not touch presentation. */
    private var actionGeneration = 0L

    // Deletion presentation: durable like the membership presentation, retired at the same
    // owner-session boundary, with its OWN completion fence (reviewer 578bb62a).
    private var deletionRequested = false
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
||||||| /tmp/mfb
    // Must already be initialized (reviewer-turned-self-inflicted NPE: Kotlin initializes in
    // Declaration order, and a null field under a non-null type fails the intrinsic check).
    private var lastRenderedEnrolled: Boolean = isEnrolled()
||||||| /tmp/mfb
    private var lastRenderedEnrolled: Boolean = isEnrolled()

    private var lastRenderedMarker: Boolean = withdrawalIntent.collectionPermittedByLocalIntent()
    private var markerReadGeneration = 0L
    private val mutableState = MutableStateFlow(render())
    val state: StateFlow<VoiceSharingSettingsUiState> = mutableState.asStateFlow()

    init {
        // The projection's observed session updates ONLY when the projection flow itself emits:
        // an unrelated emission (controller state change) under the new session must not
        // re-label a retained foreign-session projection as current (reviewer 578bb62a).
        scope.launch {
            projection.collect {
                // Publish FIRST from the cached marker: the retire above must be visible in the
                // published state immediately, or the screen keeps presenting the previous
                // session's operation until an IO hop lands. The marker refresh below re-publishes
                // only if the durable answer actually changed. This emits for every projection and
                // controller change -- exactly the frequency at which a Binder lookup and a file
                // read on Main were showing.
                retireForeignSessionPresentation()
                mutableState.value = render()
                refreshEnrolled()
            }
        }
        // ONE bounded projection request per surface construction (production): the GET that makes
        // `projectionObserved` reachable at all. Its outcome is visible in the diagnostic log; a
        // failure leaves the surface hidden fail-closed exactly as before.
        refreshProjection?.let { request ->
            scope.launch { request() }
        }
        scope.launch {
            kotlinx.coroutines.flow.combine(enrollmentController.state) { _ -> }
                .collect {
                    retireForeignSessionPresentation()
                    mutableState.value = render()
                    refreshEnrolled()
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
        deletionFailed = false
        deleteConfirmationOpen = false
        confirmationSession = 0L
    }

    /** Enrolls with the exact disclosure version whose copy this screen renders. */
    fun enroll() {
        // ACTION BOUNDARY (reviewer 66e3d5ec): retire the OLD session's stale presentation
        // FIRST, then create this action's own fields - in this order, because the retire
        // clears the same fields the action is about to set.
        retireForeignSessionPresentation()
        // A new action retires the previous failure copy (reviewer ae7922f9: durable until the
        // next action, not wiped by an unrelated emission but not carried into a fresh attempt).
        lastFailedOperation = FailedOperation.NONE
        pendingOperation = PendingOperation.ENROLL
        presentationStatus = SettingsOperationStatus.NONE
        val generation = ++actionGeneration
        // THE SESSION IS CAPTURED AT THE TAP, not when the queued coroutine starts (reviewer
        // 66ae7752): a tap issued under a session that changed before the dispatcher ran it must
        // never reach the new account's controller.
||||||| /tmp/mfb
        // The session is captured at the tap, not when the queued coroutine starts (reviewer
        // 66ae7752): a tap issued under a session that changed before the dispatcher ran it must
        // Never reach the new account's controller.
        val tappedSession = ownerSession()
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
        // ACTION BOUNDARY (reviewer 66e3d5ec): retire the OLD session's stale presentation
        // first (comment above in enroll()); the controller emission triggered by the queued
        // action can then neither retire this state nor preserve the old session's.
        retireForeignSessionPresentation()
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
        retireForeignSessionPresentation()
        deletionFailed = false
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
                is EnrollmentOutcome.DataDeleted -> {
                    // Synchronous: the server confirmed the deletion, so it is done, not pending.
                    deletionRequested = false
                    deletionFailed = false
                }

                is EnrollmentOutcome.Failed, is EnrollmentOutcome.Refused -> {
                    deletionRequested = false
                    deletionFailed = true
                }

                else -> {}
            }
            // Sync render from the cached marker: the deletion does not touch the withdrawal
            // marker, and a completion publish that hops to IO would land its state update
            // outside the caller's scheduling horizon.
            mutableState.value = render()
        }
    }

    /**
     * Records the local stop FIRST, then withdraws; a failure keeps the stop.
     *
     * Nothing on this path touches storage on the caller's thread. Turning sharing off writes a
     * durable marker, cancels a request already on the wire and deletes the queue, and doing any
     * of it inline stalls the frame the tap is waiting on. The order is preserved off-main: the
     * stop is recorded before the cancel and the withdrawal request.
     */
    fun withdraw() {
        retireForeignSessionPresentation()
        lastFailedOperation = FailedOperation.NONE
        pendingOperation = PendingOperation.WITHDRAW
        presentationStatus = SettingsOperationStatus.NONE
        val generation = ++actionGeneration
        // Same tap-time session capture as enroll() (reviewer 66ae7752). The LOCAL stop is still
        // recorded first: stopping collection on the old account is correct the instant of the tap.
        val tappedSession = ownerSession()
        scope.launch {
            // IO CROSSES, STATE DOES NOT. A render reads the durable marker, so the values it
            // needs are computed off the caller's thread, but every field this class owns is read
            // and written back here on main. Otherwise an old withdrawal could pass its generation
            // check while on IO and then overwrite a newer action's presentation.
            // Capture the IO result FIRST, then re-check on main, then publish: a render that is
            // composed lines up its fields at publish time, so publishing a value read across the
            // suspension would let an old tap paint over a newer action.
            // The generation checks below are defence in depth, not this path's guarantee. What
            // actually protects a newer action is the enrollment controller's own fence, which
            // reports the superseded request and is covered by EnrollmentControllerTest. These
            // checks sit in front of it because the repaint they prevent is cheap to rule out
            // here; a control that removed them could not see the difference, since this same
            // withdrawal publishes again further down and lands back on the newer presentation.
            val openedRead = readMarkerFenced()
            acceptMarkerRead(openedRead)
            if (generation != actionGeneration) return@launch
            mutableState.value = renderFrom(isEnrolled())
            withContext(ioDispatcher) {
                withdrawalIntent.recordWithdrawalRequested()
                // Stopping collection must reach a request already being sent. Cancelling here, at
                // the moment of the tap, is the only point that is not blocked behind that request.
                cancelInFlightUpload()
            }
            // Re-checked on main AFTER the IO suspension: a tap that arrived meanwhile owns the
            // screen, and this one must not overwrite it.
            acceptMarkerRead(readMarkerFenced())
            if (generation != actionGeneration) return@launch
            if (ownerSession() != tappedSession) {
                retireTapPresentation(generation, isEnrolled())
                return@launch
            }
            mutableState.value = renderFrom(isEnrolled())
            val outcome = withContext(ioDispatcher) { enrollmentController.withdraw() }
            if (ownerSession() != tappedSession) {
                acceptMarkerRead(readMarkerFenced())
                retireTapPresentation(generation, isEnrolled())
                return@launch
            }
            applyWithdrawalOutcome(outcome, generation)
        }
    }

    /**
     * Refreshes the cached marker on IO and re-renders only if the durable answer changed.
     *
     * Only the marker read qualifies for IO: everything else in the UI state is an in-memory field
     * this class owns. The synchronous publish in the collectors carries the retire and pending
     * presentation immediately; this pass exists so a marker changed by something outside this
     * screen (or by this screen's own earlier write) shows up on the next event.
     */
    private fun refreshEnrolled() {
        scope.launch { acceptMarkerRead(readMarkerFenced()) }
    }

    /**
     * Issues a marker read whose result is accepted only while it is the newest one.
     *
     * Every read of the durable marker goes through here, the withdrawal paths included: an older
     * read completing after a stop (or after a newer read) must not repaint the screen with the
     * value it fetched. The generation is issued on Main before the IO hop, so ordering follows
     * issue order, and acceptance happens in one place -- `acceptMarkerRead` -- which is the only
     * code that writes [lastRenderedMarker].
     */
    private suspend fun readMarkerFenced(): Pair<Long, Boolean> {
        val generation = ++markerReadGeneration
        val marker = markerRead?.invoke()
            ?: withContext(ioDispatcher) { withdrawalIntent.collectionPermittedByLocalIntent() }
        return generation to marker
    }

    /** The single writer of [lastRenderedMarker]; a superseded read updates nothing. */
    private fun acceptMarkerRead(read: Pair<Long, Boolean>) {
        val (generation, marker) = read
        if (generation != markerReadGeneration) return
        lastRenderedMarker = marker
        mutableState.value = render()
    }

    /**
     * The main-thread half of a withdrawal outcome.
     *
     * The fenced persistence effect runs on IO because it writes the marker; this method decides,
     * back on main and from the returned value, what the screen should say. It re-checks the
     * generation first, so an outcome that lost to a newer action is dropped rather than published.
     */
    private suspend fun applyWithdrawalOutcome(outcome: EnrollmentOutcome, generation: Long) {
        when (outcome) {
            is EnrollmentOutcome.Applied -> {
                // The server AGREED: reconcile the recorded request. The write is on IO; the
                // reconciliation runs under the controller's fence.
                withContext(ioDispatcher) {
                    enrollmentController.applyForOutcome(outcome) { withdrawalIntent.onServerAgrees() }
                }
                // Capture, re-check on main, then publish.
                acceptMarkerRead(readMarkerFenced())
                if (generation != actionGeneration) return
                pendingOperation = PendingOperation.NONE
                presentationStatus = SettingsOperationStatus.NONE
                mutableState.value = renderFrom(isEnrolled())
            }

            is EnrollmentOutcome.Failed, is EnrollmentOutcome.Refused -> {
                // A failure latches with the intent store, fenced, off the caller's thread.
                withContext(ioDispatcher) {
                    enrollmentController.applyForOutcome(outcome) { withdrawalIntent.onWithdrawalFailed() }
                }
                acceptMarkerRead(readMarkerFenced())
                if (generation != actionGeneration) return
                lastFailedOperation = FailedOperation.WITHDRAW
                pendingOperation = PendingOperation.NONE
                presentationStatus = SettingsOperationStatus.FAILED
                mutableState.value = renderFrom(isEnrolled())
            }

            is EnrollmentOutcome.DataDeleted,
            EnrollmentOutcome.Superseded,
            -> {
                // Nothing settled for the withdrawal, or the action was superseded and the
                // superseding action owns the screen.
                if (generation != actionGeneration) return
            }
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
                    // The durable clear is a fsync'd marker write: it runs on IO, still inside
                    // the fence's critical section (applyForOutcome itself stays atomic).
                    val released = withContext(ioDispatcher) {
                        enrollmentController.applyForOutcome(outcome) {
                            withdrawalIntent.clearOnUserEnrollment()
                        }
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
                    // The durable stop was just released, so the cached marker is stale BY
                    // CONSTRUCTION: `acceptMarkerRead` is its only writer, and nothing above
                    // refreshed it. Re-reading here is what makes the screen correct on its own,
                    // instead of correct whenever some collector happens to emit afterwards --
                    // and a StateFlow drops an emission equal to its current value, so that
                    // refresh is not a contract we can lean on. On the failure path the cache is
                    // already right (collection stays stopped), which is why this read follows
                    // the guard rather than preceding it.
                    if (generation == actionGeneration) {
                        acceptMarkerRead(readMarkerFenced())
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

||||||| /tmp/mfb
            is EnrollmentOutcome.Conflict -> {
                if (generation != actionGeneration) return
                if (operation == FailedOperation.WITHDRAW) {
                    // A CONFLICTED withdrawal did not apply either: the user's stop decision
                    // Stands and is latched through the same fenced path as a failure
                    //
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
                    // A FAILED withdrawal latches its failure with the intent store, fenced, and
                    // the latch write is a fsync'd marker write: on IO like its siblings.
                    withContext(ioDispatcher) {
                        enrollmentController.applyForOutcome(outcome) {
                            withdrawalIntent.onWithdrawalFailed()
                        }
                    }
                }
                lastFailedOperation = operation
                pendingOperation = PendingOperation.NONE
                presentationStatus = SettingsOperationStatus.FAILED
                mutableState.value = render(SettingsOperationStatus.FAILED)
            }

            is EnrollmentOutcome.DataDeleted -> {
                if (generation != actionGeneration) return
                pendingOperation = PendingOperation.NONE
                presentationStatus = SettingsOperationStatus.NONE
                mutableState.value = render(SettingsOperationStatus.NONE)
            }

            is EnrollmentOutcome.Refused -> {
                if (generation != actionGeneration) return
                if (operation == FailedOperation.WITHDRAW) {
                    withContext(ioDispatcher) {
                        enrollmentController.applyForOutcome(outcome) {
                            withdrawalIntent.onWithdrawalFailed()
                        }
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

    private fun render(status: SettingsOperationStatus = presentationStatus): VoiceSharingSettingsUiState {
        // Installed-path diagnostic (staging test, reviewer 1f2d9cbf): distinguish the POLICY
        // gate from the MEMBERSHIP gate on the device, without changing either gate.
||||||| /tmp/mfb
    private fun render(status: SettingsOperationStatus = presentationStatus): VoiceSharingSettingsUiState {
        // Installed-path diagnostic: distinguish the POLICY
        // Gate from the MEMBERSHIP gate on the device, without changing either gate.
    private fun retireTapPresentation(generation: Long, enrolled: Boolean) {
        if (generation != actionGeneration) return
        pendingOperation = PendingOperation.NONE
        presentationStatus = SettingsOperationStatus.NONE
        mutableState.value = renderFrom(enrolled)
    }
    private fun render(status: SettingsOperationStatus = presentationStatus): VoiceSharingSettingsUiState = renderFrom(isEnrolled(), status)
||||||| /tmp/mfb

    private fun render(status: SettingsOperationStatus = presentationStatus): VoiceSharingSettingsUiState = renderFrom(isEnrolled(), status)

    /**
     * Composes the UI state from a marker read taken elsewhere.
     *
     * Split out so the marker can be read on IO while every field below stays a main-thread read.
     */
    private fun render(status: SettingsOperationStatus = presentationStatus): VoiceSharingSettingsUiState = renderFrom(lastRenderedEnrolled, status)
    private fun renderFrom(
        enrolled: Boolean,
        status: SettingsOperationStatus = presentationStatus,
    ): VoiceSharingSettingsUiState {
        val observed = projection.value != null
        Timber.i(
            "voice-sharing surface: settingAvailable=%b projectionObserved=%b (session=%d) enrolled=%b",
            true,
            observed,
            ownerSession(),
            enrolled,
        )
        return VoiceSharingSettingsUiState(
            settingAvailable = true,
            enrolled = enrolled,
            // Pending is PRESENTATION state, not the shared controller's: the controller keeps the
            // last operation's Pending state even after it settles as Superseded (its own lifecycle
            // owns that), and account B must never inherit account A's spinner (reviewer 66ae7752).
            pending = pendingOperation != PendingOperation.NONE,
            projectionObserved = projection.value != null,
            deletionRequested = deletionRequested,
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
    }

    /**
     * The current session's projection, or null when none is observed FOR THIS session. Enrolled
     * requires a projection that says enrolled AND no recorded withdrawal intent: the intent
     * store's own collection gate already encodes the fail-closed combination, and the UI reads
     * the same rule so a failed withdrawal cannot render as enrolled.
     */
    private fun currentProjection(): VoiceSharingAccountProjection? = projection.value

    private fun isEnrolled(): Boolean {
        val p = currentProjection() ?: return false
        return p.voiceSharingEnrolled &&
            p.withdrawalState == WithdrawalState.ACTIVE &&
            lastRenderedMarker
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
