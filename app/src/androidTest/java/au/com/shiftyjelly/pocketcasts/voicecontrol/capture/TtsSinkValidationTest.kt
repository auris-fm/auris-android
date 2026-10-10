package au.com.shiftyjelly.pocketcasts.voicecontrol.capture

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintPcmTap
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder
import au.com.shiftyjelly.pocketcasts.voicecontrol.tts.AndroidPlatformTtsEngine
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device validation of the app-owned TTS sink: the spoken answer must reach the shared echo
 * reference, and the utterance must complete after playback drains rather than when synthesis
 * finishes. Observed, not inferred — the reference is read after speak() returns.
 */
@RunWith(AndroidJUnit4::class)
class TtsSinkValidationTest {

    private val tag = "TtsSinkValidation"

    @Test
    fun spokenAnswerReachesTheReferenceAndCompletesAfterDrain() = runBlocking<Unit> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        val engine = AndroidPlatformTtsEngine(ctx, recorder)
        try {
            engine.warmUp("en")
            val before = recorder.snapshot().size
            val elapsed = measureTimeMillis {
                withTimeoutOrNull(30_000) { engine.speak("testing one two three", "en") }
            }
            val after = recorder.snapshot().size
            Log.i(tag, "reference samples before=$before after=$after speakMs=$elapsed")

            assertTrue("the spoken answer must contribute to the echo reference", after > before)
            // A ~1s utterance cannot complete in a few ms; a synthesis-only return would.
            assertTrue("speak must not return before playback drained (took ${elapsed}ms)", elapsed > 200)
        } finally {
            engine.release()
        }
    }
}
