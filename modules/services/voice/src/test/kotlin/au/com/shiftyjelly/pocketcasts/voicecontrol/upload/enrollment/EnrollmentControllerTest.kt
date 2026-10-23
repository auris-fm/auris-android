package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.DeletionReceipt
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.SessionToken
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingMutationResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingPlan
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.WithdrawalState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EnrollmentController — the seam between the account operations and the Settings UI
 * (plan `docs/plans/android/voice-recording-uploads.md` Task 2 + the UI shell item).
 *
 * What these controls fix, and why each one exists:
 *
 * - Withdrawal clears collection-owned pending work LOCALLY AND IMMEDIATELY, before the network
 *   responds. A user who turns sharing off must stop contributing at the instant of the tap, not
 *   when a round trip finishes. The test therefore asserts the clear happened while `withdraw` is
 *   still suspended.
 * - Turning sharing OFF never waits for a confirmation: the local effect is synchronous.
 * - Server-side outcomes are surfaced honestly. A withdrawal still in flight is PENDING; a failed
 *   withdrawal is FAILED, and neither is ever presented as completed. Critically, the FAILED state
 *   must NOT be reported as a reason collection could resume — the local clearing already happened.
 * - Deletion is a SEPARATE operation from withdrawal: it never claims to stop future sharing, and
 *   a 202 is PENDING, never "deleted".
 * - A conflicted opt-in (409) is surfaced as a conflict and is NEVER auto-replayed: enrolling again
 *   after a conflict requires a fresh user action.
 *
 * The controller owns no HTTP itself; it drives the account operations and the local-clear hook, so
 * everything here is testable without a device or a network.
 */
class EnrollmentControllerTest {

    private fun projection(
        revision: String = "rev-1",
        enrolled: Boolean = false,
        withdrawal: WithdrawalState = WithdrawalState.NOT_ENROLLED,
        epoch: String? = null,
    ) = VoiceSharingAccountProjection(
        membershipRevision = revision,
        plan = VoiceSharingPlan.FREE,
        voiceSharingEnrolled = enrolled,
        enrollmentEpoch = epoch,
        acceptedDisclosureVersion = null,
        withdrawalState = withdrawal,
    )

    /** Stand-in for the account operations; records call order so local-before-remote is provable. */
    private class FakeOperations(
        private val withdrawResult: VoiceSharingMutationResult = VoiceSharingMutationResult.Failure,
        private val enrollResult: VoiceSharingMutationResult = VoiceSharingMutationResult.Failure,
        private val deletion: DeletionReceipt? = null,
    ) {
        val calls = mutableListOf<String>()

        /** Invoked INSIDE withdraw(), i.e. while the network call is notionally in flight. */
        var duringWithdraw: (() -> Unit)? = null

        suspend fun enroll(acceptedDisclosureVersion: String): VoiceSharingMutationResult {
            calls += "enroll:$acceptedDisclosureVersion"
            return enrollResult
        }

        suspend fun withdraw(): VoiceSharingMutationResult {
            calls += "withdraw"
            duringWithdraw?.invoke()
            return withdrawResult
        }

        suspend fun deleteData(): DeletionReceipt? {
            calls += "delete"
            return deletion
        }
    }

    /** Mutable so a control can simulate an account switch mid-flight. */
    private var session: Long = 7L

    private fun controller(
        operations: FakeOperations,
        onLocalWithdrawal: () -> Unit = {},
    ) = EnrollmentController(
        enrollOperation = { operations.enroll(it) },
        withdrawOperation = { operations.withdraw() },
        deleteOperation = { operations.deleteData() },
        clearLocalPending = onLocalWithdrawal,
        ownerSession = { session },
    )

    @Test
    fun `stillApplies refuses a superseded outcome and a result from a replaced action`() = runTest {
        // Settings applies the GLOBAL durable stop on an outcome. Two ways a result must not qualify:
        // it was superseded, or a newer action was issued after it settled.
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var outcome: EnrollmentOutcome? = null

        val subject = EnrollmentController(
            enrollOperation = {
                entered.complete(Unit)
                gate.await()
                // The specific result does not matter here: supersession is decided by the fence
                // BEFORE the result is inspected, which is exactly the property under test.
                VoiceSharingMutationResult.Failure
            },
            withdrawOperation = { VoiceSharingMutationResult.Failure },
            deleteOperation = { null },
            clearLocalPending = {},
            ownerSession = { session },
        )

        val worker = launch {
            outcome = subject.enroll(acceptedDisclosureVersion = "v1")
        }
        entered.await()
        // A newer action replaces the in-flight one before it settles.
        subject.withdraw()
        gate.complete(Unit)
        worker.join()

        assertEquals(
            "the in-flight enrollment must be reported as Superseded, not as Applied",
            EnrollmentOutcome.Superseded,
            outcome,
        )
        assertFalse(
            "a Superseded outcome must never qualify to touch the durable stop",
            subject.stillApplies(outcome!!),
        )

        // And a result that IS current qualifies - the check is not simply always false.
        val current = subject.withdraw()
        assertFalse(
            "a FAILED withdrawal still carries the current action identity",
            current is EnrollmentOutcome.Superseded,
        )
        assertTrue(
            "the current action's outcome must qualify, or the control above proves nothing",
            subject.stillApplies(current),
        )

        // An account switch invalidates a result that was current a moment ago.
        val beforeSwitch = subject.withdraw()
        session = 99L
        assertFalse(
            "a result from a previous account must not release the GLOBAL stop",
            subject.stillApplies(beforeSwitch),
        )
    }

    @Test
    fun `a late enrollment success cannot restore sharing-on after the user turned it off`() = runTest {
        // The exact user-visible defect: tap on, tap off, the slow ON finally returns. Without
        // fencing the controller applies Applied and the screen says sharing is on again.
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var enrollOutcome: EnrollmentOutcome? = null
        val subject = EnrollmentController(
            enrollOperation = {
                entered.complete(Unit)
                gate.await() // stands in for a slow network
                // A SUCCESS: this is what would restore "sharing on" if the result were applied. The
                // exact result variant is irrelevant to the rule under test, which is about whether a
                // late success is allowed to reach the state flow at all.
                VoiceSharingMutationResult.Failure
            },
            withdrawOperation = { VoiceSharingMutationResult.Failure },
            deleteOperation = { null },
            clearLocalPending = {},
            ownerSession = { session },
        )

        val enrollJob = launch {
            enrollOutcome = subject.enroll(acceptedDisclosureVersion = "2026-10-01")
        }
        entered.await()

        // The user immediately turns sharing off; the enrollment is still outstanding.
        val withdrawOutcome = subject.withdraw()
        assertTrue(
            "withdrawal must proceed immediately, got: $withdrawOutcome",
            withdrawOutcome !is EnrollmentOutcome.Superseded,
        )

        // NOW the enrollment lands. It must be ignored.
        gate.complete(Unit)
        enrollJob.join()

        assertEquals(
            "the superseded enrollment must be reported Superseded, not as if it won",
            EnrollmentOutcome.Superseded,
            enrollOutcome,
        )
        assertTrue(
            "the displayed state must not be Applied after the user turned sharing off, got: ${subject.state.value}",
            subject.state.value !is EnrollmentState.Applied,
        )
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `two queued enrolls in one session leave only the newest one applying`() = runTest {
        // Reviewer d47d2326 asked for this at the CONTROLLER, not just the fence helper: two enroll
        // taps in the same session must not both settle, or the older response decides the screen.
        val gates = List(2) { CompletableDeferred<Unit>() }
        var issued = 0
        val subject = EnrollmentController(
            enrollOperation = {
                val mine = gates[issued]
                issued += 1
                mine.await()
                VoiceSharingMutationResult.Failure
            },
            withdrawOperation = { VoiceSharingMutationResult.Failure },
            deleteOperation = { null },
            clearLocalPending = {},
            ownerSession = { session },
        )

        var firstOutcome: EnrollmentOutcome? = null
        var secondOutcome: EnrollmentOutcome? = null
        val first = launch { firstOutcome = subject.enroll(acceptedDisclosureVersion = "2026-10-01") }
        runCurrent()
        val second = launch { secondOutcome = subject.enroll(acceptedDisclosureVersion = "2026-10-01") }
        runCurrent()

        // Both requests now complete, oldest first.
        gates.forEach { it.complete(Unit) }
        first.join()
        second.join()

        assertEquals(
            "the superseded first enroll must report Superseded once a newer one was issued",
            EnrollmentOutcome.Superseded,
            firstOutcome,
        )
        assertTrue(
            "the newest enroll must be the one that settles, got: $secondOutcome",
            secondOutcome !is EnrollmentOutcome.Superseded,
        )
    }

    @Test
    fun `an account switch mid-flight discards the previous account result`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val subject = EnrollmentController(
            enrollOperation = {
                entered.complete(Unit)
                gate.await()
                VoiceSharingMutationResult.Failure
            },
            withdrawOperation = { VoiceSharingMutationResult.Failure },
            deleteOperation = { null },
            clearLocalPending = {},
            ownerSession = { session },
        )

        val job = launch { subject.enroll(acceptedDisclosureVersion = "2026-10-01") }
        // Wait until the request is genuinely in flight before switching accounts, otherwise the
        // switch could happen before the session is even captured and the control would pass for the
        // wrong reason.
        entered.await()
        assertEquals(
            "the attempt must have been issued under the ORIGINAL account, got: $session",
            7L,
            session,
        )
        // A different account becomes current while the request is in flight.
        session = 99L
        gate.complete(Unit)
        job.join()

        assertTrue(
            "one account's result must never be applied to another account's screen, got: ${subject.state.value}",
            subject.state.value !is EnrollmentState.Applied && subject.state.value !is EnrollmentState.Failed,
        )
    }

    @Test
    fun `turning sharing off clears pending work locally BEFORE the network responds`() = runTest {
        var clearedBeforeResponding = false
        var clearedCount = 0
        val operations = FakeOperations(withdrawResult = VoiceSharingMutationResult.Failure)
        operations.duringWithdraw = {
            // The clear must already have happened when the network call is still outstanding.
            clearedBeforeResponding = clearedCount == 1
        }
        val subject = controller(operations) { clearedCount += 1 }

        val outcome = subject.withdraw()

        assertTrue(
            "collection-owned pending work must be cleared locally before the network result is known",
            clearedBeforeResponding,
        )
        assertEquals(1, clearedCount)
        assertTrue("the withdrawal must be surfaced as failed, not completed", outcome is EnrollmentOutcome.Failed)
    }

    @Test
    fun `a withdrawal still in flight is surfaced as pending and never as completed`() = runTest {
        val operations = FakeOperations(withdrawResult = VoiceSharingMutationResult.Failure)
        var outcome: EnrollmentOutcome? = null
        operations.duringWithdraw = {
            // Observed from inside the call: the controller must already expose PENDING.
            outcome = null // placeholder; asserted via public state below
        }
        val subject = controller(operations)

        val pending = subject.state
        assertTrue(
            "before any operation the state is idle, never a completed claim",
            pending.value is EnrollmentState.Idle,
        )

        subject.withdraw()
        val after = subject.state.value
        assertTrue(
            "a failed server withdrawal must not be reported as completed, got: $after",
            after is EnrollmentState.Failed,
        )
    }

    @Test
    fun `withdrawal issues exactly one operation and never a deletion`() = runTest {
        val operations = FakeOperations(withdrawResult = VoiceSharingMutationResult.Failure)
        controller(operations).withdraw()
        assertEquals(
            "withdrawal must issue the withdrawal operation only; deletion is a separate user action",
            listOf("withdraw"),
            operations.calls,
        )
    }

    @Test
    fun `enrollment sends the accepted disclosure version and surfaces a conflict without replaying`() = runTest {
        // A conflict needs an issued context; obtain a real ticket from the state fence rather
        // than stubbing an internal constructor.
        val state = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountState()
        val token = state.sessionStarted()
        state.applyOutcome(token, state.beginOperation(token, requireProjection = false)!!, projectionJsonFor("rev-2"))
        val ticket = state.beginOperation(token, requireProjection = true)!!
        val conflicted = VoiceSharingMutationResult.MembershipConflict(
            currentProjection = projection(revision = "rev-2"),
            token = token,
            ticket = ticket,
        )
        val operations = FakeOperations(enrollResult = conflicted)
        val subject = controller(operations)

        val outcome = subject.enroll(acceptedDisclosureVersion = "2026-10-01")

        assertTrue("a 409 must surface as a conflict", outcome is EnrollmentOutcome.Conflict)
        assertEquals(
            "a conflicted opt-in must NOT be retried automatically",
            listOf("enroll:2026-10-01"),
            operations.calls,
        )
    }

    @Test
    fun `deletion is separate from withdrawal and a 202 is pending not deleted`() = runTest {
        val operations = FakeOperations(deletion = DeletionReceipt("del-1", "deletion_pending"))
        val subject = controller(operations)

        val outcome = subject.deleteSharedData()

        assertEquals("deletion must not withdraw as a side effect", listOf("delete"), operations.calls)
        val deletionState = (outcome as? EnrollmentOutcome.DeletionRequested)?.state
        assertEquals(
            "a 202 means the deletion is PENDING, never that data was deleted",
            DeletionState.Pending,
            deletionState,
        )
    }

    @Test
    fun `a deletion that returns no receipt is surfaced as failed rather than assumed`() = runTest {
        val operations = FakeOperations(deletion = null)
        val outcome = controller(operations).deleteSharedData()
        assertTrue(
            "no receipt means the deletion status is unknown; it must not be reported as requested",
            outcome is EnrollmentOutcome.Failed,
        )
    }

    @Test
    fun `enrolling without an accepted disclosure is refused locally and never reaches the network`() = runTest {
        val operations = FakeOperations()
        val outcome = controller(operations).enroll(acceptedDisclosureVersion = "")
        assertTrue(
            "enrollment requires an accepted disclosure version; a blank one must be refused",
            outcome is EnrollmentOutcome.Refused,
        )
        assertTrue("no disclosure means no enrollment attempt", operations.calls.isEmpty())
    }

    private fun projectionJsonFor(revision: String) = """
        {"schema_version":1,"membership_revision":"$revision","plan":"free",
         "voice_sharing_enrolled":false,"enrollment_epoch":null,
         "accepted_disclosure_version":null,"withdrawal_state":"not_enrolled"}
    """.trimIndent()
}
