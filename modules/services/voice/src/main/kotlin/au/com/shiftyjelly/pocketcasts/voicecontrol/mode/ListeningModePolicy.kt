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
import timber.log.Timber

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
    if (!gateState.allowed) {
        Timber.d("Mode: Off (gate blocked)")
        return ListeningMode.Off
    }
    if (micExposure == MicExposure.NoMic) {
        Timber.d("Mode: Off (no mic)")
        return ListeningMode.Off
    }
    if (isForeground) {
        Timber.d("Mode: Continuous (foreground)")
        return ListeningMode.Continuous
    }
    if (isPlaybackActive && micExposure == MicExposure.Isolated) {
        Timber.d("Mode: Continuous (playback + isolated mic)")
        return ListeningMode.Continuous
    }
    if (isPlaybackActive && micExposure == MicExposure.Exposed) {
        if (!wakeWordReady) {
            Timber.d("Mode: Off (playback + exposed mic, wake word not ready)")
            return ListeningMode.Off
        }
        Timber.d("Mode: WakeWord (playback + exposed mic)")
        return ListeningMode.WakeWord
    }
    Timber.d("Mode: Off (not foreground, no active playback) fg=%b playback=%b exposure=%s", isForeground, isPlaybackActive, micExposure)
    return ListeningMode.Off
}
