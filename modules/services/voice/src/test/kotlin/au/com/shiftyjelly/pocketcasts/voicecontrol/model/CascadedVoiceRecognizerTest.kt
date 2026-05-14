package au.com.shiftyjelly.pocketcasts.voicecontrol.model

import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.WhisperRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.PcmAudioFrame
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.SmolLmIntentParser
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoicePlaybackIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CascadedVoiceRecognizerTest {

    @Test
    fun `orchestrates ASR then intent parsing`() = runTest {
        val whisper = WhisperRecognizer(modelFile = File("/tmp/whisper")).apply {
            nativeImpl = { _, _, _ -> "pause" }
        }
        val parser = SmolLmIntentParser(modelFile = File("/tmp/lm")).apply {
            nativeImpl = { _, _ -> """{"intent": "pause"}""" }
        }
        val recognizer = CascadedVoiceRecognizer(whisper, parser)
        val clip = VoiceUtteranceClip.fromFrames(listOf(PcmAudioFrame(ShortArray(1600), 16000)))
        val ctx = VoiceRecognitionContext(PlaybackContext.Inactive, AudioRoute.Headset(true))

        assertEquals(VoicePlaybackIntent.Pause, recognizer.recognize(clip, ctx))
    }

    @Test
    fun `returns null when whisper returns empty`() = runTest {
        val whisper = WhisperRecognizer(modelFile = File("/tmp/whisper")).apply {
            nativeImpl = { _, _, _ -> "" }
        }
        val parser = SmolLmIntentParser(modelFile = File("/tmp/lm")).apply {
            nativeImpl = { _, _ -> """{"intent": "none"}""" }
        }
        val recognizer = CascadedVoiceRecognizer(whisper, parser)
        val clip = VoiceUtteranceClip.fromFrames(listOf(PcmAudioFrame(ShortArray(1600), 16000)))
        val ctx = VoiceRecognitionContext(PlaybackContext.Inactive, AudioRoute.Headset(true))

        assertNull(recognizer.recognize(clip, ctx))
    }
}
