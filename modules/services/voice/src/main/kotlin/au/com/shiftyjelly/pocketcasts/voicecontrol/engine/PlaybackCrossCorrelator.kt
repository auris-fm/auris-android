package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Cross-correlates mic audio against the playback reference to detect podcast bleed-through.
 *
 * When the reference carries a player-timeline position, the search is anchored to the position
 * rather than to a buffer offset. Arrival order cannot tell a sample played 40 ms ago from one
 * played 3 s ago that arrived later, so an offset-only search can match the wrong instant once more
 * than one renderer contributes.
 */
@Singleton
class PlaybackCrossCorrelator @Inject constructor() {

    fun isPlaybackBleed(
        micAudio: FloatArray,
        reference: PlaybackReference,
        /** Player-timeline position of the mic segment's first sample, when known. */
        micPositionMs: Long? = null,
    ): Boolean {
        val playbackBuffer = reference.samples
        if (playbackBuffer.size < micAudio.size) return false

        val sampleRate = reference.sampleRateHz
        val minDelay = (MIN_DELAY_SECONDS * sampleRate).toInt()
        val maxDelay = (MAX_DELAY_SECONDS * sampleRate).toInt()

        // When both sides carry a position, translate the plausible acoustic-delay window
        // (minDelay..maxDelay) into the reference's coordinates and search that whole range. The
        // window is what defines bleed, so anchoring must not narrow it to a single offset: a
        // genuine echo at either end of 50..500 ms is still bleed and must be found.
        val anchoredRange = anchoredOffsetRange(micAudio, reference, micPositionMs, sampleRate)
        val offsetRange = anchoredRange
            ?: (minDelay..maxDelay.coerceAtMost(playbackBuffer.size - micAudio.size))

        var maxCorrelation = 0.0
        for (offset in offsetRange) {
            if (offset < 0 || offset + micAudio.size > playbackBuffer.size) continue
            val correlation = normalizedCrossCorrelation(micAudio, playbackBuffer, offset)
            if (correlation > maxCorrelation) maxCorrelation = correlation
        }

        return maxCorrelation > BLEED_THRESHOLD
    }

    /**
     * The same 50..500 ms acoustic-delay window, expressed as buffer offsets via the player
     * positions of the reference window and the mic segment; null when either side lacks a
     * timestamp (the caller then scans the default window).
     *
     * A sample is a bleed candidate when it played one acoustic delay before the mic segment, so
     * the candidate offsets are those whose player position lies in
     * `[micStart - maxDelay, micStart - minDelay]` relative to the reference's start.
     */
    private fun anchoredOffsetRange(
        micAudio: FloatArray,
        reference: PlaybackReference,
        micPositionMs: Long?,
        sampleRate: Int,
    ): IntRange? {
        val refStart = reference.startPositionMs ?: return null
        val micStart = micPositionMs ?: return null

        val minDelayMs = (MIN_DELAY_SECONDS * 1_000).toLong()
        val maxDelayMs = (MAX_DELAY_SECONDS * 1_000).toLong()

        // Offset whose player position is `delayMs` before the mic segment, for each window edge.
        fun offsetForDelay(delayMs: Long): Int = ((micStart - delayMs - refStart) * sampleRate / 1_000L).toInt()

        val maxOffset = (reference.samples.size - micAudio.size).coerceAtLeast(0)
        val lower = offsetForDelay(maxDelayMs).coerceIn(0, maxOffset)
        val upper = offsetForDelay(minDelayMs).coerceIn(0, maxOffset)
        // The larger delay maps to the smaller offset; keep the range ordered and non-empty.
        val start = minOf(lower, upper)
        val end = maxOf(lower, upper)
        return start..end
    }

    /**
     * Offset-based entry point for callers with no position information (and renderers that supply
     * none). Prefer the [PlaybackReference] overload whenever a position exists.
     */
    fun isPlaybackBleed(
        micAudio: FloatArray,
        playbackBuffer: FloatArray,
    ): Boolean = isPlaybackBleed(
        micAudio = micAudio,
        reference = PlaybackReference(playbackBuffer, SAMPLE_RATE_HZ, startPositionMs = null),
    )

    private fun normalizedCrossCorrelation(
        signal: FloatArray,
        reference: FloatArray,
        offset: Int,
    ): Double {
        var dot = 0.0
        var normSignal = 0.0
        var normRef = 0.0
        for (i in signal.indices) {
            val s = signal[i].toDouble()
            val r = reference[offset + i].toDouble()
            dot += s * r
            normSignal += s * s
            normRef += r * r
        }
        val denom = sqrt(normSignal) * sqrt(normRef)
        return if (denom == 0.0) 0.0 else dot / denom
    }

    companion object {
        private const val BLEED_THRESHOLD = 0.3
        private const val MIN_DELAY_SECONDS = 0.050
        private const val MAX_DELAY_SECONDS = 0.500
        private const val SAMPLE_RATE_HZ = 16_000
    }
}
