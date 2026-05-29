package au.com.shiftyjelly.pocketcasts.voicecontrol.intent

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceIntentTest {
    @Test
    fun `seek relative stores milliseconds`() {
        val intent = VoiceIntent.SeekRelative(deltaMs = 30_000)

        assertEquals(30_000, intent.deltaMs)
    }

    @Test
    fun `chapter title trims query`() {
        val intent = VoiceIntent.ChapterByTitle(query = "  interview  ")

        assertEquals("interview", intent.normalizedQuery)
    }
}
