package au.com.shiftyjelly.pocketcasts.voicecontrol.route

import android.media.AudioDeviceInfo
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidAudioRouteMonitorTest {

    @Test
    fun `a device change closes the window through the production route monitor`() = runBlocking {
        // The production connection the sink's privacy guard depends on: a device event reaches
        // GracePeriodSignal through the monitor's own registered callback, not through a test-installed
        // one. The debounce and route read that follow are production.
        val signal = GracePeriodSignal(timeoutMs = 60_000L)
        val monitor = AndroidAudioRouteMonitor(ApplicationProvider.getApplicationContext(), signal)

        signal.onWakeWordDetected()
        assertTrue("the wake must open the window", signal.isActive.value)
        assertFalse("an open window is not a privacy closure", signal.isClosedByPrivacy())
        val closuresBefore = signal.privacyClosureCount()

        monitor.onDevicesChanged()

        // The owning transition must not wait on the route-read debounce: the event is the fact and the
        // read is derived. Sampled immediately, with no wait, so a closure that only happened after the
        // 500ms read could not satisfy this.
        assertTrue(
            "grace must end as the event arrives, not after the debounce (active=${signal.isActive.value})",
            signal.isClosedByPrivacy(),
        )
        assertFalse("a privacy closure ends the window", signal.isActive.value)
        assertEquals(
            "the event closes grace exactly once, not once per debounce",
            closuresBefore + 1,
            signal.privacyClosureCount(),
        )
        // Past the debounce: the route read still lands, and it must not close a second time.
        delay(1_200)
        assertEquals(
            "the debounced route read must not close grace a second time",
            closuresBefore + 1,
            signal.privacyClosureCount(),
        )
    }

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
    fun `a wake during the debounce is not ended by the pending route read`() = runBlocking {
        // A device event schedules the debounced read; the user wakes before it elapses, opening a new
        // window; the stale read then lands. It must not end a window it never saw.
        val signal = GracePeriodSignal(timeoutMs = 60_000L)
        val monitor = AndroidAudioRouteMonitor(ApplicationProvider.getApplicationContext(), signal)

        signal.onWakeWordDetected()
        val closuresBefore = signal.privacyClosureCount()
        monitor.onDevicesChanged()

        // The wake's own closure already happened; reopen the window as a later wake would.
        signal.onWakeWordDetected()
        assertTrue("the wake opens a window", signal.isActive.value)

        delay(1_200)
        assertTrue(
            "a stale debounced read must not end a window opened after it was scheduled",
            signal.isActive.value,
        )
        assertFalse("and it must not mark that window as privacy-closed", signal.isClosedByPrivacy())
        assertEquals(
            "the stale read must not count a closure against the new window",
            closuresBefore + 1,
            signal.privacyClosureCount(),
        )
    }
}
