package au.com.shiftyjelly.pocketcasts.voicecontrol.audio

interface VoiceAudioSegmenter {
    fun process(frame: PcmAudioFrame): VoiceSegmenterResult
}

sealed interface VoiceSegmenterResult {
    data object Silence : VoiceSegmenterResult
    data object SpeechStarted : VoiceSegmenterResult
    data object SpeechContinuing : VoiceSegmenterResult
    data class SpeechEnded(
        val frames: List<PcmAudioFrame>,
        val speechOnsetSample: Int = 0,
        /**
         * The last sample the VAD classified as speech, drain excluded — not the sample at which
         * the segment closed. Defaults to 0 for segmenters that do not report it, which the engine
         * reads as "unknown" rather than "no speech".
         */
        val speechEndSample: Int = 0,
        /**
         * The capture stream's frame/time observation at the moment this segment closed, as
         * `[framePosition, timestampNs, sampleRate, observable]`, or null when the segmenter has no
         * live stream to observe.
         *
         * This is what places the segment on the same clock as the playback side: [speechOnsetSample]
         * and [speechEndSample] are offsets within `frames`, and the clock converts between that
         * position and a monotonic time, so a playback window can be compared to the instant the
         * user actually spoke rather than to a buffer offset.
         */
        val captureClock: LongArray? = null,
        /**
         * The segment's END as an absolute sample position in the VAD's own consumed-stream
         * accounting (samples consumed from capture since start), snapshotted by the native VAD at
         * the moment the segment was finalized -- NOT read afterwards from the hardware observation,
         * which can lag the segment while the callback and ring advance. Null when the accounting is
         * unavailable; consumers must treat the onset time as unknown then, never substitute the
         * observation's frame position.
         */
        val segmentEndStreamPosition: Long? = null,
    ) : VoiceSegmenterResult
    data class Rejected(val reason: RejectionReason) : VoiceSegmenterResult
}

/**
 * Converts a capture-stream clock observation into the monotonic instant a sample position was
 * captured.
 *
 * `[framePosition, timestampNs, sampleRate, observable]` is one instant on the stream's monotonic
 * clock: the frame at `framePosition` was captured at `timestampNs`. A sample at stream position `p`
 * is therefore at `timestampNs + (p - framePosition) * 1e9 / sampleRate`, which is the form the
 * playback side can be compared against -- `AudioTrack.getTimestamp` reports on the same monotonic
 * timebase, so both sides land on one axis and a delay between them is a real quantity.
 *
 * Returns null when the observation is absent or the stream reports no timestamp. An absent
 * observation must stay absent: substituting the frame counter for a time would place the segment
 * confidently at an instant nothing measured.
 */
fun captureTimeNsForPosition(captureClock: LongArray?, streamPositionInSegment: Long): Long? {
    if (captureClock == null || captureClock.size < 4) return null
    val framePosition = captureClock[0]
    val timestampNs = captureClock[1]
    val sampleRate = captureClock[2]
    val observable = captureClock[3] != 0L
    if (!observable || sampleRate <= 0) return null
    return timestampNs + (streamPositionInSegment - framePosition) * 1_000_000_000L / sampleRate
}

/**
 * The monotonic instant the segment's speech onset was CAPTURED, or null when the alignment is
 * unavailable.
 *
 * The anchor is SAMPLE-ACCOUNTED, not read from the clock: the native VAD records the absolute
 * position of the segment's end (in consumed-stream samples) when it finalizes the segment, so
 * delivery lag -- the callback and ring advancing between the segment's last sample and the drain
 * loop's timestamp read -- cannot move the anchor. The onset's stream position is
 * `segmentEndStreamPosition - (segmentSamples - speechOnsetSample)`, and the clock observation only
 * supplies the instant that position is converted against. Anchoring at the observation's own frame
 * position instead would fold the queue lag into every delayed segment's position.
 *
 * Null when either the accounted end position or a timestamp observation is missing: an absent
 * binding must stay absent (the engine falls back to offset matching), never approximate.
 */
fun VoiceSegmenterResult.SpeechEnded.captureTimeOfSpeechOnsetMs(): Long? {
    val endPosition = segmentEndStreamPosition ?: return null
    val observation = captureClock ?: return null
    val segmentSamples = frames.sumOf { it.samples.size.toLong() }
    val onsetStreamPosition = endPosition - (segmentSamples - speechOnsetSample)
    return captureTimeNsForPosition(observation, onsetStreamPosition)?.div(1_000_000L)
}

enum class RejectionReason {
    /**
     * Audio segment was too short to be considered valid speech
     */
    TooShort,

    /**
     * Audio signal was too weak or unclear
     */
    LowConfidence,

    /**
     * Audio processing timeout occurred
     */
    Timeout,

    /**
     * Audio route became invalid during capture
     */
    InvalidRoute,
}
