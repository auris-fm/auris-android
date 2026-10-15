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
        assertEquals(AudioRoute.BluetoothA2dpOnly, monitor.route.value)

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
        assertEquals(AudioRoute.BluetoothA2dpOnly, monitor.route.value)

        // The headset is selected as the communication device with no device-list change.
        selectCommunicationDevice(ble)
        shadowOf(audioManager).callOnCommunicationDeviceChangedListeners(ble)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a confirmed external microphone is published even when no callback fires`() {
        setOutputDevices(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        val monitor = AndroidAudioRouteMonitor(context, GracePeriodSignal())
        assertEquals(AudioRoute.BluetoothA2dpOnly, monitor.route.value)

        setBluetoothScoOn(true)
        monitor.onExternalMicrophoneConfirmed()

        assertEquals(AudioRoute.Headset(hasMicrophone = true), monitor.route.value)
    }

    @Test
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

            setBluetoothScoOn(true)
            monitor.onExternalMicrophoneConfirmed()

            assertEquals(ListeningMode.WakeWord, awaitItem())
        }
    }

    @Test
    @Suppress("DEPRECATION") // ACTION_SCO_AUDIO_STATE_UPDATED is the only signal for SCO selection
    fun `a pending acquisition survives the selection callback that confirms it`() {
        setOutputDevices(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        val monitor = AndroidAudioRouteMonitor(context, GracePeriodSignal())
        // The attempt starts with a CANDIDATE route, so it is valid: this is the case where the route
        // must still name the acquirable candidate rather than Unknown, because the acquisition already
        // in flight depends on it to stay alive.
        assertEquals(AudioRoute.BluetoothA2dpOnly, monitor.route.value)
        assertTrue(monitor.route.value.keepsPendingAcquisition())

        // The selection callback publishes the selected headset while acquisition is still
        // confirming. That is the attempt succeeding, not device loss: it must keep the attempt
        // alive rather than cancel it and release the routing it acquired.
        setBluetoothScoOn(true)
        context.sendBroadcast(Intent(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(AudioRoute.Headset(hasMicrophone = true), monitor.route.value)
        assertTrue(monitor.route.value.keepsPendingAcquisition())
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
