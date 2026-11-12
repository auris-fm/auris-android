package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.ui


import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.WithdrawalState
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentController
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentDisclosure
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentState
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
 * The settings shell's ui state. Its truth rules (plan Task 7 ui bullet):
 *
 * - [VoiceSharingSettingsUiState.enrolled] is the server projection read through the local
 * withdrawal intent — never a tap, and never a local queue change dressed up as server
 * completion. A withdrawal tap stops collection immediately (the user decided), which makes
 * `enrolled` false BEFORE the server answers, and it stays false if the withdrawal fails.
 * - [VoiceSharingSettingsUiState.pending] is true exactly while a membership mutation is in
 * flight. Pending is never rendered as enrolled.
 * - [VoiceSharingSettingsUiState.status] surfaces conflict and failure HONESTLY and is cleared
 * only by the next outcome.
 * - The disclosure copy is the exact build constants whose version the client accepts — the UI
 * cannot render wording the consent version does not name.
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
     * current account's state and must not render.
     */
    val projectionObserved: Boolean,
    val durableChoiceKnown: Boolean = false,
    val deletionRequested: Boolean = false,
    /**
     * Historical-data eligibility, carried separately from [enrolled]: a
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
 * Settings row does: a withdrawal records the local intent first — before any request — so
 * collection stops while the server has not answered yet (reviewer-verified boundary).
 */
class VoiceSharingSettingsViewModel(
    /** The account's membership projection; null until the server has answered for this session. */
    private val projection: StateFlow<VoiceSharingAccountProjection?>,
    /**
     * the production projection prefix: the surface's
     * projection is only authoritative if something actually REQUESTS it. Before this, the
     * membership GET ran nowhere in production once the owner had a live session, so the consent
     * section stayed hidden with no request to point at. Null in controls that seed projections.
     */
    private val refreshProjection: (suspend () -> Boolean)? = null,
    private val enrollmentController: EnrollmentController,
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
    private val savedChoice: (suspend () -> au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoice?)? = null,
    /**
     * VISIBILITY provider (spec's bot, offline-start finding): does the account have ANY durable
     * record? Kept separate from [savedChoice] because "sharing is off" is a known state — the row
     * must show offline — while the switch VALUE stays on the stricter consent read.
     */
    private val knownChoice: (suspend () -> au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoice?)? = null,
) {

    // Durable ui state, not transient render inputs: the combine collector
    // Re-renders on every projection emission, and a default parameter there silently
    // Erased the failure copy between an action and the next outcome. The failure identity lasts
    // Until the NEXT action; the pending label lasts until the outcome settles.
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
    // Owner-session boundary, with its OWN completion fence.
    private var deletionRequested = false
    private var deletionFailed = false
    private var deletionGeneration = 0L

    /**
     * The open delete confirmation and the session that opened it: confirming under a different
     * authority would capture THAT session as the tap owner.
     */
    private var deleteConfirmationOpen = false
    private var confirmationSession = 0L

    /**
     * the status the screen shows, as durable ui state. Deriving it from the controller state on
     * every render was the bug: the controller keeps the LAST operation's settled state (Failed,
     * Applied), so a session-boundary retire or a local-release failure was immediately
     * overwritten by the next combine-driven re-render.
     */
    private var presentationStatus = SettingsOperationStatus.NONE
    /**
     * ONE LOCAL TRUTH (owner's Pixel consent bug): the switch renders the DURABLE choice — the same
     * SavedSharingChoiceStore record capture/upload admission reads — never the in-memory projection
     * alone. Fail-closed null until the first IO read lands. Every server answer updates the store,
     * so re-reading it here keeps the switch on exactly what admission would act on.
     */
    private var lastRenderedChoice: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoice? = null
    /** Any durable record for the account (visibility only); null until an IO read lands. */
    private var lastKnownChoice: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoice? = null
    private val mutableState = MutableStateFlow(render())
    val state: StateFlow<VoiceSharingSettingsUiState> = mutableState.asStateFlow()


    init {
        // The projection's observed session updates ONLY when the projection flow itself emits:
        // An unrelated emission (controller state change) under the new session must not
        // Re-label a retained foreign-session projection as current.
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
        // Failure leaves the surface hidden fail-closed exactly as before.
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
     * The account boundary retires operation presentation: an account switch
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
        // Action boundary: retire the old session's stale presentation
        // FIRST, then create this action's own fields - in this order, because the retire
        // Clears the same fields the action is about to set.
        retireForeignSessionPresentation()
        // A new action retires the previous failure copy (: durable until the
        // Next action, not wiped by an unrelated emission but not carried into a fresh attempt).
        lastFailedOperation = FailedOperation.NONE
        pendingOperation = PendingOperation.ENROLL
        presentationStatus = SettingsOperationStatus.NONE
        val generation = ++actionGeneration
        // The session is captured at the tap, not when the queued coroutine starts: a tap issued
        // under a session that changed before the dispatcher ran it must never reach the new
        // account's controller.
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
        // Action boundary: retire the old session's stale presentation
        // First (comment above in enroll()); the controller emission triggered by the queued
        // Action can then neither retire this state nor preserve the old session's.
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
     * re-owned by B.
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
            // Completion fence: the outcome arrives after a suspension, so both the session and
            // The deletion generation must be re-checked here - a deletion whose session changed
            // Or that was superseded while parked must not touch the screen.
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
        // Same tap-time session capture as enroll(). The LOCAL stop is still
        // Recorded first: stopping collection on the old account is correct the instant of the tap.
        val tappedSession = ownerSession()
        scope.launch {
            acceptChoiceRead(readChoiceFenced())
            if (generation != actionGeneration) return@launch
            mutableState.value = renderFrom(isEnrolled())
            // Cancel anything already on the wire; the controller writes the durable WITHDRAWN
            // record at tap time, before its network call.
            withContext(ioDispatcher) { cancelInFlightUpload() }
            acceptChoiceRead(readChoiceFenced())
            if (generation != actionGeneration) return@launch
            if (ownerSession() != tappedSession) {
                retireTapPresentation(generation, isEnrolled())
                return@launch
            }

            mutableState.value = renderFrom(isEnrolled())
            val outcome = withContext(ioDispatcher) { enrollmentController.withdraw() }
            if (ownerSession() != tappedSession) {
                acceptChoiceRead(readChoiceFenced())
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
        scope.launch { acceptChoiceRead(readChoiceFenced()) }
    }

    private suspend fun readChoiceFenced(): FencedFacts {
        val choice = withContext(ioDispatcher) { savedChoice?.invoke() }
        val known = withContext(ioDispatcher) { knownChoice?.invoke() }
        return FencedFacts(choice, known)
    }

    private fun acceptChoiceRead(read: FencedFacts) {
        lastRenderedChoice = read.choice
        lastKnownChoice = read.known
        mutableState.value = render()
    }

    /** The fenced render inputs: the durable choice and whether ANY durable record exists (row visibility). */
    private data class FencedFacts(
        val choice: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoice?,
        val known: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoice? = null,
    )
    private suspend fun applyWithdrawalOutcome(outcome: EnrollmentOutcome, generation: Long) {
        when (outcome) {
            is EnrollmentOutcome.Applied -> {
                acceptChoiceRead(readChoiceFenced())
                if (generation != actionGeneration) return
                pendingOperation = PendingOperation.NONE
                presentationStatus = SettingsOperationStatus.NONE
                mutableState.value = renderFrom(isEnrolled())
            }


            is EnrollmentOutcome.Failed, is EnrollmentOutcome.Refused -> {
                acceptChoiceRead(readChoiceFenced())
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
                acceptChoiceRead(readChoiceFenced())
                if (generation != actionGeneration) return
                pendingOperation = PendingOperation.NONE
                presentationStatus = SettingsOperationStatus.NONE
                mutableState.value = render(SettingsOperationStatus.NONE)
            }

            is EnrollmentOutcome.Failed -> {
                if (generation != actionGeneration) return
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
                lastFailedOperation = operation
                pendingOperation = PendingOperation.NONE
                presentationStatus = SettingsOperationStatus.FAILED
                mutableState.value = render(SettingsOperationStatus.FAILED)
            }

            EnrollmentOutcome.Superseded -> {
                // Nothing settled; the superseding action owns rendering. The stale outcome does
                // Not touch pending/failure/status at all.
            }
        }
    }

    /**
     * A retired (dead-session) tap cleans up its OWN pending presentation without settling — but
     * only if it is still the current action: a stale tap must have NO
     * presentation effects on a newer action's state.
     */
    private fun retireTapPresentation(generation: Long) {
        if (generation != actionGeneration) return
        pendingOperation = PendingOperation.NONE
        presentationStatus = SettingsOperationStatus.NONE
        mutableState.value = render()
    }

    /**
     * Retires a tap's presentation from a marker value read off the caller's thread.
     *
     * Same contract as [retireTapPresentation], split the same way as [renderFrom] so a session
     * mismatch discovered after an IO suspension does not read the marker while publishing.
     */
    private fun retireTapPresentation(generation: Long, enrolled: Boolean) {
        if (generation != actionGeneration) return
        pendingOperation = PendingOperation.NONE
        presentationStatus = SettingsOperationStatus.NONE
        mutableState.value = renderFrom(enrolled)
    }

    private fun render(status: SettingsOperationStatus = presentationStatus): VoiceSharingSettingsUiState = renderFrom(isEnrolled(), status)

    /**
     * Composes the UI state from a marker read taken elsewhere.
     *
     * Split out so the marker can be read on IO while every field below stays a main-thread read.
     */
    private fun renderFrom(
        enrolled: Boolean,
        status: SettingsOperationStatus = presentationStatus,
    ): VoiceSharingSettingsUiState {
        // Installed-path diagnostic: distinguish the POLICY
        // Gate from the MEMBERSHIP gate on the device, without changing either gate.
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
            // Last operation's Pending state even after it settles as Superseded (its own lifecycle
            // Owns that), and account B must never inherit account A's spinner.
            pending = pendingOperation != PendingOperation.NONE,
            projectionObserved = projection.value != null,
            durableChoiceKnown = lastKnownChoice != null,
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
     * The current session's projection, or null when none is observed for this session. Enrolled
     * requires a projection that says enrolled AND no recorded withdrawal intent: the intent
     * store's own collection gate already encodes the fail-closed combination, and the UI reads
     * the same rule so a failed withdrawal cannot render as enrolled.
     */
    private fun currentProjection(): VoiceSharingAccountProjection? = projection.value


    private fun isEnrolled(): Boolean {
        // The DURABLE choice is the switch's truth (spec one-local-truth): the same record admission
        // reads. The projection only reports whether a server answer was ever observed. Fail closed:
        // no durable record, no consent.
        return lastRenderedChoice?.state == au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SharingChoiceState.ENROLLED
    }

    /**
     * A withdrawal clears MEMBERSHIP, not the right to delete data the server already accepted
     * : the historical-data eligibility is the OLD screen's rule — enrolled
     * OR a recorded enrollment epoch — evaluated against the current session's projection.
     */
    private fun isDeleteAvailable(): Boolean {
        val p = currentProjection() ?: return false
        return p.voiceSharingEnrolled || p.enrollmentEpoch != null
    }
}
