package au.com.shiftyjelly.pocketcasts.voice.audio

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

class VoiceAudioProcessorTest {
    @Mock
    private lateinit var microphoneCapture: MicrophoneCapture

    @Mock
    private lateinit var voiceSegmenter: VoiceAudioSegmenter

    private lateinit var voiceAudioProcessor: VoiceAudioProcessor

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        voiceAudioProcessor = VoiceAudioProcessor(microphoneCapture, voiceSegmenter)
    }

    @Test
    fun `isProcessing returns false when not recording`() {
        `when`(microphoneCapture.isRecording).thenReturn(false)
        assertFalse(voiceAudioProcessor.isProcessing)
    }

    @Test
    fun `isProcessing returns true when recording`() {
        `when`(microphoneCapture.isRecording).thenReturn(true)
        assertTrue(voiceAudioProcessor.isProcessing)
    }

    @Test
    fun `stopProcessing calls microphone capture stop`() {
        voiceAudioProcessor.stopProcessing()
        verify(microphoneCapture).stopCapture()
    }

    @Test
    fun `voice audio processor integrates microphone capture with segmenter`() = runTest {
        val frame = PcmAudioFrame(shortArrayOf(100, 200), 16_000)
        val result = VoiceSegmenterResult.Silence

        `when`(microphoneCapture.startCapture()).thenReturn(flowOf(frame))
        `when`(voiceSegmenter.process(frame)).thenReturn(result)

        val results = mutableListOf<VoiceSegmenterResult>()
        voiceAudioProcessor.startProcessing()
            .collect { results.add(it) }

        assertEquals(1, results.size)
        assertEquals(result, results[0])
    }
}
