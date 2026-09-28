package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconId
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRuleState
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.AppInForegroundCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningMode

/**
 * When the microphone opening earns an audible cue.
 *
 * The spec scopes [EarconId.LISTENING_START] to the microphone turning on **in the background**
 * while a wake word is required — "optional and independent of the mandatory `WAKE_WORD`
 * confirmation", and "very subtle ... should not distract" (`voice-intents.md`). Playing it on
 * every occasion the gate lets the microphone back in — another app stops playing, a route
 * returns, the app is foregrounded — turned it into a recurring beep during ordinary use, which is
 * what was reported.
 *
 * So: only when the user cannot see that the microphone opened. The listening notification stays
 * posted in both cases, so the continuous signal that listening is on does not depend on this cue.
 */
internal object ListeningStartCue {

    fun shouldPlay(
        mode: ListeningMode,
        rules: Map<String, VoiceControlRuleState>,
    ): Boolean = mode == ListeningMode.WakeWord && rules.saysAppIsNotInForeground()

    /**
     * Requires an *explicit* background signal rather than inferring one from the rule's absence:
     * an unregistered or unknown foreground rule is not evidence that the microphone opened where
     * the user cannot see it, and guessing "background" would produce the audible noise this exists
     * to avoid. Silence is not evidence — the same rule this codebase applies to a check.
     */
    private fun Map<String, VoiceControlRuleState>.saysAppIsNotInForeground(): Boolean = this[AppInForegroundCondition.ID] is VoiceControlRuleState.Blocked
}
