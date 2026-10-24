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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
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
        on { runBlocking { execute(any(), any()) } }.thenAnswer { inv ->
            @Suppress("UNCHECKED_CAST")
            val intent = inv.getArgument<VoiceIntent>(0)
            val onOutcome = inv.getArgument<((VoiceActionOutcome) -> Unit)?>(1)
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
}
