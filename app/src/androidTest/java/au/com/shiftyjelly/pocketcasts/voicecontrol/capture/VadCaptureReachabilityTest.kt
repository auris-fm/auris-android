package au.com.shiftyjelly.pocketcasts.voicecontrol.capture

import android.Manifest
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.MicrophoneCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device reachability for the native retention logic — closes the item kept open behind the host
 * harness. That harness drives the frame loop through a fake capture, proving the accounting but not
 * that the production Oboe capture path reaches it.
 *
 * This starts the public production surface ([MicrophoneCapture.startCapture], which opens real Oboe
 * capture into the shipped native VAD over JNI) on a device and asserts the VAD path is live:
 * capture reports active and the flow runs without fault. It does NOT inject audio, so it does not
 * assert segment accounting — the host harness and filter tests do that. Silence legitimately
 * produces no segment, so the assertion is on liveness, not on an event arriving.
 */
@RunWith(AndroidJUnit4::class)
class VadCaptureReachabilityTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val tag = "VadCaptureReachability"

    @Test
    fun productionCapturePathReachesTheNativeVad() = runBlocking<Unit> {
        // The TARGET app's context, not the test APK's: the native VAD reads the model from this
        // AssetManager, and the model ships in the app, not in the instrumentation APK.
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val capture = MicrophoneCapture(targetContext)
        // startCapture returns a COLD flow: the native capture only starts once it is collected.
        val flow = capture.startCapture()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var flowedWithoutFault = true
        // Collect in the background so the engine actually starts and keeps running.
        val job = scope.launch {
            runCatching {
                flow.collect { result -> Log.i(tag, "segment result=${result::class.simpleName}") }
            }.onFailure { error ->
                flowedWithoutFault = false
                Log.e(tag, "capture flow faulted", error)
            }
        }

        try {
            // Give Oboe + the native VAD time to start, then assert the path is live.
            delay(3_000)
            assertTrue(
                "native capture must be active on the production path",
                capture.isRecording,
            )
            assertTrue("the capture flow must not fault", flowedWithoutFault)
        } finally {
            job.cancel()
            capture.stopCapture()
            scope.cancel()
        }
        assertTrue("capture stopped cleanly", !capture.isRecording)
    }
}
