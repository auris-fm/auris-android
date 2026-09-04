package au.com.shiftyjelly.pocketcasts.voicecontrol.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SenseVoiceLanguageDetectionTest {

    @Test
    fun `prefers structured lang field over text tag`() {
        assertEquals(
            "zh",
            SenseVoiceBackend.resolveDetectedLanguage("zh", "<|ja|>こんにちは"),
        )
    }

    @Test
    fun `falls back to text tag when structured lang is blank`() {
        assertEquals(
            "zh",
            SenseVoiceBackend.resolveDetectedLanguage("  ", "<|zh|>你好"),
        )
        assertEquals(
            "ja",
            SenseVoiceBackend.resolveDetectedLanguage(null, "<|ja|>こんにちは"),
        )
    }

    @Test
    fun `returns null when neither field nor tag is present`() {
        assertNull(SenseVoiceBackend.resolveDetectedLanguage("", "你好"))
        assertNull(SenseVoiceBackend.resolveDetectedLanguage(null, "hello"))
    }
}
