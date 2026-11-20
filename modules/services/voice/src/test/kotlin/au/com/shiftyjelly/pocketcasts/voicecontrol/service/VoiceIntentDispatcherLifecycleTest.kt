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
     * The two completion interleavings, FORCED one test each (@spec 5fd4864e): whether the cleanup
     * handler runs inline on the dispatching thread or asynchronously is decided by whether the
     * job completed before invokeOnCompletion registered. A single test that accepts whichever
     * shape occurs can pass without ever checking the other, so each shape gets its own test with
     * its own deterministic arrangement. BOTH assert the contract: the invocation settles exactly
     * once and no live timing record remains.
     */
    private class CleanupProbe(private val testThreadId: Long) {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val settleCalls = java.util.concurrent.atomic.AtomicInteger(0)
        var inline = false
            private set

        fun answer(): Boolean {
            settleCalls.incrementAndGet()
            if (Thread.currentThread().threadId() == testThreadId) {
                // INLINE shape: registration happened after completion; the handler runs inside
                // dispatch(). Nothing may block here -- dispatch() itself is on this thread.
                inline = true
            } else {
                // ASYNC shape: park between settle and finish so the test can observe the owed
                // record while the handler is provably mid-flight.
                started.countDown()
                release.await(10, java.util.concurrent.TimeUnit.SECONDS)
            }
            return true
        }
    }

    private fun runCleanupShape(
        id: String,
        forceInline: Boolean,
    ) {
        val probe = CleanupProbe(Thread.currentThread().threadId())
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

            // FORCED INTERLEAVING via the launch dispatcher's queue, not luck:
            // - INLINE: an already-cancelled scope whose dispatcher RUNS the launch task eagerly on
            //   this thread. The coroutine completes (cancelled before body) inside scope.launch,
            //   strictly before .also registers invokeOnCompletion -- the handler must run inline.
            // - ASYNC: an already-cancelled scope whose dispatcher QUEUES the task without running
            //   it. Registration happens first; releasing the queue afterwards completes the job on
            //   another thread, so the handler runs there, parked on the probe's latch.
            val queued = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
            val eager = object : kotlinx.coroutines.CoroutineDispatcher() {
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                    if (forceInline) block.run() else queued.add(block)
                }
            }
            val cancelled = CoroutineScope(
                kotlinx.coroutines.SupervisorJob().apply { cancel() } + eager,
            )
            val job = d.dispatch(
                scope = cancelled,
                engine = engine,
                executor = executor { _, _ -> error("the body must never run in this test") },
                render = { _, _ -> },
                onCommandRecognized = { },
                intent = VoiceIntent.Playback.Pause,
                invocationToken = token(),
                timingRequestId = id,
                launchDispatcher = eager,
            )
            assertNotNull("a dispatch still returns its job", job)

            if (forceInline) {
                assertTrue("the forced-inline shape must have run the handler inline", probe.inline)
            } else {
                // Complete the job on ANOTHER thread, after registration.
                val runner = Thread { queued.forEach { it.run() } }
                runner.start()
                assertTrue(
                    "the async handler must start off the test thread",
                    probe.started.await(10, java.util.concurrent.TimeUnit.SECONDS),
                )
                assertFalse("the forced-async shape must not have run inline", probe.inline)
                org.junit.Assert.assertFalse(
                    "while the handler is blocked the record must not be published yet",
                    published.isCompleted,
                )
                org.junit.Assert.assertTrue(
                    "while the cleanup handler is blocked the record must still be live",
                    au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.isActive(id),
                )
                probe.release.countDown()
                runner.join(10_000)
            }
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
            probe.release.countDown()
            au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.sink = previousSink
        }
    }

    @Test
    fun `cancelled before registration - cleanup runs inline and leaves no live timing record`() {
        runCleanupShape(id = "wire-cleanup-inline", forceInline = true)
    }

    @Test
    fun `registered before completion - cleanup runs async and leaves no live timing record`() {
        runCleanupShape(id = "wire-cleanup-async", forceInline = false)
    }
}
