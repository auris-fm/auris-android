package au.com.shiftyjelly.pocketcasts.voicecontrol.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyVoiceAudioSegmenterTest {
    // speechThreshold = 1500, minimumSpeechFrames = 1, trailingSilenceFrames = 4

    @Test
    fun `returns speech ended after speech followed by trailing silence`() {
        val segmenter = EnergyVoiceAudioSegmenter()
        // 2 speech frames (RMS > 1500) followed by 4 silence frames = speech ended
        segmenter.process(frame(shortArrayOf(2000, 2000)))
        segmenter.process(frame(shortArrayOf(2000, 2000)))
        segmenter.process(frame(shortArrayOf(0, 0)))
        segmenter.process(frame(shortArrayOf(0, 0)))
        segmenter.process(frame(shortArrayOf(0, 0)))
        val result = segmenter.process(frame(shortArrayOf(0, 0)))

        assertTrue(result is VoiceSegmenterResult.SpeechEnded)
    }

    @Test
    fun `speech ended includes valid speech segment`() {
        val segmenter = EnergyVoiceAudioSegmenter()
        val frames = listOf(
            frame(shortArrayOf(2000, 2000)),
            frame(shortArrayOf(2000, 2000)),
            frame(shortArrayOf(0, 0)),
            frame(shortArrayOf(0, 0)),
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
