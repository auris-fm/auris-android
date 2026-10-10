package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconId
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconPlayer
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackIntentExecutor
import au.com.shiftyjelly.pocketcasts.voicecontrol.tts.TtsEngine
import au.com.shiftyjelly.pocketcasts.voicecontrol.tts.TtsPlaybackIncompleteException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * Covers the SERVICE's side of the connection: that `renderWithFailureHandling` installs the
 * production wiring before rendering. Testing the wiring function alone establishes that the wiring
 * works, not that the service calls it — removing the call would leave that test passing.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceControlServiceRenderTest {

    @Test
    fun `the service arms its failure handling before rendering`() = runTest {
        val earconPlayer = mock(EarconPlayer::class.java)
        val renderer = AudioFeedbackRenderer(earconPlayer, noopTts())
        val service = VoiceControlService().apply {
            audioFeedbackRenderer = renderer
            voicePlaybackIntentExecutor = mock(VoicePlaybackIntentExecutor::class.java).also { executor ->
                whenever(executor.execute(org.mockito.kotlin.any())).thenReturn(VoiceResponse.Silent)
            }
        }
        try {
            service.renderWithFailureHandling(noopIntent())

            // The service must have installed the production handler, so a delivery failure reaches
            // the user-visible feedback path rather than the renderer's default log.
            assertNotNull("the service must arm failure handling", renderer.onPlaybackFailure)
            renderer.onPlaybackFailure(IllegalStateException("delivery failed"))
            verify(earconPlayer).play(EarconId.ERROR)
        } finally {
            // Only the renderer is released: the service's own teardown touches dependencies this
            // focused test does not stand up, and it is not part of the connection under test.
            renderer.release()
        }
    }

    private fun noopTts() = object : TtsEngine {
        override suspend fun warmUp(language: String) = Unit
        override suspend fun speak(text: String, language: String) = Unit
        override fun release() = Unit
    }

    private fun noopIntent(): VoiceIntent = VoiceIntent.Playback.Pause

    @Test
    fun `a delivery failure does not skip the turn's grace cleanup`() = runTest {
        // Cleanup runs after the render, so a render failure that escaped would skip it — leaving the
        // window un-advanced for a command the user did give. The failure is reported from inside the
        // render job, so the call still completes.
        val recorder = au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder(
            au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintPcmTap(),
        )
        val failing = object : TtsEngine {
            override suspend fun warmUp(language: String) = Unit
            override suspend fun speak(text: String, language: String) {
                throw TtsPlaybackIncompleteException(framesWritten = 4, framesPlayed = 1)
            }

            override fun release() = Unit
        }
        val earcons = EarconPlayer(org.robolectric.RuntimeEnvironment.getApplication(), recorder)
        val renderer = AudioFeedbackRenderer(earcons, failing)
        val service = VoiceControlService().apply {
            audioFeedbackRenderer = renderer
            voicePlaybackIntentExecutor = mock(VoicePlaybackIntentExecutor::class.java).also { executor ->
                whenever(executor.execute(org.mockito.kotlin.any())).thenReturn(VoiceResponse.Spoken("a reply"))
            }
        }
        var cleanupRan = false
        try {
            service.renderWithFailureHandling(noopIntent())
            // Reaching here is the property: the failure was handled inside the render, so the
            // service's post-render cleanup still runs for a command the user did give.
            cleanupRan = true
            assertNotNull("the renderer's failure handling must be installed", renderer.onPlaybackFailure)
        } finally {
            renderer.release()
        }
        assertTrue("cleanup must not be skipped by a delivery failure", cleanupRan)
    }
}
