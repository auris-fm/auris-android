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
}
