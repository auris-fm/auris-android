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
        // BOUND, don't bail (reviewer 4356625095): the recorder's reference is 2 s = 32 000 samples
        // while a minimum VAD segment is 41 x 1024 = 41 984, so declining on length would skip
        // correlation for EVERY real segment and let the device's own answer audio be transcribed
        // and executed. Bound the analysis window to the longest prefix that still leaves the
        // reference room for the acoustic-delay search (ref minus maxDelay): the segment's PREFIX
        // keeps `micPositionMs` valid for the analyzed samples, and the bounded window re-enters
        // the ordinary window policies below instead of a special case.
        val maxDelaySamples = (MAX_DELAY_SECONDS * reference.sampleRateHz).toInt()
        val analysis: FloatArray
        val analysisPositionMs: Long?
        if (micAudio.size > playbackBuffer.size) {
            // Only a segment LONGER than the reference needs bounding, and the bounded window must
            // stay long enough to correlate meaningfully (>= 100 ms); a tiny reference declines
            // exactly as the length bail-out did, without silently masking every real segment.
            val windowBound = playbackBuffer.size - maxDelaySamples
            if (windowBound < MIN_ANALYSIS_SAMPLES) return false
            // TAIL, not PREFIX (reviewer 6381507658): the recorder's reference holds the NEWEST 2 s,
            // so only the segment's NEWEST windowBound samples can overlap it in player time. The
            // prefix is older than the whole reference - its position window lies before offset 0
            // and the correlation is inert for every real VAD segment, leaving the device's own
            // answer transcribable and executable. Shifting the anchor to the tail's first sample
            // keeps the position truthful for the analyzed samples.
            analysis = micAudio.copyOfRange(micAudio.size - windowBound, micAudio.size)
            analysisPositionMs = micPositionMs?.plus(
                (micAudio.size - windowBound) * 1_000L / reference.sampleRateHz,
            )
        } else {
            analysis = micAudio
            analysisPositionMs = micPositionMs
        }

        val sampleRate = reference.sampleRateHz
        val minDelay = (MIN_DELAY_SECONDS * sampleRate).toInt()
        val maxDelay = (MAX_DELAY_SECONDS * sampleRate).toInt()

        // When both sides carry a position, translate the plausible acoustic-delay window
        // (minDelay..maxDelay) into the reference's coordinates and search that whole range. The
        // window is what defines bleed, so anchoring must not narrow it to a single offset: a
        // genuine echo at either end of 50..500 ms is still bleed and must be found.
        // THREE DISTINCT OUTCOMES, and collapsing any two is a defect:
        //   Unavailable  no usable timing -> search the default delay window (alignment unavailable,
        //                so the position cannot rule anything out)
        //   Empty        timing IS valid and the delay interval does not overlap the reference -> no
        //                admissible candidate; NO BLEED without correlating a fabricated offset.
        //                Matching an old waveform is not evidence of a temporally possible echo.
        //   Available    candidates exist -> correlate them.
        val candidate = candidateOffsets(analysis, reference, analysisPositionMs, sampleRate)
        val offsetRange = when (candidate) {
            is CandidateRange.Unavailable -> {
                // 50..500 ms window is offsets [maxOffset - maxDelay, maxOffset - minDelay],
                // clamped into [0, maxOffset]. PARTIAL-REFERENCE POLICY (@spec 691ee13c): offsets
                // whose implied delay falls inside the window (e.g. maxOffset 1600 = 100 ms admits
                // 0..800, i.e. delays 50..100 ms). A reference SHORTER than the minimum delay has
                // no offset with a complete in-window candidate -- maxOffset < minDelay means even
                // offset 0 implies a sub-50 ms delay -- so the comparison is DECLINED (empty range),
                // never resolved by clamping onto fabricated offset zero.
                val maxOffset = (playbackBuffer.size - analysis.size).coerceAtLeast(0)
                if (maxOffset < minDelay) {
                    IntRange.EMPTY
                } else {
                    (maxOffset - maxDelay).coerceAtLeast(0)..(maxOffset - minDelay).coerceAtMost(maxOffset)
                }
            }

            is CandidateRange.Empty -> return false

            is CandidateRange.Available -> candidate.offsets
        }

        var maxCorrelation = 0.0
        for (offset in offsetRange) {
            if (offset < 0 || offset + analysis.size > playbackBuffer.size) continue
            val correlation = normalizedCrossCorrelation(analysis, playbackBuffer, offset)
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
    private sealed interface CandidateRange {
        data object Unavailable : CandidateRange
        data object Empty : CandidateRange
        data class Available(val offsets: IntRange) : CandidateRange
    }

    private fun candidateOffsets(
        micAudio: FloatArray,
        reference: PlaybackReference,
        micPositionMs: Long?,
        sampleRate: Int,
    ): CandidateRange {
        val refStart = reference.startPositionMs ?: return CandidateRange.Unavailable
        val micStart = micPositionMs ?: return CandidateRange.Unavailable

        val minDelayMs = (MIN_DELAY_SECONDS * 1_000).toLong()
        val maxDelayMs = (MAX_DELAY_SECONDS * 1_000).toLong()

        // Offset whose player position is `delayMs` before the mic segment, for each window edge.
        fun offsetForDelay(delayMs: Long): Int = ((micStart - delayMs - refStart) * sampleRate / 1_000L).toInt()

        val maxOffset = (reference.samples.size - micAudio.size).coerceAtLeast(0)
        // The UNCLAMPED interval the trusted timing names.
        val lowerRaw = offsetForDelay(maxDelayMs)
        val upperRaw = offsetForDelay(minDelayMs)

        // NO OVERLAP with the retained reference: the interval lies entirely before offset 0 or
        // entirely past the last usable offset. The timing is VALID and it rules the reference out,
        // so there is no admissible candidate. Clamping here is what turns an excluded reference into
        // a searched one; and falling back to the default window would be equally wrong, because the
        // timing is not unavailable -- it is decisive.
        if (lowerRaw > maxOffset || upperRaw < 0) return CandidateRange.Empty

        // Clamp edges that fall partially outside: a partially overlapping window still has the
        // usable part of its range as candidates.
        val start = minOf(lowerRaw, upperRaw).coerceIn(0, maxOffset)
        val end = maxOf(lowerRaw, upperRaw).coerceIn(0, maxOffset)
        return CandidateRange.Available(start..end)
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

        /** Shortest analysis window (100 ms at 16 kHz) that a bounded bleed check will still run. */
        private const val MIN_ANALYSIS_SAMPLES = 1_600

        /**
         * The acoustic-delay window, as a property of the built-in speaker path, NOT of every route.
         *
         * The correlator searches only this range: a route whose real delay falls outside it is not
         * caught, and the anchoring above NARROWS to the window rather than widening it to suit a
         * route. These are therefore a limitation to report for a route that measures outside them,
         * never a constant to adjust until a route fits.
         */
        private const val MIN_DELAY_SECONDS = 0.050
        private const val MAX_DELAY_SECONDS = 0.500
        private const val SAMPLE_RATE_HZ = 16_000
    }
}
