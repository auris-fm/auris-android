package au.com.shiftyjelly.pocketcasts.voicecontrol.route

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.MicrophoneCapture
import timber.log.Timber

/**
 * The Android/Oboe implementation of [RouteAcquisitionPlatform].
 *
 * Classic `HFP`/`SCO` is opened with [AudioManager.startBluetoothSco]; `LE Audio` is selected
 * with [AudioManager.setCommunicationDevice]. The Oboe stream is opened through
 * [MicrophoneCapture] (AAudio backend) without starting it, so the selected device can be
 * confirmed before audio flows.
 */
class AndroidRouteAcquisitionPlatform(
    context: Context,
    private val microphoneCapture: MicrophoneCapture,
) : RouteAcquisitionPlatform {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Suppress("DEPRECATION") // isBluetoothScoOn deprecated in API 34; distinguishes inactive SCO
    override fun bluetoothCandidates(): List<BluetoothCandidate> {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val candidates = mutableListOf<BluetoothCandidate>()

        // Classic A2DP (or enumerated-but-inactive SCO) while SCO is off: the headset's
        // HFP/SCO microphone must be selected.
        if (!audioManager.isBluetoothScoOn) {
            val classic = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                ?: (outputs + inputs).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (classic != null) {
                candidates += BluetoothCandidate(classic.id, BluetoothTransport.ClassicSco)
            }
        }

        // A BLE headset output with no BLE input selected yet: LE Audio selection is needed.
        val bleOutput = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
        val bleInput = inputs.any { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
        if (bleOutput != null && !bleInput) {
            candidates += BluetoothCandidate(bleOutput.id, BluetoothTransport.LeAudio)
        }

        return candidates
    }

    @Suppress("DEPRECATION") // startBluetoothSco + stopBluetoothSco deprecated in API 33; no replacement
    override fun startBluetoothSco() {
        audioManager.startBluetoothSco()
    }

    @Suppress("DEPRECATION")
    override fun stopBluetoothSco() {
        audioManager.stopBluetoothSco()
    }

    @Suppress("DEPRECATION")
    override fun isBluetoothScoOn(): Boolean = audioManager.isBluetoothScoOn

    override fun setCommunicationDevice(deviceId: Int): Boolean {
        // A BLE candidate is enumerated as an output device before its input is selected,
        // and setCommunicationDevice accepts a BLE_HEADSET output. Only fall back to the
        // full list after input lookup so classic input ids still resolve first.
        val device = communicationDevice(deviceId) ?: return false
        return audioManager.setCommunicationDevice(device)
    }

    override fun communicationDeviceId(): Int? = audioManager.communicationDevice?.id

    override fun clearCommunicationDevice() {
        audioManager.clearCommunicationDevice()
    }

    override fun audioMode(): Int = audioManager.mode

    override fun setAudioMode(mode: Int) {
        audioManager.mode = mode
    }

    override fun deviceType(deviceId: Int): Int? = allDevices().firstOrNull { it.id == deviceId }?.type

    override fun openInputStream(): Int {
        val deviceId = microphoneCapture.openStreamForAcquisition()
        Timber.i("[RouteAcquisition] opened input stream device=%d", deviceId)
        return deviceId
    }

    override fun startInputStream(): Boolean = microphoneCapture.startAcquiredStream()

    override fun closeInputStream() {
        microphoneCapture.closeAcquiredStream()
    }

    private fun communicationDevice(deviceId: Int): AudioDeviceInfo? = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == deviceId }
        ?: allDevices().firstOrNull { it.id == deviceId }

    private fun allDevices(): Array<AudioDeviceInfo> = audioManager.getDevices(AudioManager.GET_DEVICES_ALL)
}
