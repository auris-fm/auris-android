package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRuleState
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.AppInForegroundCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cue is scoped by the spec to the microphone opening in the background (`voice-intents.md`),
 * and it was reported as a recurring beep during ordinary use, whenever the gate let the
 * microphone back in — another app stops playing, a route returns, the app is foregrounded.
 */
class ListeningStartCueTest {

    @Test
    fun `no cue while the user can see the app`() {
        val rules = mapOf(AppInForegroundCondition.ID to VoiceControlRuleState.Allowed)

        assertFalse(
            "the microphone opening is visible in the app, so the cue only adds noise",
            ListeningStartCue.shouldPlay(ListeningMode.WakeWord, rules),
        )
    }

    @Test
    fun `a cue when the microphone opens in the background`() {
        val rules = mapOf(
            AppInForegroundCondition.ID to VoiceControlRuleState.Blocked("app_in_foreground"),
        )

        assertTrue(
            "this is the case the spec names: the microphone turns on where the user cannot see it",
            ListeningStartCue.shouldPlay(ListeningMode.WakeWord, rules),
        )
    }

    @Test
    fun `no cue for a mode that does not require a wake word`() {
        val rules = mapOf(
            AppInForegroundCondition.ID to VoiceControlRuleState.Blocked("app_in_foreground"),
        )

        assertFalse(
            "the cue marks listening that awaits a wake word; continuous listening is not that",
            ListeningStartCue.shouldPlay(ListeningMode.Continuous, rules),
        )
    }

    @Test
    fun `no cue when the gate says nothing about the foreground`() {
        assertFalse(
            "an unknown foreground state is not evidence of a background start",
            ListeningStartCue.shouldPlay(ListeningMode.WakeWord, emptyMap()),
        )
    }
}
