package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The actual microphone-capture state, distinct from the listening mode the gate resolves.
 *
 * The mode reads `WakeWord`/`Continuous` whenever the route is eligible, including when stream
 * confirmation or VAD attachment later fails, so settings must not equate "eligible" with
 * "listening". Voice control runs in a foreground service the settings screen cannot query, so the
 * service publishes here and settings observes.
 */
enum class VoiceControlCaptureState { Idle, Starting, Active, StartFailed }

@Singleton
class VoiceControlCaptureStateHolder @Inject constructor() {
    private val mutableState = MutableStateFlow(VoiceControlCaptureState.Idle)

    val state: StateFlow<VoiceControlCaptureState> = mutableState.asStateFlow()

    fun update(state: VoiceControlCaptureState) {
        mutableState.value = state
    }
}
