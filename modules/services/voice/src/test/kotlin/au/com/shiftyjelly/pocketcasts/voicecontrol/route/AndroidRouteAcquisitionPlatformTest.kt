package au.com.shiftyjelly.pocketcasts.voicecontrol.route

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Candidate enumeration is what lets acquisition run at all. Presence in
 * `getDevices(GET_DEVICES_INPUTS)` is not selection for BLE, so an enumerated BLE input whose
 * communication device has not been selected must still produce a candidate.
 */
class AndroidRouteAcquisitionPlatformTest {

    private fun descriptor(id: Int, type: Int) = AudioDeviceDescriptor(id = id, type = type)

    @Test
    fun `a2dp output with sco inactive is a classic candidate`() {
        val candidates = selectBluetoothCandidates(
            outputDevices = listOf(descriptor(1, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)),
            inputDevices = emptyList(),
            bluetoothScoActive = false,
            bleCommunicationDeviceSelected = false,
        )
        assertEquals(listOf(BluetoothCandidate(1, BluetoothTransport.ClassicSco)), candidates)
    }

    @Test
    fun `an enumerated but unselected ble input is a le audio candidate`() {
        val candidates = selectBluetoothCandidates(
            outputDevices = listOf(descriptor(7, AudioDeviceInfo.TYPE_BLE_HEADSET)),
            inputDevices = listOf(descriptor(8, AudioDeviceInfo.TYPE_BLE_HEADSET)),
            bluetoothScoActive = false,
            bleCommunicationDeviceSelected = false,
        )
        // Output id is preferred so setCommunicationDevice() receives the output device.
        assertEquals(listOf(BluetoothCandidate(7, BluetoothTransport.LeAudio)), candidates)
    }

    @Test
    fun `a selected ble communication device is not a candidate`() {
        val candidates = selectBluetoothCandidates(
            outputDevices = listOf(descriptor(7, AudioDeviceInfo.TYPE_BLE_HEADSET)),
            inputDevices = listOf(descriptor(8, AudioDeviceInfo.TYPE_BLE_HEADSET)),
            bluetoothScoActive = false,
            bleCommunicationDeviceSelected = true,
        )
        assertEquals(emptyList<BluetoothCandidate>(), candidates)
    }
}
