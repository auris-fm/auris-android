package au.com.shiftyjelly.pocketcasts.voicecontrol.asr

import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.PcmAudioFrame
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceUtteranceClip
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperRecognizerTest {

    @Test
    fun `returns transcript when native transcribes`() = runTest {
        val recognizer = WhisperRecognizer(
            modelFile = java.io.File("/nonexistent"),
        ).apply {
            nativeImpl = { _, _, _ -> "play the next episode" }
        }
        val clip = VoiceUtteranceClip.fromFrames(
            listOf(PcmAudioFrame(ShortArray(16000), 16000)),
        )
        assertEquals("play the next episode", recognizer.transcribe(clip))
    }

    @Test
    fun `returns empty string on native returning empty`() = runTest {
        val recognizer = WhisperRecognizer(
            modelFile = java.io.File("/nonexistent"),
        ).apply {
            nativeImpl = { _, _, _ -> "" }
        }
        val clip = VoiceUtteranceClip.fromFrames(
            listOf(PcmAudioFrame(ShortArray(16000), 16000)),
        )
        assertEquals("", recognizer.transcribe(clip))
    }
}
