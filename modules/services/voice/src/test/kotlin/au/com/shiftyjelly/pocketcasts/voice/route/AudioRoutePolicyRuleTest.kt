package au.com.shiftyjelly.pocketcasts.voice.route

import au.com.shiftyjelly.pocketcasts.preferences.model.VoiceControlAudioRoutePolicy
import au.com.shiftyjelly.pocketcasts.voice.gate.VoiceControlRuleState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRoutePolicyRuleTest {
    @Test
    fun `headset policy allows headset with microphone`() {
        val rule = AudioRoutePolicyRule(
            route = MutableStateFlow(AudioRoute.Headset(hasMicrophone = true)),
            policy = MutableStateFlow(VoiceControlAudioRoutePolicy.HeadsetOnly),
        )

        assertEquals(VoiceControlRuleState.Allowed, rule.evaluate())
    }

    @Test
    fun `headset policy blocks speaker`() {
        val rule = AudioRoutePolicyRule(
            route = MutableStateFlow(AudioRoute.Speaker),
            policy = MutableStateFlow(VoiceControlAudioRoutePolicy.HeadsetOnly),
        )

        assertEquals(VoiceControlRuleState.Blocked("audio_route_disallowed"), rule.evaluate())
    }
}
