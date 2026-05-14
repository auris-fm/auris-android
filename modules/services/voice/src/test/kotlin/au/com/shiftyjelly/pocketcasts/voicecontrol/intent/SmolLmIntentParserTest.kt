package au.com.shiftyjelly.pocketcasts.voicecontrol.intent

import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognitionContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmolLmIntentParserTest {

    private val ctx = VoiceRecognitionContext(
        playbackContext = PlaybackContext.Inactive,
        audioRoute = AudioRoute.Headset(hasMicrophone = true),
    )

    @Test
    fun `parses next_episode intent`() {
        val parser = SmolLmIntentParser(
            modelFile = java.io.File("/nonexistent"),
        ).apply {
            nativeImpl = { _, prompt ->
                if (prompt.contains("next episode")) """{"intent": "next_episode"}""" else """{"intent": "none"}"""
            }
        }
        assertEquals(VoicePlaybackIntent.NextEpisode, parser.parseIntentSync("go to the next episode", ctx))
    }

    @Test
    fun `parses pause intent`() {
        val parser = SmolLmIntentParser(
            modelFile = java.io.File("/nonexistent"),
        ).apply {
            nativeImpl = { _, prompt ->
                if (prompt.contains("pause")) """{"intent": "pause"}""" else """{"intent": "none"}"""
            }
        }
        assertEquals(VoicePlaybackIntent.Pause, parser.parseIntentSync("pause the podcast", ctx))
    }

    @Test
    fun `parses seek_relative intent`() {
        val parser = SmolLmIntentParser(
            modelFile = java.io.File("/nonexistent"),
        ).apply {
            nativeImpl = { _, prompt ->
                if (prompt.contains("30 seconds")) """{"intent": "seek_relative", "delta_seconds": 30}""" else """{"intent": "none"}"""
            }
        }
        assertEquals(VoicePlaybackIntent.SeekRelative(30000), parser.parseIntentSync("skip forward 30 seconds", ctx))
    }

    @Test
    fun `returns null for none intent`() {
        val parser = SmolLmIntentParser(
            modelFile = java.io.File("/nonexistent"),
        ).apply {
            nativeImpl = { _, _ -> """{"intent": "none"}""" }
        }
        assertNull(parser.parseIntentSync("what is the weather", ctx))
    }

    @Test
    fun `returns null for invalid JSON`() {
        val parser = SmolLmIntentParser(
            modelFile = java.io.File("/nonexistent"),
        ).apply {
            nativeImpl = { _, _ -> "not json" }
        }
        assertNull(parser.parseIntentSync("something", ctx))
    }

    @Test
    fun `returns null for empty transcript`() {
        val parser = SmolLmIntentParser(
            modelFile = java.io.File("/nonexistent"),
        ).apply {
            nativeImpl = { _, _ -> """{"intent": "none"}""" }
        }
        assertNull(parser.parseIntentSync("", ctx))
    }
}
