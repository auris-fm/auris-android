package au.com.shiftyjelly.pocketcasts.voicecontrol.route

import android.media.AudioDeviceInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acquisition and capture startup share one owner.
 *
 * Selection callbacks publish the confirmed route while an acquisition is still polling, so the
 * mode observer can confirm, start and hand off a stream first. If the pending attempt then opens
 * a second stream it deletes the first one and its VAD processor, and the engine coordinator
 * reports "already started" with VAD attached to nothing. The attempt must reuse the stream.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteAcquisitionOverlapTest {
    private class Hardware : RouteAcquisitionPlatform {
        var selected = false
        var opens = 0
        override fun bluetoothCandidates() = emptyList<BluetoothCandidate>()
        override fun startBluetoothSco() = Unit
        override fun stopBluetoothSco() = Unit
        override fun isBluetoothScoOn() = selected
        override fun setCommunicationDevice(deviceId: Int) = true
        override fun communicationDeviceId(): Int? = null
        override fun clearCommunicationDevice() = Unit
        override fun audioMode() = 0
        override fun setAudioMode(mode: Int) = Unit
        override fun deviceType(deviceId: Int) = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        override fun openInputStream(): Int {
            opens++
            return 24
        }

        override fun startInputStream() = true
        override fun closeInputStream() = Unit
    }

    @Test
    fun `pending acquisition reuses the stream the mode observer already handed off`() = runTest {
        val hardware = Hardware()
        val acquisition = RouteAcquisition(hardware, timeoutMs = 1000, pollIntervalMs = 50)
        val pending = launch {
            acquisition.acquire(BluetoothCandidate(24, BluetoothTransport.ClassicSco)) { true }
        }
        runCurrent()

        // The selection callback publishes Headset before acquisition's next poll, so the mode
        // observer confirms and hands off a stream while the attempt is still waiting.
        hardware.selected = true
        assertTrue(acquisition.confirmCaptureStream())
        acquisition.handOffStream()
        assertEquals(1, hardware.opens)

        advanceTimeBy(50)
        runCurrent()
        pending.join()

        assertEquals(
            "a pending acquisition must reuse the stream already handed to the engine",
            1,
            hardware.opens,
        )
    }
}
