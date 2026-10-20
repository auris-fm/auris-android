@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals

import au.com.shiftyjelly.pocketcasts.sharedtest.MainCoroutineRule
import au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.LifecycleLogCapture
import au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.VoiceLifecycleDiagnostics.Source
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GraceLifecycleDiagnosticTest {
    @get:Rule val mainCoroutineRule = MainCoroutineRule()

    @Test
    fun `route closure records its actual reason and held owner`() = runTest {
        LifecycleLogCapture().use { log ->
            val signal = GracePeriodSignal(timeoutMs = 100L)
            signal.onWakeWordDetected()
            signal.holdCloudTurn(signal.currentGeneration, owner = 7)
            signal.onAudioRouteChanged(Source.ScoState)
            val lines = log.lines()
            assertTrue(lines.any { it.contains("reason=HoldAcquired") && it.contains("owner=7") && it.contains("holds_after=1") })
            assertTrue(lines.any { it.contains("reason=RouteChanged source=ScoState") && it.contains("holds_before=1 holds_after=0") && it.contains("prior_active=true active=false") })
            assertFalse(signal.isActive.value)
        }
    }

    @Test
    fun `background and timeout are recorded at distinct closing boundaries`() = runTest {
        LifecycleLogCapture().use { log ->
            val signal = GracePeriodSignal(timeoutMs = 100L)
            signal.onWakeWordDetected()
            val first = signal.currentGeneration
            signal.onAppBackgrounded()
            signal.onWakeWordDetected()
            val second = signal.currentGeneration
            advanceTimeBy(100L)
            runCurrent()
            val lines = log.lines()
            assertTrue(lines.any { it.contains("reason=Backgrounded source=Foreground window=$first ") })
            assertTrue(lines.any { it.contains("reason=Timeout source=Grace window=$second ") })
            assertEquals(1, lines.count { it.contains("reason=Timeout") })
        }
    }

    @Test
    fun `successor wake and local command show old and new window and invalidated holds`() = runTest {
        LifecycleLogCapture().use { log ->
            val signal = GracePeriodSignal(timeoutMs = 100L)
            signal.onWakeWordDetected()
            val first = signal.currentGeneration
            val held = signal.holdCloudTurn(first, owner = 9)!!
            signal.onWakeWordDetected()
            val second = signal.currentGeneration
            signal.finishCloudTurn(held)
            signal.onCommandRecognized()
            val third = signal.currentGeneration
            val lines = log.lines()
            assertTrue(lines.any { it.contains("reason=SuccessorWake") && it.contains("window=$second prior_window=$first") && it.contains("holds_before=1 holds_after=0") })
            assertTrue(lines.any { it.contains("reason=SuccessorCommand") && it.contains("window=$third prior_window=$second") })
            assertTrue(lines.any { it.contains("reason=HoldReleaseIgnored") && it.contains("owner=9") && it.contains("requested_window=$first") })
            signal.onAppBackgrounded()
        }
    }

    @Test
    fun `hold refusals distinguish inactivity privacy and stale origin without changing state`() = runTest {
        LifecycleLogCapture().use { log ->
            val signal = GracePeriodSignal()
            assertEquals(null, signal.holdCloudTurn(owner = 1))
            signal.onWakeWordDetected()
            assertEquals(null, signal.holdCloudTurn(signal.currentGeneration - 1, owner = 2))
            signal.onAppBackgrounded()
            assertEquals(null, signal.holdCloudTurn(owner = 3))
            val lines = log.lines()
            for ((reason, owner) in listOf("HoldRejectedInactive" to 1, "HoldRejectedGeneration" to 2, "HoldRejectedPrivacy" to 3)) {
                assertTrue(lines.any { it.contains("reason=$reason") && it.contains("owner=$owner") })
            }
        }
    }

    @Test
    fun `cancellation release restarts the timer and never reports timeout while held`() = runTest {
        LifecycleLogCapture().use { log ->
            val signal = GracePeriodSignal(timeoutMs = 100L)
            signal.onWakeWordDetected()
            val held = signal.holdCloudTurn(owner = 5)!!
            advanceTimeBy(200L)
            runCurrent()
            assertTrue(signal.isActive.value)
            assertFalse(log.lines().any { it.contains("reason=Timeout") })
            signal.finishCloudTurn(held, cancelled = true)
            advanceTimeBy(100L)
            runCurrent()
            val lines = log.lines()
            assertTrue(lines.any { it.contains("reason=HoldCancelled") && it.contains("owner=5") && it.contains("holds_before=1 holds_after=0") })
            assertEquals(1, lines.count { it.contains("reason=Timeout") })
        }
    }
}
