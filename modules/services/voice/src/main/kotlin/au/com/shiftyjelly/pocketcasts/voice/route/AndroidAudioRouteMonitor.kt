package au.com.shiftyjelly.pocketcasts.voice.route

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class AndroidAudioRouteMonitor @Inject constructor(
    @ApplicationContext context: Context,
) : AudioRouteMonitor {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mutableRoute = MutableStateFlow(readRoute())

    override val route: StateFlow<AudioRoute> = mutableRoute.asStateFlow()

    private val audioDeviceCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                mutableRoute.value = readRoute()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                mutableRoute.value = readRoute()
            }
        }
    } else {
        null
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        }
    }

    private fun readRoute(): AudioRoute {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return AudioRoute.Unknown
        }

        val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        val hasHeadsetInput = inputDevices.any { it.isHeadsetInput() }

        return when {
            outputDevices.any { it.isHeadsetOutput() } -> AudioRoute.Headset(hasMicrophone = hasHeadsetInput)
            outputDevices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } -> AudioRoute.Speaker
            outputDevices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } -> AudioRoute.BluetoothA2dpOnly
            else -> AudioRoute.Unknown
        }
    }

    private fun AudioDeviceInfo.isHeadsetOutput(): Boolean {
        return type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            isBleHeadset()
    }

    private fun AudioDeviceInfo.isHeadsetInput(): Boolean {
        return type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            isBleHeadset()
    }

    private fun AudioDeviceInfo.isBleHeadset(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }
}
