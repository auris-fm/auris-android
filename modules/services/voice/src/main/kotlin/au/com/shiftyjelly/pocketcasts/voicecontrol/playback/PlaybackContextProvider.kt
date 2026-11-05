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
        // ONE CONSISTENT MANAGER SNAPSHOT (reviewer finding on the provenance follow-up): episode,
        // mapped position and digest are read together, so a reset/rebind or a same-episode
        // reference replacement between two separate reads can never pair an old position with a new
        // digest. The version is bound to the MATCHED position -- in an unmatched or ad region there
        // is no mapped position, and provenance without one is not sent.
        val activeReference = if (clientPositionMs != null) {
            fingerprintTimingManager.activeReference(clientPositionMs.toInt())
        } else {
            null
        }
        val referencePositionMs = if (
            episode != null &&
            activeReference != null &&
            activeReference.episodeUuid == episode.uuid
        ) {
            activeReference.matchedReferenceTimeMs
        } else {
            null
        }
        // THE REFERENCE PROVENANCE is bound to the mapped position from the same snapshot: a known
        // digest for an instant the map did not measure (ad break, unmapped region) is NOT sent.
        val referenceAssetVersion = if (
            episode != null &&
            referencePositionMs != null &&
            activeReference?.episodeUuid == episode.uuid
        ) {
            activeReference.referenceVersion
        } else {
            null
        }
        // Screened by episode for the same reason as the sink's: a saved position belongs to the
        // episode it was recorded from.
        val state = cloudPlaybackContextState.snapshot(episode?.uuid)
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
            referenceAssetVersion = referenceAssetVersion,
        )
    }
}
