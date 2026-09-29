@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package au.com.shiftyjelly.pocketcasts.voicecontrol.mode

import app.cash.turbine.test
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGateState
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRule
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRuleGroup
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRuleState
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.AppInForegroundCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.MicExposure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningModePolicyTest {

    // --- Static resolve tests ---

    @Test
    fun `grace period active resolves to Continuous regardless of exposure`() {
        // Grace period overrides everything — the ONLY path to Continuous
        val result = resolve(
            gateState = allowedGateState,
            micExposure = MicExposure.Exposed,
            isGracePeriodActive = true,
        )
        assertEquals(ListeningMode.Continuous, result)
    }

    @Test
    fun `grace period inactive resolves to WakeWord even with Isolated route`() {
        // Isolated route still requires wake word without grace
        val result = resolve(
            gateState = allowedGateState,
            micExposure = MicExposure.Isolated,
            isGracePeriodActive = false,
        )
        assertEquals(ListeningMode.WakeWord, result)
    }

    @Test
    fun `gate blocked resolves to Off`() = runTest {
        val rule = FakeRule("test", VoiceControlRuleGroup.Setup, VoiceControlRuleState.Blocked("blocked"))
        val gate = VoiceControlGate(listOf(rule), backgroundScope)

        gate.state.test {
            val result = resolve(
                gateState = awaitItem(),
                micExposure = MicExposure.Exposed,
                isGracePeriodActive = true,
            )
            assertEquals(ListeningMode.Off, result)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `NoMic resolves to Off even with grace active`() {
        val result = resolve(
            gateState = allowedGateState,
            micExposure = MicExposure.NoMic,
            isGracePeriodActive = true,
        )
        assertEquals(ListeningMode.Off, result)
    }

    @Test
    fun `gate blocked overrides grace period`() {
        // Gate safety is absolute — even grace period cannot override it
        val result = resolve(
            gateState = VoiceControlGateState(allowed = false, rules = emptyMap()),
            micExposure = MicExposure.Exposed,
            isGracePeriodActive = true,
        )
        assertEquals(ListeningMode.Off, result)
    }

    // --- Transition tests ---

    @Test
    fun `grace opens transitions from WakeWord to Continuous`() {
        // Start with grace inactive → WakeWord
        assertEquals(
            ListeningMode.WakeWord,
            resolve(
                gateState = allowedGateState,
                micExposure = MicExposure.Exposed,
                isGracePeriodActive = false,
            ),
        )
        // Grace becomes active → Continuous
        assertEquals(
            ListeningMode.Continuous,
            resolve(
                gateState = allowedGateState,
                micExposure = MicExposure.Exposed,
                isGracePeriodActive = true,
            ),
        )
    }

    @Test
    fun `grace expires transitions from Continuous to WakeWord`() {
        // Start with grace active → Continuous
        assertEquals(
            ListeningMode.Continuous,
            resolve(
                gateState = allowedGateState,
                micExposure = MicExposure.Exposed,
                isGracePeriodActive = true,
            ),
        )
        // Grace expires → WakeWord (capture continues)
        assertEquals(
            ListeningMode.WakeWord,
            resolve(
                gateState = allowedGateState,
                micExposure = MicExposure.Exposed,
                isGracePeriodActive = false,
            ),
        )
    }

    // --- Helpers ---

    private val allowedGateState = VoiceControlGateState(
        allowed = true,
        rules = emptyMap(),
    )

    private class FakeRule(
        override val id: String,
        override val group: VoiceControlRuleGroup,
        initialState: VoiceControlRuleState,
    ) : VoiceControlRule {
        override val state = MutableStateFlow(initialState)
    }

    @Test
    fun `no cue while the user can see the app`() {
        val rules = mapOf(AppInForegroundCondition.ID to VoiceControlRuleState.Allowed)

        assertFalse(
            "the microphone opening is visible in the app, so the cue only adds noise",
            shouldPlayListeningStartCue(ListeningMode.WakeWord, rules),
        )
    }

    @Test
    fun `a cue when the microphone opens in the background`() {
        val rules = mapOf(
            AppInForegroundCondition.ID to VoiceControlRuleState.Blocked("app_in_foreground"),
        )

        assertTrue(
            "this is the case the spec names: the microphone turns on where the user cannot see it",
            shouldPlayListeningStartCue(ListeningMode.WakeWord, rules),
        )
    }

    @Test
    fun `no cue for a mode that does not require a wake word`() {
        val rules = mapOf(
            AppInForegroundCondition.ID to VoiceControlRuleState.Blocked("app_in_foreground"),
        )

        assertFalse(
            "the cue marks listening that awaits a wake word; continuous listening is not that",
            shouldPlayListeningStartCue(ListeningMode.Continuous, rules),
        )
    }

    @Test
    fun `no cue when the gate says nothing about the foreground`() {
        assertFalse(
            "an unknown foreground state is not evidence of a background start",
            shouldPlayListeningStartCue(ListeningMode.WakeWord, emptyMap()),
        )
    }
}
