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

/**
 * REAL RESOURCE CONTROLS (reviewer 8004f444 / spec ruling 7c7575f6): these run against the
 * module's actual locale resources through Robolectric application contexts, so a UI-locale
 * leak into the fallback table fails here rather than hiding behind custom test maps.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class SpokenLineResourceContextTest {

    private val zhLine = "你已达到 Auris 的每日免费额度，请明天再试。"
    private val enLine = "You have reached your Auris free daily limit. Please try again tomorrow."

    private fun contextWithUiLocale(language: String): android.content.Context {
        val app = org.robolectric.RuntimeEnvironment.getApplication() as android.content.Context
        val config = android.content.res.Configuration(app.resources.configuration)
        config.setLocale(java.util.Locale.forLanguageTag(language))
        return app.createConfigurationContext(config)
    }

    @Test
    fun `an explicit English utterance on a Chinese UI resolves English text and English voice`() {
        val resolver = SpokenTemplateResolver(contextWithUiLocale("zh"))
        val resolved = resolver.resolveLocalized("cloud_error_limit_exceeded", "en")
        assertEquals(enLine, resolved.text)
        assertEquals("en", resolved.languageTag)
    }

    @Test
    fun `a Chinese utterance on an English UI resolves the Chinese resource line with a Chinese voice`() {
        val resolver = SpokenTemplateResolver(contextWithUiLocale("en"))
        val resolved = resolver.resolveLocalized("cloud_error_limit_exceeded", "zh")
        assertEquals(zhLine, resolved.text)
        assertEquals("zh", resolved.languageTag)
    }

    @Test
    fun `an unsupported utterance language falls back to English text AND English voice`() {
        val resolver = SpokenTemplateResolver(contextWithUiLocale("zh"))
        val resolved = resolver.resolveLocalized("cloud_error_limit_exceeded", "xx")
        assertEquals(enLine, resolved.text)
        assertEquals("en", resolved.languageTag)
    }

    @Test
    fun `a missing utterance language falls back to English text AND English voice`() {
        val resolver = SpokenTemplateResolver(contextWithUiLocale("zh"))
        val resolved = resolver.resolveLocalized("cloud_error_limit_exceeded", null)
        assertEquals(enLine, resolved.text)
        assertEquals("en", resolved.languageTag)
    }
}
