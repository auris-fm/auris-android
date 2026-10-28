package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.ui

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.DeletionReceipt
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.OperationTicket
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.SessionToken
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingMutationResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingPlan
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.WithdrawalState
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentController
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentDisclosure
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.LocalWithdrawalIntentStore
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.WithdrawalIntentPersistence
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.PolicySnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * THE SETTINGS SHELL'S HONESTY CONTRACT (plan Task 7 UI bullet). The screen composes the LIVE
 * policy snapshot, the account projection and the local withdrawal intent into one UI state, and
 * surfaces every operation's outcome honestly: pending is shown while the server has not answered,
 * failure is shown as failure, and the toggle's truth is the SERVER projection through the local
 * intent — never a local queue change dressed up as server completion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSharingSettingsViewModelTest {

    @get:Rule
    val temp = TemporaryFolder()

    /**
     * A FRESH dispatcher per test. runTest(dispatcher) uses the dispatcher's own scheduler, so a
     * class-level instance carries queued tasks from one test into the next and the next runTest
     * can fail with leftover-work errors. Per-test creation keeps every scheduler empty at entry.
     */
    private var mainDispatcher = StandardTestDispatcher()

    /** Scopes this test created; cancelled in the rule BEFORE the Main dispatcher resets. */
    private val scopes = mutableListOf<kotlinx.coroutines.CoroutineScope>()

    @get:Rule
    val mainRule = object : TestWatcher() {
        override fun starting(description: Description) {
            mainDispatcher = StandardTestDispatcher()
            Dispatchers.setMain(mainDispatcher)
        }

        override fun finished(description: Description) {
            // Cancel BEFORE resetMain: a live collect must not throw after Main is gone.
            scopes.forEach { it.cancel() }
            scopes.clear()
            Dispatchers.resetMain()
        }
    }

    private val policy = MutableStateFlow(enabledSnapshot())
    private val projection = MutableStateFlow<VoiceSharingAccountProjection?>(null)
    private val enrollGate = Gate()
    private val withdrawGate = Gate()

    /** Delete resolves to a nullable DeletionReceipt, not a mutation result. */
    private class ReceiptGate {
        val release = CompletableDeferred<DeletionReceipt?>()
        suspend fun await(): DeletionReceipt? = release.await()
    }
    private val deleteGate = ReceiptGate()
    private val persistenceFlag = RecordingPersistence()

    /** Observes the durable WRITE SEQUENCE: the fenced reconciliations write after the outcome. */
    private class RecordingPersistence(
        var stopped: Boolean = false,
        var failClear: Boolean = false,
    ) : WithdrawalIntentPersistence {
        /** Every durable write, in order: the tap's stop, then any fenced reconciliation. */
        val writes = mutableListOf<Boolean>()
        override fun read(): Boolean = stopped
        override fun write(requested: Boolean): Boolean {
            writes += requested
            if (!requested && failClear) return false
            stopped = requested
            return true
        }
    }

    private fun enabledSnapshot() = PolicySnapshot(
        enabled = true,
        activatedAtMonotonicMs = 1_000L,
        activationGeneration = 1L,
    )

    private fun enrolledProjection() = VoiceSharingAccountProjection(
        membershipRevision = "rev-1",
        plan = VoiceSharingPlan.PRO,
        voiceSharingEnrolled = true,
        enrollmentEpoch = "epoch-2",
        acceptedDisclosureVersion = EnrollmentDisclosure.VERSION,
        withdrawalState = WithdrawalState.ACTIVE,
    )

    /** Arms the operation's result; completing [release] lets the suspended operation return. */
    private class Gate {
        val release = CompletableDeferred<Unit>()
        lateinit var result: VoiceSharingMutationResult

        suspend fun await(): VoiceSharingMutationResult {
            release.await()
            return result
        }
    }

    private fun viewModel(
        enrollGate: Gate = Gate(),
        withdrawGate: Gate = Gate(),
        scope: kotlinx.coroutines.CoroutineScope? = null,
        persistence: RecordingPersistence = persistenceFlag,
        ownerSession: () -> Long = { 1L },
        onEnrollIssue: (() -> Unit)? = null,
        onWithdrawIssue: (() -> Unit)? = null,
    ): VoiceSharingSettingsViewModel {
        val effective = scope ?: kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + mainDispatcher,
        ).also { scopes += it }
        val controller = EnrollmentController(
            enrollOperation = { version ->
                require(version == EnrollmentDisclosure.VERSION)
                onEnrollIssue?.invoke()
                enrollGate.await()
            },
            withdrawOperation = {
                onWithdrawIssue?.invoke()
                withdrawGate.await()
            },
            deleteOperation = { null },
            clearLocalPending = {},
            ownerSession = ownerSession,
        )
        return VoiceSharingSettingsViewModel(
            policy = policy,
            projection = projection,
            enrollmentController = controller,
            withdrawalIntent = LocalWithdrawalIntentStore(persistence),
            ownerSession = ownerSession,
            scope = effective,
        )
    }

    @Test
    fun `a disabled policy hides the setting even for an enrolled account`() = runTest(mainDispatcher) {
        policy.value = PolicySnapshot(enabled = false, activatedAtMonotonicMs = 1L, activationGeneration = 0L)
        projection.value = enrolledProjection()

        val state = viewModel().state.value

        assertFalse("a disabled policy must hide the setting", state.settingAvailable)
    }

    @Test
    fun `disclosure copy is the exact build constants the consent version names`() = runTest(mainDispatcher) {
        val state = viewModel().state.value

        assertEquals(EnrollmentDisclosure.VERSION, state.disclosureVersion)
        assertEquals(EnrollmentDisclosure.TITLE, state.disclosureTitle)
        assertEquals(EnrollmentDisclosure.SUPPORTING, state.disclosureSupporting)
        assertEquals(EnrollmentDisclosure.DETAIL, state.disclosureDetail)
    }

    @Test
    fun `an enroll tap shows PENDING until the server answers and is honest about failure`() = runTest(mainDispatcher) {
        projection.value = enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED)
        val gate = Gate()
        val vm = viewModel(enrollGate = gate)

        vm.enroll()
        mainDispatcher.scheduler.advanceUntilIdle()

        val pending = vm.state.value
        assertTrue(
            "the operation in flight must show as pending (got enrolled=" + pending.enrolled +
                " pending=" + pending.pending + " status=" + pending.status + ")",
            pending.pending,
        )
        assertFalse("pending is not enrolled: the server has not answered", pending.enrolled)

        gate.result = VoiceSharingMutationResult.Failure
        gate.release.complete(Unit)
        mainDispatcher.scheduler.advanceUntilIdle()

        val failed = vm.state.value
        assertFalse("a failed enrollment must NOT show as enrolled", failed.enrolled)
        assertFalse(failed.pending)
        assertEquals("the failure must be surfaced, not swallowed", SettingsOperationStatus.FAILED, failed.status)
    }

    @Test
    fun `an applied enrollment reflects the SERVER projection, not the local tap`() = runTest(mainDispatcher) {
        projection.value = enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED)
        val gate = Gate()
        val vm = viewModel(enrollGate = gate)

        vm.enroll()
        mainDispatcher.scheduler.advanceUntilIdle()
        // The tap alone must not flip the toggle: only the server projection does.
        assertFalse("a tap is not an application", vm.state.value.enrolled)

        gate.result = VoiceSharingMutationResult.Applied(
            enrolledProjection(),
            SessionToken(),
            OperationTicket(1L, null, 1L),
        )
        gate.release.complete(Unit)
        // The applied result carries the server projection; the refreshed snapshot agrees.
        projection.value = enrolledProjection()
        mainDispatcher.scheduler.advanceUntilIdle()

        assertTrue("a server-applied enrollment with a fresh enrolled projection IS enrolled", vm.state.value.enrolled)
        assertEquals("a settled success is not a lingering message", SettingsOperationStatus.NONE, vm.state.value.status)
    }

    @Test
    fun `a withdraw tap stops collection FIRST and stays stopped after a failed withdrawal`() = runTest(mainDispatcher) {
        projection.value = enrolledProjection()
        val gate = Gate()
        val vm = viewModel(withdrawGate = gate)
        assertTrue("PREMISE: enrolled and active", vm.state.value.enrolled)

        vm.withdraw()
        mainDispatcher.scheduler.advanceUntilIdle()

        val stopped = vm.state.value
        assertFalse("the local intent must stop collection immediately on the tap", stopped.enrolled)

        gate.result = VoiceSharingMutationResult.Failure
        gate.release.complete(Unit)
        mainDispatcher.scheduler.advanceUntilIdle()

        val failed = vm.state.value
        assertFalse("a FAILED withdrawal keeps the local stop: the user decided", failed.enrolled)
        assertEquals("the failure must be surfaced honestly", SettingsOperationStatus.FAILED, failed.status)
    }

    // --- Reviewer ae7922f9: re-enrollment must release the durable stop through the outcome fence.

    @Test
    fun `a successful re-enrollment releases the durable local stop`() = runTest(mainDispatcher) {
        // PREMISE: the account re-enrolled server-side, but a durable withdrawal intent from an
        // earlier opt-out still holds collection stopped. This is the state the current controls
        // never start from — and the one where the stop must finally be released.
        val persistence = RecordingPersistence(stopped = true)
        projection.value = enrolledProjection()
        val gate = Gate()
        val vm = viewModel(enrollGate = gate, persistence = persistence)
        assertFalse("PREMISE: the durable stop holds even with an enrolled projection", vm.state.value.enrolled)

        vm.enroll()
        mainDispatcher.scheduler.advanceUntilIdle()
        gate.result = VoiceSharingMutationResult.Applied(enrolledProjection(), SessionToken(), OperationTicket(1L, null, 1L))
        gate.release.complete(Unit)
        mainDispatcher.scheduler.advanceUntilIdle()

        assertTrue("a server-applied re-enrollment is enrolled", vm.state.value.enrolled)
        assertFalse(
            "the successful enrollment must RELEASE the durable stop (atomic with the outcome)",
            persistence.stopped,
        )
    }

    @Test
    fun `a failed enrollment keeps the durable local stop`() = runTest(mainDispatcher) {
        val persistence = RecordingPersistence(stopped = true)
        projection.value = enrolledProjection()
        val gate = Gate()
        val vm = viewModel(enrollGate = gate, persistence = persistence)

        vm.enroll()
        mainDispatcher.scheduler.advanceUntilIdle()
        gate.result = VoiceSharingMutationResult.Failure
        gate.release.complete(Unit)
        mainDispatcher.scheduler.advanceUntilIdle()

        assertFalse("a failed enrollment must NOT render as enrolled", vm.state.value.enrolled)
        assertTrue("the durable stop survives a failed enrollment", persistence.stopped)
    }

    // --- Reviewer ae7922f9: failure identity is durable UI state, not transient render input.

    @Test
    fun `the failure copy survives unrelated projection updates until the next action`() = runTest(mainDispatcher) {
        projection.value = enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED)
        val gate = Gate()
        val vm = viewModel(enrollGate = gate)

        vm.enroll()
        mainDispatcher.scheduler.advanceUntilIdle()
        gate.result = VoiceSharingMutationResult.Failure
        gate.release.complete(Unit)
        mainDispatcher.scheduler.advanceUntilIdle()
        assertEquals("PREMISE: the enrollment failed", SettingsOperationStatus.FAILED, vm.state.value.status)
        assertEquals("PREMISE: the failure names the enrollment", FailedOperation.ENROLL, vm.state.value.failedOperation)

        // An unrelated downstream emission (account projection refresh) re-renders the screen.
        projection.value = enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED, membershipRevision = "rev-2")
        mainDispatcher.scheduler.advanceUntilIdle()

        assertEquals("an unrelated update must NOT erase the failure copy", SettingsOperationStatus.FAILED, vm.state.value.status)
        assertEquals("the failure must still name the enrollment", FailedOperation.ENROLL, vm.state.value.failedOperation)
    }

    // --- Reviewer ae7922f9: pending is labeled by ITS operation, not a hard-coded withdrawal.

    @Test
    fun `pending is labeled by its operation - enrollment pending is not withdrawal pending`() = runTest(mainDispatcher) {
        projection.value = enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED)
        val enrollGate = Gate()
        val withdrawGate = Gate()
        val vm = viewModel(enrollGate = enrollGate, withdrawGate = withdrawGate)

        vm.enroll()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            "an in-flight ENROLLMENT must render enrollment-pending copy, not withdrawal copy",
            PendingOperation.ENROLL,
            vm.state.value.pendingOperation,
        )
        enrollGate.result = VoiceSharingMutationResult.Applied(enrolledProjection(), SessionToken(), OperationTicket(1L, null, 1L))
        enrollGate.release.complete(Unit)
        projection.value = enrolledProjection()
        mainDispatcher.scheduler.advanceUntilIdle()

        vm.withdraw()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            "an in-flight WITHDRAWAL renders withdrawal-pending copy",
            PendingOperation.WITHDRAW,
            vm.state.value.pendingOperation,
        )
    }

    // --- Reviewer a2136dd9: a stale outcome must not mutate the CURRENT action's presentation.

    @Test
    fun `a superseded outcome does not disturb the current action's pending label`() = runTest(mainDispatcher) {
        projection.value = enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED)
        val enrollGate = Gate()
        val withdrawGate = Gate()
        val vm = viewModel(enrollGate = enrollGate, withdrawGate = withdrawGate)

        // Enrollment A parks; the withdrawal B supersedes it and parks too.
        vm.enroll()
        mainDispatcher.scheduler.advanceUntilIdle()
        vm.withdraw()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertEquals("PREMISE: withdrawal B owns the pending label", PendingOperation.WITHDRAW, vm.state.value.pendingOperation)

        // A returns (it WOULD have succeeded) while B is still in flight - A comes back Superseded.
        enrollGate.result = VoiceSharingMutationResult.Applied(enrolledProjection(), SessionToken(), OperationTicket(1L, null, 1L))
        enrollGate.release.complete(Unit)
        mainDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "a stale outcome must NOT clear the current action's pending label",
            PendingOperation.WITHDRAW,
            vm.state.value.pendingOperation,
        )
        // The stale consume may poison DURABLE fields that only a later re-render exposes; force
        // one with an unrelated projection emission and assert again (timeline-probed: the stale
        // consume settles at advance #3, this render at #4 exposes any poison).
        projection.value = enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED, membershipRevision = "rev-mid")
        mainDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            "the stale outcome must not have poisoned the durable pending label",
            PendingOperation.WITHDRAW,
            vm.state.value.pendingOperation,
        )
        assertTrue("the current action is still pending", vm.state.value.pending)
    }

    // --- Reviewer a2136dd9: the failure presentation retires at the OWNER-SESSION boundary.

    @Test
    fun `an account session change retires the failure presentation`() = runTest(mainDispatcher) {
        var session = 1L
        projection.value = enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED)
        val gate = Gate()
        val vm = viewModel(enrollGate = gate, ownerSession = { session })

        vm.enroll()
        mainDispatcher.scheduler.advanceUntilIdle()
        gate.result = VoiceSharingMutationResult.Failure
        gate.release.complete(Unit)
        mainDispatcher.scheduler.advanceUntilIdle()
        assertEquals("PREMISE: the failure is shown for account A", SettingsOperationStatus.FAILED, vm.state.value.status)

        // A DIFFERENT account signs in; the projection switches.
        session = 2L
        projection.value = enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED, membershipRevision = "rev-B")
        mainDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "account B must not see account A's failure copy",
            SettingsOperationStatus.NONE,
            vm.state.value.status,
        )
        assertEquals(FailedOperation.NONE, vm.state.value.failedOperation)
    }

    // --- Reviewer a2136dd9: a failed LOCAL stop release is distinct from a server rejection.

    @Test
    fun `a failed local stop release surfaces distinctly and keeps the stop`() = runTest(mainDispatcher) {
        projection.value = enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED)
        val persistence = RecordingPersistence(stopped = true, failClear = true)
        val gate = Gate()
        val vm = viewModel(enrollGate = gate, persistence = persistence)

        vm.enroll()
        mainDispatcher.scheduler.advanceUntilIdle()
        gate.result = VoiceSharingMutationResult.Applied(enrolledProjection(), SessionToken(), OperationTicket(1L, null, 1L))
        gate.release.complete(Unit)
        mainDispatcher.scheduler.advanceUntilIdle()

        assertTrue("the stop survives: durable clearing failed, collection stays paused", persistence.stopped)
        assertEquals(
            "the local release failure is surfaced, not rendered as a settled success",
            SettingsOperationStatus.RELEASE_FAILED,
            vm.state.value.status,
        )
        // The operation SETTLED - a failed local release must not leave it pending forever.
        assertFalse("a settled operation is not pending (reviewer b7ad7c92)", vm.state.value.pending)
        assertEquals(PendingOperation.NONE, vm.state.value.pendingOperation)
    }

    // --- Reviewer 66ae7752: a session switch must not leave the stale action pending on the NEW account.

    @Test
    fun `an account switch does not leave the stale action pending on the new account`() = runTest(mainDispatcher) {
        var session = 1L
        projection.value = enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED)
        val gate = Gate()
        val vm = viewModel(enrollGate = gate, ownerSession = { session })

        vm.enroll()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue("PREMISE: account A's enrollment is pending", vm.state.value.pending)

        // Account B signs in while A's enrollment is still in flight.
        session = 2L
        projection.value = enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED, membershipRevision = "rev-B")
        mainDispatcher.scheduler.advanceUntilIdle()

        // A finishes now: the controller returns Superseded WITHOUT touching its own state.
        gate.result = VoiceSharingMutationResult.Applied(enrolledProjection(), SessionToken(), OperationTicket(1L, null, 1L))
        gate.release.complete(Unit)
        mainDispatcher.scheduler.advanceUntilIdle()

        assertFalse(
            "account B must not inherit account A's pending state",
            vm.state.value.pending,
        )
        assertEquals("no stale pending label either", PendingOperation.NONE, vm.state.value.pendingOperation)
        assertTrue("the setting must remain available on account B", vm.state.value.settingAvailable)
    }

    // --- Reviewer 66ae7752: a queued tap from a dead session must never issue against the NEW account.

    @Test
    fun `a tap issued under a session that changed before it runs never reaches the new account`() = runTest(mainDispatcher) {
        var session = 1L
        projection.value = enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED)
        var enrollIssues = 0
        val gate = Gate()
        val vm = viewModel(enrollGate = gate, ownerSession = { session }, onEnrollIssue = { enrollIssues++ })

        // The tap happens under session 1 but the dispatcher has not run the coroutine yet.
        vm.enroll()

        // The account switches BEFORE the queued action ever runs.
        session = 2L
        projection.value = enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED, membershipRevision = "rev-B")
        mainDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "the dead session's tap must NOT issue a mutation against the new account",
            0,
            enrollIssues,
        )
        assertFalse("the dead tap leaves no pending state on the new account", vm.state.value.pending)
        assertEquals(PendingOperation.NONE, vm.state.value.pendingOperation)
    }

    // --- Binding the reachable Settings section to this ViewModel (spec directive): the durable
    // stop side effects the fragment performed MUST move with the VM, not silently drop.

    @Test
    fun `an applied withdrawal acknowledges the local intent inside the fence`() = runTest(mainDispatcher) {
        projection.value = enrolledProjection()
        val gate = Gate()
        val vm = viewModel(withdrawGate = gate)
        assertTrue("PREMISE: enrolled and active", vm.state.value.enrolled)

        vm.withdraw()
        mainDispatcher.scheduler.advanceUntilIdle()
        gate.result = VoiceSharingMutationResult.Applied(
            enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.WITHDRAWN),
            SessionToken(),
            OperationTicket(1L, null, 1L),
        )
        gate.release.complete(Unit)
        mainDispatcher.scheduler.advanceUntilIdle()
        projection.value = enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.WITHDRAWN)
        mainDispatcher.scheduler.advanceUntilIdle()

        assertFalse("PREMISE: withdrawn on the server", vm.state.value.enrolled)
        // The store deliberately KEEPS the stop on server agreement (its own docstring: a stale
        // enrolled projection must never resume collection on its own). The observable for the
        // fenced reconciliation is a SECOND durable write(true) AFTER the outcome - the store
        // re-stating the stop through onServerAgrees.
        assertEquals(
            "an applied withdrawal must reconcile the intent store inside the fence (tap write + outcome write)",
            listOf(true, true),
            persistenceFlag.writes,
        )
    }

    @Test
    fun `a failed withdrawal records the failure with the local intent store`() = runTest(mainDispatcher) {
        projection.value = enrolledProjection()
        val gate = Gate()
        val vm = viewModel(withdrawGate = gate)

        vm.withdraw()
        mainDispatcher.scheduler.advanceUntilIdle()
        gate.result = VoiceSharingMutationResult.Failure
        gate.release.complete(Unit)
        mainDispatcher.scheduler.advanceUntilIdle()

        assertTrue("PREMISE: the stop survives a failed withdrawal", persistenceFlag.stopped)
        assertEquals(
            "a failed withdrawal must latch its failure through onWithdrawalFailed (tap write + outcome write)",
            listOf(true, true),
            persistenceFlag.writes,
        )
    }

    @Test
    fun `deletion is honest - pending is not success and failure stays visible`() = runTest(mainDispatcher) {
        projection.value = enrolledProjection()
        lateinit var vm: VoiceSharingSettingsViewModel
        val controller = EnrollmentController(
            enrollOperation = { enrollGate.await() },
            withdrawOperation = { withdrawGate.await() },
            deleteOperation = { deleteGate.await() },
            clearLocalPending = {},
            ownerSession = { 1L },
        )
        vm = VoiceSharingSettingsViewModel(
            policy = policy,
            projection = projection,
            enrollmentController = controller,
            withdrawalIntent = LocalWithdrawalIntentStore(persistenceFlag),
            ownerSession = { 1L },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + mainDispatcher).also { scopes += it },
        )
        mainDispatcher.scheduler.advanceUntilIdle()

        vm.deleteSharedData()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue("deletion in flight shows the REQUEST state", vm.state.value.deletionRequested)
        assertFalse("pending deletion is not settled success", vm.state.value.deletionFailed)

        // A null receipt is the controller's Failed path (no processing receipt from the server).
        deleteGate.release.complete(null)
        mainDispatcher.scheduler.advanceUntilIdle()
        assertFalse("a failed deletion is no longer in flight", vm.state.value.deletionRequested)
        assertTrue("a failed deletion stays visible until the next action", vm.state.value.deletionFailed)
    }

    @Test
    fun `a session switch retires deletion presentation`() = runTest(mainDispatcher) {
        projection.value = enrolledProjection()
        var session = 1L
        lateinit var vm: VoiceSharingSettingsViewModel
        val controller = EnrollmentController(
            enrollOperation = { enrollGate.await() },
            withdrawOperation = { withdrawGate.await() },
            deleteOperation = { deleteGate.await() },
            clearLocalPending = {},
            ownerSession = { session },
        )
        vm = VoiceSharingSettingsViewModel(
            policy = policy,
            projection = projection,
            enrollmentController = controller,
            withdrawalIntent = LocalWithdrawalIntentStore(persistenceFlag),
            ownerSession = { session },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + mainDispatcher).also { scopes += it },
        )
        mainDispatcher.scheduler.advanceUntilIdle()

        vm.deleteSharedData()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue("PREMISE: deletion request in flight", vm.state.value.deletionRequested)

        // Account switch: a fresh projection for the new session emits through the collector.
        session = 2L
        projection.value = enrolledProjection().copy(membershipRevision = "rev-B")
        mainDispatcher.scheduler.advanceUntilIdle()

        assertFalse("account B must not see account A's deletion state", vm.state.value.deletionRequested)
        assertFalse(vm.state.value.deletionFailed)
    }

    @Test
    fun `the shell reports whether an account projection has been observed`() = runTest(mainDispatcher) {
        val vm = viewModel()
        assertFalse("no projection yet: the section cannot show a truthful switch", vm.state.value.projectionObserved)
        projection.value = enrolledProjection()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.projectionObserved)
    }

    // --- Reviewer 578bb62a: deletion lifecycle, conflict keeps the stop, current-session projection.

    @Test
    fun `a withdrawal conflict keeps the local stop and reconciles through the fence`() = runTest(mainDispatcher) {
        projection.value = enrolledProjection()
        val gate = Gate()
        val vm = viewModel(withdrawGate = gate)

        vm.withdraw()
        mainDispatcher.scheduler.advanceUntilIdle()
        gate.result = VoiceSharingMutationResult.MembershipConflict(enrolledProjection(), SessionToken(), OperationTicket(1L, null, 1L))
        gate.release.complete(Unit)
        mainDispatcher.scheduler.advanceUntilIdle()

        assertTrue("PREMISE: the stop survives a conflicted withdrawal", persistenceFlag.stopped)
        assertEquals(
            "a CONFLICTED withdrawal must also latch through onWithdrawalFailed (tap write + outcome write)",
            listOf(true, true),
            persistenceFlag.writes,
        )
        assertEquals(SettingsOperationStatus.CONFLICT, vm.state.value.status)
    }

    @Test
    fun `deletion request-in-flight is distinct from the accepted-but-pending receipt`() = runTest(mainDispatcher) {
        projection.value = enrolledProjection()
        lateinit var vm: VoiceSharingSettingsViewModel
        val controller = EnrollmentController(
            enrollOperation = { enrollGate.await() },
            withdrawOperation = { withdrawGate.await() },
            deleteOperation = { deleteGate.await() },
            clearLocalPending = {},
            ownerSession = { 1L },
        )
        vm = VoiceSharingSettingsViewModel(
            policy = policy,
            projection = projection,
            enrollmentController = controller,
            withdrawalIntent = LocalWithdrawalIntentStore(persistenceFlag),
            ownerSession = { 1L },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + mainDispatcher).also { scopes += it },
        )
        mainDispatcher.scheduler.advanceUntilIdle()

        vm.deleteSharedData()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue("the tap is the REQUEST in flight", vm.state.value.deletionRequested)
        assertFalse("the request in flight is not yet an accepted receipt", vm.state.value.deletionReceiptPending)

        deleteGate.release.complete(DeletionReceipt("req-1", "pending"))
        mainDispatcher.scheduler.advanceUntilIdle()
        assertFalse("the accepted receipt ends the request-in-flight state", vm.state.value.deletionRequested)
        assertTrue("an accepted receipt is still PROCESSING, never deleted", vm.state.value.deletionReceiptPending)
        assertFalse(vm.state.value.deletionFailed)
    }

    @Test
    fun `a deletion completing after a session switch mutates nothing`() = runTest(mainDispatcher) {
        projection.value = enrolledProjection()
        var session = 1L
        lateinit var vm: VoiceSharingSettingsViewModel
        val controller = EnrollmentController(
            enrollOperation = { enrollGate.await() },
            withdrawOperation = { withdrawGate.await() },
            deleteOperation = { deleteGate.await() },
            clearLocalPending = {},
            ownerSession = { session },
        )
        vm = VoiceSharingSettingsViewModel(
            policy = policy,
            projection = projection,
            enrollmentController = controller,
            withdrawalIntent = LocalWithdrawalIntentStore(persistenceFlag),
            ownerSession = { session },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + mainDispatcher).also { scopes += it },
        )
        mainDispatcher.scheduler.advanceUntilIdle()

        vm.deleteSharedData()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue("PREMISE: deletion request in flight", vm.state.value.deletionRequested)

        // Session switch, THEN A's deletion completes.
        session = 2L
        projection.value = enrolledProjection().copy(membershipRevision = "rev-B")
        mainDispatcher.scheduler.advanceUntilIdle()
        deleteGate.release.complete(DeletionReceipt("req-1", "pending"))
        mainDispatcher.scheduler.advanceUntilIdle()

        assertFalse("A's late deletion receipt must not set B's presentation", vm.state.value.deletionReceiptPending)
        assertFalse(vm.state.value.deletionRequested)
        assertFalse(vm.state.value.deletionFailed)
    }

    @Test
    fun `projectionObserved means the CURRENT session's projection, not a retained one`() = runTest(mainDispatcher) {
        var currentSession = 1L
        val gate = Gate()
        val vm = viewModel(enrollGate = gate, ownerSession = { currentSession })
        assertFalse("PREMISE: nothing observed yet", vm.state.value.projectionObserved)

        projection.value = enrolledProjection()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.projectionObserved)
        assertTrue(vm.state.value.enrolled)

        // Session switch WITHOUT a projection emission yet: the retained projection belongs to A.
        // (production drops obsolete-account responses asynchronously; until B's projection
        // lands the screen must not show A's membership as the current account's). The next
        // render is a real user path: B taps the switch ON (no local stop is recorded, so the
        // guard itself must refuse the foreign projection) before B's projection arrives.
        currentSession = 2L
        vm.enroll()
        mainDispatcher.scheduler.advanceUntilIdle()

        assertFalse("a retained foreign-session projection is not the current session's", vm.state.value.projectionObserved)
        assertFalse("a foreign-session projection must not render as enrolled", vm.state.value.enrolled)
    }

    // --- Reviewer b7ad7c92: a retired tap must have NO presentation effects on a newer action.

    @Test
    fun `a retired stale tap does not clear a newer action's pending presentation`() = runTest(mainDispatcher) {
        var session = 1L
        projection.value = enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED)
        val enrollGateA = Gate()
        val enrollGateB = Gate()
        var issues = 0
        val vm = viewModel(enrollGate = enrollGateA, withdrawGate = enrollGateB, ownerSession = { session }, onEnrollIssue = { issues++ })

        // Tap A under session 1; the coroutine is queued, not yet run.
        vm.enroll()

        // The session switches, then a NEW tap B lands before the dispatcher runs anything.
        session = 2L
        projection.value = enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED, membershipRevision = "rev-B")
        vm.enroll()
        mainDispatcher.scheduler.advanceUntilIdle()

        assertEquals("PREMISE: only B's tap may issue", 1, issues)
        assertEquals(
            "B's pending presentation survives A's rejection",
            PendingOperation.ENROLL,
            vm.state.value.pendingOperation,
        )
        assertTrue("B's action is still pending", vm.state.value.pending)
    }
}
