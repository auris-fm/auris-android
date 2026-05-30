package au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordDetectorTest {

    // ── CommandWindow tests ──

    @Test
    fun `CommandWindow opens on wake word detection`() {
        val window = CommandWindow()
        assertFalse(window.isActive)
        window.onWakeWord()
        assertTrue(window.isActive)
    }

    @Test
    fun `CommandWindow stays open across follow-up utterances`() {
        val window = CommandWindow()
        window.onWakeWord()
        assertTrue(window.isActive)
        // Follow-up activity should keep window open
        window.onActivity()
        assertTrue(window.isActive)
        // A second activity call should also keep it open
        window.onActivity()
        assertTrue(window.isActive)
    }

    @Test
    fun `CommandWindow closes after silence exceeding conversation timeout`() {
        // Use a very short timeout for testing
        val shortTimeoutMs = 50L
        val window = CommandWindow(conversationTimeoutMs = shortTimeoutMs)

        window.onWakeWord()
        assertTrue(window.isActive)

        // Wait past the timeout
        Thread.sleep(shortTimeoutMs + 20)

        // Now isActive should detect the timeout and close
        assertFalse(window.isActive)
    }

    @Test
    fun `CommandWindow onActivity refreshes the window without re-opening`() {
        val shortTimeoutMs = 100L
        val window = CommandWindow(conversationTimeoutMs = shortTimeoutMs)

        window.onWakeWord()
        assertTrue(window.isActive)

        // Sleep halfway through timeout
        Thread.sleep(shortTimeoutMs / 2)
        // Activity refreshes
        window.onActivity()
        assertTrue(window.isActive)

        // Should still be active even if original open would have timed out
        Thread.sleep(shortTimeoutMs / 2 + 20)
        assertTrue(window.isActive)

        // But now sleep past the full timeout from the last refresh
        Thread.sleep(shortTimeoutMs)
        assertFalse(window.isActive)
    }

    @Test
    fun `CommandWindow close immediately sets inactive`() {
        val window = CommandWindow()
        window.onWakeWord()
        assertTrue(window.isActive)
        window.close()
        assertFalse(window.isActive)
    }

    @Test
    fun `CommandWindow reset clears state`() {
        val window = CommandWindow()
        window.onWakeWord()
        window.reset()
        assertFalse(window.isActive)
    }

    @Test
    fun `CommandWindow second onWakeWord while open refreshes window`() {
        val shortTimeoutMs = 100L
        val window = CommandWindow(conversationTimeoutMs = shortTimeoutMs)

        window.onWakeWord()
        Thread.sleep(shortTimeoutMs / 2)
        // Another wake word while already open
        val result = window.onWakeWord()
        assertTrue(result)
        assertTrue(window.isActive)

        // Should still be alive after original timeout
        Thread.sleep(shortTimeoutMs / 2 + 20)
        assertTrue(window.isActive)
    }

    // ── BuiltInKeywordDetector tests ──

    @Test
    fun `BuiltInKeywordDetector isReady is true`() {
        val detector = BuiltInKeywordDetector()
        assertTrue(detector.isReady)
    }

    @Test
    fun `BuiltInKeywordDetector returns not-detected for placeholder impl`() = runTest {
        val detector = BuiltInKeywordDetector()
        val result = detector.detect(FloatArray(1600), sampleRateHz = 16000)
        assertFalse(result.detected)
        assertEquals(0f, result.confidence)
    }

    // ── CustomKeywordDetector tests ──

    @Test
    fun `CustomKeywordDetector not enrolled by default`() {
        val detector = CustomKeywordDetector()
        assertFalse(detector.isEnrolled())
        assertFalse(detector.isReady)
    }

    @Test
    fun `CustomKeywordDetector becomes ready after enrollment`() {
        val detector = CustomKeywordDetector()
        val samples = listOf(FloatArray(1600) { 0.1f }, FloatArray(1600) { 0.2f })
        detector.enroll(samples)
        assertTrue(detector.isEnrolled())
        assertTrue(detector.isReady)
    }

    @Test
    fun `CustomKeywordDetector clears after clearEnrollment`() {
        val detector = CustomKeywordDetector()
        detector.enroll(listOf(FloatArray(1600)))
        assertTrue(detector.isEnrolled())
        detector.clearEnrollment()
        assertFalse(detector.isEnrolled())
        assertFalse(detector.isReady)
    }

    @Test
    fun `CustomKeywordDetector returns not-detected for placeholder`() = runTest {
        val detector = CustomKeywordDetector()
        detector.enroll(listOf(FloatArray(1600)))
        val result = detector.detect(FloatArray(1600), sampleRateHz = 16000)
        assertFalse(result.detected)
        assertEquals(0f, result.confidence)
    }

    @Test
    fun `CustomKeywordDetector release clears enrollment`() {
        val detector = CustomKeywordDetector()
        detector.enroll(listOf(FloatArray(1600)))
        detector.release()
        assertFalse(detector.isEnrolled())
        assertFalse(detector.isReady)
    }
}
