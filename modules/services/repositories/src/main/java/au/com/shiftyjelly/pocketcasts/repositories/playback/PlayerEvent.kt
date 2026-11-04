package au.com.shiftyjelly.pocketcasts.repositories.playback

import androidx.media3.common.PlaybackException

/**
 * A event on Player
 */
sealed class PlayerEvent {
    class PlayerError(val message: String, val error: PlaybackException? = null) : PlayerEvent()
    object BufferingStateChanged : PlayerEvent()
    object PlayerPlaying : PlayerEvent()
    object PlayerPaused : PlayerEvent()
    class Completion(val episodeUUID: String?) : PlayerEvent()
    object DurationAvailable : PlayerEvent()
    class SeekComplete(
        val positionMs: Int,
        // The invocation identity carried through the seek request; null for a seek nobody awaits.
        val completionToken: String? = null,
    ) : PlayerEvent()
    class MetadataAvailable(val metaData: EpisodeFileMetadata) : PlayerEvent()
    class RemoteMetadataNotMatched(val remoteEpisodeUuid: String) : PlayerEvent()
    class EpisodeChanged(val episodeUuid: String) : PlayerEvent()
    data class CachingComplete(val episodeUuid: String) : PlayerEvent()
    data class CachingReset(val episodeUuid: String) : PlayerEvent()

    /** Emitted once the player knows whether the prepared stream contains a playable video track. */
    data class VideoTrackChanged(val hasVideo: Boolean) : PlayerEvent()
}
