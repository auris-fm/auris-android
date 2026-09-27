package au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals

import au.com.shiftyjelly.pocketcasts.sharedtest.MainCoroutineRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GracePeriodSignalTest {

    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    @Test
    fun `the cloud escalation allowance is one per window generation`() {
        val signal = GracePeriodSignal(timeoutMs = 100L)

        // No window is open, so there is no user act to spend against. (This is
        // not the false-wake case: a false wake opens a window. It is an
        // utterance with no act behind it at all.)
        assertNull(signal.issueEscalation())

        // A wake opens a generation and its one dispatch.
        signal.onWakeWordDetected()
        val first = signal.issueEscalation()
        assertNotNull(first)
        assertNull("once per generation", signal.issueEscalation())

        // A locally handled command is another deliberate act, so it begins the
        // next generation and earns its own dispatch.
        signal.onCommandRecognized()
        val second = signal.issueEscalation()
        assertNotNull(second)
        assertNotEquals(first, second)
        assertNull(signal.issueEscalation())

        // A privacy event ends the generation; only a new wake begins another.
        signal.onAudioRouteChanged()
        assertNull(signal.issueEscalation())
        signal.onWakeWordDetected()
        assertNotNull(signal.issueEscalation())

        signal.onAppBackgrounded()
        assertNull(signal.issueEscalation())
        signal.onWakeWordDetected()
        assertNotNull(signal.issueEscalation())
    }

    @Test
    fun `a current completion extends the window through the timer`() = runTest {
        // Extending is observable through the timer: a completion that extends
        // pushes expiry past the original deadline.
        val signal = GracePeriodSignal(timeoutMs = 100L)
        signal.onWakeWordDetected()
        val generation = signal.issueEscalation()!!

        delay(80L)
        signal.onCommandRecognized(fromGeneration = generation)
        delay(40L)

        assertTrue("expiry moved out past the original deadline", signal.isActive.value)
    }

    @Test
    fun `a completion from a superseded window does not extend the new one`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 100L)
        signal.onWakeWordDetected()
        val superseded = signal.issueEscalation()!!

        // Privacy close, then a fresh wake: a new window and a new generation.
        signal.onAppBackgrounded()
        signal.onWakeWordDetected()

        delay(80L)
        // The old turn lands now. It belongs to a window that is gone, so it
        // must not hold the new one open — that is the difference between a
        // state check and an identity check.
        signal.onCommandRecognized(fromGeneration = superseded)
        delay(40L)
        assertFalse("a superseded completion must not extend", signal.isActive.value)
    }

    @Test
    fun `a completion after a privacy close does not reopen the window`() {
        val signal = GracePeriodSignal(timeoutMs = 30_000L)

        signal.onWakeWordDetected()
        val generation = signal.issueEscalation()!!

        // The network turn is still in flight when the privacy event lands.
        signal.onAppBackgrounded()
        signal.onCommandRecognized(fromGeneration = generation)

        assertFalse("a completion must not undo a close", signal.isActive.value)
        assertNull(signal.issueEscalation())
    }

    @Test
    fun `a privacy close is final until a new wake`() {
        val signal = GracePeriodSignal(timeoutMs = 30_000L)

        signal.onWakeWordDetected()
        val generation = signal.issueEscalation()!!
        signal.onAppBackgrounded()

        // No caller can reopen it: not a command, not a completion.
        signal.onCommandRecognized()
        assertFalse(signal.isActive.value)
        signal.onCommandRecognized(fromGeneration = generation)
        assertFalse(signal.isActive.value)

        // A fresh wake is a new act, so it opens a window again.
        signal.onWakeWordDetected()
        assertTrue(signal.isActive.value)
    }

    @Test
    fun `an expired or closed window refuses the escalation`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 100L)

        signal.onWakeWordDetected()
        delay(150L)
        assertFalse(signal.isActive.value)
        assertNull("an expired window is not an open act", signal.issueEscalation())

        signal.onWakeWordDetected()
        signal.onAudioRouteChanged()
        assertNull("a privacy-closed window is not an open act", signal.issueEscalation())

        signal.onWakeWordDetected()
        signal.onAppBackgrounded()
        assertNull("a privacy-closed window is not an open act", signal.issueEscalation())
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
