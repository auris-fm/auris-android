package au.com.shiftyjelly.pocketcasts.voicecontrol.audio

import android.content.Context
import android.content.res.AssetManager
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.EpisodeReferenceEligibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Reproduces the restart interleaving behind the "old collector can close the replacement
 * capture" finding: `VoiceAsrEngine.stop` cancels its collector and opens native teardown
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
            closeCount++
            capturing = false
        }

        var closeCount = 0

        override fun isCapturing(): Boolean = capturing

        override fun waitForVadEvent(timeoutMs: Int, generationOut: LongArray): Int {
            // A starved stream reports nothing and consumes nothing: only a timeout comes back.
            Thread.sleep(timeoutMs.toLong())
            return 0
        }

        override fun getSpeechPcmRange(out: ShortArray, fromSample: Int): Int = 0
        override fun getSpeechPcm(buffer: ShortArray): Int = 0

        override fun getSpeechPcmSize(): Int = 0

        override fun getSpeechOnsetSample(): Int = 0
        override fun getInProgressSnapshot(buffer: ShortArray, header: LongArray): Int {
            header[0] = 0
            header[1] = 0
            header[2] = 0
            return 0
        }
        override fun setEarlyWakeCutoff(samples: Int) = Unit

        override fun getSpeechEndSample(): Int = 0
        override fun getSpeechEndStreamPosition(): Long = 0L
        override fun getFramesConsumed(): Long = consumedFrames

        /** Frames the native VAD has consumed; a starved stream leaves this still. */
        @Volatile
        var consumedFrames: Long = 0L

        companion object {
            const val DEVICE_ID = 42
        }
    }

    private fun context(): Context {
        val context = mock(Context::class.java)
        `when`(context.assets).thenReturn(mock(AssetManager::class.java))
        return context
    }

    // Lifecycle exit: onEngineFinished clears reference eligibility
    // only when the finishing engine still OWNS the active capture.
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
        // Replacement session opens and attaches before the stale engine finishes.
        assertTrue(capture.openStreamForAcquisition() >= 0)
        assertTrue(capture.attachCapture())
        stale.dispose()
        assertTrue(
            "a stale finish must not end the replacement's eligibility",
            gate.isCaptureActive(),
        )
        // And the replacement's own finish does.
        capture.currentEngineForTest()!!.dispose()
        assertFalse(gate.isCaptureActive())
    }

    @Test
    fun `a stale collector teardown cannot stop a stream opened for its replacement`() {
        val hardware = FakeCaptureHardware()
        val capture = MicrophoneCapture(context(), hardware, EpisodeReferenceEligibility())

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
        val capture = MicrophoneCapture(context(), hardware, EpisodeReferenceEligibility())

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
        val capture = MicrophoneCapture(context(), hardware, EpisodeReferenceEligibility())

        capture.openStreamForAcquisition()
        assertTrue(capture.attachCapture())

        capture.stopCapture()

        assertEquals(1, hardware.stopCount)
        assertFalse(hardware.capturing)
    }

    @Test
    fun `a stream that delivers no frames ends the capture instead of waiting forever`() = runBlocking {
        val hardware = FakeCaptureHardware()
        val capture = MicrophoneCapture(context(), hardware, EpisodeReferenceEligibility())

        capture.openStreamForAcquisition()
        assertTrue(capture.attachCapture())

        // The consumed counter never moves: the stream is running but delivering nothing.
        val engine = capture.currentEngineForTest()!!
        val ended = withTimeoutOrNull<Unit>(OboeConfig.STARVED_AFTER_MS + OboeConfig.LIVENESS_POLL_MS * 4 + 2_000) {
            engine.captureEvents().collect { /* no events expected while starved */ }
        }
        assertTrue("a starved stream must end the capture rather than block on it forever", ended === Unit)
        assertTrue(
            "the dead stream is torn down, so the owner can restart through the existing path",
            hardware.stopCount >= 1,
        )
    }

    @Test
    fun `a stream that keeps delivering frames is not treated as starved`() = runBlocking {
        val hardware = FakeCaptureHardware()
        val capture = MicrophoneCapture(context(), hardware, EpisodeReferenceEligibility())

        capture.openStreamForAcquisition()
        assertTrue(capture.attachCapture())
        val engine = capture.currentEngineForTest()!!

        // Frames keep reaching the VAD — silence still does — so the capture must stay alive for
        // several starvation windows' worth of polling.
        val collector = launch(Dispatchers.Default) {
            runCatching { engine.captureEvents().collect { } }
        }
        val deadline = System.currentTimeMillis() + OboeConfig.STARVED_AFTER_MS * 3
        while (System.currentTimeMillis() < deadline) {
            hardware.consumedFrames += OboeConfig.FRAMES_PER_POLL
            delay(40)
        }
        assertTrue("a delivering stream must not be ended by the liveness check", collector.isActive)
        collector.cancelAndJoin()
    }

    /**
     * A release can ask to close the acquisition stream after the engine has taken it: the gate
     * can block between hand-off and the next check, and a stop releases routing after the engine's
     * own teardown. Native close deletes whatever capture exists — including the replacement a
     * restart has already opened — so the owner must refuse a close for a stream it no longer owns,
     * exactly as it refuses a stale engine's stop.
     */
    @Test
    fun `a close for a stream the engine has taken leaves the live capture alone`() {
        val hardware = FakeCaptureHardware()
        val capture = MicrophoneCapture(context(), hardware, EpisodeReferenceEligibility())

        capture.openStreamForAcquisition()
        assertTrue(capture.attachCapture())
        val owning = capture.currentEngineForTest()!!

        // The engine took this stream (hand-off), and a restart has since opened its replacement.
        capture.openStreamForAcquisition()
        assertTrue(capture.attachCapture())
        val replacement = capture.currentEngineForTest()!!
        assertTrue("the replacement is a different engine", replacement !== owning)
        val closesBefore = hardware.closeCount

        // The late release for the FIRST stream arrives now. It must not close the replacement.
        capture.closeAcquiredStream(generation = owning.generation)

        assertEquals(
            "a close for a stream that is no longer current must not delete the live capture",
            closesBefore,
            hardware.closeCount,
        )
        assertTrue("the replacement stays attached", capture.currentEngineForTest() === replacement)
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
