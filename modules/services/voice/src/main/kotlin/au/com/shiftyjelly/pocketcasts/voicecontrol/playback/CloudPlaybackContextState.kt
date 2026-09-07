package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudPlaybackContextState @Inject constructor() {
    private val lock = Any()
    private var recentReferencePositions: List<Long> = emptyList()
    private var previousReferencePositionMs: Long? = null

    internal constructor(
        recentReferencePositions: List<Long>,
        previousReferencePositionMs: Long?,
    ) : this() {
        this.recentReferencePositions = recentReferencePositions
        this.previousReferencePositionMs = previousReferencePositionMs
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            recentReferencePositions = recentReferencePositions,
            previousReferencePositionMs = previousReferencePositionMs,
        )
    }

    fun record(referencePositionMs: Long, previousReferencePositionMs: Long?) {
        synchronized(lock) {
            if (previousReferencePositionMs != null) {
                this.previousReferencePositionMs = previousReferencePositionMs
            }
            recentReferencePositions = (recentReferencePositions + referencePositionMs)
                .takeLast(RECENT_LIMIT)
        }
    }

    data class Snapshot(
        val recentReferencePositions: List<Long>,
        val previousReferencePositionMs: Long?,
    )

    companion object {
        private const val RECENT_LIMIT = 5
    }
}
