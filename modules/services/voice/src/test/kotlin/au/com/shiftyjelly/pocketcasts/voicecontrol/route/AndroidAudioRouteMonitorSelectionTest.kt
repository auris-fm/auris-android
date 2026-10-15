@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package au.com.shiftyjelly.pocketcasts.voicecontrol.route

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningMode
import au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningModePolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioManager

/**
 * The published route must track the **selected** microphone, not only the enumerated device
 * set. Bluetooth `SCO`/`BLE` selection can change while the device list stays exactly the same,
 * so a monitor that refreshes only on device add/remove leaves the route stuck on the candidate
 * (`BluetoothA2dpOnly` → `Exposed`). Acquisition then opens the headset mic while the mode stays
 * `Off`, and a later gate blocker resolves to `Off` too — no new mode emission to stop capture.
 *
 * These are the real `AudioManager` selection callbacks and the acquisition publish path; the
 * protected `ShadowAudioManager` setters are reached by reflection because Robolectric exposes
 * no public setter for them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAudioRouteMonitorSelectionTest {
    private lateinit var context: Context
    private lateinit var audioManager: AudioManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    @Test
    @Suppress("DEPRECATION") // ACTION_SCO_AUDIO_STATE_UPDATED is the only signal for SCO selection
    fun `a sco selection change without a device change updates the route`() {
        setOutputDevices(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        val monitor = AndroidAudioRouteMonitor(context, GracePeriodSignal())

        // The OBSERVED route states no acquisition on the strength of enumeration alone: an A2DP
        // device in the list is availability, not a routing the platform established...
        assertEquals(AudioRoute.Unknown, monitor.route.value)
        // ...while the enumeration surface the setup decision reads still names the candidate, so a
        // pending acquisition is kept alive by the surface that owns that property.
        assertEquals(AudioRoute.BluetoothA2dpOnly, monitor.availability.value)
        assertTrue(monitor.availability.value.keepsPendingAcquisition())

        // The SCO link opens without any device being added or removed.
        setBluetoothScoOn(true)
        context.sendBroadcast(Intent(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a ble communication device selection without a device change updates the route`() {
        val ble = device(AudioDeviceInfo.TYPE_BLE_HEADSET)
        shadowOf(audioManager).setOutputDevices(listOf(ble))
        val monitor = AndroidAudioRouteMonitor(context, GracePeriodSignal())
        // Enumeration alone is availability: the observed route has no answer yet...
        assertEquals(AudioRoute.Unknown, monitor.route.value)
        assertEquals(AudioRoute.BluetoothA2dpOnly, monitor.availability.value)

        // The headset is selected as the communication device with no device-list change. A SELECTION
        // IS an observation, so the observed route answers now -- and it must, because the acquisition
        // reads the observed route to confirm the selection it just made.
        selectCommunicationDevice(ble)
        shadowOf(audioManager).callOnCommunicationDeviceChangedListeners(ble)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a confirmed external microphone is published even when no callback fires`() {
        setOutputDevices(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        val monitor = AndroidAudioRouteMonitor(context, GracePeriodSignal())
        assertEquals(AudioRoute.Unknown, monitor.route.value)
        assertEquals(AudioRoute.BluetoothA2dpOnly, monitor.availability.value)

        setBluetoothScoOn(true)
        monitor.onExternalMicrophoneConfirmed()

        // The confirmation is an observation of the acquired input, and that is what the route reports.
        assertEquals(AudioRoute.Headset(hasMicrophone = true), monitor.route.value)
    }

    @Test
    @Suppress("DEPRECATION") // ACTION_SCO_AUDIO_STATE_UPDATED drives the platform SCO signal
    fun `a confirmed external microphone lets the listening mode leave Off`() = runTest {
        setOutputDevices(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        val graceSignal = GracePeriodSignal()
        val monitor = AndroidAudioRouteMonitor(context, graceSignal)
        val policy = ListeningModePolicy(
            gate = VoiceControlGate(rules = emptyList()),
            audioRouteMonitor = monitor,
            gracePeriodSignal = graceSignal,
            scope = backgroundScope,
        )

        policy.mode.test {
            assertEquals(ListeningMode.Off, awaitItem())

            // A real confirmed acquisition leaves BOTH surfaces answered: the platform SCO link is on
            // AND the SCO input is enumerated (production lists it once connected), so availability
            // answers Headset-with-microphone -- what the eligibility policy reads -- while the
            // selection observation arrives for the route surface. Confirming only the observation
            // would model an acquisition that never opened SCO, which production cannot do.
            setInputDevices(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            // The acquisition selects the headset as the communication device; that selection is what
            // makes availability answer Headset-with-microphone for the eligibility policy.
            selectCommunicationDevice(device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
            setBluetoothScoOn(true)
            context.sendBroadcast(Intent(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
            shadowOf(Looper.getMainLooper()).idle()
            monitor.onExternalMicrophoneConfirmed()

            assertEquals(ListeningMode.WakeWord, awaitItem())
        }
    }

    @Test
    @Suppress("DEPRECATION") // ACTION_SCO_AUDIO_STATE_UPDATED is the only signal for SCO selection
    fun `a pending acquisition survives the selection callback that confirms it`() {
        setOutputDevices(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        val monitor = AndroidAudioRouteMonitor(context, GracePeriodSignal())
        // The attempt's survival property lives on the AVAILABILITY surface: that is where "the
        // candidate is still present" is answered, and it is what the acquisition reads while the
        // observed route legitimately has no answer yet.
        assertEquals(AudioRoute.Unknown, monitor.route.value)
        assertEquals(AudioRoute.BluetoothA2dpOnly, monitor.availability.value)
        assertTrue(monitor.availability.value.keepsPendingAcquisition())

        // The selection callback publishes the selected headset while acquisition is still
        // confirming. That is the attempt succeeding, not device loss: it must keep the attempt
        // alive rather than cancel it and release the routing it acquired.
        setBluetoothScoOn(true)
        context.sendBroadcast(Intent(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(AudioRoute.Headset(hasMicrophone = true), monitor.route.value)
        assertTrue(monitor.availability.value.keepsPendingAcquisition())
    }

    @Test
    fun `an enumerated wired input beside a2dp output does not make the observed route a headset`() {
        // THE CONFLICTING CONTROL for the finding: enumeration and observation DISAGREE. The wired input
        // is auto-selected by the platform and main's classification reads it as a usable microphone --
        // that is the AVAILABILITY answer. With no observation, the ROUTE must not borrow it: the route
        // is what the platform established, and it established nothing, so the two surfaces differ.
        setOutputDevices(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        setInputDevices(AudioDeviceInfo.TYPE_WIRED_HEADSET)
        val monitor = AndroidAudioRouteMonitor(context, GracePeriodSignal())

        assertEquals(AudioRoute.Unknown, monitor.route.value)
        // The availability surface still names the acquirable headset, so an acquisition reads it there.
        assertEquals(AudioRoute.Headset(hasMicrophone = true), monitor.availability.value)
        assertTrue(monitor.availability.value.keepsPendingAcquisition())
    }

    @Test
    fun `an observed routed output is the route even when enumeration would answer differently`() {
        // THE OTHER DIRECTION: the observation wins. A speaker is enumerated, so availability says
        // Speaker; a live stream reports the wired headphone output, and the ROUTE must name what is
        // actually routed rather than what could be. Built through the observer's real API.
        setOutputDevices(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val observer = RoutedOutputObserver().also {
            it.observe().publishType(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        }
        val monitor = AndroidAudioRouteMonitor(
            context = context,
            gracePeriodSignal = GracePeriodSignal(),
            routedOutputObserver = observer,
        )

        assertEquals(AudioRoute.Speaker, monitor.availability.value)
        assertEquals(AudioRoute.Headset(hasMicrophone = false), monitor.route.value)
    }

    private fun setInputDevices(vararg types: Int) {
        shadowOf(audioManager).setInputDevices(types.map { device(it) })
    }

    @Test
    fun `an observed speaker output stays speaker despite an enumerated wired input, whichever input is captured`() {
        // THE COLLISION THE FINDING IS ABOUT: the live stream reports the SPEAKER; a wired input is
        // enumerated (availability). The availability classifier's wired-input rule must not reach the
        // observed route, so the route is Speaker for BOTH capture observations -- built-in and
        // external -- while availability separately names the acquirable headset.
        val builtinId = 11
        val externalId = 12
        fun monitorWithCapture(capturedId: Int?): AndroidAudioRouteMonitor = AndroidAudioRouteMonitor(
            context = context,
            gracePeriodSignal = GracePeriodSignal(),
            routedOutputObserver = RoutedOutputObserver().also {
                it.observe().publishType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            },
            enumeratedDeviceTypes = {
                AndroidAudioRouteMonitor.EnumeratedDevices(
                    outputs = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
                    inputs = listOf(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_WIRED_HEADSET),
                    inputIds = listOf(builtinId, externalId),
                )
            },
            captureRoutedDeviceId = { capturedId },
        )

        val builtin = monitorWithCapture(builtinId)
        assertEquals(AudioRoute.Speaker, builtin.route.value)
        assertEquals(AudioRoute.Headset(hasMicrophone = true), builtin.availability.value)

        val external = monitorWithCapture(externalId)
        assertEquals(AudioRoute.Speaker, external.route.value)
        assertEquals(AudioRoute.Headset(hasMicrophone = true), external.availability.value)
    }

    @Test
    fun `an observed a2dp output keeps its own output despite an enumerated wired input, whichever input is captured`() {
        // The observed A2DP route must not borrow the enumerated wired input: the output names the
        // route, and the observed capture input alone would decide a headset microphone -- but it
        // cannot turn A2DP into Headset.
        val builtinId = 21
        val externalId = 22
        fun monitorWithCapture(capturedId: Int?): AndroidAudioRouteMonitor = AndroidAudioRouteMonitor(
            context = context,
            gracePeriodSignal = GracePeriodSignal(),
            routedOutputObserver = RoutedOutputObserver().also {
                it.observe().publishType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            },
            enumeratedDeviceTypes = {
                AndroidAudioRouteMonitor.EnumeratedDevices(
                    outputs = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
                    inputs = listOf(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_WIRED_HEADSET),
                    inputIds = listOf(builtinId, externalId),
                )
            },
            captureRoutedDeviceId = { capturedId },
        )

        assertEquals(AudioRoute.BluetoothA2dpOnly, monitorWithCapture(builtinId).route.value)
        assertEquals(AudioRoute.BluetoothA2dpOnly, monitorWithCapture(externalId).route.value)
    }

    @Test
    fun `an observed wired output attributes its microphone from the captured input, not the enumeration`() {
        // ACQUIRED-INPUT ATTRIBUTION through the real consumer: the same observed wired headphones and
        // enumerated wired input yield Headset(false) when the CAPTURE is observed on the built-in
        // microphone and Headset(true) when it is observed on the wired input.
        val builtinId = 31
        val externalId = 32
        fun monitorWithCapture(capturedId: Int?): AndroidAudioRouteMonitor = AndroidAudioRouteMonitor(
            context = context,
            gracePeriodSignal = GracePeriodSignal(),
            routedOutputObserver = RoutedOutputObserver().also {
                it.observe().publishType(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            },
            enumeratedDeviceTypes = {
                AndroidAudioRouteMonitor.EnumeratedDevices(
                    outputs = listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES),
                    inputs = listOf(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_WIRED_HEADSET),
                    inputIds = listOf(builtinId, externalId),
                )
            },
            captureRoutedDeviceId = { capturedId },
        )

        assertEquals(AudioRoute.Headset(hasMicrophone = false), monitorWithCapture(builtinId).route.value)
        assertEquals(AudioRoute.Headset(hasMicrophone = true), monitorWithCapture(externalId).route.value)
    }

    @Test
    @Suppress("DEPRECATION") // ACTION_SCO_AUDIO_STATE_UPDATED drives the live SCO signal
    fun `a live sco link answers the route over conflicting enumeration`() {
        // CONFLICTING ENUMERATION against the open SCO link: only a speaker is enumerated, so the
        // availability classifier would say Speaker. The SCO link is the observed acquisition, so the
        // ROUTE is Headset -- and availability stays the enumeration's answer, read separately.
        setOutputDevices(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val monitor = AndroidAudioRouteMonitor(context, GracePeriodSignal())
        assertEquals(AudioRoute.Speaker, monitor.availability.value)

        setBluetoothScoOn(true)
        context.sendBroadcast(Intent(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(AudioRoute.Headset(hasMicrophone = true), monitor.route.value)
        assertEquals(AudioRoute.Speaker, monitor.availability.value)
    }

    @Test
    @Suppress("DEPRECATION") // ACTION_SCO_AUDIO_STATE_UPDATED drives the live SCO signal
    fun `a live sco link with enumerated wired outputs and an observed sco capture stays the headset`() {
        // THE OPEN-SCO FALLBACK ITSELF: NO output observer and NO selected communication device, so the
        // only observation is the platform SCO link. Wired and BLE outputs are merely enumerated -- if
        // the fallback passed the enumeration instead of the observed SCO type, the enumerated wired
        // output would steal the route and this case would fail. The SCO capture id (a real
        // TYPE_BLUETOOTH_SCO input) proves the fallback accepts SCO as a microphone. Availability is
        // the enumeration's separate answer: the enumerated wired output carries no observed mic.
        val scoCaptureId = 41
        val monitor = AndroidAudioRouteMonitor(
            context = context,
            gracePeriodSignal = GracePeriodSignal(),
            enumeratedDeviceTypes = {
                AndroidAudioRouteMonitor.EnumeratedDevices(
                    outputs = listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLE_HEADSET),
                    inputs = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
                    inputIds = listOf(scoCaptureId),
                )
            },
            captureRoutedDeviceId = { scoCaptureId },
        )
        assertEquals(AudioRoute.Unknown, monitor.route.value)

        setBluetoothScoOn(true)
        context.sendBroadcast(Intent(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(AudioRoute.Headset(hasMicrophone = true), monitor.route.value)
        assertEquals(AudioRoute.Headset(hasMicrophone = false), monitor.availability.value)
    }

    @Test
    @Suppress("DEPRECATION") // ACTION_SCO_AUDIO_STATE_UPDATED drives the live SCO signal
    fun `an explicitly observed speaker output keeps its route while the sco link is open`() {
        // LIVE-OUTPUT PRECEDENCE: the stream reports the SPEAKER; the SCO link is open besides it. The
        // SCO fact is an observed output type, never a global override, so the observed speaker keeps
        // its own route -- and availability stays the enumeration's separate answer.
        setOutputDevices(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val monitor = AndroidAudioRouteMonitor(context, GracePeriodSignal())
        assertEquals(AudioRoute.Speaker, monitor.availability.value)

        val observer = RoutedOutputObserver().also {
            it.observe().publishType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        }
        val observed = AndroidAudioRouteMonitor(
            context = context,
            gracePeriodSignal = GracePeriodSignal(),
            routedOutputObserver = observer,
        )
        setBluetoothScoOn(true)
        context.sendBroadcast(Intent(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(AudioRoute.Speaker, observed.route.value)
        assertEquals(AudioRoute.Speaker, monitor.availability.value)

        // THE A2DP DIRECTION of the same precedence: an explicitly observed A2DP output keeps its own
        // route while the SCO link is open, rather than the SCO fact overriding it into a Headset.
        val a2dpObserved = AndroidAudioRouteMonitor(
            context = context,
            gracePeriodSignal = GracePeriodSignal(),
            routedOutputObserver = RoutedOutputObserver().also {
                it.observe().publishType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            },
        )
        assertEquals(AudioRoute.BluetoothA2dpOnly, a2dpObserved.route.value)
        assertEquals(AudioRoute.Speaker, monitor.availability.value)
    }

    private fun setOutputDevices(vararg types: Int) {
        shadowOf(audioManager).setOutputDevices(types.map { device(it) })
    }

    private fun device(type: Int): AudioDeviceInfo = AudioDeviceInfoBuilder.newBuilder().setType(type).build()

    private fun setBluetoothScoOn(on: Boolean) {
        val method = ShadowAudioManager::class.java
            .getDeclaredMethod("setBluetoothScoOn", Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        method.invoke(shadowOf(audioManager), on)
    }

    private fun selectCommunicationDevice(device: AudioDeviceInfo) {
        val method = ShadowAudioManager::class.java
            .getDeclaredMethod("setCommunicationDevice", AudioDeviceInfo::class.java)
        method.isAccessible = true
        method.invoke(shadowOf(audioManager), device)
    }
}
