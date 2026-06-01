package au.com.shiftyjelly.pocketcasts.voicecontrol.mode

import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.voicecontrol.foreground.ForegroundStateMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGateState
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContextMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRouteMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.MicExposure
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.toMicExposure
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordDetector
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@Singleton
class ListeningModePolicy @Inject constructor(
    private val gate: VoiceControlGate,
    private val audioRouteMonitor: AudioRouteMonitor,
    private val foregroundState: ForegroundStateMonitor,
    private val playbackContextMonitor: PlaybackContextMonitor,
    private val wakeWordDetector: WakeWordDetector,
    @ApplicationScope private val scope: CoroutineScope,
) {
    val mode: StateFlow<ListeningMode> = combine(
        gate.state,
        audioRouteMonitor.route,
        foregroundState.isInForeground,
        playbackContextMonitor.context,
    ) { gateState, route, isForeground, contextState ->
        val isPlaybackActive = contextState is au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContext.Active
        resolve(gateState, route.toMicExposure(), isForeground, isPlaybackActive, wakeWordDetector.isReady)
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = resolve(
            gateState = gate.state.value,
            micExposure = audioRouteMonitor.route.value.toMicExposure(),
            isForeground = foregroundState.isInForeground.value,
            isPlaybackActive = playbackContextMonitor.context.value
                is au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContext.Active,
            wakeWordReady = wakeWordDetector.isReady,
        ),
    )
}

internal fun resolve(
    gateState: VoiceControlGateState,
    micExposure: MicExposure,
    isForeground: Boolean,
    isPlaybackActive: Boolean,
    wakeWordReady: Boolean,
): ListeningMode {
    if (!gateState.allowed) return ListeningMode.Off
    if (micExposure == MicExposure.NoMic) return ListeningMode.Off
    if (isForeground) return ListeningMode.Continuous
    if (isPlaybackActive && micExposure == MicExposure.Isolated) return ListeningMode.Continuous
    if (isPlaybackActive && micExposure == MicExposure.Exposed) {
        if (!wakeWordReady) return ListeningMode.Off
        return ListeningMode.WakeWord
    }
    return ListeningMode.Off
}
