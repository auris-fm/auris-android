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

        return classifyRoute(
            outputDeviceTypes = outputDevices.map { it.type },
            inputDeviceTypes = inputDevices.map { it.type },
        )
    }

    internal companion object {
        fun classifyRoute(
            outputDeviceTypes: List<Int>,
            inputDeviceTypes: List<Int>,
        ): AudioRoute {
            val hasHeadsetInput = inputDeviceTypes.any { it.isHeadsetInput() }
            val hasA2dp = outputDeviceTypes.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }

            return when {
                outputDeviceTypes.any { it.isHeadsetOutput() } -> AudioRoute.Headset(hasMicrophone = hasHeadsetInput)
                hasA2dp -> AudioRoute.Headset(hasMicrophone = true)
                outputDeviceTypes.any { it == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } -> AudioRoute.Speaker
                else -> AudioRoute.Unknown
            }
        }

        private fun Int.isHeadsetOutput(): Boolean {
            return this == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                this == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                this == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                isBleHeadset()
        }

        private fun Int.isHeadsetInput(): Boolean {
            return this == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                this == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                isBleHeadset()
        }

        private fun Int.isBleHeadset(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                this == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
    }
}
