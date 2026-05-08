package au.com.shiftyjelly.pocketcasts.voice.audio

import kotlin.math.abs

class EnergyVoiceAudioSegmenter @javax.inject.Inject constructor() : VoiceAudioSegmenter {
    private val speechThreshold: Int = 700
    private val minimumSpeechFrames: Int = 3
    private val trailingSilenceFrames: Int = 4
    private val maxSpeechDurationMs: Long = 10_000 // 10 seconds max speech duration
    private val frames = mutableListOf<PcmAudioFrame>()
    private var speechFrames = 0
    private var silenceFrames = 0
    private var speechStartTimeMs: Long = 0L

    override fun process(frame: PcmAudioFrame): VoiceSegmenterResult {
        val isSpeech = frame.samples.any { abs(it.toInt()) >= speechThreshold }

        // Check for timeout if we're in speech mode
        if (speechFrames > 0 && System.currentTimeMillis() - speechStartTimeMs > maxSpeechDurationMs) {
            val segment = if (speechFrames >= minimumSpeechFrames) frames.toList() else null
            reset()
            return if (segment != null) {
                VoiceSegmenterResult.SpeechEnded(segment)
            } else {
                VoiceSegmenterResult.Rejected(RejectionReason.Timeout)
            }
        }

        if (isSpeech) {
            frames += frame
            speechFrames += 1
            silenceFrames = 0
            if (speechFrames == 1) {
                speechStartTimeMs = System.currentTimeMillis()
            }
            return if (speechFrames == 1) VoiceSegmenterResult.SpeechStarted else VoiceSegmenterResult.SpeechContinuing
        }

        if (speechFrames > 0) {
            frames += frame
            silenceFrames += 1
            if (silenceFrames >= trailingSilenceFrames) {
                val segment = if (speechFrames >= minimumSpeechFrames) frames.toList() else null
                reset()
                return if (segment != null) {
                    VoiceSegmenterResult.SpeechEnded(segment)
                } else {
                    VoiceSegmenterResult.Rejected(RejectionReason.TooShort)
                }
            }
        }

        return VoiceSegmenterResult.Silence
    }

    private fun reset() {
        frames.clear()
        speechFrames = 0
        silenceFrames = 0
        speechStartTimeMs = 0L
    }
}
