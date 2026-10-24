package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import au.com.shiftyjelly.pocketcasts.voicecontrol.service.EngineQuiescence.Verdict
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun `a job the engine cancelled is expected shutdown, not a held failure`() = runBlocking {
        // Reviewer 47a3a267: the teardown cancels these very jobs. Treating every cancelled job as
        // abnormal marked an ordinary shutdown as Held.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val job = scope.launch(Dispatchers.IO) { awaitCancellation() }
            job.cancel()
            job.join()
            assertTrue("the job must be settled before judging", job.isCompleted)
            val verdict = EngineQuiescence.settle(
                stop = {},
                unfinished = { listOf(job) },
                join = { it.join() },
                // Our own cancellation: no failure cause.
                completionCause = { null },
            )
            assertEquals("expected cancellation must settle, got $verdict", Verdict.Settled, verdict)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an already pruned job is judged from the captured set, not the live list`() = runBlocking {
        // Reviewer 47a3a267: judging from the LIVE list means the same shutdown is classified Held
        // or Settled purely on prune timing. The captured jobs must be the evidence.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val failedJob = scope.launch(Dispatchers.IO) { runCatching { throw IllegalStateException("engine died") } }
            failedJob.join()
            // The job is JOINED first (so it is captured), then pruned from the live list — exactly
            // what the engine's completion callback does. Only the captured set can still see it.
            var live: List<kotlinx.coroutines.Job> = listOf(failedJob)
            val verdict = EngineQuiescence.settle(
                stop = {},
                unfinished = { live },
                join = {
                    it.join()
                    // After joining, the engine prunes the finished entry.
                    live = emptyList()
                },
                completionCause = { IllegalStateException("engine died") },
            )
            // The live list is now EMPTY, so a live-list scan would settle. The captured set still
            // holds the failed job, so the failure must be seen even though it was pruned.
            assertTrue(
                "a pruned failed job must still be judged, got $verdict",
                verdict is Verdict.AbnormallyFinished,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `only a cancellation the engine did not initiate counts as a failure`() = runBlocking {
        // Reviewer 47a3a267: the teardown cancels these jobs itself, so blanket-treating every
        // cancelled job as abnormal reported a normal shutdown as Held.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val engineCancelled = scope.launch(Dispatchers.IO) { awaitCancellation() }
            engineCancelled.cancel()
            engineCancelled.join()
            val strangerCancelled = scope.launch(Dispatchers.IO) { awaitCancellation() }
            strangerCancelled.cancel()
            strangerCancelled.join()

            assertNull(
                "a cancellation the engine initiated is expected shutdown",
                EngineQuiescence.abnormalEngineFinish(engineCancelled, setOf(engineCancelled)),
            )
            assertNotNull(
                "a cancellation nobody accounted for must NOT be reported as clean",
                EngineQuiescence.abnormalEngineFinish(strangerCancelled, setOf(engineCancelled)),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a job that finished abnormally fails closed even though join returned`() = runBlocking {
        // Reviewer 28b32255: awaiting a Job reports nothing about success, so a finished-but-failed
        // engine job must not be mistaken for a settled engine.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var released = false
        try {
            // SupervisorJob keeps the failure from cancelling the scope, and the handler keeps it
            // from escaping as an UNCAUGHT exception (which poisons later tests in this class).
            val failed = CompletableDeferred<Unit>()
            val job = scope.launch(Dispatchers.IO) {
                runCatching { throw IllegalStateException("engine died") }
                    .onFailure { failed.complete(Unit) }
            }
            failed.await()
            job.join()
            val verdict = EngineQuiescence.settle(
                stop = {},
                unfinished = { listOf(job) },
                join = { it.join() },
                completionCause = { IllegalStateException("engine died") },
            )
            if (verdict == Verdict.Settled) released = true
            assertTrue("completion is not success, got $verdict", verdict is Verdict.AbnormallyFinished)
            assertTrue("no release may happen after an abnormal finish", !released)
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
