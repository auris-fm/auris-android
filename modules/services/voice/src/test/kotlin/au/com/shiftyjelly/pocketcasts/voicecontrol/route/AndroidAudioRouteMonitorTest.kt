package au.com.shiftyjelly.pocketcasts.voicecontrol.route

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidAudioRouteMonitorTest {

    @Test
    fun `wired headset with mic`() {
        val route = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET),
            inputDeviceTypes = listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET),
        )
        assertEquals(AudioRoute.Headset(hasMicrophone = true), route)
    }

    @Test
    fun `wired headphones without mic`() {
        val route = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES),
            inputDeviceTypes = emptyList(),
        )
        assertEquals(AudioRoute.Headset(hasMicrophone = false), route)
    }

    @Test
    fun `bluetooth sco headset with active sco is headset with mic`() {
        val route = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
            inputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
            bluetoothScoActive = true,
        )
        assertEquals(AudioRoute.Headset(hasMicrophone = true), route)
    }

    @Test
    fun `airpods a2dp with sco devices enumerated but sco inactive needs sco open`() {
        // Android enumerates TYPE_BLUETOOTH_SCO I/O for AirPods even when the SCO
        // link is off (music is on A2DP). That must NOT be treated as an already-open
        // headset mic — otherwise VoiceAsrEngine skips startBluetoothSco() and capture
        // stays on the phone bottom mic.
        val route = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = listOf(
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            ),
            inputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
            bluetoothScoActive = false,
        )
        assertEquals(AudioRoute.BluetoothA2dpOnly, route)
    }

    @Test
    fun `airpods with active sco is headset with mic`() {
        val route = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = listOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            ),
            inputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
            bluetoothScoActive = true,
        )
        assertEquals(AudioRoute.Headset(hasMicrophone = true), route)
    }

    @Test
    fun `bluetooth a2dp with wired headset mic input`() {
        val route = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
            inputDeviceTypes = listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET),
        )
        assertEquals(AudioRoute.Headset(hasMicrophone = true), route)
    }

    @Test
    fun `bluetooth a2dp only without headset input`() {
        val route = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
            inputDeviceTypes = emptyList(),
        )
        assertEquals(AudioRoute.BluetoothA2dpOnly, route)
    }

    @Test
    fun `builtin speaker only`() {
        val route = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
            inputDeviceTypes = emptyList(),
        )
        assertEquals(AudioRoute.Speaker, route)
    }

    @Test
    fun `a2dp preferred over builtin speaker`() {
        val route = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = listOf(
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            ),
            inputDeviceTypes = emptyList(),
        )
        assertEquals(AudioRoute.BluetoothA2dpOnly, route)
    }

    @Test
    fun `wired headset preferred over a2dp`() {
        val route = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = listOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
            ),
            inputDeviceTypes = listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET),
        )
        assertEquals(AudioRoute.Headset(hasMicrophone = true), route)
    }

    @Test
    fun `unknown devices`() {
        val route = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = listOf(AudioDeviceInfo.TYPE_USB_DEVICE),
            inputDeviceTypes = emptyList(),
        )
        assertEquals(AudioRoute.Unknown, route)
    }

    @Test
    fun `empty devices`() {
        val route = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = emptyList(),
            inputDeviceTypes = emptyList(),
        )
        assertEquals(AudioRoute.Unknown, route)
    }

    @Test
    fun `ble headset output without a selected ble input is an acquirable candidate`() {
        // A BLE headset that has connected output but whose microphone the session has
        // not selected yet must be acquirable, not NoMic: LE Audio selection (setCommunicationDevice)
        // is what turns it into an Isolated headset.
        val route = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET),
            inputDeviceTypes = emptyList(),
        )
        assertEquals(AudioRoute.BluetoothA2dpOnly, route)
        assertEquals(MicExposure.Exposed, route.toMicExposure())
    }

    @Test
    fun `ble headset output with a selected ble input is headset with mic`() {
        val route = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET),
            inputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET),
        )
        assertEquals(AudioRoute.Headset(hasMicrophone = true), route)
    }
}
