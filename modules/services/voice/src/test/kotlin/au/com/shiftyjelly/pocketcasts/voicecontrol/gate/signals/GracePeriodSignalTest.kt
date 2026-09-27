package au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals

import au.com.shiftyjelly.pocketcasts.sharedtest.MainCoroutineRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GracePeriodSignalTest {

    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    @Test
    fun `the cloud escalation budget is one per window`() {
        val signal = GracePeriodSignal(timeoutMs = 100L)

        // No window is open, so there is no user act to spend against. (This is
        // not the false-wake case: a false wake opens a window. It is an
        // utterance with no act behind it at all.)
        assertFalse(signal.tryConsumeEscalation())

        // A new act opens a fresh budget.
        signal.onWakeWordDetected()
        assertTrue(signal.tryConsumeEscalation())
        assertFalse(signal.tryConsumeEscalation())

        // A recognised command is another deliberate act, so it resets too.
        signal.onCommandRecognized()
        assertTrue(signal.tryConsumeEscalation())

        // The privacy closes end the window; the next wake opens a new one.
        signal.onAudioRouteChanged()
        signal.onWakeWordDetected()
        assertTrue(signal.tryConsumeEscalation())

        signal.onAppBackgrounded()
        signal.onWakeWordDetected()
        assertTrue(signal.tryConsumeEscalation())
    }

    @Test
    fun `handling the escalation extends the window without refreshing its budget`() {
        val signal = GracePeriodSignal(timeoutMs = 100L)

        signal.onWakeWordDetected()
        assertTrue(signal.tryConsumeEscalation())
        signal.onCommandRecognized(fromEscalation = true)
        assertTrue(signal.isActive.value)
        assertFalse(signal.tryConsumeEscalation())

        // A locally recognised command is a different act and does refresh it.
        signal.onCommandRecognized()
        assertTrue(signal.tryConsumeEscalation())
    }

    @Test
    fun `a fallback completing after a privacy close does not reopen the window`() {
        val signal = GracePeriodSignal(timeoutMs = 30_000L)

        signal.onWakeWordDetected()
        assertTrue(signal.tryConsumeEscalation())

        // The network turn is still in flight when the privacy event lands.
        signal.onAppBackgrounded()
        signal.onCommandRecognized(fromEscalation = true)

        assertFalse("a completion must not undo a close", signal.isActive.value)
        assertFalse(signal.tryConsumeEscalation())

        signal.onWakeWordDetected()
        assertTrue(signal.tryConsumeEscalation())
        signal.onAudioRouteChanged()
        signal.onCommandRecognized(fromEscalation = true)

        assertFalse("a completion must not undo a close", signal.isActive.value)
    }

    @Test
    fun `a fallback completing while the window is open still extends it`() {
        val signal = GracePeriodSignal(timeoutMs = 100L)

        signal.onWakeWordDetected()
        assertTrue(signal.tryConsumeEscalation())
        signal.onCommandRecognized(fromEscalation = true)

        assertTrue(signal.isActive.value)
        assertFalse("and does not re-arm the budget", signal.tryConsumeEscalation())
    }

    @Test
    fun `an expired or closed window refuses the escalation`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 100L)

        signal.onWakeWordDetected()
        delay(150L)
        assertFalse(signal.isActive.value)
        assertFalse("an expired window is not an open act", signal.tryConsumeEscalation())

        signal.onWakeWordDetected()
        signal.onAudioRouteChanged()
        assertFalse("a privacy-closed window is not an open act", signal.tryConsumeEscalation())

        signal.onWakeWordDetected()
        signal.onAppBackgrounded()
        assertFalse("a privacy-closed window is not an open act", signal.tryConsumeEscalation())
    }

    @Test
    fun `initially inactive`() {
        val signal = GracePeriodSignal()
        assertFalse(signal.isActive.value)
    }

    @Test
    fun `active after recognized command`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 100L)
        signal.onCommandRecognized()
        assertTrue(signal.isActive.value)
    }

    @Test
    fun `expires after timeout`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 100L)
        signal.onCommandRecognized()
        assertTrue(signal.isActive.value)
        delay(150L)
        assertFalse(signal.isActive.value)
    }

    @Test
    fun `command resets timer`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 200L)
        signal.onCommandRecognized()
        delay(150L)
        signal.onCommandRecognized() // reset
        delay(150L)
        assertTrue(signal.isActive.value) // still active
        delay(60L)
        assertFalse(signal.isActive.value) // 210ms after last command
    }

    @Test
    fun `active after wake word detection`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 100L)
        signal.onWakeWordDetected()
        assertTrue(signal.isActive.value)
    }

    @Test
    fun `wake word detection expires after timeout`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 100L)
        signal.onWakeWordDetected()
        assertTrue(signal.isActive.value)
        delay(150L)
        assertFalse(signal.isActive.value)
    }

    @Test
    fun `wake word resets the same timer as command`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 200L)
        signal.onCommandRecognized()
        delay(150L)
        signal.onWakeWordDetected() // resets the shared timer
        delay(150L)
        assertTrue(signal.isActive.value) // still active
        delay(60L)
        assertFalse(signal.isActive.value) // 210ms after wake word
    }

    @Test
    fun `route change breaks grace period`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 200L)
        signal.onCommandRecognized()
        assertTrue(signal.isActive.value)
        signal.onAudioRouteChanged()
        assertFalse(signal.isActive.value)
    }

    @Test
    fun `app switch breaks grace period`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 200L)
        signal.onCommandRecognized()
        assertTrue(signal.isActive.value)
        signal.onAppBackgrounded()
        assertFalse(signal.isActive.value)
    }
}
