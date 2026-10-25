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
 * The monotonic instant the segment's speech onset was CAPTURED, or null when the stream reported no
 * timestamp.
 *
 * The clock observation is taken at segment COMPLETION (OboeCaptureEngine reads it in the drain loop
 * after the whole segment has been copied), so the observation's frame position names the sample just
 * past the segment's end -- not the onset. The onset's stream position is therefore
 * `framePosition - (segmentSamples - speechOnsetSample)`, and that is the position converted here.
 * Anchoring at the observation's own position instead would place the segment at the instant it was
 * DELIVERED, up to a whole segment late, and the bleed filter's delay window would miss the device's
 * own answer on long utterances.
 */
fun VoiceSegmenterResult.SpeechEnded.captureTimeOfSpeechOnsetMs(): Long? {
    val observation = captureClock ?: return null
    val segmentSamples = frames.sumOf { it.samples.size.toLong() }
    val onsetStreamPosition = observation[0] - (segmentSamples - speechOnsetSample)
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
