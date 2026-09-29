package au.com.shiftyjelly.pocketcasts.voicecontrol.feedback

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule both the cloud sink and the pipeline rely on: a client-owned line is spoken only in the
 * language it is written in, and everywhere else the caller falls back to the error earcon.
 */
class SpokenLineTest {

    private val resolver = SpokenTemplateResolver(mapOf("some.line" to "A spoken line"))

    @Test
    fun `the source locale gets the line`() {
        assertEquals("A spoken line", SpokenLine.forKey("some.line", resolver, Locale.ENGLISH))
    }

    @Test
    fun `another locale gets nothing rather than English`() {
        assertEquals("", SpokenLine.forKey("some.line", resolver, Locale.KOREAN))
    }

    @Test
    fun `a key with no template is empty`() {
        assertEquals("", SpokenLine.forKey("not.a.key", resolver, Locale.ENGLISH))
    }
}
