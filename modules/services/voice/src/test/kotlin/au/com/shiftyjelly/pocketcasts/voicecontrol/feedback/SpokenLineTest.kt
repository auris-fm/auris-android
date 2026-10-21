package au.com.shiftyjelly.pocketcasts.voicecontrol.feedback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule the cloud sink and the pipeline rely on (spec 54287f13): a line is spoken in the
 * UTTERANCE's language when this build carries a translation for it, and falls back to the
 * default (English) line otherwise — never to silence, and never to the device UI locale.
 */
class SpokenLineTest {

    private val resolver = SpokenTemplateResolver(
        templates = mapOf("some.line" to "A spoken line"),
        languageTemplates = mapOf(
            "ko" to mapOf("some.line" to "외국어 라인"),
            "zh" to mapOf("some.line" to "中文台词"),
        ),
    )

    @Test
    fun `the utterance language gets its line`() {
        assertEquals("외국어 라인", SpokenLine.forKey("some.line", resolver, "ko"))
    }

    @Test
    fun `an unsupported language falls back to the default line rather than silence`() {
        assertEquals("A spoken line", SpokenLine.forKey("some.line", resolver, "fr"))
    }

    @Test
    fun `a null language gets the default line`() {
        assertEquals("A spoken line", SpokenLine.forKey("some.line", resolver, null))
    }

    @Test
    fun `a language tag with region uses its language part`() {
        assertEquals("中文台词", SpokenLine.forKey("some.line", resolver, "zh-CN"))
    }

    @Test
    fun `a key with no template is empty`() {
        assertEquals("", SpokenLine.forKey("not.a.key", resolver, "ko"))
    }
}
