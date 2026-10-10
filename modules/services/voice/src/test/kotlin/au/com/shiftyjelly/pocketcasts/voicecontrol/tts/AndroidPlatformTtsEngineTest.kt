package au.com.shiftyjelly.pocketcasts.voicecontrol.tts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintPcmTap
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidPlatformTtsEngineTest {
    private lateinit var engine: AndroidPlatformTtsEngine
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        engine = AndroidPlatformTtsEngine(
            context,
            PlaybackBufferRecorder(FingerprintPcmTap()),
        )
    }

    @Test
    fun `release disposes resources without crash`() {
        engine.release()
        // No crash expected
    }

    @Test
    fun `speak before initialization returns without throwing`() = runTest {
        // The engine tolerates speak before TTS is ready, which is what the renderer relies on when
        // a reply arrives during startup.
        engine.speak("hello", "en")
    }

    @Test
    fun `release is idempotent`() {
        engine.release()
        engine.release()
    }
}
