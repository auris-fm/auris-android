package au.com.shiftyjelly.pocketcasts.voicecontrol.capture

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The authoritative check for the route row: what the app's own `GET_DEVICES_OUTPUTS` offers, which is
 * exactly what AndroidAudioRouteMonitor.readRoute() reads.
 *
 * A timestamped audio log says a sink *was* available; only this list says what is offered now. @reviewer
 * asked for this check specifically, having read a log rather than the live device list.
 */
@RunWith(AndroidJUnit4::class)
class AudioOutputEnumerationTest {

    private val tag = "AudioOutputs"

    @Test
    fun theAppSeesWhatTheSystemOffersAsAnOutput() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        val described = outputs.map { describe(it.type) }
        Log.i(tag, "outputs=$described")
        Log.i(tag, "hasA2dp=${outputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }}")
        Log.i(tag, "hasBle=${outputs.any { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }}")
        // ENUMERATION IS NOT ROUTING. This list answers "what could be an output", not "where does the
        // player route". If an A2DP device is offered here while a player is silent, that is a different
        // fact from it being active. Reading these as the same question is how a wrong conclusion about
        // the media route gets stated with confidence.
        val communication = manager.communicationDevice
        Log.i(tag, "communicationDevice=${communication?.let { describe(it.type) }}")

        // The measurement, not an assertion about which route is right: the list must be non-empty, and
        // the log states whether an external route is currently offered. Asserting the absence of A2DP
        // would encode today's device state as a requirement.
        assertTrue("the output list must be readable (got=$described)", outputs.isNotEmpty())
    }

    /** Type names are not on every API level, so map the constants the route code itself uses. */
    private fun describe(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wiredHeadset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wiredHeadphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bt_a2dp"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt_sco"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble_headset"
        else -> "type($type)"
    }
}
