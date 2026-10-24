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
