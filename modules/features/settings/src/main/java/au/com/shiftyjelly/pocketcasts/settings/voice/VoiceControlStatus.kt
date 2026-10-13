package au.com.shiftyjelly.pocketcasts.settings.voice

import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.EnabledByUserCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGateState
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRuleState
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.BatteryOkCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.ModelsReadyCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.NotCastingCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.NotOnCallCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.OtherAppPlayingCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningMode
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.MicExposure

/**
 * The voice-control status line shown in settings, in the order the gate checks permission,
 * setup, conflicts, context and route. The first matching row wins
 * (`voice-control-core.md` > "Privacy and UX").
 */
internal fun voiceControlStatus(
    mode: ListeningMode,
    gate: VoiceControlGateState,
    micExposure: MicExposure,
    permissionGranted: Boolean,
): String {
    if (!permissionGranted) return "Blocked: microphone permission required"
    if (gate.isNotAllowed(EnabledByUserCondition.ID)) return "Disabled in settings"
    if (gate.isNotAllowed(ModelsReadyCondition.ID)) return "Blocked: model download in progress"
    if (!gate.setupPassed) return "Blocked: setup"
    if (gate.isNotAllowed(NotOnCallCondition.ID)) return "Blocked: phone call in progress"
    if (gate.isNotAllowed(NotCastingCondition.ID)) return "Blocked: casting active"
    if (gate.isNotAllowed(BatteryOkCondition.ID)) return "Blocked: battery saver"
    if (gate.isNotAllowed(OtherAppPlayingCondition.ID)) return "Blocked: another app is playing audio"
    if (!gate.contextPassed) return "Inactive: no playback or foreground context"
    return when (micExposure) {
        MicExposure.NoMic -> "No microphone on this route"

        MicExposure.Exposed -> "Built-in microphone; a headset or earbud microphone is required"

        MicExposure.Isolated -> when (mode) {
            ListeningMode.Continuous -> "Active (grace period)"
            ListeningMode.WakeWord -> "Active (wake word required)"
            ListeningMode.Off -> "Off"
        }
    }
}

/**
 * A rule that is `Unknown` or `Blocked` is not `Allowed`, and the gate treats either as a
 * non-passing condition, so the status must too. A rule absent from the map is not evidence of
 * a blocker (the gate's rule set can be partial), so it is ignored rather than guessed at.
 */
private fun VoiceControlGateState.isNotAllowed(ruleId: String): Boolean = rules[ruleId]?.let { it !is VoiceControlRuleState.Allowed } ?: false
