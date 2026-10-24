package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import au.com.shiftyjelly.pocketcasts.voicecontrol.service.TeardownRetry.Decision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Controls for the retry of a HELD teardown.
 *
 * The collision-free case is that a retry must never release until the CURRENT state proves quiet,
 * and must never repeat a release that already ran. Each decision branch is executed here; the
 * unfinished jobs are real coroutines, not stubs.
 */
class TeardownRetryControlTest {
    @Test
    fun `a retry releases only once the current state proves quiet`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val gate = CompletableDeferred<Unit>()
        try {
            val held = scope.launch(Dispatchers.IO) { gate.await() }
            // The retry rechecks the CURRENT state; the job is still running, so it stays held.
            val whileHeld = TeardownRetry.decide(recheck = { listOf(held) })
            assertTrue("unfinished work must keep the teardown held, got $whileHeld", whileHeld is Decision.StillHeld)
            gate.complete(Unit)
            held.join()
            val afterDrain = TeardownRetry.decide(recheck = { listOf(held) })
            assertTrue("once quiet the retry may release, got $afterDrain", afterDrain is Decision.Quiescent)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an unfinished job that keeps running is never reported as quiescent`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val never = scope.launch(Dispatchers.IO) { awaitCancellation() }
            val decision = TeardownRetry.decide(recheck = { listOf(never) })
            assertTrue("a running job must not be quiescent, got $decision", decision is Decision.StillHeld)
            assertEquals("the unfinished count is what the owner observes", 1, (decision as Decision.StillHeld).unfinished)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a failed recheck stays held rather than claiming success`() {
        val decision = TeardownRetry.decide(recheck = { throw IllegalStateException("state unreadable") })
        assertTrue("an unreadable state must not release, got $decision", decision is Decision.Unproven)
    }

    @Test
    fun `a retry without continued observation is refused`() {
        val decision = TeardownRetry.decide(recheck = { emptyList() }, observe = false)
        assertTrue("releasing without observing is not a retry, got $decision", decision is Decision.Unproven)
    }

    @Test
    fun `a retry never repeats a release that already completed`() {
        val all = listOf("backend", "session", "recognizer", "feedback", "notification")
        val completed = setOf("backend", "session")
        assertEquals(
            "a retry must skip destructive cleanup that already ran",
            listOf("recognizer", "feedback", "notification"),
            TeardownRetry.pendingReleases(all, completed),
        )
        assertFalse("a partially released teardown is not fully released", TeardownRetry.fullyReleased(all, completed))
        assertTrue(
            "a completed teardown is fully released",
            TeardownRetry.fullyReleased(all, all.toSet()),
        )
    }

    @Test
    fun `a retry will not release past a hold that quiescence cannot explain`() = runBlocking {
        // Reviewer 9f873b94: deciding on list emptiness alone let a retry release resources after a
        // FAILED STOP or an abnormal engine finish. The jobs were gone from the list, but the reason
        // the teardown held had never been resolved — so an empty list was not evidence of safety.
        val unresolved = EngineQuiescence.Verdict.StopFailed(IllegalStateException("the final stop threw"))
        val decision = TeardownRetry.decide(
            recheck = { emptyList() },
            unresolvedHold = unresolved,
        )
        assertTrue(
            "an empty list must NOT make an unexplained hold retryable, got $decision",
            decision is TeardownRetry.Decision.Unproven,
        )
    }

    @Test
    fun `a hold that IS about quiescence is cleared by observing quiescence`() = runBlocking {
        // The complement: Residual means "work was still finishing", so finding it finished is
        // exactly the evidence that resolves it. Without this, the guard above could be satisfied by
        // refusing every retry, which would make the whole retry path dead code.
        val decision = TeardownRetry.decide(
            recheck = { emptyList() },
            unresolvedHold = null,
        )
        assertTrue("quiescence must permit the retry, got $decision", decision is TeardownRetry.Decision.Quiescent)
    }

    @Test
    fun `a held teardown is not reported as released by omission`() {
        // Nothing released yet: every release is still pending, so it cannot read as complete.
        val all = listOf("backend", "session")
        assertFalse(
            "a teardown that released nothing must not read as complete",
            TeardownRetry.fullyReleased(all, emptySet()),
        )
        assertEquals("every release is pending", all, TeardownRetry.pendingReleases(all, emptySet()))
    }
}
