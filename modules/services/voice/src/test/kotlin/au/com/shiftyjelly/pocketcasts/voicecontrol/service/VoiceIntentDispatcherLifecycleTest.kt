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

    @Test
    fun `a dispatch cancelled before its body runs leaves no live timing record`() {
        // The executor's finally is what normally finishes the record. A launch cancelled BEFORE
        // its body first executes never reaches it, so without an owner in invokeOnCompletion the
        // registry keeps one live entry per such turn -- and a superseded turn is cancelled
        // routinely.
        // The cleanup handler settles the invocation BEFORE it finishes the record. Blocking that
        // settle on a latch the test holds pins the handler between "started" and "finished", so
        // the assertions below run while the record is provably still owed. Blocking only when
        // called OFF the test thread: if the job finished before invokeOnCompletion was registered,
        // the handler runs inline inside dispatch() and the blocked-state check could never happen.
        val handlerStarted = java.util.concurrent.CountDownLatch(1)
        val releaseHandler = java.util.concurrent.CountDownLatch(1)
        val ranOnTestThread = java.util.concurrent.atomic.AtomicBoolean(false)
        val testThreadId = Thread.currentThread().threadId()
        val engine = engine()
        org.mockito.kotlin.whenever(engine.settleInvocation(org.mockito.kotlin.any()))
            .thenAnswer {
                if (Thread.currentThread().threadId() == testThreadId) {
                    ranOnTestThread.set(true)
                } else {
                    handlerStarted.countDown()
                    releaseHandler.await(10, java.util.concurrent.TimeUnit.SECONDS)
                }
                true
            }
        val id = "wire-cancelled-before-body"
        // A publish wait with a bound, registered as the sink: the deterministic replacement for
        // join(), which can return before the completion handlers have run. The sink completes
        // only on THIS record's message, so a straggler from another test cannot satisfy it.
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

            // Already-cancelled scope: the body never gets to run.
            val cancelled = CoroutineScope(
                kotlinx.coroutines.SupervisorJob().apply { cancel() } + Dispatchers.Default,
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
            )
            assertNotNull("a dispatch still returns its job", job)

            // The handler must be observed running OFF the test thread and then blocked. If it ran
            // inline instead, this setup cannot prove anything about join() ordering — fail loudly.
            org.junit.Assert.assertTrue(
                "the cleanup handler must start on another thread within 10s (inline on test thread: " +
                    ranOnTestThread.get() + ")",
                handlerStarted.await(10, java.util.concurrent.TimeUnit.SECONDS),
            )
            org.junit.Assert.assertFalse(
                "while the handler is blocked the record must not be published yet",
                published.isCompleted,
            )
            org.junit.Assert.assertTrue(
                "while the cleanup handler is blocked the record must still be live",
                au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.isActive(id),
            )

            releaseHandler.countDown()
            runBlocking {
                withTimeout(10_000) {
                    withContext(Dispatchers.Default) { published.await() }
                }
            }
            assertFalse(
                "after the handler finished its work the record must be finished",
                au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.isActive(id),
            )
        } finally {
            // Release the handler FIRST so a failed assertion cannot leave it parked for 10 s,
            // then restore the default sink so later tests in this JVM publish where they expect.
            releaseHandler.countDown()
            au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.TurnTimingRecord.sink = previousSink
        }
    }
}
