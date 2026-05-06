package au.com.shiftyjelly.pocketcasts.voice.gate

import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceControlGateTest {
    @Test
    fun `gate is allowed when all required rules are allowed`() = runTest {
        val rule = FakeRule("playback", VoiceControlRuleState.Allowed)
        val gate = VoiceControlGate(listOf(rule))

        gate.state.test {
            assertEquals(VoiceControlGateState.Allowed, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `gate is blocked when required rule is blocked`() = runTest {
        val gate = VoiceControlGate(
            listOf(FakeRule("route", VoiceControlRuleState.Blocked("disallowed_route"))),
        )

        gate.state.test {
            assertEquals(
                VoiceControlGateState.Blocked(mapOf("route" to VoiceControlRuleState.Blocked("disallowed_route"))),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    private class FakeRule(
        override val id: String,
        initialState: VoiceControlRuleState,
    ) : VoiceControlRule {
        override val state = MutableStateFlow(initialState)
    }
}
