package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintTimingManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.PlaybackContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

interface PlaybackContextProvider {
    fun current(): PlaybackContext

    /**
     * What the ENGINE reports RIGHT NOW, or null when no observation can be made.
     *
     * Deliberately separate from [current]: that one is the turn's request context, enriched for the
     * server. This is measurement, and it is the only thing an app-operation result may rest on — a
     * dispatched command is not evidence of an outcome.
     *
     * WIRE KEYS, named by the contract rather than by the engine: `playing` (boolean), `episode_id`,
     * `position_ms`. The engine's own vocabulary (`State.PLAYING`, `playing`) is mapped here, at the
     * boundary, so no caller has to know it.
     *
     * **null means "we could not look", never "not playing".** A caller that reads absence as a
     * measurement is claiming one it never took.
     */
    fun currentObservedState(): JSONObject? = null
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
        )
    }

    /**
     * The engine's current state, mapped to the contract's wire keys.
     *
     * `playing` comes from [au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackState.isPlaying]
     * — read NOW from the relay, not from any cached turn context. `EMPTY` means the engine holds no
     * episode at all, which is not an observation about a paused player, so it is reported as
     * unavailable rather than as `playing = false`.
     */
    override fun currentObservedState(): JSONObject? {
        val state = playbackManager.playbackStateRelay.blockingFirst()
        if (state.state == au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackState.State.EMPTY) {
            return null
        }
        return JSONObject()
            .put(OBSERVED_PLAYING, state.isPlaying)
            .put(OBSERVED_EPISODE_ID, state.episodeUuid)
            .put(OBSERVED_POSITION_MS, state.positionMs)
    }
}

/** The wire key the contract names for the engine's playing flag (`voice-intents.md` 818c8f75). */
private const val OBSERVED_PLAYING = "playing"
private const val OBSERVED_EPISODE_ID = "episode_id"
private const val OBSERVED_POSITION_MS = "position_ms"
