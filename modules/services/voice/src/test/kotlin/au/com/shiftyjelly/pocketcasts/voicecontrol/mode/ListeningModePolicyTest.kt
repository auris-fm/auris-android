@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package au.com.shiftyjelly.pocketcasts.voicecontrol.mode

import app.cash.turbine.test
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGateState
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRule
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRuleGroup
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRuleState
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.MicExposure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningModePolicyTest {

    @Test
    fun `gate blocked resolves to Off`() = runTest {
        val rule = FakeRule("test", VoiceControlRuleGroup.Setup, VoiceControlRuleState.Blocked("blocked"))
        val gate = VoiceControlGate(listOf(rule), backgroundScope)

        gate.state.test {
            val result = resolve(
                gateState = awaitItem(),
                micExposure = MicExposure.Exposed,
                isForeground = true,
                isPlaybackActive = true,
                wakeWordReady = true,
            )
            assertEquals(ListeningMode.Off, result)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `NoMic resolves to Off`() {
        val result = resolve(
            gateState = VoiceControlGateState(allowed = true, rules = emptyMap()),
            micExposure = MicExposure.NoMic,
            isForeground = false,
            isPlaybackActive = true,
            wakeWordReady = true,
        )
        assertEquals(ListeningMode.Off, result)
    }

    @Test
    fun `foreground resolves to Continuous regardless of exposure`() {
        val result = resolve(
            gateState = VoiceControlGateState(allowed = true, rules = emptyMap()),
            micExposure = MicExposure.Exposed,
            isForeground = true,
            isPlaybackActive = false,
            wakeWordReady = true,
        )
        assertEquals(ListeningMode.Continuous, result)
    }

    @Test
    fun `background with Isolated and active context resolves to Continuous`() {
        val result = resolve(
            gateState = VoiceControlGateState(allowed = true, rules = emptyMap()),
            micExposure = MicExposure.Isolated,
            isForeground = false,
            isPlaybackActive = true,
            wakeWordReady = true,
        )
        assertEquals(ListeningMode.Continuous, result)
    }

    @Test
    fun `background with Exposed and active context resolves to WakeWord`() {
        val result = resolve(
            gateState = VoiceControlGateState(allowed = true, rules = emptyMap()),
            micExposure = MicExposure.Exposed,
            isForeground = false,
            isPlaybackActive = true,
            wakeWordReady = true,
        )
        assertEquals(ListeningMode.WakeWord, result)
    }

    @Test
    fun `background with no active context resolves to Off`() {
        val result = resolve(
            gateState = VoiceControlGateState(allowed = true, rules = emptyMap()),
            micExposure = MicExposure.Exposed,
            isForeground = false,
            isPlaybackActive = false,
            wakeWordReady = true,
        )
        assertEquals(ListeningMode.Off, result)
    }

    @Test
    fun `paused but active context still yields a listening mode`() {
        val result = resolve(
            gateState = VoiceControlGateState(allowed = true, rules = emptyMap()),
            micExposure = MicExposure.Exposed,
            isForeground = false,
            isPlaybackActive = true,
            wakeWordReady = true,
        )
        assertEquals(ListeningMode.WakeWord, result)
    }

    @Test
    fun `foreground with Isolated resolves to Continuous`() {
        val result = resolve(
            gateState = VoiceControlGateState(allowed = true, rules = emptyMap()),
            micExposure = MicExposure.Isolated,
            isForeground = true,
            isPlaybackActive = false,
            wakeWordReady = true,
        )
        assertEquals(ListeningMode.Continuous, result)
    }

    @Test
    fun `WakeWord mode with detector not ready resolves to Off`() {
        val result = resolve(
            gateState = VoiceControlGateState(allowed = true, rules = emptyMap()),
            micExposure = MicExposure.Exposed,
            isForeground = false,
            isPlaybackActive = true,
            wakeWordReady = false,
        )
        assertEquals(ListeningMode.Off, result)
    }

    private class FakeRule(
        override val id: String,
        override val group: VoiceControlRuleGroup,
        initialState: VoiceControlRuleState,
    ) : VoiceControlRule {
        override val state = MutableStateFlow(initialState)
    }
}
