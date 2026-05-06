package au.com.shiftyjelly.pocketcasts.voice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    @Test
    fun `discards below minimum burst after trailing silence`() {
        val segmenter = EnergyVoiceAudioSegmenter(
            speechThreshold = 500,
            minimumSpeechFrames = 2,
            trailingSilenceFrames = 2,
        )

        segmenter.process(frame(shortArrayOf(800, 900)))
        segmenter.process(frame(shortArrayOf(0, 0)))
        val result = segmenter.process(frame(shortArrayOf(0, 0)))

        assertEquals(VoiceSegmenterResult.Silence, result)
    }

    @Test
    fun `starts fresh speech after discarding below minimum burst`() {
        val segmenter = EnergyVoiceAudioSegmenter(
            speechThreshold = 500,
            minimumSpeechFrames = 2,
            trailingSilenceFrames = 2,
        )

        segmenter.process(frame(shortArrayOf(800, 900)))
        segmenter.process(frame(shortArrayOf(0, 0)))
        segmenter.process(frame(shortArrayOf(0, 0)))
        val result = segmenter.process(frame(shortArrayOf(900, 900)))

        assertEquals(VoiceSegmenterResult.SpeechStarted, result)
    }

    @Test
    fun `speech ended includes valid speech segment`() {
        val segmenter = EnergyVoiceAudioSegmenter(
            speechThreshold = 500,
            minimumSpeechFrames = 2,
            trailingSilenceFrames = 2,
        )
        val frames = listOf(
            frame(shortArrayOf(800, 900)),
            frame(shortArrayOf(900, 900)),
            frame(shortArrayOf(0, 0)),
            frame(shortArrayOf(0, 0)),
        )

        frames.dropLast(1).forEach(segmenter::process)
        val result = segmenter.process(frames.last())

        assertEquals(VoiceSegmenterResult.SpeechEnded(frames), result)
    }

    @Test
    fun `pcm audio frame uses sample contents for value semantics`() {
        val frame = frame(shortArrayOf(1, 2, 3))
        val equalFrame = frame(shortArrayOf(1, 2, 3))
        val differentSamples = frame(shortArrayOf(1, 2, 4))
        val differentSampleRate = PcmAudioFrame(samples = shortArrayOf(1, 2, 3), sampleRateHz = 8_000)

        assertEquals(equalFrame, frame)
        assertEquals(equalFrame.hashCode(), frame.hashCode())
        assertNotEquals(differentSamples, frame)
        assertNotEquals(differentSampleRate, frame)
        assertEquals("PcmAudioFrame(samples=[1, 2, 3], sampleRateHz=16000)", frame.toString())
    }

    private fun frame(samples: ShortArray) = PcmAudioFrame(samples = samples, sampleRateHz = 16_000)
}
