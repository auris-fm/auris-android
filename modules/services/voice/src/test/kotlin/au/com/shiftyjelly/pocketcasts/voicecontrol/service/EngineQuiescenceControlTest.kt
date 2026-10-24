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
    fun `a throwing join fails closed instead of escaping as an exception`() = runBlocking {
        // Reviewer bdb914a6: a join that throws has NOT proven the job finished. It must become a
        // verdict, not an exception thrown at the caller.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var released = false
        try {
            val job = scope.launch(Dispatchers.IO) { CompletableDeferred<Unit>().await() }
            val verdict = EngineQuiescence.settle(
                stop = {},
                unfinished = { listOf(job) },
                join = { throw IllegalStateException("join blew up") },
            )
            if (verdict == Verdict.Settled) released = true
            assertTrue("a throwing join must yield JoinFailed, got $verdict", verdict is Verdict.JoinFailed)
            assertTrue("no release may happen when a join failed", !released)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `work that never settles fails closed when the set keeps growing`() = runBlocking {
        // Reviewer 6ce4ad78: the previous version created jobs that completed immediately, so the
        // set could spuriously look settled. These jobs are HELD open: a pool per round, each one
        // added by unfinished() and never finished, with bounded waits and cleanup.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val gates = mutableListOf<CompletableDeferred<Unit>>()
        var released = false
        try {
            fun holdOne(): Job {
                val gate = CompletableDeferred<Unit>()
                gates.add(gate)
                return scope.launch(Dispatchers.IO) { withTimeout(5_000) { gate.await() } }
            }
            val jobs = mutableListOf<Job>()
            val verdict = EngineQuiescence.settle(
                stop = {},
                unfinished = {
                    // A NEW held job per sample: the set genuinely grows and never empties.
                    jobs.add(holdOne())
                    jobs.toList()
                },
                join = { /* deliberately does not release the gates */ },
            )
            if (verdict == Verdict.Settled) released = true
            assertTrue("a growing set of held jobs must fail closed, got $verdict", verdict !is Verdict.Settled)
            assertTrue("no release may happen when work never settled", !released)
        } finally {
            gates.forEach { it.complete(Unit) }
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
