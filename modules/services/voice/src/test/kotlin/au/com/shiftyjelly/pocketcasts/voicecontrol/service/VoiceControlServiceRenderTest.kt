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
        try {
            service.renderWithFailureHandling(noopIntent())
            // No local flag is asserted here: `cleanupRan = true` before the assertion would pass
            // whenever the call returns, and the call THROWS if the failure escapes -- so a flag
            // re-tests the return, not any cleanup. The property this case can establish is that a
            // delivery failure is handled inside the render, leaving the method to return normally.
            // The grace cleanup is measured through the production launch path in
            // `an ordinary failure keeps feedback and grace cleanup, and the next command is handled`,
            // which is where that cleanup actually lives (handleIntent's launch block, not here).
            assertNotNull("the renderer's failure handling must be installed", renderer.onPlaybackFailure)
        } finally {
            renderer.release()
        }
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

    /**
     * @spec's assigned service-level case: the FIRST execution throws, feedback and grace cleanup
     * occur, and the SECOND command is handled.
     *
     * The two-command shape is the point. The earlier test shows the first command recovers; this one
     * drives `handleIntent` -- the production entry point -- so the grace window is the real
     * `GracePeriodSignal`, and the second command is asserted HANDLED rather than inferred from
     * `SupervisorJob` semantics. That inference was flagged as unmeasured by @reviewer and is what this
     * closes.
     */
    @Test
    fun `an ordinary failure keeps feedback and grace cleanup, and the next command is handled`() = runTest {
        val earconPlayer = mock(EarconPlayer::class.java)
        val renderer = AudioFeedbackRenderer(earconPlayer, noopTts())
        val executor = mock(VoicePlaybackIntentExecutor::class.java)
        whenever(executor.execute(org.mockito.kotlin.any()))
            .thenThrow(IllegalStateException("Cloud route: channel full, could not deliver terminal Error"))
            .thenReturn(VoiceResponse.Silent)
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal(timeoutMs = 60_000L)
        val service = VoiceControlService().apply {
            audioFeedbackRenderer = renderer
            voicePlaybackIntentExecutor = executor
            gracePeriodSignal = signal
            // Dispatcher.Main under Robolectric so the service's own scope can run.
            voiceControlServiceController = mock(au.com.shiftyjelly.pocketcasts.voicecontrol.service.VoiceControlServiceController::class.java)
        }
        signal.onWakeWordDetected()
        // Spend this window's single escalation so the window state is distinguishable afterwards.
        signal.issueEscalation()
        assertEquals("the probe needs a spent escalation", "allowance spent in this window", signal.escalationRefusal())
        try {
            // Command 1 -- throws. Drive the production entry point, which owns the grace cleanup.
            // The window's OWN state is what the cleanup moves, so sample it before and after: a
            // non-CloudRoute intent has no generation, and onCommandRecognized then resets the open
            // window rather than closing it, so "still active" is the correct outcome here and the
            // cleanup is visible as the window being touched for a command that failed.
            service.handleIntentForTest(VoiceIntent.Playback.Pause)
            // The launch is on Dispatchers.IO, which runTest's virtual time does not advance: wait on
            // REAL time until the first command has been attempted.
            awaitRealTime { org.mockito.Mockito.mockingDetails(executor).invocations.size >= 1 }

            // Feedback: the service must have produced a failure response rather than letting the
            // throw escape. The renderer dispatches on Main.immediate, so idle the looper for it.
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            verify(earconPlayer, org.mockito.Mockito.atLeastOnce()).play(EarconId.ERROR)
            // AND the cleanup ran, shown through an effect only the cleanup can produce: an ordinary
            // intent has no generation, so onCommandRecognized opens a NEW window and advances the
            // generation. Spending the old window's single escalation first makes the difference
            // visible -- a spent escalation in the OLD generation stays refused, while a fresh window
            // accepts one. A launch that died before the cleanup leaves the spent escalation refused.
            assertEquals(
                "the cleanup must begin a new window for the command the user gave: " +
                    "refusal=${signal.escalationRefusal()}",
                "available",
                signal.escalationRefusal(),
            )

            // Command 2 -- handled normally. A different intent type avoids the service's debounce.
            service.handleIntentForTest(VoiceIntent.Playback.Resume)
            awaitRealTime { org.mockito.Mockito.mockingDetails(executor).invocations.size >= 2 }
            verify(executor, org.mockito.Mockito.times(2)).execute(org.mockito.kotlin.any())
        } finally {
            renderer.release()
        }
    }

    /** Poll on real time: the service's scope dispatches off the test scheduler. */
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

    /**
     * @spec's launch-path cancellation check: a cancelled command produces NO feedback and does NOT
     * renew the grace allowance.
     *
     * Driven through `handleIntent`, so both halves are observed at the launch site rather than at the
     * helper: the helper rethrows cancellation, the launch rethrows it again before the cleanup, and
     * nothing may reach `onCommandRecognized`.
     *
     * The allowance probe is the same one the ordinary-failure case uses, and it is why this is a
     * check rather than a state read: the wake's escalation is spent first, so a renewed window would
     * ACCEPT a further escalation while a window left untouched keeps refusing one.
     */
    @Test
    fun `a cancelled command gives no feedback and does not renew grace`() = runTest {
        val earconPlayer = mock(EarconPlayer::class.java)
        val renderer = AudioFeedbackRenderer(earconPlayer, noopTts())
        val executor = mock(VoicePlaybackIntentExecutor::class.java)
        whenever(executor.execute(org.mockito.kotlin.any()))
            .thenThrow(kotlinx.coroutines.CancellationException("caller withdrew"))
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal(timeoutMs = 60_000L)
        val service = VoiceControlService().apply {
            audioFeedbackRenderer = renderer
            voicePlaybackIntentExecutor = executor
            gracePeriodSignal = signal
            voiceControlServiceController = mock(au.com.shiftyjelly.pocketcasts.voicecontrol.service.VoiceControlServiceController::class.java)
        }
        signal.onWakeWordDetected()
        // Spend this window's single escalation so a renewal would be visible as "available".
        signal.issueEscalation()
        assertEquals(
            "the probe needs a spent escalation",
            "allowance spent in this window",
            signal.escalationRefusal(),
        )
        try {
            service.handleIntentForTest(VoiceIntent.Playback.Pause)
            awaitRealTime { org.mockito.Mockito.mockingDetails(executor).invocations.size >= 1 }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()

            // No feedback: cancellation is the caller withdrawing, not a failure to report.
            verify(earconPlayer, org.mockito.Mockito.never()).play(EarconId.ERROR)
            // And the window was not renewed, so the spent allowance stays spent.
            assertEquals(
                "cancellation must not reopen or advance the window: refusal=${signal.escalationRefusal()}",
                "allowance spent in this window",
                signal.escalationRefusal(),
            )
        } finally {
            renderer.release()
        }
    }
}
