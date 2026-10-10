@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackState
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRuleState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class PlaybackContextActiveConditionTest {
    @Test
    fun `paused playback with current episode has active playback context`() = runTest {
        val playbackState = MutableStateFlow(
            PlaybackState(
                state = PlaybackState.State.PAUSED,
                episodeUuid = "episode-id",
            ),
        )
        val playbackManager: PlaybackManager = mock {
            on { playbackStateFlow } doReturn playbackState as Flow<PlaybackState>
        }

        val monitor = PlaybackContextMonitor(
            playbackManager,
            au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal(),
            backgroundScope,
        )
        runCurrent()

        assertEquals(PlaybackContext.Active(currentEpisodeUuid = "episode-id", isPlaying = false), monitor.context.value)
    }

    @Test
    fun `current episode allows listening even when paused`() {
        val condition = PlaybackContextActiveCondition(MutableStateFlow(PlaybackContext.Active(currentEpisodeUuid = "episode-id", isPlaying = false)))

        assertEquals(VoiceControlRuleState.Allowed, condition.evaluate())
    }

    @Test
    fun `missing episode blocks listening`() {
        val condition = PlaybackContextActiveCondition(MutableStateFlow(PlaybackContext.Inactive))

        assertEquals(VoiceControlRuleState.Blocked("playback_context_inactive"), condition.evaluate())
    }

    @Test
    fun `the monitor forwards the real signal's privacy state and closure count`() = runTest {
        // The sink reads privacy through the monitor, so this forwarding is part of the production
        // connection the guard depends on. Driven through a REAL signal so the value has a producer.
        val signal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal()
        val monitor = PlaybackContextMonitor(mock(), signal, backgroundScope)

        assertFalse("an open window reports no privacy closure", monitor.isPrivacyClosed())
        val before = monitor.privacyClosureCount()

        signal.onAppBackgrounded()

        assertTrue("a privacy close must be visible through the monitor", monitor.isPrivacyClosed())
        assertEquals(
            "the monitor must reflect the signal's count, not a copy of its own",
            before + 1,
            monitor.privacyClosureCount(),
        )
    }
}
