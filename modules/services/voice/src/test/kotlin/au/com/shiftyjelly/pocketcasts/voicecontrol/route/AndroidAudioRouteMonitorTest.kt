package au.com.shiftyjelly.pocketcasts.voicecontrol.route

import android.media.AudioDeviceInfo
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// API 34 supplies AudioManager.getCommunicationDevice(); the Robolectric default does not, and its
// absence failed these tests with NoSuchMethodError instead of exercising the classification.
@Config(sdk = [35])
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
            bleCommunicationDeviceSelected = true,
        )
        assertEquals(AudioRoute.Headset(hasMicrophone = true), route)
    }

    @Test
    fun `ble input enumerated but not selected is an acquirable candidate`() {
        // A BLE input appearing in getDevices(INPUTS) is not selection. Only
        // getCommunicationDevice() reporting the headset selects it, so an enumerated-but-
        // unselected input must still need acquisition rather than be treated as Isolated.
        val route = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET),
            inputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET),
            bleCommunicationDeviceSelected = false,
        )
        assertEquals(AudioRoute.BluetoothA2dpOnly, route)
        assertEquals(MicExposure.Exposed, route.toMicExposure())
    }

    @Test
    fun `ble input enumerated and selected is headset with mic`() {
        val route = AndroidAudioRouteMonitor.classifyRoute(
            outputDeviceTypes = emptyList(),
            inputDeviceTypes = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET),
            bleCommunicationDeviceSelected = true,
        )
        assertEquals(AudioRoute.Headset(hasMicrophone = true), route)
    }

    // ── Additions that main's suite does not cover ─────────────────────────
    //
    // These exercise the ROUTED-OUTPUT observation and the availability/route separation. They do not
    // restate main's classification rules; main's own tests above are the authority for those.

    @Test
    fun `a live stream's routed output outranks enumeration`() {
        val signal = GracePeriodSignal()
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val monitor = AndroidAudioRouteMonitor(
            context = ctx,
            gracePeriodSignal = signal,
            routedOutputObserver = publishingObserver(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
        )
        assertEquals(
            "the observed routing decides the route, not enumerated availability",
            AudioRoute.Speaker,
            monitor.route.value,
        )
    }

    @Test
    fun `an unknown routed output stays unknown rather than becoming a speaker`() {
        val signal = GracePeriodSignal()
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val monitor = AndroidAudioRouteMonitor(
            context = ctx,
            gracePeriodSignal = signal,
            routedOutputObserver = publishingObserver(null),
        )
        // Nullable on purpose: a missing observation must stay missing rather than becoming a claim.
        assertTrue(
            "no routed answer reads as Unknown (got=${monitor.route.value})",
            monitor.route.value is AudioRoute.Unknown,
        )
    }

    @Test
    fun `a device change closes the window through the production route monitor`() = runBlocking {
        // The production connection the sink's privacy guard depends on: a device event reaches
        // GracePeriodSignal through the monitor's own callback, not a test-installed one.
        val signal = GracePeriodSignal(timeoutMs = 60_000L)
        val monitor = AndroidAudioRouteMonitor(ApplicationProvider.getApplicationContext(), signal)

        signal.onWakeWordDetected()
        assertTrue("the wake must open the window", signal.isActive.value)
        assertFalse("an open window is not a privacy closure", signal.isClosedByPrivacy())
        val closuresBefore = signal.privacyClosureCount()

        monitor.onDevicesChanged()

        assertTrue(
            "grace must end as the event arrives, not after the debounce",
            signal.isClosedByPrivacy(),
        )
        assertFalse("a privacy closure ends the window", signal.isActive.value)
        assertEquals(
            "the event closes grace exactly once, not once per debounce",
            closuresBefore + 1,
            signal.privacyClosureCount(),
        )
    }

    /**
     * An observer with one live stream already publishing [type].
     *
     * Built through the production API rather than subclassed: `RoutedOutputObserver` is a final
     * `@Singleton` whose ownership token is the mechanism, so a hand-rolled stub would exercise a
     * different contract than the one production reads.
     */
    private fun publishingObserver(type: Int?): RoutedOutputObserver = RoutedOutputObserver().also { it.observe().publishType(type) }
}
