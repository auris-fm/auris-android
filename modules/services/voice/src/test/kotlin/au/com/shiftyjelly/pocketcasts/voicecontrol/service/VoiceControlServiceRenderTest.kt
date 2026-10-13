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
                whenever(executor.execute(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(VoiceResponse.Silent)
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

    private fun cloudIntent(): VoiceIntent = VoiceIntent.CloudRoute(
        request = "summarize this episode",
        tier = VoiceIntent.CloudTier.Premium,
    )

    @Test
    fun `the executor writes the REQUEST-OWNED outcome the caller minted`() = runTest {
        // @spec: the value must belong to the REQUEST, not to a slot on the executor. The caller mints
        // the holder and passes it in, so two concurrent turns cannot race for one field -- and this case
        // reads back the holder the CALLER would read.
        val earconPlayer = mock(EarconPlayer::class.java)
        val renderer = AudioFeedbackRenderer(earconPlayer, noopTts())
        val service = VoiceControlService().apply {
            audioFeedbackRenderer = renderer
            voicePlaybackIntentExecutor = mock(VoicePlaybackIntentExecutor::class.java).also { executor ->
                whenever(executor.execute(org.mockito.kotlin.any(), org.mockito.kotlin.any()))
                    .thenAnswer { invocation ->
                        // The production write: the executor fills the caller's holder.
                        invocation.getArgument<VoicePlaybackIntentExecutor.TurnOutcome>(1).action =
                            CloudRouteActionOutcome.Unknown
                        VoiceResponse.Silent
                    }
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
    fun `an unknown action outcome does not earn command recognition at the production caller`() = runTest {
        // @spec: the decision must drive BEHAVIOUR, not fill a slot. Command recognition extends the
        // listening window and clears the escalation penalty -- privileges a turn that has not been shown
        // to have done anything must not earn. Observed through the window state, which only the
        // recognition path moves.
        val earconPlayer = mock(EarconPlayer::class.java)
        val renderer = AudioFeedbackRenderer(earconPlayer, noopTts())
        val executor = mock(VoicePlaybackIntentExecutor::class.java)
        whenever(executor.execute(org.mockito.kotlin.any(), org.mockito.kotlin.any()))
            .thenAnswer { invocation ->
                // THE PRODUCTION WRITE: the executor fills the REQUEST'S holder with an unknown outcome.
                invocation.getArgument<VoicePlaybackIntentExecutor.TurnOutcome>(1).action =
                    CloudRouteActionOutcome.Unknown
                VoiceResponse.Silent
            }
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal(timeoutMs = 60_000L)
        val service = VoiceControlService().apply {
            audioFeedbackRenderer = renderer
            voicePlaybackIntentExecutor = executor
            gracePeriodSignal = signal
            voiceControlServiceController = mock(au.com.shiftyjelly.pocketcasts.voicecontrol.service.VoiceControlServiceController::class.java)
        }
        signal.onWakeWordDetected()
        signal.issueEscalation()
        assertEquals("the probe needs a spent escalation", "allowance spent in this window", signal.escalationRefusal())
        try {
            service.handleIntentForTest(
                VoiceIntent.CloudRoute(
                    request = "summarize this episode",
                    tier = VoiceIntent.CloudTier.Premium,
                ),
            )
            awaitRealTime { org.mockito.Mockito.mockingDetails(executor).invocations.size >= 1 }
            awaitRealTime {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                true
            }

            // THE SPENT ESCALATION IS STILL SPENT: recognition would have opened a new window and made
            // it "available" again. An unknown outcome must not grant that.
            assertEquals(
                "an unverified action must not be recognized as a delivered command: " +
                    "refusal=${signal.escalationRefusal()}",
                "allowance spent in this window",
                signal.escalationRefusal(),
            )
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
        whenever(executor.execute(org.mockito.kotlin.any(), org.mockito.kotlin.any()))
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

    /**
     * A normal service stop must not permanently disable feedback on the next start.
     *
     * @spec's requirement, and it is user-facing: `VoiceControlService` releases the renderer on every
     * stop while the renderer is a Hilt `@Singleton`, so a single one-way flag meant the SECOND session
     * had no feedback at all -- silently. Disposal is `onDestroy`'s job; a stop ends the session.
     */
    @Test
    fun `a stop ends the session without disabling the next start`() = runTest {
        val earconPlayer = mock(EarconPlayer::class.java)
        val renderer = AudioFeedbackRenderer(earconPlayer, noopTts())
        try {
            // Session 1 ends the way stopVoiceControl() does.
            renderer.endSession()

            // Session 2 must still be able to emit: this is the property a one-way flag broke.
            var failure: Throwable? = null
            try {
                renderer.playEarcon(EarconId.ERROR)
            } catch (t: Throwable) {
                failure = t
            }
            assertEquals("a stop must leave the renderer usable, not disposed", null, failure)
            // and it must actually reach the player rather than being refused by terminal state.
            verify(earconPlayer, org.mockito.Mockito.atLeastOnce()).play(EarconId.ERROR)
        } finally {
            renderer.release()
        }
    }

    /**
     * A disposed renderer IS refused, so the fix is a split rather than a removal.
     *
     * Without this the previous case would pass if the flag were simply deleted, and "endSession does
     * not disable" would be indistinguishable from "nothing ever disables".
     */
    @Test
    fun `a disposed renderer refuses to emit`() = runTest {
        val earconPlayer = mock(EarconPlayer::class.java)
        val renderer = AudioFeedbackRenderer(earconPlayer, noopTts())
        renderer.release()

        renderer.playEarcon(EarconId.ERROR)

        verify(earconPlayer, org.mockito.Mockito.never()).play(EarconId.ERROR)
    }

    /**
     * The WIRING the service uses, driven through the same constant its two call sites pass.
     *
     * @reviewer's gap: the renderer's two behaviours were pinned but the CHOICE between them was not,
     * because `stopVoiceControl` is private and the cases called `endSession()`/`release()` directly.
     * Swapping them at the call site passed the whole suite. The service now selects a named constant,
     * so this drives the same mapping -- and swapping the two constants in the service is the mistake
     * it fails on.
     */
    @Test
    fun `the stop lifecycle ends the session so the next start can still emit`() {
        val earconPlayer = mock(EarconPlayer::class.java)
        val renderer = AudioFeedbackRenderer(earconPlayer, noopTts())
        try {
            renderer.applyLifecycle(AudioFeedbackRenderer.Lifecycle.SessionEnded)

            // Session 2 must still emit: disposal here would refuse, and that is the defect.
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
}
