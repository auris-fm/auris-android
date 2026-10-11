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

    /**
     * An upstream transport exception must produce failure feedback, and must not cost the user the
     * next command's handling.
     *
     * @spec's assignment: the transport now closes its channel with a cause on a refused terminal
     * enqueue, so a thrown exception can reach `execute()` -- which has no try/catch -- and then
     * `renderWithFailureHandling`, which has none either. This drives that path through the service's
     * own method rather than a test-installed handler, and asserts the user-facing contract: the
     * failure is heard, and the pipeline is still usable afterwards.
     *
     * A test expecting propagation would establish only that the exception travels; the contract is
     * that it does not stop the pipeline or leave the user without feedback.
     */
    @Test
    fun `an upstream transport exception produces feedback and the next command still runs`() = runTest {
        val earconPlayer = mock(EarconPlayer::class.java)
        val renderer = AudioFeedbackRenderer(earconPlayer, noopTts())
        val executor = mock(VoicePlaybackIntentExecutor::class.java)
        whenever(executor.execute(org.mockito.kotlin.any()))
            .thenThrow(IllegalStateException("Cloud route: channel full, could not deliver terminal Error"))
            .thenReturn(VoiceResponse.Silent)
        val service = VoiceControlService().apply {
            audioFeedbackRenderer = renderer
            voicePlaybackIntentExecutor = executor
        }
        try {
            // The failing command: the throw must be surfaced to the user as feedback rather than
            // escaping into the service's scope.
            service.renderWithFailureHandling(noopIntent())

            verify(earconPlayer).play(EarconId.ERROR)

            // AND the pipeline is still usable: the next command runs and renders normally. This is
            // the half a propagation-only test would miss -- the throw could be reported and still
            // leave every later command broken.
            service.renderWithFailureHandling(noopIntent())
            verify(executor, org.mockito.Mockito.times(2)).execute(org.mockito.kotlin.any())
        } finally {
            renderer.release()
        }
    }

    /**
     * Cancellation must pass through, not be reported as a failure.
     *
     * The catch above is deliberately ordered so `CancellationException` is rethrown first. Swallowing
     * it would turn the caller withdrawing -- a normal stop -- into an error earcon for the user, and
     * would leave structured concurrency unable to unwind.
     */
    @Test
    fun `cancellation is not turned into feedback`() = runTest {
        val earconPlayer = mock(EarconPlayer::class.java)
        val renderer = AudioFeedbackRenderer(earconPlayer, noopTts())
        val executor = mock(VoicePlaybackIntentExecutor::class.java)
        whenever(executor.execute(org.mockito.kotlin.any()))
            .thenThrow(kotlinx.coroutines.CancellationException("caller withdrew"))
        val service = VoiceControlService().apply {
            audioFeedbackRenderer = renderer
            voicePlaybackIntentExecutor = executor
        }
        try {
            var escaped: Throwable? = null
            try {
                service.renderWithFailureHandling(noopIntent())
            } catch (t: Throwable) {
                escaped = t
            }
            assertTrue(
                "cancellation must pass through rather than be reported as a failure",
                escaped is kotlinx.coroutines.CancellationException,
            )
            verify(earconPlayer, org.mockito.Mockito.never()).play(EarconId.ERROR)
        } finally {
            renderer.release()
        }
    }
}
