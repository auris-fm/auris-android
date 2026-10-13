package au.com.shiftyjelly.pocketcasts.settings.voice

import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.EnabledByUserCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGateState
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRuleState
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.BatteryOkCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.DeviceSupportedCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.ModelsReadyCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.NotCastingCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.NotOnCallCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.OtherAppPlayingCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningMode
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.MicExposure
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every row of the settings status table in `voice-control-core.md` > "Privacy and UX",
 * plus the precedence the table's "first matching row wins" ordering implies.
 */
class VoiceControlStatusTest {

    @Test
    fun `microphone permission missing wins over every other state`() {
        val gate = gateState(
            rules = mapOf(
                EnabledByUserCondition.ID to blocked(),
                ModelsReadyCondition.ID to blocked(),
                NotOnCallCondition.ID to blocked(),
            ),
            setupPassed = false,
            conflictsPassed = false,
            contextPassed = false,
        )

        assertEquals(
            "Blocked: microphone permission required",
            voiceControlStatus(ListeningMode.Continuous, gate, MicExposure.NoMic, permissionGranted = false),
        )
    }

    @Test
    fun `blocked enabled by user reads disabled in settings`() {
        val gate = gateState(
            rules = mapOf(EnabledByUserCondition.ID to blocked()),
            setupPassed = false,
        )

        assertEquals(
            "Disabled in settings",
            voiceControlStatus(ListeningMode.Continuous, gate, MicExposure.Isolated, permissionGranted = true),
        )
    }

    @Test
    fun `blocked models ready reads model download in progress`() {
        val gate = gateState(
            rules = mapOf(ModelsReadyCondition.ID to blocked()),
            setupPassed = false,
        )

        assertEquals(
            "Blocked: model download in progress",
            voiceControlStatus(ListeningMode.WakeWord, gate, MicExposure.Isolated, permissionGranted = true),
        )
    }

    @Test
    fun `still-loading models ready reads model download in progress`() {
        val gate = gateState(
            rules = mapOf(ModelsReadyCondition.ID to unknown()),
            setupPassed = false,
        )

        assertEquals(
            "Blocked: model download in progress",
            voiceControlStatus(ListeningMode.WakeWord, gate, MicExposure.Isolated, permissionGranted = true),
        )
    }

    @Test
    fun `another blocked setup condition reads blocked setup`() {
        val gate = gateState(
            rules = mapOf(DeviceSupportedCondition.ID to blocked()),
            setupPassed = false,
        )

        assertEquals(
            "Blocked: setup",
            voiceControlStatus(ListeningMode.WakeWord, gate, MicExposure.Isolated, permissionGranted = true),
        )
    }

    @Test
    fun `blocked not on call reads phone call in progress`() {
        val gate = gateState(
            rules = mapOf(NotOnCallCondition.ID to blocked()),
            conflictsPassed = false,
        )

        assertEquals(
            "Blocked: phone call in progress",
            voiceControlStatus(ListeningMode.WakeWord, gate, MicExposure.Isolated, permissionGranted = true),
        )
    }

    @Test
    fun `blocked not casting reads casting active`() {
        val gate = gateState(
            rules = mapOf(NotCastingCondition.ID to blocked()),
            conflictsPassed = false,
        )

        assertEquals(
            "Blocked: casting active",
            voiceControlStatus(ListeningMode.WakeWord, gate, MicExposure.Isolated, permissionGranted = true),
        )
    }

    @Test
    fun `blocked battery ok reads battery saver`() {
        val gate = gateState(
            rules = mapOf(BatteryOkCondition.ID to blocked()),
            conflictsPassed = false,
        )

        assertEquals(
            "Blocked: battery saver",
            voiceControlStatus(ListeningMode.WakeWord, gate, MicExposure.Isolated, permissionGranted = true),
        )
    }

    @Test
    fun `blocked other app playing reads another app is playing audio`() {
        val gate = gateState(
            rules = mapOf(OtherAppPlayingCondition.ID to blocked()),
            conflictsPassed = false,
        )

        assertEquals(
            "Blocked: another app is playing audio",
            voiceControlStatus(ListeningMode.WakeWord, gate, MicExposure.Isolated, permissionGranted = true),
        )
    }

    @Test
    fun `no listening context reads inactive`() {
        val gate = gateState(contextPassed = false)

        assertEquals(
            "Inactive: no playback or foreground context",
            voiceControlStatus(ListeningMode.WakeWord, gate, MicExposure.Isolated, permissionGranted = true),
        )
    }

    @Test
    fun `no microphone on route reads no microphone`() {
        val gate = gateState()

        assertEquals(
            "No microphone on this route",
            voiceControlStatus(ListeningMode.Off, gate, MicExposure.NoMic, permissionGranted = true),
        )
    }

    @Test
    fun `built-in microphone reads headset required`() {
        val gate = gateState()

        assertEquals(
            "Built-in microphone; a headset or earbud microphone is required",
            voiceControlStatus(ListeningMode.Off, gate, MicExposure.Exposed, permissionGranted = true),
        )
    }

    @Test
    fun `continuous mode reads active grace period`() {
        val gate = gateState()

        assertEquals(
            "Active (grace period)",
            voiceControlStatus(ListeningMode.Continuous, gate, MicExposure.Isolated, permissionGranted = true),
        )
    }

    @Test
    fun `wake word mode reads active wake word required`() {
        val gate = gateState()

        assertEquals(
            "Active (wake word required)",
            voiceControlStatus(ListeningMode.WakeWord, gate, MicExposure.Isolated, permissionGranted = true),
        )
    }

    @Test
    fun `otherwise reads off`() {
        val gate = gateState()

        assertEquals(
            "Off",
            voiceControlStatus(ListeningMode.Off, gate, MicExposure.Isolated, permissionGranted = true),
        )
    }

    @Test
    fun `blocked setup beats a blocked conflict`() {
        val gate = gateState(
            rules = mapOf(
                EnabledByUserCondition.ID to blocked(),
                NotOnCallCondition.ID to blocked(),
            ),
            setupPassed = false,
            conflictsPassed = false,
        )

        assertEquals(
            "Disabled in settings",
            voiceControlStatus(ListeningMode.WakeWord, gate, MicExposure.Isolated, permissionGranted = true),
        )
    }

    @Test
    fun `blocked models beats a blocked conflict`() {
        val gate = gateState(
            rules = mapOf(
                ModelsReadyCondition.ID to blocked(),
                NotCastingCondition.ID to blocked(),
            ),
            setupPassed = false,
            conflictsPassed = false,
        )

        assertEquals(
            "Blocked: model download in progress",
            voiceControlStatus(ListeningMode.WakeWord, gate, MicExposure.Isolated, permissionGranted = true),
        )
    }

    @Test
    fun `blocked conflict beats the missing-microphone row`() {
        val gate = gateState(
            rules = mapOf(NotOnCallCondition.ID to blocked()),
            conflictsPassed = false,
        )

        assertEquals(
            "Blocked: phone call in progress",
            voiceControlStatus(ListeningMode.WakeWord, gate, MicExposure.NoMic, permissionGranted = true),
        )
    }

    @Test
    fun `built-in microphone row needs an allowed context`() {
        val gate = gateState(contextPassed = false)

        assertEquals(
            "Inactive: no playback or foreground context",
            voiceControlStatus(ListeningMode.Off, gate, MicExposure.Exposed, permissionGranted = true),
        )
    }

    private fun gateState(
        rules: Map<String, VoiceControlRuleState> = emptyMap(),
        setupPassed: Boolean = true,
        conflictsPassed: Boolean = true,
        contextPassed: Boolean = true,
    ) = VoiceControlGateState(
        allowed = setupPassed && conflictsPassed && contextPassed,
        rules = rules,
        setupPassed = setupPassed,
        conflictsPassed = conflictsPassed,
        contextPassed = contextPassed,
    )

    private fun blocked() = VoiceControlRuleState.Blocked("blocked")

    private fun unknown() = VoiceControlRuleState.Unknown("unknown")
}
