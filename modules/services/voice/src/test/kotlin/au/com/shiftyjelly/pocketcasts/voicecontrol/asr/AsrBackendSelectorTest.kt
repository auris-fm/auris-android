package au.com.shiftyjelly.pocketcasts.voicecontrol.asr

import dagger.Lazy
import java.util.Locale
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AsrBackendSelectorTest {

    private lateinit var senseVoiceBackend: SenseVoiceBackend
    private lateinit var canaryFlashBackend: CanaryFlashBackend
    private lateinit var selector: AsrBackendSelector

    private fun <T> lazyOf(value: T): Lazy<T> = object : Lazy<T> {
        override fun get(): T = value
    }

    private fun buildSelector(locale: Locale = locale("en")): AsrBackendSelector = AsrBackendSelector(
        senseVoiceBackend = lazyOf(senseVoiceBackend),
        canaryFlashBackend = lazyOf(canaryFlashBackend),
        currentLocale = { locale },
    )

    private fun locale(tag: String): Locale = Locale.forLanguageTag(tag)

    @Before
    fun setUp() {
        senseVoiceBackend = SenseVoiceBackend()
        canaryFlashBackend = CanaryFlashBackend(currentLocale = { Locale.getDefault() })
        selector = buildSelector()
    }

    @Test
    fun `english locale selects senseVoiceBackend fast path`() {
        assertSame(senseVoiceBackend, selector.select())
    }

    @Test
    fun `cjk locale selects senseVoiceBackend`() {
        for (lang in listOf("zh", "ja", "ko", "yue")) {
            selector = buildSelector(locale(lang))
            assertSame(senseVoiceBackend, selector.select())
        }
    }

    @Test
    fun `german locale selects canaryFlashBackend`() {
        selector = buildSelector(locale("de"))
        assertSame(canaryFlashBackend, selector.select())
    }

    @Test
    fun `french and spanish locales select canaryFlashBackend`() {
        for (lang in listOf("fr", "es")) {
            selector = buildSelector(locale(lang))
            assertSame(canaryFlashBackend, selector.select())
        }
    }

    @Test
    fun `unsupported locale returns null without selecting Whisper`() {
        for (lang in listOf("ar", "ru", "pt")) {
            selector = buildSelector(locale(lang))
            assertNull("Locale $lang should surface unsupported", selector.select())
        }
    }

    @Test
    fun `product matrix never selects WhisperCppBackend`() {
        for (lang in listOf("en", "zh", "de", "ar", "ru")) {
            selector = buildSelector(locale(lang))
            val backend = selector.select()
            if (backend != null) {
                assertTrue(
                    "Locale $lang must not select Whisper",
                    backend !is WhisperCppBackend,
                )
            }
        }
    }

    @Test
    fun `manual override to canary-flash selects canaryFlashBackend`() {
        selector.manualOverride = "canary-flash"
        assertSame(canaryFlashBackend, selector.select())
    }

    @Test
    fun `manual override to sensevoice selects senseVoiceBackend`() {
        selector.manualOverride = "sensevoice"
        assertSame(senseVoiceBackend, selector.select())
    }

    @Test
    fun `manual override to whisper-cpp is rejected`() {
        selector.manualOverride = "whisper-cpp"
        assertThrows("Unknown backend override", IllegalStateException::class.java) {
            selector.select()
        }
    }

    @Test
    fun `manual override to unknown backend throws error`() {
        selector.manualOverride = "unknown"
        assertThrows("Unknown backend override", IllegalStateException::class.java) {
            selector.select()
        }
    }
}
