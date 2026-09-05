package au.com.shiftyjelly.pocketcasts.voicecontrol.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanaryFlashBackendTest {
    @Test
    fun translatedTranscriptKeepsConfiguredSourceLanguageNotEn() {
        val result = CanaryFlashBackend.asrResultForTranslatedText(
            text = "Go back three minutes.",
            sourceLanguage = "de",
        )
        assertEquals("Go back three minutes.", result.text)
        assertEquals("de", result.detectedLanguage)
    }

    @Test
    fun blankTranscriptStillReportsConfiguredSourceLanguage() {
        val result = CanaryFlashBackend.asrResultForTranslatedText(
            text = "   ",
            sourceLanguage = "fr",
        )
        assertEquals("", result.text)
        assertEquals("fr", result.detectedLanguage)
    }

    @Test
    fun englishSourceLocaleStillReportsEnNotRelabeled() {
        // en→en still preserves configured source; engine uses backend kind via canTranslate.
        val result = CanaryFlashBackend.asrResultForTranslatedText(
            text = "pause",
            sourceLanguage = "en",
        )
        assertEquals("pause", result.text)
        assertEquals("en", result.detectedLanguage)
        assertNull(result.tokens)
    }
}
