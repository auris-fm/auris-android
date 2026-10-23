package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudPlaybackContextState @Inject constructor() {
    private val lock = Any()
    private var recentReferencePositions: List<Long> = emptyList()
    private var previousReferencePositionMs: Long? = null

    /**
     * A state seeded with positions FOR a known episode.
     *
     * The episode is required rather than optional: positions belong to an episode, so a fixture that
     * seeded them without one would be asserting the very behaviour the scoping exists to prevent.
     */
    internal constructor(
        recentReferencePositions: List<Long>,
        previousReferencePositionMs: Long?,
        episodeId: String,
    ) : this() {
        this.recentReferencePositions = recentReferencePositions
        this.previousReferencePositionMs = previousReferencePositionMs
        this.recordedEpisodeId = episodeId
    }

    /**
     * The saved positions, but ONLY for the episode they were saved from.
     *
     * A reference position is a position IN an episode, so it describes that episode and nothing else.
     * Returning it for a different episode would send a position belonging to a recording the turn is
     * not about -- the wire would then carry a reference the server must reject, and a caller could not
     * tell that from a genuine one.
     *
     * @param episodeId the episode the caller is about to describe; null means no episode at all, and
     * then there are no positions to return.
     */
    fun snapshot(episodeId: String? = null): Snapshot = synchronized(lock) {
        // No episode, or a different one from the one these positions were recorded for: nothing to
        // offer. `null` and a mismatch both mean "these positions are not about this turn".
        val belongsToCaller = episodeId != null && episodeId == recordedEpisodeId
        Snapshot(
            recentReferencePositions = if (belongsToCaller) recentReferencePositions else emptyList(),
            previousReferencePositionMs = if (belongsToCaller) previousReferencePositionMs else null,
        )
    }

    /**
     * Records a position against the episode it belongs to.
     *
     * Naming the episode is what keeps saved positions from outliving their subject: the previous
     * unconditional assignment let positions recorded while episode A played be sent while episode B
     * was being asked about.
     */
    fun record(
        referencePositionMs: Long,
        previousReferencePositionMs: Long?,
        episodeId: String? = null,
    ) {
        synchronized(lock) {
            if (episodeId == null) {
                // Without an episode there is no subject to attach to, so this clears rather than
                // inheriting whatever was recorded for another episode.
                recordedEpisodeId = null
                recentReferencePositions = emptyList()
                this.previousReferencePositionMs = null
                return
            }
            if (episodeId != recordedEpisodeId) {
                // A different episode: its positions have no business here, so start over rather than
                // appending to another recording's history.
                recordedEpisodeId = episodeId
                recentReferencePositions = emptyList()
                this.previousReferencePositionMs = null
            }
            // Assign unconditionally within the episode: a null means "no valid reference-timeline
            // value this turn" -- keeping a previous turn's value would re-send exactly the
            // wrong-timeline payload this field exists to avoid.
            this.previousReferencePositionMs = previousReferencePositionMs
            recentReferencePositions = (recentReferencePositions + referencePositionMs)
                .takeLast(RECENT_LIMIT)
        }
    }

    /** The episode the saved positions were recorded FOR, or null when none. */
    private var recordedEpisodeId: String? = null

    data class Snapshot(
        val recentReferencePositions: List<Long>,
        val previousReferencePositionMs: Long?,
    )

    companion object {
        private const val RECENT_LIMIT = 5
    }
}
