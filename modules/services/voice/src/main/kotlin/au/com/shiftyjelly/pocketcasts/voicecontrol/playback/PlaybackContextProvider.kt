package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintTimingManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.PlaybackContext
import javax.inject.Inject
import javax.inject.Singleton

interface PlaybackContextProvider {
    fun current(): PlaybackContext
}

@Singleton
class PlaybackManagerPlaybackContextProvider @Inject constructor(
    private val playbackManager: PlaybackManager,
    private val fingerprintTimingManager: FingerprintTimingManager,
    private val cloudPlaybackContextState: CloudPlaybackContextState,
) : PlaybackContextProvider {
    override fun current(): PlaybackContext {
        val episode = playbackManager.getCurrentEpisode()
        val playbackState = playbackManager.playbackStateRelay.blockingFirst()
        val clientPositionMs = when {
            episode == null -> 0L
            playbackState.episodeUuid == episode.uuid -> playbackState.positionMs.toLong()
            else -> episode.playedUpToMs.toLong()
        }
        val referencePositionMs = if (episode != null && fingerprintTimingManager.activeEpisodeUuid == episode.uuid) {
            fingerprintTimingManager.matchedReferenceTime(clientPositionMs.toInt())
                ?.times(1000)
                ?.toLong()
        } else {
            null
        }
        val state = cloudPlaybackContextState.snapshot()
        return PlaybackContext(
            episodeId = episode?.uuid.orEmpty(),
            podcastId = episode?.podcastOrSubstituteUuid.orEmpty(),
            referencePositionMs = referencePositionMs,
            clientPositionMs = clientPositionMs,
            recentReferencePositions = state.recentReferencePositions,
            previousReferencePositionMs = state.previousReferencePositionMs,
        )
    }
}
