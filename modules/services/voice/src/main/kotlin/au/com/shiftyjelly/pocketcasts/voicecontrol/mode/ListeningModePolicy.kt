package au.com.shiftyjelly.pocketcasts.voicecontrol.mode

import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconId
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGateState
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRuleState
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
 * [EarconId.LISTENING_START] means "the microphone is on": optional, independent of the mandatory
 * wake-word confirmation, and "very subtle ... should not distract" (`voice-intents.md`).
 *
 * It plays whenever listening begins and requires the wake word. An earlier version played it only
 * when the app was in the background, to keep it out of ordinary foreground use — but "background"
 * is also every time another app briefly releases the microphone, so navigation and video apps got
 * the tone on each automatic restart and the applications ended up cycling over who holds audio.
 * The condition that read as restraint was in fact the bug: the cue marked us TAKING the microphone
 * back, which is exactly the moment that must be quiet. Ordinary foreground use plays it once, when
 * listening starts, so the noise it was meant to prevent does not return.
 *
 * Continuous listening is exempt: the cue marks listening that awaits a wake word, not the
 * follow-up window that immediate's grace period opens.
 *
 * Coverage: the predicate is tested here. Its use is not — it is called from `VoiceControlService`'s
 * start path, which no test drives — so reverting that call site would still leave the suite green.
 * That gap is accepted rather than closed; closing it needs a service-level test, and this note is
 * the record.
 */
internal fun shouldPlayListeningStartCue(mode: ListeningMode): Boolean = mode == ListeningMode.WakeWord

@Singleton
class ListeningModePolicy @Inject constructor(
    private val gate: VoiceControlGate,
    private val audioRouteMonitor: AudioRouteMonitor,
    private val gracePeriodSignal: GracePeriodSignal,
    @ApplicationScope private val scope: CoroutineScope,
) {
    // ELIGIBILITY READS AVAILABILITY, NOT THE OBSERVATION. The observed route is Unknown until a
    // stream is routed or SCO opens, and SCO only opens once acquisition runs -- which waits for this
    // mode to leave Off. Reading the observation here would deadlock connected AirPods in Off forever.
    // `availability` answers the policy's actual question: is an external microphone candidate
    // present (or already the selected input).
    val mode: StateFlow<ListeningMode> = combine(
        gate.state,
        audioRouteMonitor.availability,
        gracePeriodSignal.isActive,
    ) { gateState, availability, isGracePeriodActive ->
        resolve(
            gateState = gateState,
            micExposure = availability.toMicExposure(),
            isGracePeriodActive = isGracePeriodActive,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = resolve(
            gateState = gate.state.value,
            micExposure = audioRouteMonitor.availability.value.toMicExposure(),
            isGracePeriodActive = gracePeriodSignal.isActive.value,
        ),
    )
}

internal fun resolve(
    gateState: VoiceControlGateState,
    micExposure: MicExposure,
    isGracePeriodActive: Boolean,
): ListeningMode {
    if (!gateState.allowed) return ListeningMode.Off
    // Capture requires an external microphone; Exposed (built-in) and NoMic both block.
    if (micExposure != MicExposure.Isolated) return ListeningMode.Off

    // Grace period is the ONLY wake-word waiver
    return if (isGracePeriodActive) ListeningMode.Continuous else ListeningMode.WakeWord
}
