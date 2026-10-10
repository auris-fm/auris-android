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
        // ORDERING REGRESSION. Capture must own its ONNX Runtime dependency: it previously resolved it
        // with RTLD_NOLOAD only and so failed whenever wake/embedding/transcriber setup had not run
        // first. Assert the precondition explicitly, so this test fails if that dependency returns —
        // reverting the load fallback makes the assertion below unreachable (capture cannot start).
        assertTrue(
            "precondition: this process has NOT loaded onnxruntime via another component",
            !MicrophoneCapture.isOnnxRuntimeLoaded(),
        )

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
                "capture must start with onnxruntime not pre-loaded by another component",
                capture.isRecording,
            )
            assertTrue("the capture flow must not fault", flowedWithoutFault)
            // Captured audio must actually REACH the VAD, not merely have the capture running. A
            // liveness check cannot show this in silence, because no speech event fires; the frame
            // counter can.
            val framesConsumed = MicrophoneCapture.framesConsumed()
            Log.i(tag, "vad frames consumed=$framesConsumed")
            assertTrue(
                "captured frames must reach the native VAD (consumed=$framesConsumed)",
                framesConsumed > 0,
            )
        } finally {
            job.cancel()
            capture.stopCapture()
            scope.cancel()
        }
        assertTrue("capture stopped cleanly", !capture.isRecording)
    }
}
