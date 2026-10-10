package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconId
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconPlayer
import au.com.shiftyjelly.pocketcasts.voicecontrol.tts.TtsPlaybackIncompleteException
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
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
    fun `without the wiring the failure does not raise feedback`() {
        val earconPlayer = mock(EarconPlayer::class.java)
        val renderer = AudioFeedbackRenderer(earconPlayer, unreachableTts())
        try {
            // The mirror of the test above: with no attachPlaybackFailureHandling call, the default
            // handler only logs, so no earcon may be raised. Without this assertion the first test
            // could pass for a reason unrelated to the wiring.
            renderer.onPlaybackFailure(TtsPlaybackIncompleteException(framesWritten = 10, framesPlayed = 2))
            verify(earconPlayer, never()).play(EarconId.ERROR)
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
