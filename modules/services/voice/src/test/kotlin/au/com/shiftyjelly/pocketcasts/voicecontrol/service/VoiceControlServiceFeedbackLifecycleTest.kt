@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import android.app.Service
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconId
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconPlayer
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackIntentExecutor
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.RouteAcquisition
import au.com.shiftyjelly.pocketcasts.voicecontrol.tts.FakeTtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceControlServiceFeedbackLifecycleTest {
    @Test
    fun `destroying one service leaves shared feedback usable by the next service`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val earcon = mock<EarconPlayer>()
        whenever(earcon.play(EarconId.SUCCESS)).thenReturn(EarconPlayer.AdmissionToken(1L))
        whenever(earcon.completionFor(any())).thenReturn(EarconPlayer.EarconCompletion.Consumed)
        val tts = FakeTtsEngine()
        val renderer = AudioFeedbackRenderer(earcon, tts)
        try {
            val first = service(renderer, "first session")
            first.renderWithFailureHandling(VoiceIntent.Playback.Pause)
            advanceUntilIdle()
            assertEquals("first session", tts.lastSpokenText)

            // The real service teardown, including stopVoiceControl(), not a lifecycle enum fixture.
            first.onDestroy()

            val next = service(renderer, "next session")
            next.renderWithFailureHandling(VoiceIntent.Playback.Pause)
            advanceUntilIdle()

            assertEquals("a new service reuses the same Hilt singletons", "next session", tts.lastSpokenText)
            verify(earcon, times(2)).play(EarconId.SUCCESS)
        } finally {
            renderer.release()
            Dispatchers.resetMain()
        }
    }

    private suspend fun service(renderer: AudioFeedbackRenderer, reply: String): VoiceControlService = spy(VoiceControlService()).apply {
        audioFeedbackRenderer = renderer
        playbackBufferRecorder = mock<PlaybackBufferRecorder>()
        routeAcquisition = mock<RouteAcquisition>()
        voiceRecognizer = mock<VoiceRecognizer>()
        gracePeriodSignal = GracePeriodSignal()
        notificationManager = mock<VoiceControlNotificationManager>()
        voiceControlServiceController = mock<VoiceControlServiceController>()
        voicePlaybackIntentExecutor = mock<VoicePlaybackIntentExecutor>().also { executor ->
            whenever(executor.execute(any(), any())).thenReturn(VoiceResponse.Combined(EarconId.SUCCESS, reply))
        }
        // OS transport is outside this boundary test; the actual service teardown runs unchanged.
        doNothing().whenever(this).stopForeground(Service.STOP_FOREGROUND_REMOVE)
        doNothing().whenever(this).stopSelf()
    }
}
