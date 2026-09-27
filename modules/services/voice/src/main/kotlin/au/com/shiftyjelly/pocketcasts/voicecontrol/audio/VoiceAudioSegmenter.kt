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
         * Sample index where voicing stopped, as the segmenter measured it.
         *
         * The frames carry the trailing-silence window too, so [frames] ends
         * later than the speech does. The difference is what tells a wake phrase
         * followed by silence from a wake phrase followed by a question, and
         * only the segmenter knows it — a consumer measuring the segment against
         * a deadline would see speech that was never there. 0 means the
         * segmenter did not report it.
         */
        val speechEndSample: Int = 0,
    ) : VoiceSegmenterResult
    data class Rejected(val reason: RejectionReason) : VoiceSegmenterResult
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
