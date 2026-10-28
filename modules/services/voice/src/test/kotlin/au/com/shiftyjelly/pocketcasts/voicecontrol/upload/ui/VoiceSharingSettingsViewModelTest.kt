package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.ui

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

    private class RecordingPersistence(var stopped: Boolean = false) : WithdrawalIntentPersistence {
        override fun read(): Boolean = stopped
        override fun write(requested: Boolean): Boolean {
            stopped = requested
            return true
        }
    }

    private val policy = MutableStateFlow(enabledSnapshot())
    private val projection = MutableStateFlow<VoiceSharingAccountProjection?>(null)

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
        persistence: RecordingPersistence = RecordingPersistence(),
    ): VoiceSharingSettingsViewModel {
        val effective = scope ?: kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + mainDispatcher,
        ).also { scopes += it }
        val controller = EnrollmentController(
            enrollOperation = { version ->
                require(version == EnrollmentDisclosure.VERSION)
                enrollGate.await()
            },
            withdrawOperation = { withdrawGate.await() },
            deleteOperation = { null },
            clearLocalPending = {},
            ownerSession = { 1L },
        )
        return VoiceSharingSettingsViewModel(
            policy = policy,
            projection = projection,
            enrollmentController = controller,
            withdrawalIntent = LocalWithdrawalIntentStore(persistence),
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
}
