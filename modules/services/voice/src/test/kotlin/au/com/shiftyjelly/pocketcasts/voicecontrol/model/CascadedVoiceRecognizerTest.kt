package au.com.shiftyjelly.pocketcasts.voicecontrol.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.WhisperRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.PcmAudioFrame
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.SmolLmIntentParser
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoicePlaybackIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CascadedVoiceRecognizerTest {
    @get:Rule val tempDir = TemporaryFolder()

    @Test
    fun `orchestrates ASR then intent parsing`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val modelManager = ModelManager(context).apply { filesDir = tempDir.root }
        modelManager.whisperModelFile.parentFile!!.mkdirs()
        modelManager.whisperModelFile.writeText("fake model")
        modelManager.smolLmModelFile.parentFile!!.mkdirs()
        modelManager.smolLmModelFile.writeText("fake model")

        val whisper = WhisperRecognizer(modelFile = modelManager.whisperModelFile).apply {
            nativeImpl = { _, _, _ -> "pause" }
        }
        val parser = SmolLmIntentParser(modelFile = modelManager.smolLmModelFile).apply {
            nativeImpl = { _, _ -> """{"intent": "pause"}""" }
        }
        val recognizer = CascadedVoiceRecognizer(whisper, parser, modelManager)
        val clip = VoiceUtteranceClip.fromFrames(listOf(PcmAudioFrame(ShortArray(1600), 16000)))
        val ctx = VoiceRecognitionContext(PlaybackContext.Inactive, AudioRoute.Headset(true))

        assertEquals(VoicePlaybackIntent.Pause, recognizer.recognize(clip, ctx))
    }

    @Test
    fun `returns null when whisper returns empty`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val modelManager = ModelManager(context).apply { filesDir = tempDir.root }
        modelManager.whisperModelFile.parentFile!!.mkdirs()
        modelManager.whisperModelFile.writeText("fake model")
        modelManager.smolLmModelFile.parentFile!!.mkdirs()
        modelManager.smolLmModelFile.writeText("fake model")

        val whisper = WhisperRecognizer(modelFile = modelManager.whisperModelFile).apply {
            nativeImpl = { _, _, _ -> "" }
        }
        val parser = SmolLmIntentParser(modelFile = modelManager.smolLmModelFile).apply {
            nativeImpl = { _, _ -> """{"intent": "none"}""" }
        }
        val recognizer = CascadedVoiceRecognizer(whisper, parser, modelManager)
        val clip = VoiceUtteranceClip.fromFrames(listOf(PcmAudioFrame(ShortArray(1600), 16000)))
        val ctx = VoiceRecognitionContext(PlaybackContext.Inactive, AudioRoute.Headset(true))

        assertNull(recognizer.recognize(clip, ctx))
    }
}
