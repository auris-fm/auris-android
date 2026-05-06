package au.com.shiftyjelly.pocketcasts.voice.audio

import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyVoiceAudioSegmenterTest {
    @Test
    fun `returns speech ended after speech followed by trailing silence`() {
        val segmenter = EnergyVoiceAudioSegmenter(
            speechThreshold = 500,
            minimumSpeechFrames = 2,
            trailingSilenceFrames = 2,
        )

        segmenter.process(frame(shortArrayOf(800, 900)))
        segmenter.process(frame(shortArrayOf(900, 900)))
        segmenter.process(frame(shortArrayOf(0, 0)))
        val result = segmenter.process(frame(shortArrayOf(0, 0)))

        assertTrue(result is VoiceSegmenterResult.SpeechEnded)
    }

    private fun frame(samples: ShortArray) = PcmAudioFrame(samples = samples, sampleRateHz = 16_000)
}
