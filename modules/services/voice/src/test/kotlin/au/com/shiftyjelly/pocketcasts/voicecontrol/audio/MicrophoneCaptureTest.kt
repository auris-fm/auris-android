package au.com.shiftyjelly.pocketcasts.voicecontrol.audio

import android.content.Context
import android.content.res.AssetManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Reproduces the restart interleaving behind the "old collector can close the replacement
 * capture" finding: `VoiceAsrEngine.stop()` cancels its collector and opens native teardown
 * before the cancelled collector's flow `finally` has run, so that `finally` must be unable to
 * stop a capture that was opened for the replacement session.
 *
 * The native calls are behind [CaptureHardware], so the exact ordering can be driven directly
 * without Oboe/JNI.
 */
class MicrophoneCaptureTest {

    private class FakeCaptureHardware : CaptureHardware {
        var capturing = false
        var stopCount = 0

        override fun startCaptureAndVad(sampleRate: Int, channels: Int, assetManager: AssetManager): Boolean {
            capturing = true
            return true
        }

        override fun stopCaptureAndVad() {
            stopCount++
            capturing = false
        }

        override fun openInputCapture(sampleRate: Int, channels: Int): Int {
            // Opening replaces the stream but does not start it.
            capturing = false
            return DEVICE_ID
        }

        override fun startInputStream(): Boolean = true

        override fun closeInputCapture() {
            capturing = false
        }

        override fun isCapturing(): Boolean = capturing

        override fun waitForVadEvent(timeoutMs: Int): Int = -1

        override fun getSpeechPcm(buffer: ShortArray): Int = 0

        override fun getSpeechPcmSize(): Int = 0

        override fun getSpeechOnsetSample(): Int = 0

        override fun getSpeechEndSample(): Int = 0

        companion object {
            const val DEVICE_ID = 42
        }
    }

    private fun context(): Context {
        val context = mock(Context::class.java)
        `when`(context.assets).thenReturn(mock(AssetManager::class.java))
        return context
    }

    @Test
    fun `a stale collector teardown cannot stop a stream opened for its replacement`() {
        val hardware = FakeCaptureHardware()
        val capture = MicrophoneCapture(context(), hardware)

        // First capture: open the stream and attach engine A.
        assertTrue(capture.openStreamForAcquisition() >= 0)
        assertTrue(capture.attachCapture())
        val stale = capture.currentEngineForTest()!!
        assertTrue(hardware.capturing)

        // The replacement stream is opened (its own generation) before the cancelled collector's
        // flow `finally` has run. Active engine is still the stale one at this point.
        assertTrue(capture.openStreamForAcquisition() >= 0)

        // The stale collector's cleanup runs now. It must not stop the newly opened stream.
        stale.dispose()

        assertEquals(0, hardware.stopCount)

        // The replacement engine can still attach to the surviving stream.
        assertTrue(capture.attachCapture())
        assertTrue(hardware.capturing)
    }

    @Test
    fun `a stale collector teardown cannot stop an attached replacement capture`() {
        val hardware = FakeCaptureHardware()
        val capture = MicrophoneCapture(context(), hardware)

        capture.openStreamForAcquisition()
        assertTrue(capture.attachCapture())
        val stale = capture.currentEngineForTest()!!

        // Replacement attached before the stale cleanup runs.
        capture.openStreamForAcquisition()
        assertTrue(capture.attachCapture())
        assertTrue(hardware.capturing)

        stale.dispose()

        assertEquals(0, hardware.stopCount)
        assertTrue(hardware.capturing)
    }

    @Test
    fun `stopping the current capture tears the native stream down`() {
        val hardware = FakeCaptureHardware()
        val capture = MicrophoneCapture(context(), hardware)

        capture.openStreamForAcquisition()
        assertTrue(capture.attachCapture())

        capture.stopCapture()

        assertEquals(1, hardware.stopCount)
        assertFalse(hardware.capturing)
    }

    @Test
    fun `closing the acquired stream stops a later stale teardown from touching native capture`() {
        val hardware = FakeCaptureHardware()
        val capture = MicrophoneCapture(context(), hardware)

        capture.openStreamForAcquisition()
        assertTrue(capture.attachCapture())
        val stale = capture.currentEngineForTest()!!

        capture.closeAcquiredStream()
        stale.dispose()

        assertEquals(0, hardware.stopCount)
    }
}
