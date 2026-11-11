package au.com.shiftyjelly.pocketcasts.voicecontrol.audio
import android.content.Context
import android.content.res.AssetManager
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.EpisodeReferenceEligibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
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
            capturing = false
            return DEVICE_ID
        }
        override fun startInputStream(): Boolean = true
        override fun closeInputCapture() {
            capturing = false
        }
        override fun isCapturing(): Boolean = capturing
        override fun waitForVadEvent(timeoutMs: Int): Int = -1
        override fun getSpeechPcmRange(out: ShortArray, fromSample: Int): Int = 0
        override fun getSpeechPcm(buffer: ShortArray): Int = 0
        override fun getSpeechPcmSize(): Int = 0
        override fun getSpeechOnsetSample(): Int = 0
        override fun getSpeechEndSample(): Int = 0
        override fun getSpeechEndStreamPosition(): Long = 0L
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
    fun `a current engine finishing ends reference eligibility`() {
        val gate = EpisodeReferenceEligibility()
        val hardware = FakeCaptureHardware()
        val capture = MicrophoneCapture(context(), hardware, gate)
        assertTrue(capture.openStreamForAcquisition() >= 0)
        assertTrue(capture.attachCapture())
        assertTrue("precondition: capture active", gate.isCaptureActive())
        capture.currentEngineForTest()!!.dispose()
        assertFalse("the owning engine's finish must end eligibility", gate.isCaptureActive())
    }
    @Test
    fun `a stale engine finishing does not end the replacement's reference eligibility`() {
        val gate = EpisodeReferenceEligibility()
        val hardware = FakeCaptureHardware()
        val capture = MicrophoneCapture(context(), hardware, gate)
        assertTrue(capture.openStreamForAcquisition() >= 0)
        assertTrue(capture.attachCapture())
        val stale = capture.currentEngineForTest()!!
        assertTrue(capture.openStreamForAcquisition() >= 0)
        assertTrue(capture.attachCapture())
        stale.dispose()
        assertTrue(
            "a stale finish must not end the replacement's eligibility",
            gate.isCaptureActive(),
        )
        capture.currentEngineForTest()!!.dispose()
        assertFalse(gate.isCaptureActive())
    }
    @Test
    fun `a stale collector teardown cannot stop a stream opened for its replacement`() {
        val hardware = FakeCaptureHardware()
        val capture = MicrophoneCapture(context(), hardware, EpisodeReferenceEligibility())
        assertTrue(capture.openStreamForAcquisition() >= 0)
        assertTrue(capture.attachCapture())
        val stale = capture.currentEngineForTest()!!
        assertTrue(hardware.capturing)
        assertTrue(capture.openStreamForAcquisition() >= 0)
        stale.dispose()
        assertEquals(0, hardware.stopCount)
        assertTrue(capture.attachCapture())
        assertTrue(hardware.capturing)
    }
    @Test
    fun `a stale collector teardown cannot stop an attached replacement capture`() {
        val hardware = FakeCaptureHardware()
        val capture = MicrophoneCapture(context(), hardware, EpisodeReferenceEligibility())
        capture.openStreamForAcquisition()
        assertTrue(capture.attachCapture())
        val stale = capture.currentEngineForTest()!!
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
        val capture = MicrophoneCapture(context(), hardware, EpisodeReferenceEligibility())
        capture.openStreamForAcquisition()
        assertTrue(capture.attachCapture())
        capture.stopCapture()
        assertEquals(1, hardware.stopCount)
        assertFalse(hardware.capturing)
    }
    @Test
    fun `closing the acquired stream stops a later stale teardown from touching native capture`() {
        val hardware = FakeCaptureHardware()
        val capture = MicrophoneCapture(context(), hardware, EpisodeReferenceEligibility())
        capture.openStreamForAcquisition()
        assertTrue(capture.attachCapture())
        val stale = capture.currentEngineForTest()!!
        capture.closeAcquiredStream()
        stale.dispose()
        assertEquals(0, hardware.stopCount)
    }
}
