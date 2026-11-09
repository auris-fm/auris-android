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
 * wake-word confirmation, and "very subtle ... should not distract" (`voice-intents.md`). It plays
 * when listening STARTS in a session that requires the wake word.
 *
 * The question is why this start happened, not whether the app is visible. Two earlier versions got
 * it wrong in opposite directions: gating on "the app is in the background" made every automatic
 * recovery audible -- an automatic restart is always a background start -- so the cue fought with
 * whatever other app had just released the microphone, and dropping that condition cued every
 * recovery as well, since the foreground never told a first start from a restart.
 *
 * So the caller passes the answer:
 * - [ListeningMode.Continuous] is exempt: the cue marks listening that awaits a wake word.
 * - [isAutomaticRecovery] silences the cue. Capture ended on its own and we are taking the
 *   microphone back; whatever released it is probably still active, and that is the moment the user
 *   experiences as us fighting for audio. The listening notification still updates, so the state is
 *   not silent -- only the tone is.
 *
 * Coverage: the predicate is tested here. Its call site is not -- `VoiceControlService`'s start path
 * is driven by `VoiceControlCaptureRestartTest`, which asserts the cue count across a real
 * start/terminate/recover cycle, so a regression that re-cues inside the service is caught there
 * rather than only here.
 */
internal fun shouldPlayListeningStartCue(
    mode: ListeningMode,
    isAutomaticRecovery: Boolean,
): Boolean = mode == ListeningMode.WakeWord && !isAutomaticRecovery

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
