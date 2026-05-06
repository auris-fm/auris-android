package au.com.shiftyjelly.pocketcasts.voice.playback

import au.com.shiftyjelly.pocketcasts.voice.gate.VoiceControlRuleState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackContextRuleTest {
    @Test
    fun `current episode allows listening even when paused`() {
        val rule = PlaybackContextRule(MutableStateFlow(PlaybackContext.Active(currentEpisodeUuid = "episode-id")))

        assertEquals(VoiceControlRuleState.Allowed, rule.evaluate())
    }

    @Test
    fun `missing episode blocks listening`() {
        val rule = PlaybackContextRule(MutableStateFlow(PlaybackContext.Inactive))

        assertEquals(VoiceControlRuleState.Blocked("playback_context_inactive"), rule.evaluate())
    }
}
