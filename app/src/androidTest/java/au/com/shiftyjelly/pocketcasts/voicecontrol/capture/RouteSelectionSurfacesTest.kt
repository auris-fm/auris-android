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
 * Which platform observation, if any, distinguishes "an output is available" from "the audio is being
 * sent to it".
 *
 * @spec asked for a production observation or explicit session requirement that can tell the two apart
 * before the route-selection item can be called settled. This enumerates the candidate APIs and reports
 * what each actually answers on a real device, rather than assuming one exists.
 */
@RunWith(AndroidJUnit4::class)
class RouteSelectionSurfacesTest {

    private val tag = "RouteSurface"

    @Suppress("DEPRECATION") // isBluetoothScoOn deprecated in API 34; the production monitor reads it
    @Test
    fun theCandidateObservationsAndWhatEachAnswers() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // (a) Enumeration: what COULD be an output. This is what the monitor reads today.
        val enumerated = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { describe(it.type) }
        // (b) A per-stream query would answer "where is THIS stream routed", which is the candidate for
        // "selected". getDevicesForAttributes is not in this project's SDK, so it is not a surface this
        // app can read today — recorded as such rather than assumed available.
        val musicRoute = "not available in this SDK (getDevicesForAttributes absent)"
        // (c) The communication route: a third question, and not media.
        val communication = manager.communicationDevice?.let { describe(it.type) }

        Log.i(tag, "enumerated=$enumerated")
        Log.i(tag, "musicRouteAttributes=$musicRoute")
        Log.i(tag, "communicationDevice=$communication")
        Log.i(tag, "isBluetoothScoOn=${manager.isBluetoothScoOn}")
        Log.i(tag, "isMusicActive=${manager.isMusicActive}")

        // The test records rather than asserts a routing property: which surface is authoritative is the
        // question under investigation, and encoding an answer here would assume what is being asked.
        assertTrue("enumeration must be readable (got=$enumerated)", enumerated.isNotEmpty())
    }

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
