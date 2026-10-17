@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AndroidAudioRouteMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.BluetoothCandidate
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.BluetoothTransport
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.RouteAcquisition
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.RouteAcquisitionPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioManager

/**
 * The Off branch fires acquisition from AVAILABILITY (the enumeration surface that answers "is an
 * external microphone candidate present"), but the OBSERVED route is `Unknown` until SCO opens --
 * and [RouteAcquisition.acquire] refuses any route that is not `BluetoothA2dpOnly`. Passing the
 * observation made acquisition a SILENT `NotNeeded` no-op: reported on device as "the app doesn't
 * grab the mic at all", with `acquiring external microphone for route=Unknown` and no follow-up.
 *
 * The candidate must therefore reach `acquire` from availability, the same surface that triggered
 * the branch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceControlServiceAcquisitionTest {
    private lateinit var context: Context
    private lateinit var audioManager: AudioManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // The service launches acquisition on Dispatchers.Main: the unconfined test dispatcher
        // runs the launch inline on the calling thread instead of queueing to the looper.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Records the operations acquisition performs, so the test can see HOW FAR it got. */
    private class RecordingPlatform : RouteAcquisitionPlatform {
        var startScoCalls = 0
        var stopScoCalls = 0

        override fun bluetoothCandidates() = listOf(BluetoothCandidate(deviceId = 41, transport = BluetoothTransport.ClassicSco))

        override fun startBluetoothSco() {
            startScoCalls++
        }

        override fun stopBluetoothSco() {
            stopScoCalls++
        }

        override fun isBluetoothScoOn() = false

        override fun setCommunicationDevice(deviceId: Int) = true

        override fun communicationDeviceId(): Int? = null

        override fun clearCommunicationDevice() = Unit

        override fun audioMode() = AudioManager.MODE_NORMAL

        override fun setAudioMode(mode: Int) = Unit

        override fun deviceType(deviceId: Int): Int? = null

        override fun openInputStream() = -1

        override fun startInputStream() = false

        override fun closeInputStream() = Unit
    }

    @Test
    fun `the off branch acquires the availability candidate although the observed route is unknown`() = runTest {
        setOutputDevices(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        val monitor = AndroidAudioRouteMonitor(context, GracePeriodSignal())
        // The two surfaces DISAGREE, which is exactly the on-device shape: nothing is routed or
        // selected, so the observation has no answer, while the enumeration names the candidate.
        assertEquals("precondition: no observation yet", AudioRoute.Unknown, monitor.route.value)
        assertEquals(
            "precondition: availability names the candidate",
            AudioRoute.BluetoothA2dpOnly,
            monitor.availability.value,
        )

        val platform = RecordingPlatform()
        val service = VoiceControlService().apply {
            gate = VoiceControlGate(rules = emptyList(), scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            audioRouteMonitor = monitor
            routeAcquisition = RouteAcquisition(platform, timeoutMs = 500, pollIntervalMs = 10)
        }

        service.acquireExternalMicrophoneIfCandidate()

        assertEquals(
            "acquisition must PROCEED on the availability candidate: startBluetoothSco is the first " +
                "platform act of a ClassicSco acquisition, and with the observed route passed instead " +
                "acquire refuses Unknown as NotNeeded before touching the platform",
            1,
            platform.startScoCalls,
        )
    }

    @Test
    fun `the off branch does not acquire when availability names no candidate`() = runTest {
        setOutputDevices(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val monitor = AndroidAudioRouteMonitor(context, GracePeriodSignal())
        val platform = RecordingPlatform()
        val service = VoiceControlService().apply {
            gate = VoiceControlGate(rules = emptyList(), scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            audioRouteMonitor = monitor
            routeAcquisition = RouteAcquisition(platform, timeoutMs = 500, pollIntervalMs = 10)
        }

        val fired = service.acquireExternalMicrophoneIfCandidate()

        assertEquals("no candidate, nothing to acquire", false, fired)
        assertEquals("the platform must be untouched", 0, platform.startScoCalls)
    }

    private fun setOutputDevices(vararg types: Int) {
        shadowOf(audioManager).setOutputDevices(
            types.map { type ->
                AudioDeviceInfoBuilder.newBuilder().setType(type).build()
            },
        )
    }
}
