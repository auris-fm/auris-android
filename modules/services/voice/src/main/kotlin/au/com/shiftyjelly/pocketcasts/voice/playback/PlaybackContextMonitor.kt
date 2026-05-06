package au.com.shiftyjelly.pocketcasts.voice.playback

import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface PlaybackContext {
    data class Active(val currentEpisodeUuid: String) : PlaybackContext
    data object Inactive : PlaybackContext
}

class PlaybackContextMonitor(
    playbackManager: PlaybackManager,
    scope: CoroutineScope,
) {
    val context: StateFlow<PlaybackContext> = playbackManager.playbackStateFlow
        .map(::toPlaybackContext)
        .stateIn(scope, SharingStarted.Eagerly, PlaybackContext.Inactive)

    private fun toPlaybackContext(playbackState: PlaybackState): PlaybackContext {
        val currentEpisodeUuid = playbackState.episodeUuid
        return if (currentEpisodeUuid.isNotBlank() && !playbackState.isStopped && !playbackState.isEmpty) {
            PlaybackContext.Active(currentEpisodeUuid = currentEpisodeUuid)
        } else {
            PlaybackContext.Inactive
        }
    }
}
