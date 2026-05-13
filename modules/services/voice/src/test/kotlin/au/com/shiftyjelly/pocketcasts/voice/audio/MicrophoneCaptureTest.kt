package au.com.shiftyjelly.pocketcasts.voice.audio

import android.media.AudioFormat
import android.media.AudioRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class MicrophoneCaptureTest {
    @Test
    fun `microphone capture creates proper audio configuration`() {
        val capture = MicrophoneCapture()
        assertEquals(16_000, MicrophoneCapture.SAMPLE_RATE_HZ)
        assertEquals(AudioFormat.CHANNEL_IN_MONO, AudioFormat.CHANNEL_IN_MONO)
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_16BIT)
        assertEquals(1, MicrophoneCapture.CHANNELS)
        assertEquals(2, MicrophoneCapture.BYTES_PER_SAMPLE)
    }

    @Test
    fun `isRecording returns false when not recording`() {
        val capture = MicrophoneCapture()
        assertFalse(capture.isRecording)
    }

    @Test
    fun `stopCapture does not throw when audio record is null`() {
        val capture = MicrophoneCapture()
        capture.stopCapture()
        assertFalse(capture.isRecording)
    }

    @Test
    fun `microphone capture exception types are properly defined`() {
        val initException = MicrophoneCaptureException.InitializationFailed("test")
        val readException = MicrophoneCaptureException.ReadFailed("test")
        val captureException = MicrophoneCaptureException.CaptureFailed("test")

        assertTrue(initException.javaClass == MicrophoneCaptureException.InitializationFailed::class.java)
        assertTrue(readException.javaClass == MicrophoneCaptureException.ReadFailed::class.java)
        assertTrue(captureException.javaClass == MicrophoneCaptureException.CaptureFailed::class.java)

        assertEquals("test", initException.message)
        assertEquals("test", readException.message)
        assertEquals("test", captureException.message)
    }

    @Test
    fun `pcm audio frame can be created with samples and sample rate`() {
        val samples = shortArrayOf(100, 200, 300)
        val frame = PcmAudioFrame(samples = samples, sampleRateHz = 16_000)

        assertEquals(16_000, frame.sampleRateHz)
        assertEquals(3, frame.samples.size)
        assertEquals(100.toShort(), frame.samples[0])
        assertEquals(200.toShort(), frame.samples[1])
        assertEquals(300.toShort(), frame.samples[2])
    }

    @Test
    fun `pcm audio frame equality works correctly`() {
        val frame1 = PcmAudioFrame(samples = shortArrayOf(1, 2, 3), sampleRateHz = 16_000)
        val frame2 = PcmAudioFrame(samples = shortArrayOf(1, 2, 3), sampleRateHz = 16_000)
        val frame3 = PcmAudioFrame(samples = shortArrayOf(1, 2, 4), sampleRateHz = 16_000)
        val frame4 = PcmAudioFrame(samples = shortArrayOf(1, 2, 3), sampleRateHz = 8_000)

        assertEquals(frame1, frame2)
        assertEquals(frame1.hashCode(), frame2.hashCode())
        assertTrue(frame1 != frame3)
        assertTrue(frame1 != frame4)
    }

    @Test
    fun `pcm audio frame string representation is correct`() {
        val frame = PcmAudioFrame(samples = shortArrayOf(1, 2, 3), sampleRateHz = 16_000)
        val expected = "PcmAudioFrame(samples=[1, 2, 3], sampleRateHz=16000)"
        assertEquals(expected, frame.toString())
    }

    @Test
    fun `voice segmenter result sealed types are properly defined`() {
        val silence = VoiceSegmenterResult.Silence
        val speechStarted = VoiceSegmenterResult.SpeechStarted
        val speechContinuing = VoiceSegmenterResult.SpeechContinuing
        val frames = listOf(PcmAudioFrame(shortArrayOf(1, 2), 16_000))
        val speechEnded = VoiceSegmenterResult.SpeechEnded(frames)

        assertTrue(silence.javaClass == VoiceSegmenterResult.Silence::class.java)
        assertTrue(speechStarted.javaClass == VoiceSegmenterResult.SpeechStarted::class.java)
        assertTrue(speechContinuing.javaClass == VoiceSegmenterResult.SpeechContinuing::class.java)
        assertTrue(speechEnded.javaClass == VoiceSegmenterResult.SpeechEnded::class.java)

        assertEquals(frames, speechEnded.frames)
    }
}
