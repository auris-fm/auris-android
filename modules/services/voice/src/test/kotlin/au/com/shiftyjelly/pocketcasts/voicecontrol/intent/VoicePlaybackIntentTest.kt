package au.com.shiftyjelly.pocketcasts.voicecontrol.intent

import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePlaybackIntentTest {
    @Test
    fun `seek relative stores milliseconds`() {
        val intent = VoicePlaybackIntent.SeekRelative(deltaMs = 30_000)

        assertEquals(30_000, intent.deltaMs)
    }

    @Test
    fun `chapter title trims query`() {
        val intent = VoicePlaybackIntent.ChapterByTitle(query = "  interview  ")

        assertEquals("interview", intent.normalizedQuery)
    }
}
