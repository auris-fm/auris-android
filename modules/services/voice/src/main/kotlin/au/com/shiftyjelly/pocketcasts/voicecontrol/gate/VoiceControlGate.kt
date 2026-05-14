package au.com.shiftyjelly.pocketcasts.voicecontrol.gate

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class VoiceControlGate(
    rules: List<VoiceControlRule>,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) {
    val state: StateFlow<VoiceControlGateState> = if (rules.isEmpty()) {
        kotlinx.coroutines.flow.MutableStateFlow(VoiceControlGateState.Blocked(emptyMap()))
    } else {
        combine(rules.map { it.state }) { ruleStates ->
            computeState(rules.zip(ruleStates))
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = computeState(rules.map { it to it.state.value }),
        )
    }

    private fun computeState(ruleStates: List<Pair<VoiceControlRule, VoiceControlRuleState>>): VoiceControlGateState {
        val blockedRules = ruleStates
            .filter { (_, state) -> state != VoiceControlRuleState.Allowed }
            .associate { (rule, state) -> rule.id to state }

        return if (blockedRules.isEmpty()) {
            VoiceControlGateState.Allowed
        } else {
            VoiceControlGateState.Blocked(blockedRules)
        }
    }
}

sealed interface VoiceControlGateState {
    data object Allowed : VoiceControlGateState
    data class Blocked(val rules: Map<String, VoiceControlRuleState>) : VoiceControlGateState
}
