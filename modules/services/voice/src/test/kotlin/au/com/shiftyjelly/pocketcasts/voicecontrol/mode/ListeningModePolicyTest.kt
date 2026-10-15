package au.com.shiftyjelly.pocketcasts.voicecontrol.mode

import app.cash.turbine.test
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGateState
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRuleState
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.AppInForegroundCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRouteMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.MicExposure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningModePolicyTest {

    // --- Static resolve tests ---

    // Grace period active on an isolated route → Continuous (the only wake-word waiver)
    @Test
    fun `grace period active → Continuous`() {
        assertEquals(
            ListeningMode.Continuous,
            resolve(allowedGateState, MicExposure.Isolated, isGracePeriodActive = true),
        )
    }

    // Grace period inactive → WakeWord
    @Test
    fun `grace period inactive → WakeWord`() {
        assertEquals(
            ListeningMode.WakeWord,
            resolve(allowedGateState, MicExposure.Isolated, isGracePeriodActive = false),
        )
    }

    // Gate blocked → Off
    @Test
    fun `gate blocked → Off`() {
        assertEquals(
            ListeningMode.Off,
            resolve(blockedGateState, MicExposure.Isolated, isGracePeriodActive = true),
        )
    }

    // Capture requires an external microphone: NoMic → Off even with grace active
    @Test
    fun `no mic → Off`() {
        assertEquals(
            ListeningMode.Off,
            resolve(allowedGateState, MicExposure.NoMic, isGracePeriodActive = true),
        )
    }

    // Exposed (built-in microphone) → Off regardless of grace
    @Test
    fun `built-in microphone → Off`() {
        assertEquals(
            ListeningMode.Off,
            resolve(allowedGateState, MicExposure.Exposed, isGracePeriodActive = true),
        )
        assertEquals(
            ListeningMode.Off,
            resolve(allowedGateState, MicExposure.Exposed, isGracePeriodActive = false),
        )
    }

    // Grace is the only waiver: the same isolated route is WakeWord without it and Continuous with it
    @Test
    fun `grace period is the only waiver`() {
        assertEquals(
            ListeningMode.WakeWord,
            resolve(allowedGateState, MicExposure.Isolated, isGracePeriodActive = false),
        )
        assertEquals(
            ListeningMode.Continuous,
            resolve(allowedGateState, MicExposure.Isolated, isGracePeriodActive = true),
        )
    }

    // --- Helpers ---

    private val allowedGateState = VoiceControlGateState(
        allowed = true,
        rules = emptyMap(),
    )

    private val blockedGateState = VoiceControlGateState(
        allowed = false,
        rules = emptyMap(),
    )

    // --- Listening start cue tests ---

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

    // --- Live mode flow ---

    // THE START-ELIGIBILITY QUESTION IS AVAILABILITY'S. After the observation-first correction, the
    // OBSERVED route is Unknown until a stream is routed or SCO opens -- but SCO only opens once
    // acquisition runs, and acquisition waits for the mode to leave Off. If the policy read the
    // observed route, connected AirPods would hold the pipeline in Off forever: no observation can
    // ever arrive. The policy must read `availability` -- the enumeration surface that answers "is an
    // external microphone candidate present" -- while the observed route stays the observation.
    @Test
    fun `an available headset candidate leaves Off even though the observed route is Unknown`() = runTest {
        val routeFlow = MutableStateFlow<AudioRoute>(AudioRoute.Unknown)
        val availabilityFlow = MutableStateFlow<AudioRoute>(AudioRoute.BluetoothA2dpOnly)
        val policy = ListeningModePolicy(
            gate = VoiceControlGate(rules = emptyList(), scope = backgroundScope),
            audioRouteMonitor = object : AudioRouteMonitor {
                override val route: StateFlow<AudioRoute> = routeFlow
                override val availability: StateFlow<AudioRoute> = availabilityFlow
            },
            gracePeriodSignal = GracePeriodSignal(),
            scope = backgroundScope,
        )

        policy.mode.test {
            assertEquals(
                "an unacquired A2DP candidate still leaves the built-in microphone exposed -- Off, " +
                    "while the service's start branch acquires it from availability",
                ListeningMode.Off,
                awaitItem(),
            )

            // Acquisition succeeds: the platform SCO link is on and availability answers Headset.
            availabilityFlow.value = AudioRoute.Headset(hasMicrophone = true)

            assertEquals(ListeningMode.WakeWord, awaitItem())

            availabilityFlow.value = AudioRoute.Speaker

            assertEquals(
                "no external microphone candidate, so nothing to listen on",
                ListeningMode.Off,
                awaitItem(),
            )
        }
    }
    // --- Live mode flow ---

    // The core spec's integration case: unplugging the headset moves the route to the built-in
    // microphone, which is `Exposed`, so capture stops instead of continuing on it.
    @Test
    fun `a headset that unplugs stops capture`() = runTest {
        val routeFlow = MutableStateFlow<AudioRoute>(AudioRoute.Headset(hasMicrophone = true))
        val policy = ListeningModePolicy(
            gate = VoiceControlGate(rules = emptyList(), scope = backgroundScope),
            audioRouteMonitor = object : AudioRouteMonitor {
                override val route: StateFlow<AudioRoute> = routeFlow
                override val availability: StateFlow<AudioRoute> = routeFlow
            },
            gracePeriodSignal = GracePeriodSignal(),
            scope = backgroundScope,
        )

        policy.mode.test {
            assertEquals(ListeningMode.WakeWord, awaitItem())

            routeFlow.value = AudioRoute.Speaker

            assertEquals(
                "the route falls back to the built-in microphone, which does not listen",
                ListeningMode.Off,
                awaitItem(),
            )
        }
    }
}
