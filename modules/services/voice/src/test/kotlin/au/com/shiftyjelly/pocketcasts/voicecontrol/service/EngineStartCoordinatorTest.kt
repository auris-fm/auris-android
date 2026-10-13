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
 * Successful acquisition changes the route to an isolated headset, which emits a new listening
 * mode and sends the mode-flow collector into `startEngine` while the acquisition's own start is
 * still suspended on `ensureReady()`. Without serialization both pass the "not started" check and
 * call `VoiceAsrEngine.start` twice, overwriting the processing job and double-starting native
 * capture. These pin the single-flight rule the service relies on.
 */
class EngineStartCoordinatorTest {

    @Test
    fun `a second start waits for the in-flight one and does not start a second engine`() = runTest {
        val coordinator = EngineStartCoordinator()
        var invocations = 0
        val inFlight = CompletableDeferred<Unit>()

        val first = launch {
            coordinator.startIfNotRunning {
                invocations++
                inFlight.await()
                true
            }
        }
        runCurrent()
        val second = launch {
            coordinator.startIfNotRunning {
                invocations++
                true
            }
        }
        runCurrent()

        // The second caller must still be waiting on the first, not starting its own engine.
        assertEquals(1, invocations)

        inFlight.complete(Unit)
        advanceUntilIdle()
        first.join()
        second.join()

        assertEquals(1, invocations)
        assertTrue(coordinator.isStarted)
    }

    @Test
    fun `a failed start is not marked started and the next caller retries`() = runTest {
        val coordinator = EngineStartCoordinator()
        var invocations = 0

        val first = coordinator.startIfNotRunning {
            invocations++
            false
        }

        assertFalse(first)
        assertFalse(coordinator.isStarted)

        val second = coordinator.startIfNotRunning {
            invocations++
            true
        }

        assertTrue(second)
        assertTrue(coordinator.isStarted)
        assertEquals(2, invocations)
    }

    @Test
    fun `a running engine is not started again`() = runTest {
        val coordinator = EngineStartCoordinator()
        var invocations = 0
        coordinator.startIfNotRunning {
            invocations++
            true
        }

        val again = coordinator.startIfNotRunning {
            invocations++
            true
        }

        assertTrue(again)
        assertTrue(coordinator.isStarted)
        assertEquals(1, invocations)
    }

    @Test
    fun `stopping allows a later start`() = runTest {
        val coordinator = EngineStartCoordinator()
        var invocations = 0
        coordinator.startIfNotRunning {
            invocations++
            true
        }

        coordinator.onStopped()
        assertFalse(coordinator.isStarted)

        coordinator.startIfNotRunning {
            invocations++
            true
        }
        assertEquals(2, invocations)
    }

    /**
     * The engine attaches the acquired stream inside `VoiceAsrEngine.start`; the earcon and the
     * listening notification are side effects that run afterwards. A throw from one of them must
     * not make the start report failure: `VoiceControlService.acquireCandidate` reads this result
     * to decide between handing the acquired stream to the engine and releasing it, and a false
     * result would close a stream the engine already owns (and, because the coordinator would not
     * be marked started, let the next mode emission start a second engine).
     */
    @Test
    fun `a post-start side effect failure does not un-start the engine`() = runTest {
        val coordinator = EngineStartCoordinator()
        var engineStarts = 0
        var sideEffectRan = false

        val result = coordinator.startIfNotRunning(
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
        assertTrue("the engine must remain marked started", coordinator.isStarted)
        assertTrue(sideEffectRan)
        assertEquals(1, engineStarts)

        // A second caller must still see a running engine, not start a second one.
        val again = coordinator.startIfNotRunning(
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
     * The acquisition caller clears its own job before it suspends in `ensureReady()`, so a gate
     * loss cannot cancel it. A generation captured before the attempt lets the start lambda
     * re-check, after the suspension, whether the attempt is still wanted; an invalidated
     * attempt must not commit capture.
     */
    @Test
    fun `a gate loss during a suspended start invalidates the pending attempt`() = runTest {
        val coordinator = EngineStartCoordinator()
        var committed = false
        val ready = CompletableDeferred<Unit>()

        val job = launch {
            val generation = coordinator.generation()
            coordinator.startIfNotRunning {
                ready.await() // stands in for backend.ensureReady()
                if (!coordinator.isCurrent(generation)) return@startIfNotRunning false
                committed = true
                true
            }
        }

        runCurrent()
        // Gate loss while the start is suspended: the mode goes Off, but no engine is running
        // yet, so only this invalidation can stop the pending start.
        coordinator.invalidatePendingStart()
        ready.complete(Unit)
        advanceUntilIdle()
        job.join()

        assertFalse("a stale start must not commit capture", committed)
        assertFalse("a stale start must not be recorded as running", coordinator.isStarted)
    }

    @Test
    fun `invalidate only affects attempts that captured an older generation`() = runTest {
        val coordinator = EngineStartCoordinator()

        val stale = coordinator.generation()
        coordinator.invalidatePendingStart()
        val fresh = coordinator.generation()

        assertFalse(coordinator.isCurrent(stale))
        assertTrue(coordinator.isCurrent(fresh))

        var committed = false
        val started = coordinator.startIfNotRunning {
            committed = coordinator.isCurrent(fresh)
            committed
        }
        assertTrue(started)
        assertTrue(committed)
        assertTrue(coordinator.isStarted)
    }
}
