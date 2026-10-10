package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

/**
 * A window of playback audio submitted for emission, with the player-timeline position of its
 * oldest sample.
 *
 * The position is what makes alignment meaningful when more than one renderer contributes: a
 * buffer assembled by arrival order cannot distinguish a sample that played 40 ms ago from one that
 * played 3 s ago but arrived later, so correlation against it can match the wrong instant.
 * Carrying [startPositionMs] lets the correlator restrict its search to the delay window that is
 * actually plausible for the segment being judged, rather than to raw buffer offsets.
 *
 * [startPositionMs] is null when the contributing renderers supplied no timestamps; callers then
 * fall back to offset-based correlation, which is the behaviour before timestamps existed.
 */
data class PlaybackReference(
    val samples: FloatArray,
    val sampleRateHz: Int,
    val startPositionMs: Long?,
) {
    /** Position of the newest sample, or null when the window is untimestamped. */
    val endPositionMs: Long?
        get() = startPositionMs?.let { it + (samples.size * 1_000L) / sampleRateHz }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlaybackReference) return false
        return sampleRateHz == other.sampleRateHz &&
            startPositionMs == other.startPositionMs &&
            samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + sampleRateHz
        result = 31 * result + (startPositionMs?.hashCode() ?: 0)
        return result
    }
}
