package au.com.shiftyjelly.pocketcasts.voicecontrol.service
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconId
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconPlayer
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.CloudRouteActionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackIntentExecutor
import au.com.shiftyjelly.pocketcasts.voicecontrol.tts.TtsEngine
import au.com.shiftyjelly.pocketcasts.voicecontrol.tts.TtsPlaybackIncompleteException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
@RunWith(RobolectricTestRunner::class)
class VoiceControlServiceRenderTest {
    @Test
    fun `the service arms its failure handling before rendering`() = runTest {
        val earconPlayer = mock(EarconPlayer::class.java)
        val renderer = AudioFeedbackRenderer(earconPlayer, noopTts())
        val service = VoiceControlService().apply {
            audioFeedbackRenderer = renderer
            voicePlaybackIntentExecutor = StubVoiceExecutor()
        }
        try {
            service.renderWithFailureHandling(noopIntent())
            assertNotNull("the service must arm failure handling", renderer.onPlaybackFailure)
            renderer.onPlaybackFailure(IllegalStateException("delivery failed"))
            verify(earconPlayer).play(EarconId.ERROR)
        } finally {
            renderer.release()
        }
    }
    private fun noopTts() = object : TtsEngine {
        override suspend fun warmUp(language: String) = Unit
        override suspend fun speak(text: String, language: String): Boolean = true
        override fun release() = Unit
    }
    private fun noopIntent(): VoiceIntent = VoiceIntent.Playback.Pause
    private fun cloudIntent(): VoiceIntent = VoiceIntent.CloudRoute(
        request = "summarize this episode",
        tier = VoiceIntent.CloudTier.Premium,
    )
    @Test
    fun `the executor writes the REQUEST-OWNED outcome the caller minted`() = runTest {
        val earconPlayer = mock(EarconPlayer::class.java)
        val renderer = AudioFeedbackRenderer(earconPlayer, noopTts())
        val service = VoiceControlService().apply {
            audioFeedbackRenderer = renderer
            voicePlaybackIntentExecutor = StubVoiceExecutor { _, outcome, _ ->
                outcome.action = CloudRouteActionOutcome.Unknown
                VoiceResponse.Silent
            }
        }
        val outcome = VoicePlaybackIntentExecutor.TurnOutcome()
        try {
            service.renderWithFailureHandling(cloudIntent(), outcome)
            assertEquals(
                "the outcome the executor reports must land in the CALLER'S holder",
                CloudRouteActionOutcome.Unknown,
                outcome.action,
            )
        } finally {
            renderer.release()
        }
    }
    @Test
    fun `a cancelled command gives no feedback and does not renew grace`() = runTest {
        val earconPlayer = mock(EarconPlayer::class.java)
        val renderer = AudioFeedbackRenderer(earconPlayer, noopTts())
        var executed = false
        val executor = StubVoiceExecutor { _, _, _ ->
            executed = true
            throw kotlinx.coroutines.CancellationException("caller withdrew")
        }
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal(timeoutMs = 60_000L)
        val service = VoiceControlService().apply {
            audioFeedbackRenderer = renderer
            voicePlaybackIntentExecutor = executor
            gracePeriodSignal = signal
            voiceControlServiceController = mock(au.com.shiftyjelly.pocketcasts.voicecontrol.service.VoiceControlServiceController::class.java)
        }
        signal.onWakeWordDetected()
        val captured = signal.currentGeneration
        val timerBefore = signal.timerJobForTest()
        try {
            val result = runCatching { service.renderWithFailureHandling(VoiceIntent.Playback.Pause) }
            org.junit.Assert.assertTrue(
                "cancellation must propagate out of the service, not be swallowed: " + result,
                result.exceptionOrNull() is kotlinx.coroutines.CancellationException,
            )
            org.junit.Assert.assertTrue("the executor must have run", executed)
            verify(earconPlayer, org.mockito.Mockito.never()).play(EarconId.ERROR)
            org.junit.Assert.assertSame("cancellation cannot reset inactivity", timerBefore, signal.timerJobForTest())
            assertEquals(captured, signal.currentGeneration)
            org.junit.Assert.assertTrue(signal.isCurrent(captured))
        } finally {
            renderer.release()
        }
    }
    @Test
    fun `a stop ends the session without disabling the next start`() = runTest {
        val earconPlayer = mock(EarconPlayer::class.java)
        val renderer = AudioFeedbackRenderer(earconPlayer, noopTts())
        try {
            renderer.endSession()
            var failure: Throwable? = null
            try {
                renderer.playEarcon(EarconId.ERROR)
            } catch (t: Throwable) {
                failure = t
            }
            assertEquals("a stop must leave the renderer usable, not disposed", null, failure)
            verify(earconPlayer, org.mockito.Mockito.atLeastOnce()).play(EarconId.ERROR)
        } finally {
            renderer.release()
        }
    }
    @Test
    fun `a disposed renderer refuses to emit`() = runTest {
        val earconPlayer = mock(EarconPlayer::class.java)
        val renderer = AudioFeedbackRenderer(earconPlayer, noopTts())
        renderer.release()
        renderer.playEarcon(EarconId.ERROR)
        verify(earconPlayer, org.mockito.Mockito.never()).play(EarconId.ERROR)
    }
    @Test
    fun `the stop lifecycle ends the session so the next start can still emit`() {
        val earconPlayer = mock(EarconPlayer::class.java)
        val renderer = AudioFeedbackRenderer(earconPlayer, noopTts())
        try {
            renderer.applyLifecycle(AudioFeedbackRenderer.Lifecycle.SessionEnded)
            renderer.playEarcon(EarconId.ERROR)
            verify(earconPlayer, org.mockito.Mockito.atLeastOnce()).play(EarconId.ERROR)
        } finally {
            renderer.release()
        }
    }
    @Test
    fun `the disposal lifecycle refuses a later emit`() {
        val earconPlayer = mock(EarconPlayer::class.java)
        val renderer = AudioFeedbackRenderer(earconPlayer, noopTts())
        renderer.applyLifecycle(AudioFeedbackRenderer.Lifecycle.Disposed)
        renderer.playEarcon(EarconId.ERROR)
        verify(earconPlayer, org.mockito.Mockito.never()).play(EarconId.ERROR)
    }
    private suspend fun awaitRealTime(
        timeoutMs: Long = 5_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
    }
}
