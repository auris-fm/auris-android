package au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals

import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContextMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * @deprecated Removed per spec: grace is the only wake-word waiver.
 * Playback recency no longer selects the listening mode.
 */
@Deprecated("Removed per spec update")
class PlaybackRecencySignal internal constructor(
    private val playbackContextSource: StateFlow<PlaybackContext>,
    private val timeoutMs: Long,
) {
    @Deprecated("Removed per spec update")
    constructor(playbackContextMonitor: PlaybackContextMonitor) : this(
        playbackContextSource = playbackContextMonitor.context,
        timeoutMs = RECENCY_TIMEOUT_MS,
    )

    private val _isRecent = MutableStateFlow(false)
    val isRecent: StateFlow<Boolean> = _isRecent

    private var timerJob: Job? = null
    private val scope = CoroutineScope(Job() + Dispatchers.Main)

    init {
        scope.launch {
            playbackContextSource.collect { ctx ->
                when (ctx) {
                    is PlaybackContext.Active -> {
                        if (ctx.isPlaying) {
                            // Playing — mark recent and cancel any expiry timer
                            _isRecent.value = true
                            timerJob?.cancel()
                            timerJob = null
                        } else {
                            // Paused — start grace timer if currently recent
                            if (_isRecent.value && timerJob == null) {
                                timerJob = scope.launch {
                                    delay(timeoutMs)
                                    _isRecent.value = false
                                    timerJob = null
                                }
                            }
                        }
                    }

                    is PlaybackContext.Inactive -> {
                        // Playback stopped — start grace timer if currently recent
                        if (_isRecent.value && timerJob == null) {
                            timerJob = scope.launch {
                                delay(timeoutMs)
                                _isRecent.value = false
                                timerJob = null
                            }
                        }
                    }
                }
            }
        }
    }

    internal companion object {
        internal const val RECENCY_TIMEOUT_MS = 30_000L
    }
}
