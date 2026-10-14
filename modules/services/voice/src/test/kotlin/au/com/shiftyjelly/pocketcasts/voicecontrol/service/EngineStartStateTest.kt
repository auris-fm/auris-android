@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ASR engine has a single starter — the mode-flow collector — so no start lock exists; these
 * pin the started flag and the generation that lets a start suspended in the backend readiness
 * wait abandon instead of committing after a stop or a gate loss.
 */
class EngineStartStateTest {

    @Test
    fun `a failed start is not marked started and the next caller retries`() = runTest {
        val state = EngineStartState()
        var invocations = 0

        val first = state.startIfNotRunning {
            invocations++
            false
        }

        assertFalse(first)
        assertFalse(state.isStarted)

        val second = state.startIfNotRunning {
            invocations++
            true
        }

        assertTrue(second)
        assertTrue(state.isStarted)
        assertEquals(2, invocations)
    }

    @Test
    fun `a running engine is not started again`() = runTest {
        val state = EngineStartState()
        var invocations = 0
        state.startIfNotRunning {
            invocations++
            true
        }

        val again = state.startIfNotRunning {
            invocations++
            true
        }

        assertTrue(again)
        assertTrue(state.isStarted)
        assertEquals(1, invocations)
    }

    @Test
    fun `stopping allows a later start`() = runTest {
        val state = EngineStartState()
        var invocations = 0
        state.startIfNotRunning {
            invocations++
            true
        }

        state.onStopped()
        assertFalse(state.isStarted)

        state.startIfNotRunning {
            invocations++
            true
        }
        assertEquals(2, invocations)
    }

    /**
     * The engine attaches the confirmed stream inside `VoiceAsrEngine.start`, and the earcon and
     * listening notification are side effects that run afterwards. A throw from one of them must
     * not un-set the started state, or the next mode emission would start a second engine over the
     * live one.
     */
    @Test
    fun `a post-start side effect failure does not un-start the engine`() = runTest {
        val state = EngineStartState()
        var engineStarts = 0
        var sideEffectRan = false

        val result = state.startIfNotRunning(
            start = {
                engineStarts++
                true
            },
            onStarted = {
                sideEffectRan = true
                throw IllegalStateException("earcon/notification failed")
            },
        )

        assertTrue("a side-effect failure must not be reported as a failed start", result)
        assertTrue("the engine must remain marked started", state.isStarted)
        assertTrue(sideEffectRan)
        assertEquals(1, engineStarts)

        val again = state.startIfNotRunning(
            start = {
                engineStarts++
                true
            },
            onStarted = {},
        )
        assertTrue(again)
        assertEquals(1, engineStarts)
    }

    /**
     * A stop can arrive while a start is suspended in `ensureReady()`; the start must then abandon
     * rather than commit capture for a service that is shutting down.
     */
    @Test
    fun `a stop during a suspended start invalidates the pending attempt`() = runTest {
        val state = EngineStartState()
        var committed = false
        val ready = CompletableDeferred<Unit>()

        val job = launch {
            val generation = state.generation()
            state.startIfNotRunning {
                ready.await() // stands in for backend.ensureReady()
                if (!state.isCurrent(generation)) return@startIfNotRunning false
                committed = true
                true
            }
        }

        runCurrent()
        state.invalidatePendingStart()
        ready.complete(Unit)
        advanceUntilIdle()
        job.join()

        assertFalse("a stale start must not commit capture", committed)
        assertFalse("a stale start must not be recorded as running", state.isStarted)
    }

    @Test
    fun `invalidate only affects attempts that captured an older generation`() = runTest {
        val state = EngineStartState()

        val stale = state.generation()
        state.invalidatePendingStart()
        val fresh = state.generation()

        assertFalse(state.isCurrent(stale))
        assertTrue(state.isCurrent(fresh))

        var committed = false
        val started = state.startIfNotRunning {
            committed = state.isCurrent(fresh)
            committed
        }
        assertTrue(started)
        assertTrue(committed)
        assertTrue(state.isStarted)
    }
}
