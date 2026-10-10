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
            val written = engine.framesWritten()
            val playedAtReturn = engine.framesPlayedAtReturn()
            Log.i(
                tag,
                "reference before=$before after=$after speakMs=$elapsed " +
                    "framesWritten=$written framesPlayedAtReturn=$playedAtReturn",
            )

            // Contribution: the answer is on the reference timeline.
            assertTrue("the spoken answer must contribute to the echo reference", after > before)

            // Drain, observed rather than inferred from elapsed time: at return, everything submitted
            // must already have been played. A completion that returned while frames were still
            // queued would show playedAtReturn < written, which is the failure this pins.
            assertTrue("the sink must have been fed", written > 0)
            assertTrue(
                "speak() must return only after playback drained " +
                    "(written=$written playedAtReturn=$playedAtReturn)",
                playedAtReturn >= written,
            )
        } finally {
            engine.release()
        }
    }
}
