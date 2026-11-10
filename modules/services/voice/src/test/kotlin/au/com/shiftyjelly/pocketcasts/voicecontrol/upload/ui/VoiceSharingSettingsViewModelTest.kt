package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.ui

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingMutationResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingPlan
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.WithdrawalState
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentController
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentDisclosure
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.LocalWithdrawalIntentStore
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.WithdrawalIntentPersistence
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

/**
 * THE SETTINGS SHELL'S HONESTY CONTRACT (plan Task 7 UI bullet). The screen composes the LIVE
 * the account projection and the local withdrawal intent into one UI state, and
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

    private val projection = MutableStateFlow<VoiceSharingAccountProjection?>(null)

    /** Publishes the account's projection (the surface reads it as ordinary account state). */
    @Suppress("UNUSED_PARAMETER")
    private fun setProjection(session: Long, p: VoiceSharingAccountProjection?) {
        projection.value = p
    }

    private fun setProjection(p: VoiceSharingAccountProjection?) = setProjection(1L, p)
    private val enrollGate = Gate()
    private val withdrawGate = Gate()

    /** Delete resolves to a nullable DeletionReceipt, not a mutation result. */
    private class ReceiptGate {
        val release = CompletableDeferred<Boolean>()
        suspend fun await(): Boolean = release.await()
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

        /** Notified for every write, so a control can observe where the write was reached from. */
        var onWrite: ((Boolean) -> Unit)? = null

        /** Notified for every read, so a control can observe where the read was reached from. */
        var onRead: (() -> Unit)? = null

        override fun read(): Boolean {
            onRead?.invoke()
            return stopped
        }

        override fun write(requested: Boolean): Boolean {
            onWrite?.invoke(requested)
            writes += requested
            if (!requested && failClear) return false
            stopped = requested
            return true
        }
    }

    private fun enrolledProjection() = VoiceSharingAccountProjection(
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
        cancelInFlightUpload: () -> Unit = {},
        // Defaults to the test scheduler so the existing controls stay deterministic when they
        // pump; the main-thread control passes a real IO dispatcher to observe the thread.
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = mainDispatcher,
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
            deleteOperation = { false },
            clearLocalPending = {},
            ownerSession = ownerSession,
        )
        return VoiceSharingSettingsViewModel(
            projection = projection,
            enrollmentController = controller,
            withdrawalIntent = LocalWithdrawalIntentStore(persistence),
            ownerSession = ownerSession,
            scope = effective,
            cancelInFlightUpload = cancelInFlightUpload,
            ioDispatcher = ioDispatcher,
        )
    }

    @Test
    fun `every storage touch on the withdraw path runs on the injected io dispatcher`() = runTest(mainDispatcher) {
        // This control builds its own surface, so it also owns a private dispatcher: parking its
        // coroutines on the shared test Main would let them run inside a later test.
        // The claim is a DISPATCHER boundary, not a thread name. A thread name cannot see it,
        // because a coroutine runs on its own thread even with no dispatcher switch, so the
        // control marks each block the injected dispatcher runs and records, per touch, whether
        // the touch happened inside one. A count would not do: nested dispatches make later
        // inline work look crossed.
        val touches = mutableListOf<String>()
        val crossed = ThreadLocal.withInitial { false }
        fun note(what: String) {
            touches += if (crossed.get() == true) what else "INLINE:$what"
        }

        // Own dispatcher: parking this surface's coroutines on the shared test Main would let
        // them run inside a later test.
        val privateDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val marker = RecordingPersistence()
        marker.onRead = { note("marker-read") }
        marker.onWrite = { requested -> note(if (requested) "marker-stop" else "marker-clear") }
        var purges = 0
        val controller = EnrollmentController(
            enrollOperation = { VoiceSharingMutationResult.Failure },
            // A failure drives consume()'s Failed branch, which latches through the store.
            withdrawOperation = { VoiceSharingMutationResult.Failure },
            deleteOperation = { false },
            clearLocalPending = {
                purges++
                note("queue-purge")
            },
            ownerSession = { 1L },
        )
        val io = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                privateDispatcher.dispatch(
                    context,
                    Runnable {
                        val previous = crossed.get()
                        crossed.set(true)
                        try {
                            block.run()
                        } finally {
                            crossed.set(previous)
                        }
                    },
                )
            }
        }
        val vm = VoiceSharingSettingsViewModel(
            projection = projection,
            enrollmentController = controller,
            withdrawalIntent = LocalWithdrawalIntentStore(marker),
            ownerSession = { 1L },
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + privateDispatcher,
            ).also { scopes += it },
            ioDispatcher = io,
        )

        // Construction and the projection collector each render once, and a render reads the
        // marker. Those are the initial draw, not the tap: settle them, then measure the tap.
        setProjection(enrolledProjection())
        advanceUntilIdle()
        touches.clear()

        vm.withdraw()
        advanceUntilIdle()

        // The surface re-renders on every controller-state emission, and that render reads the
        // marker on the UI scope: it publishes state, it does not start the work. This control
        // pins the withdraw path, so it measures the touches the tap itself causes.
        val tapPath = touches.subList(0, touches.indexOf("queue-purge") + 1)
        val inline = tapPath.filter { it.startsWith("INLINE:") }
        assertEquals("no storage touch on the withdraw path may run inline: $touches", emptyList<String>(), inline)
        // Every path the reviewer named must actually be exercised, or the control proves
        // nothing about it: the marker read, the queue purge, the stop, and the outcome latch.
        assertTrue("the marker read must be on IO: $touches", touches.any { it.startsWith("marker-read") })
        assertTrue("the queue purge must be on IO: $touches", touches.any { it.startsWith("queue-purge") })
        assertTrue("the stop must be on IO: $touches", touches.any { it.startsWith("marker-stop") })
        assertTrue(
            "the failure latch must be on IO: $touches",
            touches.count { it.startsWith("marker-clear") || it.startsWith("marker-stop") } >= 2,
        )
        assertEquals("the purge runs once", 1, purges)
    }

    /** Bounded poll on real dispatchers: true as soon as [condition] holds. */
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
        // Observe the WRITE itself, inline with publication. A collector dispatched onto main
        // only proves the collector's thread, so the record has to happen where the value is set.
        val mainExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "sharing-main").apply { isDaemon = true }
        }
        val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "sharing-io").apply { isDaemon = true }
        }
        val mainDispatcher = mainExecutor.asCoroutineDispatcher()
        val writes = java.util.Collections.synchronizedList(mutableListOf<String>())
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + mainDispatcher)
        // The withdrawal needs a result to settle on, or it parks forever and nothing is observed.
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
            // Observe the state flow itself. Each emission is recorded on the thread that made
            // it, so this is inline with publication - not a snapshot taken later from elsewhere.
            // `drop(1)`: a StateFlow replays its current value to a new collector, and that replay
            // happens on the subscribing thread - it is not a write.
            val watch = kotlinx.coroutines.CoroutineScope(mainDispatcher).launch(Dispatchers.Unconfined) {
                vm.state.drop(1).collect { writes += Thread.currentThread().name }
            }
            val issued = java.util.concurrent.CountDownLatch(1)
            kotlinx.coroutines.CoroutineScope(mainDispatcher).launch {
                vm.withdraw()
                issued.countDown()
            }
            assertTrue("the tap is issued", issued.await(10, java.util.concurrent.TimeUnit.SECONDS))
            awaitTrue(10_000) { vm.state.value.pendingOperation == PendingOperation.WITHDRAW }
            withdrawGate.release.complete(Unit)
            // Wait for the withdrawal to reach its SETTLED presentation, not for a fixed window:
            // a sleep would record whatever happened to land and could miss the outcome publish.
            awaitTrue(10_000) { vm.state.value.pendingOperation != PendingOperation.NONE }
            assertTrue(
                "the withdrawal must settle during the observation window",
                awaitTrue(10_000) { !vm.state.value.pending },
            )
            watch.cancel()

            assertTrue("the surface must publish state: $writes", writes.isNotEmpty())
            // `Dispatchers.Unconfined` resumes in the publishing coroutine, so the name recorded
            // here is the publisher's thread. Anything not on main means the write escaped.
            val offMain = writes.filterNot { it.startsWith("sharing-main") }
            assertEquals("every state write must happen on main: $writes", emptyList<String>(), offMain)
        } finally {
            scope.cancel()
            mainExecutor.shutdownNow()
            ioExecutor.shutdownNow()
        }
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
        // The tap alone must not flip the toggle: only the server projection does.
        assertFalse("a tap is not an application", vm.state.value.enrolled)

        gate.result = VoiceSharingMutationResult.Applied(enrolledProjection())
        gate.release.complete(Unit)
        // The applied result carries the server projection; the refreshed snapshot agrees.
        setProjection(enrolledProjection())
        mainDispatcher.scheduler.advanceUntilIdle()

        assertTrue("a server-applied enrollment with a fresh enrolled projection IS enrolled", vm.state.value.enrolled)
        assertEquals("a settled success is not a lingering message", SettingsOperationStatus.NONE, vm.state.value.status)
    }

    @Test
    fun `a withdraw tap stops collection FIRST and stays stopped after a failed withdrawal`() = runTest(mainDispatcher) {
        setProjection(enrolledProjection())
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

    // ---: re-enrollment must release the durable stop through the outcome fence.

    @Test
    fun `a successful re-enrollment releases the durable local stop`() = runTest(mainDispatcher) {
        // PREMISE: the account re-enrolled server-side, but a durable withdrawal intent from an
        // earlier opt-out still holds collection stopped. This is the state the current controls
        // never start from — and the one where the stop must finally be released.
        val persistence = RecordingPersistence(stopped = true)
        setProjection(enrolledProjection())
        val gate = Gate()
        val vm = viewModel(enrollGate = gate, persistence = persistence)
        assertFalse("PREMISE: the durable stop holds even with an enrolled projection", vm.state.value.enrolled)

        vm.enroll()
        mainDispatcher.scheduler.advanceUntilIdle()
        gate.result = VoiceSharingMutationResult.Applied(enrolledProjection())
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
        setProjection(enrolledProjection())
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

    // ---: failure identity is durable UI state, not transient render input.

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

        // An unrelated downstream emission (account projection refresh) re-renders the screen.
        setProjection(enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED))
        mainDispatcher.scheduler.advanceUntilIdle()

        assertEquals("an unrelated update must NOT erase the failure copy", SettingsOperationStatus.FAILED, vm.state.value.status)
        assertEquals("the failure must still name the enrollment", FailedOperation.ENROLL, vm.state.value.failedOperation)
    }

    // ---: pending is labeled by ITS operation, not a hard-coded withdrawal.

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

    // ---: a stale outcome must not mutate the CURRENT action's presentation.

    @Test
    fun `a superseded outcome does not disturb the current action's pending label`() = runTest(mainDispatcher) {
        setProjection(enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED))
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
        enrollGate.result = VoiceSharingMutationResult.Applied(enrolledProjection())
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
        setProjection(enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED, membershipRevision = "rev-mid"))
||||||| /tmp/mfb
        // One with an unrelated projection emission and assert again (timeline-probed: the stale
        // Consume settles at advance #3, this render at #4 exposes any poison).
        setProjection(enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED, membershipRevision = "rev-mid"))
        setProjection(enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED))
        mainDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            "the stale outcome must not have poisoned the durable pending label",
            PendingOperation.WITHDRAW,
            vm.state.value.pendingOperation,
        )
        assertTrue("the current action is still pending", vm.state.value.pending)
    }

    // --- Reviewer a2136dd9: the failure presentation retires at the OWNER-SESSION boundary.
||||||| /tmp/mfb
    // --- Reviewer a2136dd9: the failure presentation retires at the owner-session boundary.

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

        // A DIFFERENT account signs in; the projection switches.
        session = 2L
        // B's projection differs from A's: an equal value is not an emission, so it would not
        // report the new session at all.
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

    // ---: a failed LOCAL stop release is distinct from a server rejection.

    @Test
    fun `a failed local stop release surfaces distinctly and keeps the stop`() = runTest(mainDispatcher) {
        setProjection(enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED))
        val persistence = RecordingPersistence(stopped = true, failClear = true)
        val gate = Gate()
        val vm = viewModel(enrollGate = gate, persistence = persistence)

        vm.enroll()
        mainDispatcher.scheduler.advanceUntilIdle()
        gate.result = VoiceSharingMutationResult.Applied(enrolledProjection())
        gate.release.complete(Unit)
        mainDispatcher.scheduler.advanceUntilIdle()

        assertTrue("the stop survives: durable clearing failed, collection stays paused", persistence.stopped)
        assertEquals(
            "the local release failure is surfaced, not rendered as a settled success",
            SettingsOperationStatus.RELEASE_FAILED,
            vm.state.value.status,
        )
        // The operation SETTLED - a failed local release must not leave it pending forever.
        assertFalse("a settled operation is not pending", vm.state.value.pending)
        assertEquals(PendingOperation.NONE, vm.state.value.pendingOperation)
    }

    // ---: a session switch must not leave the stale action pending on the NEW account.

    @Test
    fun `an account switch does not leave the stale action pending on the new account`() = runTest(mainDispatcher) {
        var session = 1L
        setProjection(enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED))
        val gate = Gate()
        val vm = viewModel(enrollGate = gate, ownerSession = { session })

        vm.enroll()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue("PREMISE: account A's enrollment is pending", vm.state.value.pending)

        // Account B signs in while A's enrollment is still in flight. B's projection must differ
        // from A's, because an equal value is not an emission and so would report no new session.
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

        // A finishes now: the controller returns Superseded WITHOUT touching its own state.
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

    // ---: a queued tap from a dead session must never issue against the NEW account.

    @Test
    fun `a tap issued under a session that changed before it runs never reaches the new account`() = runTest(mainDispatcher) {
        var session = 1L
        setProjection(enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED))
        var enrollIssues = 0
        val gate = Gate()
        val vm = viewModel(enrollGate = gate, ownerSession = { session }, onEnrollIssue = { enrollIssues++ })

        // The tap happens under session 1 but the dispatcher has not run the coroutine yet.
        vm.enroll()

        // The account switches BEFORE the queued action ever runs.
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

    // --- Binding the reachable Settings section to this ViewModel (spec directive): the durable
    // stop side effects the fragment performed MUST move with the VM, not silently drop.

    @Test
    fun `an applied withdrawal acknowledges the local intent inside the fence`() = runTest(mainDispatcher) {
        setProjection(enrolledProjection())
        val gate = Gate()
        val vm = viewModel(withdrawGate = gate)
        assertTrue("PREMISE: enrolled and active", vm.state.value.enrolled)

        vm.withdraw()
        mainDispatcher.scheduler.advanceUntilIdle()
        gate.result = VoiceSharingMutationResult.Applied(
            enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.WITHDRAWN),
        )
        gate.release.complete(Unit)
        mainDispatcher.scheduler.advanceUntilIdle()
        setProjection(enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.WITHDRAWN))
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
        setProjection(enrolledProjection())
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
        setProjection(enrolledProjection())
        lateinit var vm: VoiceSharingSettingsViewModel
        val controller = EnrollmentController(
            enrollOperation = { enrollGate.await() },
            withdrawOperation = { withdrawGate.await() },
            deleteOperation = { deleteGate.await() },
            clearLocalPending = {},
            ownerSession = { 1L },
        )
        vm = VoiceSharingSettingsViewModel(
            projection = projection,
            enrollmentController = controller,
            withdrawalIntent = LocalWithdrawalIntentStore(persistenceFlag),
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

        // An unconfirmed deletion is the controller's Failed path.
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
            enrollOperation = { enrollGate.await() },
            withdrawOperation = { withdrawGate.await() },
            deleteOperation = { deleteGate.await() },
            clearLocalPending = {},
            ownerSession = { session },
        )
        vm = VoiceSharingSettingsViewModel(
            projection = projection,
            enrollmentController = controller,
            withdrawalIntent = LocalWithdrawalIntentStore(persistenceFlag),
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

        // Account switch: a fresh projection for the new session emits through the collector.
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

    // ---: deletion lifecycle, conflict keeps the stop, current-session projection.

    @Test
    fun `a deletion in flight is distinct from confirmed deletion`() = runTest(mainDispatcher) {
        setProjection(enrolledProjection())
        lateinit var vm: VoiceSharingSettingsViewModel
        val controller = EnrollmentController(
            enrollOperation = { enrollGate.await() },
            withdrawOperation = { withdrawGate.await() },
            deleteOperation = { deleteGate.await() },
            clearLocalPending = {},
            ownerSession = { 1L },
        )
        vm = VoiceSharingSettingsViewModel(
            projection = projection,
            enrollmentController = controller,
            withdrawalIntent = LocalWithdrawalIntentStore(persistenceFlag),
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
        // Deletion is synchronous, so a confirmed answer is DONE: nothing is left processing.
        assertFalse(vm.state.value.deletionFailed)
    }

    @Test
    fun `a deletion completing after a session switch mutates nothing`() = runTest(mainDispatcher) {
        setProjection(enrolledProjection())
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
            projection = projection,
            enrollmentController = controller,
            withdrawalIntent = LocalWithdrawalIntentStore(persistenceFlag),
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

        // Session switch, THEN A's deletion completes.
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

        // The account boundary's session end (VoiceSharingAccountSessionCoordinator.onSessionEnded)
        // clears the state; the screen follows the flow and shows nothing rather than A's membership.
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
            enrollOperation = { enrollGate.await() },
            withdrawOperation = { withdrawGate.await() },
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
            withdrawalIntent = LocalWithdrawalIntentStore(persistenceFlag),
            ownerSession = { session },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + mainDispatcher).also { scopes += it },
        )
        mainDispatcher.scheduler.advanceUntilIdle()

        vm.requestDelete()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue("PREMISE: confirmation open under A", vm.state.value.deleteConfirmationOpen)

        // Account switch, THEN the user confirms the still-open dialog.
        session = 2L
        setProjection(enrolledProjection().copy(enrollmentEpoch = "epoch-B"))
        mainDispatcher.scheduler.advanceUntilIdle()
        vm.confirmDelete()
        mainDispatcher.scheduler.advanceUntilIdle()

        assertEquals("B must not become the tap owner of A's confirmation", 0, deletions)
        assertFalse("the stale confirmation is dismissed, not silently re-owned", vm.state.value.deleteConfirmationOpen)

        // Second path: the session moves WITHOUT any flow emission (the authority snapshot is
        // read directly, asynchronously). The dialog is still open under the stale session when
        // the user confirms - the confirm-time authority check is the only guard.
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
            enrollOperation = { enrollGate.await() },
            withdrawOperation = { withdrawGate.await() },
            deleteOperation = { deleteGate.await() },
            clearLocalPending = {},
            ownerSession = { session },
        )
        vm = VoiceSharingSettingsViewModel(
            projection = projection,
            enrollmentController = controller,
            withdrawalIntent = LocalWithdrawalIntentStore(persistenceFlag),
            ownerSession = { session },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + mainDispatcher).also { scopes += it },
        )
        mainDispatcher.scheduler.advanceUntilIdle()

        // A→B switch (no emission yet), THEN B opens a confirmation before the queued
        // collector runs. The collector's retire must adopt B, not close B's dialog.
        session = 2L
        vm.requestDelete()
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue(
            "B's own confirmation must survive the queued retire",
            vm.state.value.deleteConfirmationOpen,
        )

        // Same ordering for the deletion-in-flight state after confirmation.
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
            enrollOperation = { enrollGate.await() },
            withdrawOperation = { withdrawGate.await() },
            deleteOperation = { deleteGate.await() },
            clearLocalPending = {},
            ownerSession = { session },
        )
        vm = VoiceSharingSettingsViewModel(
            projection = projection,
            enrollmentController = controller,
            withdrawalIntent = LocalWithdrawalIntentStore(persistenceFlag),
            ownerSession = { session },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + mainDispatcher).also { scopes += it },
        )
        mainDispatcher.scheduler.advanceUntilIdle()

        // A's membership failure is left on screen.
        setProjection(enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED))
        vm.enroll()
        mainDispatcher.scheduler.advanceUntilIdle()
        enrollGate.result = VoiceSharingMutationResult.Failure
        enrollGate.release.complete(Unit)
        mainDispatcher.scheduler.advanceUntilIdle()
        assertEquals("PREMISE: A's failure is visible", SettingsOperationStatus.FAILED, vm.state.value.status)

        // Switch to B; B runs a DIFFERENT operation before the collector runs.
        session = 2L
        vm.requestDelete()
        vm.confirmDelete()
        mainDispatcher.scheduler.advanceUntilIdle()

        assertEquals("B's screen must not carry A's failure", SettingsOperationStatus.NONE, vm.state.value.status)
        assertEquals(FailedOperation.NONE, vm.state.value.failedOperation)
        assertTrue("PREMISE: B's deletion is in flight", vm.state.value.deletionRequested)

        // Reverse direction: A leaves a DELETION failure; B runs ENROLL (which does not touch
        // the deletion fields itself) before the collector - the boundary retire is the only
        // thing that can clear them.
        session = 1L
        setProjection(enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED))
        vm = VoiceSharingSettingsViewModel(
            projection = projection,
            enrollmentController = EnrollmentController(
                enrollOperation = { enrollGate2.await() },
                withdrawOperation = { withdrawGate2.await() },
                deleteOperation = { deleteGate2.await() },
                clearLocalPending = {},
                ownerSession = { session },
            ),
            withdrawalIntent = LocalWithdrawalIntentStore(persistenceFlag),
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

    // ---: a retired tap must have NO presentation effects on a newer action.

    @Test
    fun `a retired stale tap does not clear a newer action's pending presentation`() = runTest(mainDispatcher) {
        var session = 1L
        setProjection(enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED))
        val enrollGateA = Gate()
        val enrollGateB = Gate()
        var issues = 0
        val vm = viewModel(enrollGate = enrollGateA, withdrawGate = enrollGateB, ownerSession = { session }, onEnrollIssue = { issues++ })

        // Tap A under session 1; the coroutine is queued, not yet run.
        vm.enroll()

        // The session switches, then a NEW tap B lands before the dispatcher runs anything.
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
        // The uploader loop is blocked inside the send and cannot reach its own gate, so the tap is
        // the only thing that can stop the request.
        val vm = viewModel(cancelInFlightUpload = { cancelled++ })

        vm.withdraw()
        // The cancel still happens for the tap itself, ahead of the withdrawal request; it just
        // no longer runs on the caller's thread, so the tap's work has to be pumped.
        mainDispatcher.scheduler.advanceUntilIdle()

        assertEquals("withdrawing must reach a request already on the wire", 1, cancelled)
    }

    /**
     * An IO dispatcher whose tasks run only when the test runs them, in any order.
     *
     * The stale-read control needs a read that fetched its value BEFORE the durable stop but is
     * accepted AFTER a fresher read -- an ordering the FIFO test scheduler cannot produce, and real
     * IO cannot guarantee. Holding the tasks explicitly makes the interleaving exact.
     */
    private class GatedDispatcher : CoroutineDispatcher() {
        private val queue = mutableListOf<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue += block
        }

        /** Runs the oldest held task. */
        fun runOldest() {
            check(queue.isNotEmpty()) { "no gated IO task is waiting" }
            queue.removeAt(0).run()
        }

        /** Runs the newest held task, leaving older ones parked. */
        fun runNewest() {
            check(queue.isNotEmpty()) { "no gated IO task is waiting" }
            queue.removeAt(queue.lastIndex).run()
        }

        val hasTasks: Boolean get() = queue.isNotEmpty()
    }

    /**
     * A projection going unenrolled must retire the published membership with no IO completion.
     *
     * The cache holds only the durable marker; the projection conjuncts are composed fresh at
     * render time. A version that cached the whole `isEnrolled()` conjunction published stale
     * `enrolled=true` here until its IO refresh landed.
     */
    @Test
    fun `an unenrolling projection retires the published value with no io completion`() = runTest(mainDispatcher) {
        val io = GatedDispatcher()
        setProjection(enrolledProjection())
        lateinit var vm: VoiceSharingSettingsViewModel
        val controller = EnrollmentController(
            enrollOperation = { enrollGate.await() },
            withdrawOperation = { withdrawGate.await() },
            deleteOperation = { deleteGate.await() },
            clearLocalPending = {},
            ownerSession = { 1L },
        )
        vm = VoiceSharingSettingsViewModel(
            projection = projection,
            enrollmentController = controller,
            withdrawalIntent = LocalWithdrawalIntentStore(persistenceFlag),
            ownerSession = { 1L },
            ioDispatcher = io,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + mainDispatcher).also { scopes += it },
        )
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue("PREMISE: enrolled under an enrolled projection", vm.state.value.enrolled)

        setProjection(enrolledProjection().copy(voiceSharingEnrolled = false, withdrawalState = WithdrawalState.NOT_ENROLLED))
        // No gated IO task is run: the retirement must come from the synchronous publish alone.
        mainDispatcher.scheduler.runCurrent()
        assertFalse(
            "the unenrolled projection must be visible without waiting for any marker read",
            vm.state.value.enrolled,
        )
    }

    /**
     * The withdrawal's fresh reads must update the cache the collectors render from.
     *
     * The reviewer's scenario: a withdrawal publishes fresh values while an older refresh read is
     * still out; if those fresh values bypass the cache, the next collector-driven render composes
     * from the stale cache and repaints enrolled after the user stopped. The fenced read makes the
     * withdrawal's own reads the newest issued ones, so they are exactly the values the cache
     * keeps, and a cache-driven render after the stop says not enrolled.
     */
    @Test
    fun `the cache a collector render composes from reflects the withdrawal's stop`() = runTest(mainDispatcher) {
        val io = GatedDispatcher()
        setProjection(enrolledProjection())
        lateinit var vm: VoiceSharingSettingsViewModel
        val controller = EnrollmentController(
            enrollOperation = { enrollGate.await() },
            withdrawOperation = { withdrawGate.await() },
            deleteOperation = { deleteGate.await() },
            clearLocalPending = {},
            ownerSession = { 1L },
        )
        vm = VoiceSharingSettingsViewModel(
            projection = projection,
            enrollmentController = controller,
            withdrawalIntent = LocalWithdrawalIntentStore(persistenceFlag),
            ownerSession = { 1L },
            ioDispatcher = io,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + mainDispatcher).also { scopes += it },
        )
        mainDispatcher.scheduler.advanceUntilIdle()
        // Settle the construction-time collector refreshes so the only gated work left belongs to
        // the withdrawal.
        while (io.hasTasks) {
            io.runOldest()
            mainDispatcher.scheduler.runCurrent()
        }
        assertTrue("PREMISE: enrolled", vm.state.value.enrolled)

        // Withdrawal runs to its suspension on the controller gate: stop written, fresh reads
        // accepted, published state retired.
        vm.withdraw()
        // Each cycle: the launch body runs and suspends on its gated read, the read executes, and
        // the body resumes on Main to accept the value.
        repeat(3) {
            mainDispatcher.scheduler.runCurrent()
            io.runOldest()
            mainDispatcher.scheduler.runCurrent()
        }
        assertFalse("PREMISE: the stop is published", vm.state.value.enrolled)

        // A collector event re-renders from the cache with no IO in between.
        setProjection(enrolledProjection().copy(enrollmentEpoch = "epoch-3"))
        mainDispatcher.scheduler.runCurrent()
        assertFalse(
            "the cache-driven render must reflect the stop",
            vm.state.value.enrolled,
        )
    }
}
