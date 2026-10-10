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

        // When both sides carry a position, the audible sample is the one whose player position
        // falls minDelay..maxDelay *before the mic segment's position* — which is a claim about
        // time, not about buffer offset.
        val anchoredOffset = anchorOffset(micAudio, reference, micPositionMs, sampleRate)
        val offsetRange = if (anchoredOffset != null) {
            anchoredOffset..anchoredOffset
        } else {
            minDelay..maxDelay.coerceAtMost(playbackBuffer.size - micAudio.size)
        }

        var maxCorrelation = 0.0
        for (offset in offsetRange) {
            if (offset < 0 || offset + micAudio.size > playbackBuffer.size) continue
            val correlation = normalizedCrossCorrelation(micAudio, playbackBuffer, offset)
            if (correlation > maxCorrelation) maxCorrelation = correlation
        }

        return maxCorrelation > BLEED_THRESHOLD
    }

    /**
     * Buffer offset whose player position is one acoustic delay before the mic segment, or null
     * when either side lacks a timestamp (the caller then falls back to scanning offsets).
     */
    private fun anchorOffset(
        micAudio: FloatArray,
        reference: PlaybackReference,
        micPositionMs: Long?,
        sampleRate: Int,
    ): Int? {
        val refStart = reference.startPositionMs ?: return null
        val micStart = micPositionMs ?: return null
        // The middle of the plausible delay window; the window is the same range the unanchored scan
        // covers, so this narrows where to look without changing what counts as bleed.
        val assumedDelayMs = ((MIN_DELAY_SECONDS + MAX_DELAY_SECONDS) * 1_000.0 / 2).toLong()
        val wantedMs = micStart - assumedDelayMs
        val offsetSamples = ((wantedMs - refStart) * sampleRate / 1_000L).toInt()
        val maxOffset = (reference.samples.size - micAudio.size).coerceAtLeast(0)
        return offsetSamples.coerceIn(0, maxOffset)
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
