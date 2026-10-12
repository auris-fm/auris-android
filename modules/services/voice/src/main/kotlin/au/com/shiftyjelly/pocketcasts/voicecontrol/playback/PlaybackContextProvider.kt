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
        // NO EPISODE MEANS NO POSITION, not position zero: zero is the very start of a recording, and
        // reporting it for "nothing is playing" places the turn at an instant that does not exist.
        val clientPositionMs: Long? = when {
            episode == null -> null
            playbackState.episodeUuid == episode.uuid -> playbackState.positionMs.toLong()
            else -> episode.playedUpToMs.toLong()
        }
        val referencePositionMs = if (
            episode != null &&
            clientPositionMs != null &&
            fingerprintTimingManager.activeEpisodeUuid == episode.uuid
        ) {
            fingerprintTimingManager.matchedReferenceTime(clientPositionMs.toInt())
                ?.times(1000)
                ?.toLong()
        } else {
            null
        }
        val state = cloudPlaybackContextState.snapshot()
        return PlaybackContext(
            // Absence is passed through as ABSENCE. `orEmpty()` fabricated an empty identifier that
            // serializes as a real `episode_id` on the wire, so a discovery turn with nothing playing
            // claimed a subject it did not have -- and the server cannot tell that from a genuine
            // blank id.
            episodeId = episode?.uuid,
            podcastId = episode?.podcastOrSubstituteUuid,
            referencePositionMs = referencePositionMs,
            clientPositionMs = clientPositionMs,
            recentReferencePositions = state.recentReferencePositions,
            previousReferencePositionMs = state.previousReferencePositionMs,
        )
    }
}
