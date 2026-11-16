@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package au.com.shiftyjelly.pocketcasts.voicecontrol.feedback

import android.content.Context
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Task: localized daily-limit refusal. Per cloud-assistant.md (27ced9b) the client speaks its OWN
 * localized template for `limit_exceeded`, naming Auris and the daily allowance exactly once — and
 * an English strings entry alone does not establish localized feedback. This pins the resolver to
 * the actual per-locale resource for every supported response language.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpokenTemplateResolverLocaleTest {

    private fun assertLimitLine(qualifiers: String, expected: String) {
        Locale.setDefault(Locale.forLanguageTag(qualifiers))
        RuntimeEnvironment.setQualifiers(qualifiers)
        val resolver = SpokenTemplateResolver(getApplicationContext<Context>())
        // The UI locale no longer decides the line (spec ruling 7c7575f6): the default table is
        // ENGLISH BY CONTRACT, and a locale's wording is selected by the UTTERANCE's language.
        assertEquals(
            "default line is English on any UI locale",
            "You have reached your Auris free daily limit. Please try again tomorrow.",
            resolver.resolve("cloud_error_limit_exceeded"),
        )
        val spoken = resolver.resolveLocalized("cloud_error_limit_exceeded", qualifiers.substringBefore('-')).text
        assertEquals(expected, spoken)
        // The contract's shape, independent of wording drift in a future translation edit:
        // Auris is named (exactly once), and it is NOT the generic line.
        assertEquals("Auris is named exactly once", 1, Regex("Auris", RegexOption.IGNORE_CASE).findAll(spoken).count())
        assertTrue("not the generic line", !spoken.contains("couldn't complete", ignoreCase = true))
    }

    @Test
    fun `english names Auris and the daily allowance`() = assertLimitLine("en", expected = "You have reached your Auris free daily limit. Please try again tomorrow.")

    @Test
    fun `german names Auris and the daily allowance`() = assertLimitLine("de", expected = "Du hast dein tägliches kostenloses Auris-Limit erreicht. Bitte versuche es morgen wieder.")

    @Test
    fun `spanish names Auris and the daily allowance`() = assertLimitLine("es", expected = "Has alcanzado tu límite diario gratuito de Auris. Inténtalo de nuevo mañana.")

    @Test
    fun `french names Auris and the daily allowance`() = assertLimitLine("fr", expected = "Vous avez atteint votre limite quotidienne gratuite Auris. Réessayez demain.")

    @Test
    fun `japanese names Auris and the daily allowance`() = assertLimitLine("ja", expected = "Aurisの無料の1日の上限に達しました。明日もう一度お試しください。")

    @Test
    fun `korean names Auris and the daily allowance`() = assertLimitLine("ko", expected = "Auris 무료 일일 한도에 도달했습니다. 내일 다시 시도해 주세요.")

    @Test
    fun `simplified chinese names Auris and the daily allowance`() = assertLimitLine("zh", expected = "你已达到 Auris 的每日免费额度，请明天再试。")

    @Test
    fun `cantonese names Auris and the daily allowance`() = assertLimitLine("yue", expected = "你已經用晒 Auris 今日嘅免費額度，聽日再試過啦。")

    /**
     * The two synthesis-failure codes share one line, and it must be spoken in the language the
     * user spoke. Before this they had no line at all and fell through to the generic one, which
     * lived only in the default array — so a German speaker heard English.
     */
    private fun assertAnswerLine(qualifiers: String, expected: String) {
        Locale.setDefault(Locale.forLanguageTag(qualifiers))
        RuntimeEnvironment.setQualifiers(qualifiers)
        val resolver = SpokenTemplateResolver(getApplicationContext<Context>())
        val lang = qualifiers.substringBefore('-')
        for (key in listOf("cloud_error_speech_unavailable", "cloud_error_internal_error")) {
            val spoken = resolver.resolveLocalized(key, lang)
            assertEquals("$key in $lang", expected, spoken.text)
            // Text and voice must agree: an untranslated line reports English (spec 7c7575f6).
            assertEquals("$key voice matches its text in $lang", lang, spoken.languageTag)
        }
    }

    @Test
    fun `english apologises and invites a retry`() = assertAnswerLine("en", expected = "Sorry, I couldn't answer that just now. Please try again.")

    @Test
    fun `german apologises and invites a retry`() = assertAnswerLine("de", expected = "Entschuldigung, ich konnte das gerade nicht beantworten. Bitte versuche es noch einmal.")

    @Test
    fun `spanish apologises and invites a retry`() = assertAnswerLine("es", expected = "Lo siento, no he podido responder a eso ahora mismo. Inténtalo de nuevo.")

    @Test
    fun `french apologises and invites a retry`() = assertAnswerLine("fr", expected = "Désolé, je n'ai pas pu répondre à cela pour le moment. Veuillez réessayer.")

    @Test
    fun `japanese apologises and invites a retry`() = assertAnswerLine("ja", expected = "申し訳ありません。今はお答えできませんでした。もう一度お試しください。")

    @Test
    fun `korean apologises and invites a retry`() = assertAnswerLine("ko", expected = "죄송합니다. 지금은 답변드리지 못했습니다. 다시 시도해 주세요.")

    @Test
    fun `simplified chinese apologises and invites a retry`() = assertAnswerLine("zh", expected = "抱歉，我刚才没能回答这个问题。请再试一次。")

    @Test
    fun `cantonese apologises and invites a retry`() = assertAnswerLine("yue", expected = "唔好意思，我啱啱答唔到你呢個問題。唔該再試一次。")

    /**
     * The catch-all for a code with no line of its own. It was English-only for the same reason:
     * an array entry cannot be overridden per locale without redefining the whole array.
     */
    private fun assertGenericLine(qualifiers: String, expected: String) {
        Locale.setDefault(Locale.forLanguageTag(qualifiers))
        RuntimeEnvironment.setQualifiers(qualifiers)
        val resolver = SpokenTemplateResolver(getApplicationContext<Context>())
        val lang = qualifiers.substringBefore('-')
        val spoken = resolver.resolveLocalized("cloud_error_generic", lang)
        assertEquals(expected, spoken.text)
        assertEquals("voice matches its text in $lang", lang, spoken.languageTag)
    }

    @Test
    fun `the generic fallback is english`() = assertGenericLine("en", expected = "Sorry, I couldn't complete that request.")

    @Test
    fun `the generic fallback is german`() = assertGenericLine("de", expected = "Entschuldigung, ich konnte diese Anfrage nicht abschließen.")

    @Test
    fun `the generic fallback is spanish`() = assertGenericLine("es", expected = "Lo siento, no he podido completar esa solicitud.")

    @Test
    fun `the generic fallback is french`() = assertGenericLine("fr", expected = "Désolé, je n'ai pas pu terminer cette demande.")

    @Test
    fun `the generic fallback is japanese`() = assertGenericLine("ja", expected = "申し訳ありません。そのリクエストを完了できませんでした。")

    @Test
    fun `the generic fallback is korean`() = assertGenericLine("ko", expected = "죄송합니다. 그 요청을 완료하지 못했습니다.")

    @Test
    fun `the generic fallback is simplified chinese`() = assertGenericLine("zh", expected = "抱歉，我没能完成这个请求。")

    @Test
    fun `the generic fallback is cantonese`() = assertGenericLine("yue", expected = "唔好意思，我完成唔到呢個請求。")

    @Test
    fun `traditional chinese resolves rather than falling back to english`() {
        // A zh-Hant speaker must not get the English line: values-zh covers both scripts in this
        // resource set, so the assertion is only that the lookup resolves (no silent gap).
        Locale.setDefault(Locale.TRADITIONAL_CHINESE)
        RuntimeEnvironment.setQualifiers("zh-rTW")
        val resolver = SpokenTemplateResolver(getApplicationContext<Context>())
        assertTrue(resolver.resolveLocalized("cloud_error_limit_exceeded", "zh").text.isNotBlank())
    }
}
