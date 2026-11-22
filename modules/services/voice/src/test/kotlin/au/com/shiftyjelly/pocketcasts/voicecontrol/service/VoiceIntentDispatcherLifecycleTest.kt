package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.VoiceAsrEngine
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoiceActionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackIntentExecutor
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ExecutionOutcome
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

/**
 * REAL DISPATCHER LIFECYCLE CONTROLS (spec 250ddcc3, reviewer 92833c6d/087140b7): these drive the
 * actual [VoiceIntentDispatcher] the service delegates to, with real coroutine cancellation, real
 * throws and a real execution path. The engine boundary is mocked because the dispatcher's
 * CONTRACT is exactly "settle exactly once, report only invocation-local facts"; the engine's
 * aggregation semantics have their own dedicated controls
 * ([VoiceAsrEngineAggregationTest]).
 */
class VoiceIntentDispatcherLifecycleTest {

    private fun dispatcher(): VoiceIntentDispatcher = VoiceIntentDispatcher()

    /**
     * A handler-throwing dispatch fails its Job; without supervision that failure escapes the
     * scope's thread and pollutes other tests in the same JVM. Real scopes are supervised the
     * same way (the service uses a supervisor strategy), so this mirrors production.
     */
    private fun scope(): CoroutineScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> })

    private fun engine() = Mockito.mock(VoiceAsrEngine::class.java)

    private fun token() = VoiceAsrEngine.InvocationToken(id = 1L, captureId = UUID.randomUUID())

    private fun executor(behavior: (VoiceIntent, ((VoiceActionOutcome) -> Unit)?) -> VoiceResponse) = org.mockito.kotlin.mock<VoicePlaybackIntentExecutor> {
        on { runBlocking { execute(any(), any(), any(), anyOrNull()) } }.thenAnswer { inv ->
            @Suppress("UNCHECKED_CAST")
            val intent = inv.getArgument<VoiceIntent>(0)
            // execute(intent, outcome, onOutcome, timingRequestId): the REQUEST-OWNED outcome is
            // arg 1; the per-action callback is arg 2 (the old 2-arg signature is gone).
            val onOutcome = inv.getArgument<((VoiceActionOutcome) -> Unit)?>(2)
            behavior(intent, onOutcome)
        }
    }

    @Test
    fun `a successful dispatch settles exactly once`() = runBlocking {
        val engine = engine()
        var reported: VoiceActionOutcome? = null
        val job = dispatcher().dispatch(
            scope = scope(),
            engine = engine,
            executor = executor { intent, onOutcome ->
                onOutcome?.invoke(VoiceActionOutcome.Performed)
                VoiceResponse.Silent
            },
            render = { _, _ -> },
            onCommandRecognized = { },
            intent = VoiceIntent.Playback.Pause,
            invocationToken = token(),
        )
        job?.join()
        verify(engine, times(1)).settleInvocation(any())
        Unit
    }

    @Test
    fun `cancellation before the body runs still settles the token`() = runBlocking {
        val engine = engine()
        val scope = CoroutineScope(Job().apply { cancel() })
        val job = dispatcher().dispatch(
            scope = scope,
            engine = engine,
            executor = executor { _, _ -> error("the body must never run") },
            render = { _, _ -> },
            onCommandRecognized = { },
            intent = VoiceIntent.Playback.Pause,
            invocationToken = token(),
        )
        job?.join()
        // THE PRE-BODY EXIT (reviewer dab802ec): a launch finally would never run; the
        // completion handler must.
        verify(engine, times(1)).settleInvocation(any())
        Unit
    }

    @Test
    fun `a handler throw still settles the token exactly once`() = runBlocking {
        val engine = engine()
        val job = dispatcher().dispatch(
            scope = scope(),
            engine = engine,
            executor = executor { _, _ -> throw IllegalStateException("sink exploded") },
            render = { _, _ -> },
            onCommandRecognized = { },
            intent = VoiceIntent.Playback.Pause,
            invocationToken = token(),
        )
        job?.join()
        verify(engine, times(1)).settleInvocation(any())
        Unit
    }

    @Test
    fun `a debounced dispatch leaves no live timing record in the registry`() {
        // The debounce exit never reaches the executor, whose finally would finish the record:
        // finishing it here is what stops the registry holding one live entry per dropped turn.
        val engine = engine()
        val d = dispatcher()
        val t = token()
        val id = "wire-debounced"
        val record = au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord(id)
        record.mark(au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.Stage.AcousticEnd, 1_000_000_000L)
        au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.registerFor(id, record)
        d.dispatch(
            scope = scope(),
            engine = engine,
            executor = executor { _, _ -> VoiceResponse.Silent },
            render = { _, _ -> },
            onCommandRecognized = { },
            intent = VoiceIntent.Playback.Pause,
            invocationToken = null,
        )
        val job = d.dispatch(
            scope = scope(),
            engine = engine,
            executor = executor { _, _ -> error("debounced: must not run") },
            render = { _, _ -> },
            onCommandRecognized = { },
            intent = VoiceIntent.Playback.Pause,
            invocationToken = t,
            timingRequestId = id,
        )
        assertNull("a debounced dispatch launches nothing", job)
        assertTrue(
            "the dropped turn's record must be finished, not left live in the registry",
            !au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.isActive(id),
        )
    }

    @Test
    fun `a debounced dispatch settles its token immediately`() {
        val engine = engine()
        val d = dispatcher()
        val t = token()
        d.dispatch(
            scope = scope(),
            engine = engine,
            executor = executor { _, _ -> VoiceResponse.Silent },
            render = { _, _ -> },
            onCommandRecognized = { },
            intent = VoiceIntent.Playback.Pause,
            invocationToken = null,
        )
        val job = d.dispatch(
            scope = scope(),
            engine = engine,
            executor = executor { _, _ -> error("debounced: must not run") },
            render = { _, _ -> },
            onCommandRecognized = { },
            intent = VoiceIntent.Playback.Pause,
            invocationToken = t,
        )
        assertNull("a debounced dispatch launches nothing", job)
        verify(engine, times(1)).settleInvocation(t)
    }

    @Test
    fun `a debounced dispatch is a client refusal, not a silent stage`() {
        // The spec's fixed refusal mapping: a debounce-suppressed turn is a client refusal, so its
        // settled record must carry failed/refused/client_refused -- not the not_run fallthrough a
        // bare settle produces, which reads as "nothing was attempted".
        val engine = engine()
        val d = dispatcher()
        val t = token()
        d.dispatch(
            scope = scope(),
            engine = engine,
            executor = executor { _, _ -> VoiceResponse.Silent },
            render = { _, _ -> },
            onCommandRecognized = { },
            intent = VoiceIntent.Playback.Pause,
            invocationToken = null,
        )
        d.dispatch(
            scope = scope(),
            engine = engine,
            executor = executor { _, _ -> error("debounced: must not run") },
            render = { _, _ -> },
            onCommandRecognized = { },
            intent = VoiceIntent.Playback.Pause,
            invocationToken = t,
        )
        verify(engine, times(1)).reportExecutionResult(eq(t), eq(ExecutionOutcome.Refused))
    }

    @Test
    fun `facts reported through the invocation-local channel reach the engine by token`() = runBlocking {
        val engine = engine()
        val t = token()
        val job = dispatcher().dispatch(
            scope = scope(),
            engine = engine,
            executor = executor { _, onOutcome ->
                onOutcome?.invoke(VoiceActionOutcome.Performed)
                onOutcome?.invoke(VoiceActionOutcome.Failed("later"))
                VoiceResponse.Silent
            },
            render = { _, _ -> },
            onCommandRecognized = { },
            intent = VoiceIntent.Playback.Pause,
            invocationToken = t,
        )
        job?.join()
        // BOTH per-action facts are delivered (preservation is the engine's aggregation input);
        // presentation mapping never turns Dispatched into a claim.
        verify(engine, times(1)).reportExecutionResult(eq(t), eq(ExecutionOutcome.Success))
        verify(engine, times(1)).reportExecutionResult(eq(t), eq(ExecutionOutcome.Failure))
        Unit
    }

    /**
     * The completion arrangements, FORCED one test each, with PRODUCTION dispatch only (no seam):
     * the handler runs on whichever thread completes the job, so each test chooses the completing
     * thread deterministically. BOTH assert the contract: the invocation settles exactly once and
     * no live timing record remains. The third shape -- the job completing before invokeOnCompletion
     * registers -- has no test-side control point (registration sits inside dispatch, nanoseconds
     * after launch), and the contract is thread-independent (settlement is one-shot per token), so
     * it needs no test of its own.
     */
    private class CleanupProbe {
        val bodyStarted = java.util.concurrent.CountDownLatch(1)
        val bodyRelease = java.util.concurrent.CountDownLatch(1)
        val cleanupEntered = java.util.concurrent.CountDownLatch(1)
        val settleCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val cleanupThread = java.util.concurrent.atomic.AtomicLong(0)

        fun answer(): Boolean {
            settleCalls.incrementAndGet()
            cleanupThread.set(Thread.currentThread().threadId())
            cleanupEntered.countDown()
            return true
        }
    }

    private fun runCleanupShape(id: String, cancelFromAnotherThread: Boolean) {
        val probe = CleanupProbe()
        val engine = engine()
        org.mockito.kotlin.whenever(engine.settleInvocation(org.mockito.kotlin.any()))
            .thenAnswer { probe.answer() }
        val published = CompletableDeferred<Unit>()
        val previousSink = au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.sink
        au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.sink = { _, message ->
            if (message.contains(id)) published.complete(Unit)
        }
        try {
            val d = dispatcher()
            val record = au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord(id)
            record.mark(au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.Stage.AcousticEnd, 1_000_000_000L)
            au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.registerFor(id, record)

            // FORCED ARRANGEMENT via the SCOPE'S job, not the launch dispatcher:
            // - INLINE: an already-cancelled scope. The child completes (cancelled before the body)
            //   at creation, on the CALLING thread -- the handler runs inline inside dispatch().
            // - ASYNC: a live scope whose body parks in the executor. Cancelling from the test
            //   thread marks the job cancelling; the parked body unwinds on its IO worker when
            //   released, so the job completes on the WORKER thread and the handler runs there.
            val scopeFor = CoroutineScope(
                (
                    if (!cancelFromAnotherThread) {
                        // FORCED NO-BODY: an already-cancelled scope. The child completes cancelled
                        // before its body ever dispatches to the executor.
                        kotlinx.coroutines.SupervisorJob().apply { cancel() }
                    } else {
                        // FORCED ASYNC: a live scope. The body parks in the executor; cancelling marks
                        // the job cancelling, and the parked body unwinds on its IO worker when
                        // released, so the job completes on the WORKER thread. The body then throws
                        // (the dispatch is dead), which the handler swallows: an unhandled throw would
                        // leak into the global uncaught-exception monitor of the NEXT test class.
                        SupervisorJob()
                    }
                ) +
                    kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> },
            )
            val job = d.dispatch(
                scope = scopeFor,
                engine = engine,
                executor = executor { _, _ ->
                    probe.bodyStarted.countDown()
                    probe.bodyRelease.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    error("a cancelled dispatch must never complete its body")
                },
                render = { _, _ -> },
                onCommandRecognized = { },
                intent = VoiceIntent.Playback.Pause,
                invocationToken = token(),
                timingRequestId = id,
            )
            assertNotNull("a dispatch still returns its job", job)
            if (!cancelFromAnotherThread) {
                // Cancelled scope: the child completes cancelled without its body ever dispatching.
                assertTrue("the cleanup must run", probe.cleanupEntered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            } else {
                assertTrue("the body must have started", probe.bodyStarted.await(10, java.util.concurrent.TimeUnit.SECONDS))
                // Cancelling only MARKS the job cancelling; the parked body must unwind for the job
                // to COMPLETE, so release it and then observe the handler on the completing thread.
                job!!.cancel()
                probe.bodyRelease.countDown()
                // The record was handed to the executor (the body reached execute), so finishing
                // it is the executor's finally -- simulated here, as the real one does on exit.
                au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.finishFor(id, cancelled = true, failed = false)
                assertTrue("the cleanup must run", probe.cleanupEntered.await(10, java.util.concurrent.TimeUnit.SECONDS))
                org.junit.Assert.assertNotEquals(
                    "a cancelled-mid-body dispatch settles on the completing worker thread, not the test's",
                    Thread.currentThread().threadId(),
                    probe.cleanupThread.get(),
                )
            }
            job?.let { j -> runBlocking { withTimeout(10_000) { j.join() } } }

            runBlocking {
                withTimeout(10_000) {
                    withContext(Dispatchers.Default) { published.await() }
                }
            }
            assertFalse(
                "after the handler finished its work the record must be finished",
                au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.isActive(id),
            )
            assertEquals("the invocation must settle exactly once", 1, probe.settleCalls.get())
        } finally {
            probe.bodyRelease.countDown()
            au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.sink = previousSink
        }
    }

    @Test
    fun `cancelled scope - cleanup settles with the body never run and leaves no live record`() {
        runCleanupShape(id = "wire-cleanup-inline", cancelFromAnotherThread = false)
    }

    @Test
    fun `cancelled mid-body - cleanup runs on the body worker and leaves no live record`() {
        runCleanupShape(id = "wire-cleanup-async", cancelFromAnotherThread = true)
    }
}
