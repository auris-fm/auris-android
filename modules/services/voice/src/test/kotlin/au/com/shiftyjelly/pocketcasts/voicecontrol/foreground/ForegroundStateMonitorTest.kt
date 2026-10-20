@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package au.com.shiftyjelly.pocketcasts.voicecontrol.foreground

import au.com.shiftyjelly.pocketcasts.repositories.playback.AppLifecycleProvider
import au.com.shiftyjelly.pocketcasts.sharedtest.MainCoroutineRule
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The production foreground/grace connection: background preserves cloud holds and closes idle grace. Driven through
 * the real manager and a real signal so the closure is observed rather than inferred, and so severing
 * the manager's call fails here.
 */
class ForegroundStateMonitorTest {
    @get:Rule
    val coroutineRule = MainCoroutineRule()

    @Test
    fun `backgrounding preserves the running cloud hold and its window ownership`() = runTest {
        val foreground = MutableStateFlow(true)
        val lifecycle = object : AppLifecycleProvider {
            override val isInForeground = foreground
        }
        val signal = GracePeriodSignal(timeoutMs = 100L)
        ForegroundStateMonitor(lifecycle, backgroundScope, signal)
        runCurrent()
        signal.onWakeWordDetected()
        val origin = signal.currentGeneration
        val hold = signal.holdCloudTurn(origin, owner = 7)!!
        val closures = signal.privacyClosureCount()
        var ended = 0
        signal.onWindowEnded = { ended++ }
        foreground.value = false
        runCurrent()
        assertTrue("background alone must preserve the held conversation", signal.isActive.value)
        assertTrue(signal.isCurrent(origin))
        assertEquals(closures, signal.privacyClosureCount())
        assertEquals(0, ended)
        advanceTimeBy(350L)
        runCurrent()
        assertTrue(signal.isActive.value)
        signal.finishCloudTurn(hold)
        advanceTimeBy(99L)
        runCurrent()
        assertTrue(signal.isActive.value)
        advanceTimeBy(1L)
        runCurrent()
        assertFalse(signal.isActive.value)
        assertEquals(1, ended)
    }

    @Test
    fun `losing the foreground closes the window and advances the closure count`() = runTest {
        val foreground = MutableStateFlow(true)
        val lifecycle = object : AppLifecycleProvider {
            override val isInForeground = foreground
        }
        val signal = GracePeriodSignal(timeoutMs = 60_000L)
        // Unconfined so the production collect observes the transition eagerly; the assertion is about
        // the manager's behaviour, not about scheduler timing.
        val monitor = ForegroundStateMonitor(
            lifecycle,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            signal,
        )
        signal.onWakeWordDetected()
        assertTrue("the wake opens a window", signal.isActive.value)
        val closuresBefore = signal.privacyClosureCount()
        foreground.value = false

        assertTrue("losing the foreground must close the window", signal.isClosedByPrivacy())
        assertFalse("a privacy closure ends the window", signal.isActive.value)
        assertEquals(
            "exactly one closure must be counted",
            closuresBefore + 1,
            signal.privacyClosureCount(),
        )
    }
}
