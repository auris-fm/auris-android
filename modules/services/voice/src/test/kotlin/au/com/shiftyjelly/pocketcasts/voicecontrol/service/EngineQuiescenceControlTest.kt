package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import au.com.shiftyjelly.pocketcasts.voicecontrol.service.EngineQuiescence.Verdict
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ENGINE QUIESCENCE CONTROL.
 *
 * Reviewer 9294defb: the teardown's 8-round join loop "unconditionally proceeds to release even if
 * unseen unfinished jobs remain", `return@repeat` skipped only an iteration, and a failed final stop
 * was logged and ignored. Those are properties of a decision, so the decision is a value type
 * ([EngineQuiescence]) that this control executes — source-text presence mutants cannot establish
 * fail-closed behaviour.
 *
 * The safety property: ONLY [Verdict.Settled] permits resource release. Every other verdict must
 * hold.
 */
class EngineQuiescenceControlTest {

    private fun liveJob(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        withTimeout(5_000) { CompletableDeferred<Unit>().await() }
    }

    @Test
    fun `settles and permits release when every job finishes`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var released = false
        try {
            val job = liveJob(scope)
            job.cancel()
            val verdict = EngineQuiescence.settle(
                stop = {},
                unfinished = { listOf(job) },
                join = { it.join() },
            )
            if (verdict == Verdict.Settled) released = true
            assertEquals(Verdict.Settled, verdict)
            assertTrue("a settled engine permits release", released)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a failed final stop fails closed and releases nothing`() = runBlocking {
        var released = false
        val verdict = EngineQuiescence.settle(
            stop = { throw IllegalStateException("engine is wedged") },
            unfinished = { emptyList() },
            join = { it.join() },
        )
        if (verdict == Verdict.Settled) released = true
        assertTrue("a stop failure must not release", verdict is Verdict.StopFailed)
        assertTrue("no release may happen on a failed stop", !released)
    }

    @Test
    fun `round exhaustion fails closed even though every join succeeded`() = runBlocking {
        // The set keeps growing: each round joins the known jobs but a new one appears. This is the
        // exact shape the reviewer described — joins succeed, work never settles.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var released = false
        try {
            val jobs = mutableListOf<Job>()
            val verdict = EngineQuiescence.settle(
                stop = {},
                unfinished = {
                    // A fresh, already-finished-looking job each call: the set never empties.
                    val j = scope.launch(Dispatchers.IO) { }
                    jobs.add(j)
                    listOf(j) + jobs.dropLast(1).take(1)
                },
                join = { it.join() },
            )
            if (verdict == Verdict.Settled) released = true
            assertTrue(
                "work that never settles must fail closed with a residual count, got $verdict",
                verdict is Verdict.Residual,
            )
            assertTrue("no release may happen when work never settled", !released)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a residual job that never finishes fails closed`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var released = false
        try {
            // GENUINELY unfinished: pending forever. join() in this test does not wait, so the loop
            // cannot drain it and exhaustion is the correct verdict.
            val job = scope.launch(Dispatchers.IO) { CompletableDeferred<Unit>().await() }
            val verdict = EngineQuiescence.settle(
                stop = {},
                unfinished = { listOf(job) },
                join = { /* deliberately does not wait */ },
            )
            if (verdict == Verdict.Settled) released = true
            assertTrue("an unfinished job at release time must fail closed, got $verdict", verdict !is Verdict.Settled)
            assertTrue("no release may happen with unfinished work", !released)
        } finally {
            scope.cancel()
        }
    }
}
