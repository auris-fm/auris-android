package au.com.shiftyjelly.pocketcasts.voicecontrol.mode

import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconId
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGateState
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRuleState
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.AppInForegroundCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRouteMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.MicExposure
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.toMicExposure
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * When the microphone opening earns an audible cue.
 *
 * The spec scopes [EarconId.LISTENING_START] to the microphone turning on **in the background**
 * while a wake word is required — "optional and independent of the mandatory `WAKE_WORD`
 * confirmation", and "very subtle ... should not distract" (`voice-intents.md`). Playing it on every
 * occasion the gate lets the microphone back in — another app stops playing, a route returns, the app
 * is foregrounded — turned it into a recurring beep during ordinary use, which is what was reported.
 *
 * So: only when the user cannot see that the microphone opened. It lives here rather than at the call
 * site because this file is where the listening rules are covered by unit tests, and a decision taken
 * only inside the service is one no test can pin. The listening notification stays posted in both
 * cases, so the continuous signal that listening is on does not depend on this cue.
 *
 * Requires an *explicit* background signal rather than inferring one from the rule's absence: an
 * unregistered or unknown foreground rule is not evidence that the microphone opened where the user
 * cannot see it, and guessing "background" would produce the audible noise this exists to avoid.
 * Silence is not evidence — the same rule this codebase applies to a check.
 */
internal fun shouldPlayListeningStartCue(
    mode: ListeningMode,
    rules: Map<String, VoiceControlRuleState>,
): Boolean = mode == ListeningMode.WakeWord && rules[AppInForegroundCondition.ID] is VoiceControlRuleState.Blocked

@Singleton
class ListeningModePolicy @Inject constructor(
    private val gate: VoiceControlGate,
    private val audioRouteMonitor: AudioRouteMonitor,
    private val gracePeriodSignal: GracePeriodSignal,
    @ApplicationScope private val scope: CoroutineScope,
) {
    val mode: StateFlow<ListeningMode> = combine(
        gate.state,
        audioRouteMonitor.route,
        gracePeriodSignal.isActive,
    ) { gateState, route, isGracePeriodActive ->
        resolve(
            gateState = gateState,
            micExposure = route.toMicExposure(),
            isGracePeriodActive = isGracePeriodActive,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = resolve(
            gateState = gate.state.value,
            micExposure = audioRouteMonitor.route.value.toMicExposure(),
            isGracePeriodActive = gracePeriodSignal.isActive.value,
        ),
    )
}

internal fun resolve(
    gateState: VoiceControlGateState,
    micExposure: MicExposure,
    isGracePeriodActive: Boolean,
): ListeningMode {
    if (!gateState.allowed) {
        return ListeningMode.Off
    }
    if (micExposure == MicExposure.NoMic) {
        return ListeningMode.Off
    }

    // Grace period is the ONLY wake-word waiver
    if (isGracePeriodActive) {
        return ListeningMode.Continuous
    }

    return ListeningMode.WakeWord
}
