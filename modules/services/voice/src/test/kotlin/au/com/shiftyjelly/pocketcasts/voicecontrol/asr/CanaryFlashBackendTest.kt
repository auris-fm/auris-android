package au.com.shiftyjelly.pocketcasts.voicecontrol.asr

import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
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

    @Test
    fun loadedDeSessionKeepsDeLabelAfterLocaleChangesToFr() {
        val locale = AtomicReference(Locale.GERMAN)
        val backend = CanaryFlashBackend(currentLocale = { locale.get() })
        backend.bindLoadedSourceLanguageForTest("de")

        locale.set(Locale.FRENCH)

        assertEquals("de", backend.loadedSourceLanguage())
        val result = CanaryFlashBackend.resultForDecodedText(
            text = "Go back to 3 minutes.",
            loadedSourceLanguage = backend.loadedSourceLanguage()!!,
        )
        assertEquals("de", result.detectedLanguage)
        assertEquals("fr", locale.get().language)
    }

    @Test
    fun releaseClearsLoadedSourceLanguage() {
        val backend = CanaryFlashBackend(currentLocale = { Locale.GERMAN })
        backend.bindLoadedSourceLanguageForTest("de")
        assertEquals("de", backend.loadedSourceLanguage())

        backend.release()

        assertNull(backend.loadedSourceLanguage())
    }
}
