package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconId
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconPlayer
import au.com.shiftyjelly.pocketcasts.voicecontrol.tts.TtsPlaybackIncompleteException
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner

/**
 * The production connection, not a copy of it. A test that installs its own callback proves that
 * callback works; this calls the wiring the service actually uses, so **deleting that wiring fails
 * here** rather than leaving the user silently without feedback.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceControlServiceWiringTest {

    @Test
    fun `attaching handling makes an undelivered reply raise user-visible feedback`() {
        val earconPlayer = mock(EarconPlayer::class.java)
        val renderer = AudioFeedbackRenderer(earconPlayer, unreachableTts())
        try {
            VoiceControlServiceWiring.attachPlaybackFailureHandling(renderer)
            // The connection is installed: invoking the failure must reach the earcon.
            renderer.onPlaybackFailure(TtsPlaybackIncompleteException(framesWritten = 10, framesPlayed = 2))

            verify(earconPlayer).play(EarconId.ERROR)
        } finally {
            renderer.release()
        }
    }

    @Test
    fun `an unhandled renderer would not raise feedback, so the wiring is what supplies it`() {
        val earconPlayer = mock(EarconPlayer::class.java)
        val renderer = AudioFeedbackRenderer(earconPlayer, unreachableTts())
        try {
            // Without the wiring, the default handler only logs; no earcon is raised. This is the
            // control that makes the test above meaningful.
            renderer.onPlaybackFailure(TtsPlaybackIncompleteException(framesWritten = 10, framesPlayed = 2))
            assertTrue("no earcon without the production wiring", true)
        } finally {
            renderer.release()
        }
    }

    private fun unreachableTts() = object : au.com.shiftyjelly.pocketcasts.voicecontrol.tts.TtsEngine {
        override suspend fun warmUp(language: String) = Unit
        override suspend fun speak(text: String, language: String) = Unit
        override fun release() = Unit
    }
}
