package au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals

import au.com.shiftyjelly.pocketcasts.sharedtest.MainCoroutineRule
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GraceCloudTurnHoldTest {
    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    @Test
    fun `only a live matching origin can acquire a hold`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 100L)
        assertNull(signal.holdCloudTurn())
        signal.onWakeWordDetected()
        assertNull(signal.holdCloudTurn(signal.currentGeneration - 1L))
        advanceTimeBy(100L)
        runCurrent()
        assertFalse(signal.isActive.value)
        assertNull(signal.holdCloudTurn(signal.currentGeneration))
    }

    @Test
    fun `overlapping turns release independently and only the last starts inactivity`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 100L)
        signal.onWakeWordDetected()
        val first = signal.holdCloudTurn()!!
        val second = signal.holdCloudTurn()!!
        signal.finishCloudTurn(first)
        advanceTimeBy(300L)
        runCurrent()
        assertTrue(signal.isActive.value)
        signal.finishCloudTurn(second)
        advanceTimeBy(100L)
        runCurrent()
        assertFalse(signal.isActive.value)
    }

    @Test
    fun `duplicate completion does not extend the countdown`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 100L)
        signal.onWakeWordDetected()
        val hold = signal.holdCloudTurn()!!
        signal.finishCloudTurn(hold)
        advanceTimeBy(80L)
        signal.finishCloudTurn(hold)
        advanceTimeBy(20L)
        runCurrent()
        assertFalse(signal.isActive.value)
    }

    @Test
    fun `a reset while held does not arm an expiry and does not mint another allowance`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 100L)
        signal.onWakeWordDetected()
        val origin = signal.issueEscalation()!!
        val hold = signal.holdCloudTurn(origin)!!
        signal.onCommandRecognized(fromGeneration = origin, restoresAllowance = false)
        signal.armTimerForLockedForTest(origin)
        advanceTimeBy(300L)
        runCurrent()
        assertTrue(signal.isActive.value)
        assertEquals(origin, signal.currentGeneration)
        assertNull(signal.issueEscalation(origin))
        signal.finishCloudTurn(hold)
        advanceTimeBy(100L)
        runCurrent()
        assertFalse(signal.isActive.value)
    }

    @Test
    fun `privacy closes held grace and the old completion cannot reopen it`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 100L)
        signal.onWakeWordDetected()
        val hold = signal.holdCloudTurn()!!
        signal.onAudioRouteChanged()
        assertFalse(signal.isActive.value)
        assertNull(signal.holdCloudTurn())
        signal.finishCloudTurn(hold)
        advanceTimeBy(300L)
        runCurrent()
        assertFalse(signal.isActive.value)
    }

    @Test
    fun `a replaced window cannot inherit the old hold or its completion`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 100L)
        signal.onWakeWordDetected()
        val old = signal.holdCloudTurn()!!
        signal.onWakeWordDetected()
        advanceTimeBy(80L)
        signal.finishCloudTurn(old)
        advanceTimeBy(20L)
        runCurrent()
        assertFalse(signal.isActive.value)
        signal.onWakeWordDetected()
        val fresh = signal.holdCloudTurn()!!
        signal.finishCloudTurn(old)
        advanceTimeBy(300L)
        runCurrent()
        assertTrue("a stale completion cannot release the successor's hold", signal.isActive.value)
        signal.finishCloudTurn(fresh)
        signal.onAppBackgrounded()
    }
}
