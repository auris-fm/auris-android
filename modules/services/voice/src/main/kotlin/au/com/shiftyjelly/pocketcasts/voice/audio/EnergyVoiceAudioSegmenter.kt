package au.com.shiftyjelly.pocketcasts.voice.audio

import kotlin.math.abs

class EnergyVoiceAudioSegmenter @javax.inject.Inject constructor(
    private val speechThreshold: Int = 700,
    private val minimumSpeechFrames: Int = 3,
    private val trailingSilenceFrames: Int = 4,
) : VoiceAudioSegmenter {
    private val frames = mutableListOf<PcmAudioFrame>()
    private var speechFrames = 0
    private var silenceFrames = 0

    override fun process(frame: PcmAudioFrame): VoiceSegmenterResult {
        val isSpeech = frame.samples.any { abs(it.toInt()) >= speechThreshold }
        if (isSpeech) {
            frames += frame
            speechFrames += 1
            silenceFrames = 0
            return if (speechFrames == 1) VoiceSegmenterResult.SpeechStarted else VoiceSegmenterResult.SpeechContinuing
        }

        if (speechFrames > 0) {
            frames += frame
            silenceFrames += 1
            if (speechFrames >= minimumSpeechFrames && silenceFrames >= trailingSilenceFrames) {
                val segment = frames.toList()
                reset()
                return VoiceSegmenterResult.SpeechEnded(segment)
            }
        }

        return VoiceSegmenterResult.Silence
    }

    private fun reset() {
        frames.clear()
        speechFrames = 0
        silenceFrames = 0
    }
}
