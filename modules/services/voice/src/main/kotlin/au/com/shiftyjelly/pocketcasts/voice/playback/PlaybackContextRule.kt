package au.com.shiftyjelly.pocketcasts.voice.playback

import au.com.shiftyjelly.pocketcasts.voice.gate.VoiceControlRule
import au.com.shiftyjelly.pocketcasts.voice.gate.VoiceControlRuleState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class PlaybackContextRule(
    private val playbackContext: StateFlow<PlaybackContext>,
    scope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
) : VoiceControlRule {
    override val id = "playback_context"
    override val state: StateFlow<VoiceControlRuleState> = playbackContext
        .map { evaluate() }
        .stateIn(
            scope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            evaluate(),
        )

    fun evaluate(): VoiceControlRuleState {
        return when (playbackContext.value) {
            is PlaybackContext.Active -> VoiceControlRuleState.Allowed
            PlaybackContext.Inactive -> VoiceControlRuleState.Blocked("playback_context_inactive")
        }
    }
}
