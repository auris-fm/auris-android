package au.com.shiftyjelly.pocketcasts.voicecontrol.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleMlKitTranslatorTest {

    private val translator = GoogleMlKitTranslator()

    @Test
    fun `maps yue to zh`() {
        assertEquals("zh", translator.mapLanguage("yue"))
        assertEquals("zh", translator.mapLanguage("ZH"))
    }

    @Test
    fun `maps common CJK codes`() {
        assertEquals("ja", translator.mapLanguage("ja"))
        assertEquals("ko", translator.mapLanguage("ko"))
    }

    @Test
    fun `returns null for unsupported codes`() {
        assertNull(translator.mapLanguage("xx"))
        assertNull(translator.mapLanguage(""))
    }
}
