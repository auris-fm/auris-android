package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.ui
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingMutationResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingPlan
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.WithdrawalState
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentController
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentDisclosure
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSharingSettingsViewModelTest {
    @get:Rule
    val temp = TemporaryFolder()
    private var mainDispatcher = StandardTestDispatcher()
    private val scopes = mutableListOf<kotlinx.coroutines.CoroutineScope>()

    /** Launched jobs whose scopes were never handed to the rule (leak-proofing, CI flake on 7a9664f95e). */
    private val extraJobs = mutableListOf<kotlinx.coroutines.Job>()
    @get:Rule
    val mainRule = object : TestWatcher() {
        override fun starting(description: Description) {
            mainDispatcher = StandardTestDispatcher()
            Dispatchers.setMain(mainDispatcher)
        }
        override fun finished(description: Description) {
            scopes.forEach { it.cancel() }
            extraJobs.forEach { it.cancel() }
            // DRAIN before resetMain (CI flake on 7a9664f95e): a cancelled-but-still-dispatched
            // collector that touches Dispatchers.Main AFTER resetMain throws
            // "Main was accessed when ... unset", and runTest attributes it to the NEXT test
            // (UncaughtExceptionsBeforeTest). Let cancellation take effect while Main still exists.
            mainDispatcher.scheduler.runCurrent()
            scopes.clear()
            extraJobs.clear()
            Dispatchers.resetMain()
        }
    }
    private val projection = MutableStateFlow<VoiceSharingAccountProjection?>(null)
    @Suppress("UNUSED_PARAMETER")
    private fun setProjection(session: Long, p: VoiceSharingAccountProjection?) {
        projection.value = p
    }
    private fun setProjection(p: VoiceSharingAccountProjection?) = setProjection(1L, p)
    private val enrollGate = Gate()
    private val withdrawGate = Gate()
    private class ReceiptGate {
        val release = CompletableDeferred<Boolean>()
        suspend fun await(): Boolean = release.await()
    }
    private val deleteGate = ReceiptGate()
    private fun enrolledProjection() = VoiceSharingAccountProjection(
        plan = VoiceSharingPlan.PRO,
        voiceSharingEnrolled = true,
        enrollmentEpoch = "epoch-2",
        acceptedDisclosureVersion = EnrollmentDisclosure.VERSION,
        withdrawalState = WithdrawalState.ACTIVE,
    )
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
        ownerSession: () -> Long = { 1L },
        onEnrollIssue: (() -> Unit)? = null,
        onWithdrawIssue: (() -> Unit)? = null,
        cancelInFlightUpload: () -> Unit = {},
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = mainDispatcher,
        recordLocalWithdrawal: () -> Boolean = { false },
        savedChoice: (suspend () -> au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoice?)? = null,
    ): VoiceSharingSettingsViewModel {
        // Mirrors what the production store holds given the projection the test published: apply()
        // persists exactly this rule (enrolled+epoch+disclosure -> ENROLLED, withdrawn -> WITHDRAWN,
        // else NOT_ENROLLED). Tests override with an explicit provider to stage divergence.
        val effectiveChoice = savedChoice ?: {
            val p = projection.value
            when {
                p != null && p.voiceSharingEnrolled &&
                    p.enrollmentEpoch != null && p.acceptedDisclosureVersion != null ->
                    au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoice(
                        account = "user@example.com",
                        state = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SharingChoiceState.ENROLLED,
                        epoch = p.enrollmentEpoch,
                        acceptedDisclosureVersion = p.acceptedDisclosureVersion,
                    )
                p != null && p.withdrawalState == WithdrawalState.WITHDRAWN ->
                    au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoice(
                        account = "user@example.com",
                        state = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SharingChoiceState.WITHDRAWN,
                        epoch = null,
                        acceptedDisclosureVersion = null,
                    )
                else -> null
            }
        }
        val effective = scope ?: kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + mainDispatcher,
        ).also { scopes += it }
        val controller = EnrollmentController(
            enrollOperation = { version, _ ->
                require(version == EnrollmentDisclosure.VERSION)
                onEnrollIssue?.invoke()
                enrollGate.await()
            },
            withdrawOperation = { _ ->
                onWithdrawIssue?.invoke()
                withdrawGate.await()
            },
            deleteOperation = { false },
            clearLocalPending = {},
            recordLocalWithdrawal = recordLocalWithdrawal,
            ownerSession = ownerSession,
        )
        return VoiceSharingSettingsViewModel(
            projection = projection,
            enrollmentController = controller,
            ownerSession = ownerSession,
            scope = effective,
            cancelInFlightUpload = cancelInFlightUpload,
            ioDispatcher = ioDispatcher,
            savedChoice = effectiveChoice,
        )
    }

    private fun awaitTrue(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }
    @Test
    fun `presentation is published on main, observed at the write`() {
        val mainExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "sharing-main").apply { isDaemon = true }
        }
        val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "sharing-io").apply { isDaemon = true }
        }
        val mainDispatcher = mainExecutor.asCoroutineDispatcher()
        val writes = java.util.Collections.synchronizedList(mutableListOf<String>())
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + mainDispatcher).also { scopes += it }
        val withdrawGate = Gate()
        withdrawGate.result = VoiceSharingMutationResult.Failure
        val vm = try {
            viewModel(
                withdrawGate = withdrawGate,
                scope = scope,
                ioDispatcher = ioExecutor.asCoroutineDispatcher(),
            )
        } finally {}
        try {
            setProjection(enrolledProjection())
            val watch = kotlinx.coroutines.CoroutineScope(mainDispatcher).launch(Dispatchers.Unconfined) {
                vm.state.drop(1).collect { writes += Thread.currentThread().name }
            }.also { extraJobs += it }
            val issued = java.util.concurrent.CountDownLatch(1)
            kotlinx.coroutines.CoroutineScope(mainDispatcher).launch {
                vm.withdraw()
                issued.countDown()
            }.also { extraJobs += it }
            assertTrue("the tap is issued", issued.await(10, java.util.concurrent.TimeUnit.SECONDS))
            awaitTrue(10_000) { vm.state.value.pendingOperation == PendingOperation.WITHDRAW }
            withdrawGate.release.complete(Unit)
            awaitTrue(10_000) { vm.state.value.pendingOperation != PendingOperation.NONE }
            assertTrue(
                "the withdrawal must settle during the observation window",
                awaitTrue(10_000) { !vm.state.value.pending },
            )
            watch.cancel()
            assertTrue("the surface must publish state: $writes", writes.isNotEmpty())
            val offMain = writes.filterNot { it.startsWith("sharing-main") }
            assertEquals("every state write must happen on main: $writes", emptyList<String>(), offMain)
        } finally {
            scope.cancel()
            mainExecutor.shutdownNow()
            ioExecutor.shutdownNow()
        }
    }
    /**
     * ONE LOCAL TRUTH (spec, owner's Pixel consent bug): the switch renders the DURABLE choice —
     * the same record capture/upload admission reads — not the in-memory projection. Here the
     * durable choice says ENROLLED while the projection (a stale server refresh) says not-enrolled:
     * the surface must show ON, matching what admission would do.
     */
    @Test
    fun `the switch renders the durable choice, not the projection`() = runTest(mainDispatcher) {
        val vm = viewModel(
            savedChoice = {
                au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoice(
                    account = "user@example.com",
                    state = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SharingChoiceState.ENROLLED,
                    epoch = "epoch-2",
                    acceptedDisclosureVersion = EnrollmentDisclosure.VERSION,
                )
            },
        )
        setProjection(
            VoiceSharingAccountProjection(
                plan = VoiceSharingPlan.PRO,
                voiceSharingEnrolled = false,
                enrollmentEpoch = null,
                acceptedDisclosureVersion = null,
                withdrawalState = WithdrawalState.ACTIVE,
            ),
        )
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { kotlinx.coroutines.yield() }
        assertTrue(
            "the durable consent is ON, so the switch must be ON: ${vm.state.value.enrolled}",
            vm.state.value.enrolled,
        )
    }

    /** Companion control: a durable WITHDRAWN choice must show OFF even when the projection says enrolled. */
    @Test
    fun `the switch renders OFF when the durable choice is withdrawn`() = runTest(mainDispatcher) {
        val vm = viewModel(
            savedChoice = {
                au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoice(
                    account = "user@example.com",
                    state = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SharingChoiceState.WITHDRAWN,
                    epoch = null,
                    acceptedDisclosureVersion = null,
                )
            },
        )
        setProjection(enrolledProjection())
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { kotlinx.coroutines.yield() }
        assertTrue(
            "the durable consent is OFF, so the switch must be OFF: ${vm.state.value.enrolled}",
            !vm.state.value.enrolled,
        )
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
        setProjection(enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED))
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
        setProjection(enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED))
        val gate = Gate()
        val vm = viewModel(enrollGate = gate)
        vm.enroll()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertFalse("a tap is not an application", vm.state.value.enrolled)
        gate.result = VoiceSharingMutationResult.Applied(enrolledProjection())
        gate.release.complete(Unit)
        setProjection(enrolledProjection())
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue("a server-applied enrollment with a fresh enrolled projection IS enrolled", vm.state.value.enrolled)
        assertEquals("a settled success is not a lingering message", SettingsOperationStatus.NONE, vm.state.value.status)
    }
    @Test
    fun `a withdraw tap stops collection FIRST and stays stopped after a failed withdrawal`() = runTest(mainDispatcher) {
        setProjection(enrolledProjection())
        var durable: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoice? =
            au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoice(
                account = "user@example.com",
                state = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SharingChoiceState.ENROLLED,
                epoch = "epoch-2",
                acceptedDisclosureVersion = EnrollmentDisclosure.VERSION,
            )
        val gate = Gate()
        val vm = viewModel(
            withdrawGate = gate,
            recordLocalWithdrawal = {
                durable = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoice(
                    account = "user@example.com",
                    state = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SharingChoiceState.WITHDRAWN,
                    epoch = null,
                    acceptedDisclosureVersion = null,
                )
                true
            },
            savedChoice = { durable },
        )
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue("PREMISE: enrolled and active", vm.state.value.enrolled)
        vm.withdraw()
        mainDispatcher.scheduler.advanceUntilIdle()
        val stopped = vm.state.value
        assertFalse("the durable local stop must stop collection immediately on the tap", stopped.enrolled)
        gate.result = VoiceSharingMutationResult.Failure
        gate.release.complete(Unit)
        mainDispatcher.scheduler.advanceUntilIdle()
        val failed = vm.state.value
        assertFalse("a FAILED withdrawal keeps the local stop: the user decided", failed.enrolled)
        assertEquals("the failure must be surfaced honestly", SettingsOperationStatus.FAILED, failed.status)
    }



    @Test
    fun `the failure copy survives unrelated projection updates until the next action`() = runTest(mainDispatcher) {
        setProjection(enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED))
        val gate = Gate()
        val vm = viewModel(enrollGate = gate)
        vm.enroll()
        mainDispatcher.scheduler.advanceUntilIdle()
        gate.result = VoiceSharingMutationResult.Failure
        gate.release.complete(Unit)
        mainDispatcher.scheduler.advanceUntilIdle()
        assertEquals("PREMISE: the enrollment failed", SettingsOperationStatus.FAILED, vm.state.value.status)
        assertEquals("PREMISE: the failure names the enrollment", FailedOperation.ENROLL, vm.state.value.failedOperation)
        setProjection(enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED))
        mainDispatcher.scheduler.advanceUntilIdle()
        assertEquals("an unrelated update must NOT erase the failure copy", SettingsOperationStatus.FAILED, vm.state.value.status)
        assertEquals("the failure must still name the enrollment", FailedOperation.ENROLL, vm.state.value.failedOperation)
    }
    @Test
    fun `pending is labeled by its operation - enrollment pending is not withdrawal pending`() = runTest(mainDispatcher) {
        setProjection(enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED))
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
        enrollGate.result = VoiceSharingMutationResult.Applied(enrolledProjection())
        enrollGate.release.complete(Unit)
        setProjection(enrolledProjection())
        mainDispatcher.scheduler.advanceUntilIdle()
        vm.withdraw()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            "an in-flight WITHDRAWAL renders withdrawal-pending copy",
            PendingOperation.WITHDRAW,
            vm.state.value.pendingOperation,
        )
    }
    @Test
    fun `a superseded outcome does not disturb the current action's pending label`() = runTest(mainDispatcher) {
        setProjection(enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED))
        val enrollGate = Gate()
        val withdrawGate = Gate()
        val vm = viewModel(enrollGate = enrollGate, withdrawGate = withdrawGate)
        vm.enroll()
        mainDispatcher.scheduler.advanceUntilIdle()
        vm.withdraw()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertEquals("PREMISE: withdrawal B owns the pending label", PendingOperation.WITHDRAW, vm.state.value.pendingOperation)
        enrollGate.result = VoiceSharingMutationResult.Applied(enrolledProjection())
        enrollGate.release.complete(Unit)
        mainDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            "a stale outcome must NOT clear the current action's pending label",
            PendingOperation.WITHDRAW,
            vm.state.value.pendingOperation,
        )
        setProjection(enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED))
        mainDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            "the stale outcome must not have poisoned the durable pending label",
            PendingOperation.WITHDRAW,
            vm.state.value.pendingOperation,
        )
        assertTrue("the current action is still pending", vm.state.value.pending)
    }
    @Test
    fun `an account session change retires the failure presentation`() = runTest(mainDispatcher) {
        var session = 1L
        setProjection(enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED))
        val gate = Gate()
        val vm = viewModel(enrollGate = gate, ownerSession = { session })
        vm.enroll()
        mainDispatcher.scheduler.advanceUntilIdle()
        gate.result = VoiceSharingMutationResult.Failure
        gate.release.complete(Unit)
        mainDispatcher.scheduler.advanceUntilIdle()
        assertEquals("PREMISE: the failure is shown for account A", SettingsOperationStatus.FAILED, vm.state.value.status)
        session = 2L
        setProjection(
            VoiceSharingAccountProjection(
                plan = VoiceSharingPlan.FREE,
                voiceSharingEnrolled = false,
                enrollmentEpoch = null,
                acceptedDisclosureVersion = null,
                withdrawalState = WithdrawalState.NOT_ENROLLED,
            ),
        )
        mainDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            "account B must not see account A's failure copy",
            SettingsOperationStatus.NONE,
            vm.state.value.status,
        )
        assertEquals(FailedOperation.NONE, vm.state.value.failedOperation)
    }

    @Test
    fun `an account switch does not leave the stale action pending on the new account`() = runTest(mainDispatcher) {
        var session = 1L
        setProjection(enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED))
        val gate = Gate()
        val vm = viewModel(enrollGate = gate, ownerSession = { session })
        vm.enroll()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue("PREMISE: account A's enrollment is pending", vm.state.value.pending)
        session = 2L
        setProjection(
            VoiceSharingAccountProjection(
                plan = VoiceSharingPlan.FREE,
                voiceSharingEnrolled = false,
                enrollmentEpoch = null,
                acceptedDisclosureVersion = null,
                withdrawalState = WithdrawalState.NOT_ENROLLED,
            ),
        )
        mainDispatcher.scheduler.advanceUntilIdle()
        gate.result = VoiceSharingMutationResult.Applied(enrolledProjection())
        gate.release.complete(Unit)
        mainDispatcher.scheduler.advanceUntilIdle()
        assertFalse(
            "account B must not inherit account A's pending state",
            vm.state.value.pending,
        )
        assertEquals("no stale pending label either", PendingOperation.NONE, vm.state.value.pendingOperation)
        assertTrue("the setting must remain available on account B", vm.state.value.settingAvailable)
    }
    @Test
    fun `a tap issued under a session that changed before it runs never reaches the new account`() = runTest(mainDispatcher) {
        var session = 1L
        setProjection(enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED))
        var enrollIssues = 0
        val gate = Gate()
        val vm = viewModel(enrollGate = gate, ownerSession = { session }, onEnrollIssue = { enrollIssues++ })
        vm.enroll()
        session = 2L
        setProjection(enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED))
        mainDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            "the dead session's tap must NOT issue a mutation against the new account",
            0,
            enrollIssues,
        )
        assertFalse("the dead tap leaves no pending state on the new account", vm.state.value.pending)
        assertEquals(PendingOperation.NONE, vm.state.value.pendingOperation)
    }


    @Test
    fun `deletion is honest - pending is not success and failure stays visible`() = runTest(mainDispatcher) {
        setProjection(enrolledProjection())
        lateinit var vm: VoiceSharingSettingsViewModel
        val controller = EnrollmentController(
            enrollOperation = { _, _ -> enrollGate.await() },
            withdrawOperation = { _ -> withdrawGate.await() },
            deleteOperation = { deleteGate.await() },
            clearLocalPending = {},
            ownerSession = { 1L },
        )
        vm = VoiceSharingSettingsViewModel(
            projection = projection,
            enrollmentController = controller,
            ownerSession = { 1L },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + mainDispatcher).also { scopes += it },
        )
        mainDispatcher.scheduler.advanceUntilIdle()
        vm.requestDelete()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue("PREMISE: the confirmation dialog is open", vm.state.value.deleteConfirmationOpen)
        vm.confirmDelete()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue("deletion in flight shows the REQUEST state", vm.state.value.deletionRequested)
        assertFalse("pending deletion is not settled success", vm.state.value.deletionFailed)
        deleteGate.release.complete(false)
        mainDispatcher.scheduler.advanceUntilIdle()
        assertFalse("a failed deletion is no longer in flight", vm.state.value.deletionRequested)
        assertTrue("a failed deletion stays visible until the next action", vm.state.value.deletionFailed)
    }
    @Test
    fun `a session switch retires deletion presentation`() = runTest(mainDispatcher) {
        setProjection(enrolledProjection())
        var session = 1L
        lateinit var vm: VoiceSharingSettingsViewModel
        val controller = EnrollmentController(
            enrollOperation = { _, _ -> enrollGate.await() },
            withdrawOperation = { _ -> withdrawGate.await() },
            deleteOperation = { deleteGate.await() },
            clearLocalPending = {},
            ownerSession = { session },
        )
        vm = VoiceSharingSettingsViewModel(
            projection = projection,
            enrollmentController = controller,
            ownerSession = { session },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + mainDispatcher).also { scopes += it },
        )
        mainDispatcher.scheduler.advanceUntilIdle()
        vm.requestDelete()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue("PREMISE: the confirmation dialog is open", vm.state.value.deleteConfirmationOpen)
        vm.confirmDelete()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue("PREMISE: deletion request in flight", vm.state.value.deletionRequested)
        session = 2L
        setProjection(enrolledProjection().copy(enrollmentEpoch = "epoch-B"))
        mainDispatcher.scheduler.advanceUntilIdle()
        assertFalse("account B must not see account A's deletion state", vm.state.value.deletionRequested)
        assertFalse(vm.state.value.deletionFailed)
    }
    @Test
    fun `the shell reports whether an account projection has been observed`() = runTest(mainDispatcher) {
        val vm = viewModel()
        assertFalse("no projection yet: the section cannot show a truthful switch", vm.state.value.projectionObserved)
        setProjection(enrolledProjection())
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.projectionObserved)
    }
    @Test
    fun `a deletion in flight is distinct from confirmed deletion`() = runTest(mainDispatcher) {
        setProjection(enrolledProjection())
        lateinit var vm: VoiceSharingSettingsViewModel
        val controller = EnrollmentController(
            enrollOperation = { _, _ -> enrollGate.await() },
            withdrawOperation = { _ -> withdrawGate.await() },
            deleteOperation = { deleteGate.await() },
            clearLocalPending = {},
            ownerSession = { 1L },
        )
        vm = VoiceSharingSettingsViewModel(
            projection = projection,
            enrollmentController = controller,
            ownerSession = { 1L },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + mainDispatcher).also { scopes += it },
        )
        mainDispatcher.scheduler.advanceUntilIdle()
        vm.requestDelete()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue("PREMISE: the confirmation dialog is open", vm.state.value.deleteConfirmationOpen)
        vm.confirmDelete()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue("the tap is the REQUEST in flight", vm.state.value.deletionRequested)
        deleteGate.release.complete(true)
        mainDispatcher.scheduler.advanceUntilIdle()
        assertFalse("the confirmed deletion ends the request-in-flight state", vm.state.value.deletionRequested)
        assertFalse(vm.state.value.deletionFailed)
    }
    @Test
    fun `a deletion completing after a session switch mutates nothing`() = runTest(mainDispatcher) {
        setProjection(enrolledProjection())
        var session = 1L
        lateinit var vm: VoiceSharingSettingsViewModel
        val controller = EnrollmentController(
            enrollOperation = { _, _ -> enrollGate.await() },
            withdrawOperation = { _ -> withdrawGate.await() },
            deleteOperation = { deleteGate.await() },
            clearLocalPending = {},
            ownerSession = { session },
        )
        vm = VoiceSharingSettingsViewModel(
            projection = projection,
            enrollmentController = controller,
            ownerSession = { session },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + mainDispatcher).also { scopes += it },
        )
        mainDispatcher.scheduler.advanceUntilIdle()
        vm.requestDelete()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue("PREMISE: the confirmation dialog is open", vm.state.value.deleteConfirmationOpen)
        vm.confirmDelete()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue("PREMISE: deletion request in flight", vm.state.value.deletionRequested)
        session = 2L
        setProjection(enrolledProjection().copy(enrollmentEpoch = "epoch-B"))
        mainDispatcher.scheduler.advanceUntilIdle()
        deleteGate.release.complete(true)
        mainDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.state.value.deletionRequested)
        assertFalse(vm.state.value.deletionFailed)
    }
    @Test
    fun `a session end drops the projection, so the next account never sees the previous one`() = runTest(mainDispatcher) {
        val vm = viewModel()
        assertFalse("PREMISE: nothing observed yet", vm.state.value.projectionObserved)
        setProjection(enrolledProjection())
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.projectionObserved)
        assertTrue(vm.state.value.enrolled)
        setProjection(null)
        mainDispatcher.scheduler.advanceUntilIdle()
        assertFalse("no projection means the section cannot claim membership", vm.state.value.projectionObserved)
        assertFalse(vm.state.value.enrolled)
    }
    @Test
    fun `deleting previously accepted data stays available after a withdrawal with an epoch`() = runTest(mainDispatcher) {
        val withdrawn = enrolledProjection().copy(
            voiceSharingEnrolled = false,
            withdrawalState = WithdrawalState.WITHDRAWN,
            enrollmentEpoch = "epoch-1",
        )
        setProjection(withdrawn)
        mainDispatcher.scheduler.advanceUntilIdle()
        val vm = viewModel()
        assertFalse("PREMISE: withdrawn", vm.state.value.enrolled)
        assertTrue(
            "a withdrawal clears membership, not the right to delete accepted data",
            vm.state.value.deleteAvailable,
        )
    }
    @Test
    fun `deletion is unavailable without membership or a recorded epoch`() = runTest(mainDispatcher) {
        setProjection(enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED, enrollmentEpoch = null))
        mainDispatcher.scheduler.advanceUntilIdle()
        val vm = viewModel()
        assertFalse(vm.state.value.deleteAvailable)
    }
    @Test
    fun `a confirmation opened under one session is rejected when confirmed under another`() = runTest(mainDispatcher) {
        var session = 1L
        lateinit var vm: VoiceSharingSettingsViewModel
        var deletions = 0
        val controller = EnrollmentController(
            enrollOperation = { _, _ -> enrollGate.await() },
            withdrawOperation = { _ -> withdrawGate.await() },
            deleteOperation = {
                deletions++
                deleteGate.await()
            },
            clearLocalPending = {},
            ownerSession = { session },
        )
        vm = VoiceSharingSettingsViewModel(
            projection = projection,
            enrollmentController = controller,
            ownerSession = { session },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + mainDispatcher).also { scopes += it },
        )
        mainDispatcher.scheduler.advanceUntilIdle()
        vm.requestDelete()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue("PREMISE: confirmation open under A", vm.state.value.deleteConfirmationOpen)
        session = 2L
        setProjection(enrolledProjection().copy(enrollmentEpoch = "epoch-B"))
        mainDispatcher.scheduler.advanceUntilIdle()
        vm.confirmDelete()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertEquals("B must not become the tap owner of A's confirmation", 0, deletions)
        assertFalse("the stale confirmation is dismissed, not silently re-owned", vm.state.value.deleteConfirmationOpen)
        session = 1L
        setProjection(enrolledProjection())
        mainDispatcher.scheduler.advanceUntilIdle()
        vm.requestDelete()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue("PREMISE: confirmation open under A", vm.state.value.deleteConfirmationOpen)
        session = 3L
        vm.confirmDelete()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertEquals("a no-emission session move must also reject the confirmation", 0, deletions)
        assertFalse(vm.state.value.deleteConfirmationOpen)
    }
    @Test
    fun `a confirmation opened under the new session before the collector runs survives the retire`() = runTest(mainDispatcher) {
        var session = 1L
        lateinit var vm: VoiceSharingSettingsViewModel
        val controller = EnrollmentController(
            enrollOperation = { _, _ -> enrollGate.await() },
            withdrawOperation = { _ -> withdrawGate.await() },
            deleteOperation = { deleteGate.await() },
            clearLocalPending = {},
            ownerSession = { session },
        )
        vm = VoiceSharingSettingsViewModel(
            projection = projection,
            enrollmentController = controller,
            ownerSession = { session },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + mainDispatcher).also { scopes += it },
        )
        mainDispatcher.scheduler.advanceUntilIdle()
        session = 2L
        vm.requestDelete()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue(
            "B's own confirmation must survive the queued retire",
            vm.state.value.deleteConfirmationOpen,
        )
        vm.confirmDelete()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue(
            "B's deletion request must survive the queued retire",
            vm.state.value.deletionRequested,
        )
    }
    @Test
    fun `a cross-operation action retires the old session's stale presentation before its own`() = runTest(mainDispatcher) {
        var session = 1L
        lateinit var vm: VoiceSharingSettingsViewModel
        val controller = EnrollmentController(
            enrollOperation = { _, _ -> enrollGate.await() },
            withdrawOperation = { _ -> withdrawGate.await() },
            deleteOperation = { deleteGate.await() },
            clearLocalPending = {},
            ownerSession = { session },
        )
        vm = VoiceSharingSettingsViewModel(
            projection = projection,
            enrollmentController = controller,
            ownerSession = { session },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + mainDispatcher).also { scopes += it },
        )
        mainDispatcher.scheduler.advanceUntilIdle()
        setProjection(enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED))
        vm.enroll()
        mainDispatcher.scheduler.advanceUntilIdle()
        enrollGate.result = VoiceSharingMutationResult.Failure
        enrollGate.release.complete(Unit)
        mainDispatcher.scheduler.advanceUntilIdle()
        assertEquals("PREMISE: A's failure is visible", SettingsOperationStatus.FAILED, vm.state.value.status)
        session = 2L
        vm.requestDelete()
        vm.confirmDelete()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertEquals("B's screen must not carry A's failure", SettingsOperationStatus.NONE, vm.state.value.status)
        assertEquals(FailedOperation.NONE, vm.state.value.failedOperation)
        assertTrue("PREMISE: B's deletion is in flight", vm.state.value.deletionRequested)
        session = 1L
        setProjection(enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED))
        vm = VoiceSharingSettingsViewModel(
            projection = projection,
            enrollmentController = EnrollmentController(
                enrollOperation = { _, _ -> enrollGate2.await() },
                withdrawOperation = { _ -> withdrawGate2.await() },
                deleteOperation = { deleteGate2.await() },
                clearLocalPending = {},
                ownerSession = { session },
            ),
            ownerSession = { session },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + mainDispatcher).also { scopes += it },
        )
        mainDispatcher.scheduler.advanceUntilIdle()
        vm.requestDelete()
        vm.confirmDelete()
        mainDispatcher.scheduler.advanceUntilIdle()
        deleteGate2.release.complete(false)
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue("PREMISE: A's deletion failure is visible", vm.state.value.deletionFailed)
        session = 2L
        vm.enroll()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertFalse("B's enroll must not preserve A's deletion failure", vm.state.value.deletionFailed)
        assertFalse(vm.state.value.deletionRequested)
    }
    private val enrollGate2 = Gate()
    private val withdrawGate2 = Gate()
    private val deleteGate2 = ReceiptGate()
    @Test
    fun `a retired stale tap does not clear a newer action's pending presentation`() = runTest(mainDispatcher) {
        var session = 1L
        setProjection(enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED))
        val enrollGateA = Gate()
        val enrollGateB = Gate()
        var issues = 0
        val vm = viewModel(enrollGate = enrollGateA, withdrawGate = enrollGateB, ownerSession = { session }, onEnrollIssue = { issues++ })
        vm.enroll()
        session = 2L
        setProjection(enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED))
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
    @Test
    fun `a withdraw tap cancels a request that is already being sent`() = runTest(mainDispatcher) {
        setProjection(enrolledProjection())
        var cancelled = 0
        val vm = viewModel(cancelInFlightUpload = { cancelled++ })
        vm.withdraw()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertEquals("withdrawing must reach a request already on the wire", 1, cancelled)
    }
    private class GatedDispatcher : CoroutineDispatcher() {
        private val queue = mutableListOf<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue += block
        }
        fun runOldest() {
            check(queue.isNotEmpty()) { "no gated IO task is waiting" }
            queue.removeAt(0).run()
        }
        fun runNewest() {
            check(queue.isNotEmpty()) { "no gated IO task is waiting" }
            queue.removeAt(queue.lastIndex).run()
        }
        val hasTasks: Boolean get() = queue.isNotEmpty()
    }
    /**
     * ONE LOCAL TRUTH, the owner's Pixel bug in test form: an unenrolling PROJECTION alone must not
     * flip the switch off while the durable choice still says ENROLLED — that mismatch is exactly
     * what let capture run "while the switch showed off". The switch follows the durable record:
     * it turns off only when the record itself is withdrawn, never from a projection render.
     */
    @Test
    fun `an unenrolling projection alone does not turn the switch off while the durable choice stands`() = runTest(mainDispatcher) {
        val io = GatedDispatcher()
        setProjection(enrolledProjection())
        var durable: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoice? =
            au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoice(
                account = "user@example.com",
                state = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SharingChoiceState.ENROLLED,
                epoch = "epoch-2",
                acceptedDisclosureVersion = EnrollmentDisclosure.VERSION,
            )
        lateinit var vm: VoiceSharingSettingsViewModel
        val controller = EnrollmentController(
            enrollOperation = { _, _ -> enrollGate.await() },
            withdrawOperation = { _ -> withdrawGate.await() },
            deleteOperation = { deleteGate.await() },
            clearLocalPending = {},
            ownerSession = { 1L },
        )
        vm = VoiceSharingSettingsViewModel(
            projection = projection,
            enrollmentController = controller,
            ownerSession = { 1L },
            ioDispatcher = io,
            savedChoice = { durable },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + mainDispatcher).also { scopes += it },
        )
        mainDispatcher.scheduler.advanceUntilIdle()
        while (io.hasTasks) {
            io.runOldest()
            mainDispatcher.scheduler.runCurrent()
        }
        assertTrue("PREMISE: enrolled under an enrolled projection and a durable consent", vm.state.value.enrolled)
        setProjection(enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED))
        mainDispatcher.scheduler.runCurrent()
        while (io.hasTasks) {
            io.runOldest()
            mainDispatcher.scheduler.runCurrent()
        }
        assertTrue(
            "a stale not-enrolled projection must not turn the switch off while the durable choice says enrolled",
            vm.state.value.enrolled,
        )
        durable = null // the server's answer updates the durable record (apply persists it)
        // A new server answer is what triggers the next fenced read of the record.
        setProjection(enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED, enrollmentEpoch = "epoch-stale-refresh"))
        mainDispatcher.scheduler.advanceUntilIdle()
        while (io.hasTasks) {
            io.runOldest()
            mainDispatcher.scheduler.runCurrent()
        }
        assertFalse(
            "once the durable record no longer holds consent, the switch is off",
            vm.state.value.enrolled,
        )
    }


}
