@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package au.com.shiftyjelly.pocketcasts.voicecontrol.feedback

import android.content.Context
import android.media.AudioManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.di.VoiceControlModule
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRuleState
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.OtherAppPlayingCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContextMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.tts.FakeTtsEngine
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
class AudioFeedbackAttributionTest {
    @Test
    fun `already rendering cue is attributed before the first heartbeat`() = runTest {
        val player = player(EarconPlayer.EarconCompletion.Playing)
        val renderer = AudioFeedbackRenderer(player, FakeTtsEngine()).apply {
            scopeDispatcherForTest = StandardTestDispatcher(testScheduler)
        }
        try {
            renderer.playEarcon(EarconId.WAKE_WORD)
            assertFalse(renderer.hasEmittedAudio.value)
            assertFalse(condition(renderer).otherAppPlaying(true))
            assertTrue(renderer.hasEmittedAudio.value)
        } finally {
            renderer.release()
        }
    }

    @Test
    fun `queued cue does not hide foreign output but measured start is recognized immediately`() = runTest {
        val player = player(EarconPlayer.EarconCompletion.Queued)
        val renderer = AudioFeedbackRenderer(player, FakeTtsEngine()).apply {
            scopeDispatcherForTest = StandardTestDispatcher(testScheduler)
        }
        try {
            renderer.playEarcon(EarconId.WAKE_WORD)
            val condition = condition(renderer)
            assertTrue(condition.otherAppPlaying(true))
            assertFalse(renderer.hasEmittedAudio.value)
            whenever(player.completionFor(any())).thenReturn(EarconPlayer.EarconCompletion.Playing)
            assertFalse(condition.otherAppPlaying(true))
            assertTrue(renderer.hasEmittedAudio.value)
        } finally {
            renderer.release()
        }
    }

    @Test
    fun `completed cue cannot refresh attribution forever`() = runTest {
        val player = player(EarconPlayer.EarconCompletion.Consumed)
        val renderer = AudioFeedbackRenderer(player, FakeTtsEngine()).apply {
            scopeDispatcherForTest = StandardTestDispatcher(testScheduler)
        }
        try {
            renderer.playEarcon(EarconId.WAKE_WORD)
            val condition = condition(renderer)
            assertFalse(condition.otherAppPlaying(true))
            runCurrent()
            advanceTimeBy(1_501)
            runCurrent()
            ShadowSystemClock.advanceBy(Duration.ofSeconds(6))
            assertTrue(condition.otherAppPlaying(true))
        } finally {
            renderer.release()
        }
    }

    @Test
    fun `session ending during progress read cannot attribute the stopped cue`() = runTest {
        val player = player(EarconPlayer.EarconCompletion.Queued)
        val renderer = AudioFeedbackRenderer(player, FakeTtsEngine()).apply {
            scopeDispatcherForTest = StandardTestDispatcher(testScheduler)
        }
        try {
            renderer.playEarcon(EarconId.WAKE_WORD)
            whenever(player.completionFor(any())).thenAnswer {
                renderer.endSession()
                EarconPlayer.EarconCompletion.Playing
            }
            assertTrue(condition(renderer).otherAppPlaying(true))
            assertFalse(renderer.hasEmittedAudio.value)
        } finally {
            renderer.release()
        }
    }

    @Test
    fun `successor admitted during progress read cannot inherit predecessor sound`() = runTest {
        val player = player(EarconPlayer.EarconCompletion.Queued)
        val renderer = AudioFeedbackRenderer(player, FakeTtsEngine()).apply {
            scopeDispatcherForTest = StandardTestDispatcher(testScheduler)
        }
        try {
            renderer.playEarcon(EarconId.WAKE_WORD)
            whenever(player.play(EarconId.SUCCESS)).thenReturn(EarconPlayer.AdmissionToken(2L))
            whenever(player.completionFor(1L)).thenAnswer {
                renderer.playEarcon(EarconId.SUCCESS)
                EarconPlayer.EarconCompletion.Playing
            }
            val condition = condition(renderer)
            assertTrue(condition.otherAppPlaying(true))
            assertTrue(condition.otherAppPlaying(true))
            assertFalse(renderer.hasEmittedAudio.value)
        } finally {
            renderer.release()
        }
    }

    @Test
    fun `observation expiry retires decision-time progress reads`() = runTest {
        val player = player(EarconPlayer.EarconCompletion.Queued)
        val renderer = AudioFeedbackRenderer(player, FakeTtsEngine()).apply {
            scopeDispatcherForTest = StandardTestDispatcher(testScheduler)
        }
        try {
            renderer.playEarcon(EarconId.WAKE_WORD)
            runCurrent()
            advanceTimeBy(2_001)
            runCurrent()
            assertEquals(AudioFeedbackRenderer.CueObservation.Unknown, renderer.lastCueObservation.value?.observation)
            whenever(player.completionFor(any())).thenReturn(EarconPlayer.EarconCompletion.Playing)
            renderer.refreshEarconAttribution()
            assertFalse(renderer.hasEmittedAudio.value)
        } finally {
            renderer.release()
        }
    }

    @Test
    fun `short own cue cannot publish a stale block after it is consumed`() = pollTimeline(shortCue = true, queued = false)

    @Test
    fun `long own cue is attributed before the first observer tick`() = pollTimeline(shortCue = false, queued = false)

    @Test
    fun `queued cue with foreign output remains blocked`() = pollTimeline(shortCue = false, queued = true)

    // Exercise the actual poll/debounce and production provider. A real single-thread dispatcher is
    // intentional: pollLoop switches to Dispatchers.IO, so virtual time alone cannot order its return.
    private fun pollTimeline(shortCue: Boolean, queued: Boolean) = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            withContext(dispatcher) {
                val observed = AtomicReference(
                    if (shortCue || queued) EarconPlayer.EarconCompletion.Queued else EarconPlayer.EarconCompletion.Playing,
                )
                val music = AtomicBoolean(true)
                val firstProgressRead = CompletableDeferred<Unit>()
                val firstMusicRead = CompletableDeferred<Unit>()
                val player = player(EarconPlayer.EarconCompletion.Queued)
                whenever(player.emittedDurationMs()).thenReturn(if (shortCue) 150L else 1_500L)
                whenever(player.completionFor(any())).thenAnswer {
                    val result = observed.get()
                    firstProgressRead.complete(Unit)
                    result
                }
                val renderer = AudioFeedbackRenderer(player, FakeTtsEngine())
                val scope = CoroutineScope(SupervisorJob() + dispatcher)
                try {
                    val audioManager = mock<AudioManager>()
                    whenever(audioManager.isMusicActive).thenAnswer {
                        val result = music.get()
                        firstMusicRead.complete(Unit)
                        result
                    }
                    val context = mock<Context>()
                    whenever(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
                    val playback = mock<PlaybackContextMonitor>()
                    whenever(playback.isHostAudioActive).thenReturn(MutableStateFlow(false))
                    renderer.playEarcon(EarconId.WAKE_WORD)
                    val condition = VoiceControlModule.provideOtherAppPlayingCondition(context, playback, renderer, scope)
                    val states = mutableListOf<VoiceControlRuleState>()
                    scope.launch { condition.state.collect { states += it } }
                    withTimeout(2_000) { firstMusicRead.await() }
                    if (shortCue) {
                        // The initial active-music read sees a cue whose head is still queued. Only
                        // after that sampled verdict do we deliver its measured completion and silence.
                        withTimeout(2_000) { firstProgressRead.await() }
                        observed.set(EarconPlayer.EarconCompletion.Consumed)
                        music.set(false)
                    }
                    delay(750)
                    if (queued) {
                        assertEquals(VoiceControlRuleState.Blocked("other_app_playing"), condition.state.value)
                        assertFalse(renderer.hasEmittedAudio.value)
                    } else {
                        assertEquals(VoiceControlRuleState.Allowed, condition.state.value)
                        assertFalse(states.any { it is VoiceControlRuleState.Blocked })
                    }
                } finally {
                    scope.cancel()
                    renderer.release()
                }
            }
        } finally {
            Dispatchers.resetMain()
            dispatcher.close()
        }
    }

    private fun player(completion: EarconPlayer.EarconCompletion): EarconPlayer = mock<EarconPlayer>().also {
        whenever(it.play(EarconId.WAKE_WORD)).thenReturn(EarconPlayer.AdmissionToken(1L))
        whenever(it.emittedDurationMs()).thenReturn(1_500L)
        whenever(it.completionFor(any())).thenReturn(completion)
    }

    private fun condition(renderer: AudioFeedbackRenderer) = OtherAppPlayingCondition(
        hostIsPlaying = MutableStateFlow(false),
        hasEmittedAudio = renderer.hasEmittedAudio,
        lastEmittedAtMs = renderer.lastEmittedAtMs,
        lastCueObservation = renderer.lastCueObservation,
        refreshSelfEmission = renderer::refreshEarconAttribution,
    )
}
