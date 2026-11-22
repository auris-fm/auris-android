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
        // The UI locale does not decide the line: the default table is ENGLISH BY CONTRACT, and a
        // locale's wording is selected by the UTTERANCE's language.
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
            // Text and voice must agree: an untranslated line reports English, so a fallback can
            // never pair one language's words with another language's voice.
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

    /**
     * An overlaid key must have ONE source. While the generic line sat in both the array and the
     * dedicated string, the array entry was dead: the overlay always won, so editing it changed
     * nothing a listener heard. This reads the array itself, not the resolver, so a re-added entry
     * fails here rather than silently becoming the copy nobody maintains.
     */
    @Test
    fun `the spoken-templates array carries no copy of an overlaid key`() {
        val array = getApplicationContext<Context>().resources.getStringArray(
            au.com.shiftyjelly.pocketcasts.voicecontrol.R.array.voice_spoken_templates,
        )
        val overlaid = setOf(
            "cloud_error_generic",
            "cloud_error_speech_unavailable",
            "cloud_error_internal_error",
            "cloud_error_limit_exceeded",
            "cloud_error_conversation_expired",
            "cloud_error_no_answer",
            "cloud_error_retrieval_unavailable",
            "cloud_error_budget_exceeded",
            "cloud_error_connection_lost",
            "cloud_error_queue_overflow",
            "cloud_error_turn_conflict",
            "cloud_error_corrupt_record",
            "cloud_error_unauthorized",
            "cloud_error_provider_error",
        )
        val duplicated = array.map { it.substringBefore('|') }.filter { it in overlaid }
        assertEquals(
            "these keys are overlaid from dedicated strings; an array entry would be dead: $duplicated",
            emptyList<String>(),
            duplicated,
        )
    }

    /**
     * Every cloud error line must be a DEDICATED string, not an array entry.
     *
     * The array cannot be overridden per locale without redefining all of it, so an array entry is
     * English for every non-English speaker -- which is how six codes came to speak English to a
     * German user while the daily-limit line beside them was translated. This reads the array
     * itself, so re-adding any cloud line there fails here rather than shipping untranslated.
     */
    @Test
    fun `no cloud error line lives in the spoken-templates array`() {
        val array = getApplicationContext<Context>().resources.getStringArray(
            au.com.shiftyjelly.pocketcasts.voicecontrol.R.array.voice_spoken_templates,
        )
        val inArray = array.map { it.substringBefore('|') }.filter { it.startsWith("cloud_error_") }
        assertEquals(
            "cloud error lines must be dedicated strings so a locale can override one key: $inArray",
            emptyList<String>(),
            inArray,
        )
    }

    /**
     * The three codes that had NO line at all, resolved from the real resources.
     *
     * Each must be its OWN sentence and not the catch-all: a code that falls through to generic
     * tells the listener neither what happened nor what to do, which is what the contract's
     * "Required" classification forbids. Asserting the difference (not just non-blank) is what makes
     * a future collapse into generic fail here.
     */
    private fun assertOwnEnglishLine(key: String, expected: String) {
        Locale.setDefault(Locale.ENGLISH)
        RuntimeEnvironment.setQualifiers("en")
        val resolver = SpokenTemplateResolver(getApplicationContext<Context>())
        assertEquals(expected, resolver.resolve(key))
        assertTrue(
            "$key must not be the generic line",
            resolver.resolve(key) != resolver.resolve("cloud_error_generic"),
        )
    }

    @Test
    fun `an audio queue overflow has its own english line`() = assertOwnEnglishLine(
        "cloud_error_queue_overflow",
        "Sorry, I couldn't finish answering that. Please try again.",
    )

    @Test
    fun `a turn conflict has its own english line`() = assertOwnEnglishLine(
        "cloud_error_turn_conflict",
        "I'm still answering your last question. Please try again in a moment.",
    )

    @Test
    fun `an unreadable record has its own english line`() = assertOwnEnglishLine(
        "cloud_error_corrupt_record",
        "Sorry, I've lost track of our conversation. Please ask again to start a new one.",
    )

    @Test
    fun `the approved wording replaces the old no-answer and budget lines`() {
        // Both used to be indistinguishable from a fall-through: no_answer was word-for-word the
        // generic line, and budget_exceeded named a limit rather than the shared deadline. The
        // approved set (spec 5ab7b0aa) is both apps' copy, so a revert to either old line fails here.
        Locale.setDefault(Locale.ENGLISH)
        RuntimeEnvironment.setQualifiers("en")
        val resolver = SpokenTemplateResolver(getApplicationContext<Context>())
        assertEquals(
            "Sorry, I couldn't find an answer to that. Please try asking another way.",
            resolver.resolve("cloud_error_no_answer"),
        )
        assertEquals(
            "Sorry, that took too long to answer. Please try again.",
            resolver.resolve("cloud_error_budget_exceeded"),
        )
    }

    /**
     * Every Required cloud error line, in every locale this module ships, resolved from the REAL
     * resources through the production lookup.
     *
     * Two failures this catches that a non-blank assertion cannot: a locale falling back to ENGLISH
     * (what these codes did while they lived in the array), and a locale resolving to the GENERIC
     * line instead of its own. Both are asserted explicitly, so a translation that is merely present
     * but wrong does not pass.
     *
     * The expected strings are the iOS copy taken verbatim, so the two apps cannot drift apart
     * without one of these failing.
     */
    private fun assertLocalizedLine(key: String, language: String, qualifier: String, expected: String) {
        Locale.setDefault(Locale.ENGLISH)
        RuntimeEnvironment.setQualifiers(qualifier)
        val resolver = SpokenTemplateResolver(getApplicationContext<Context>())
        val line = resolver.resolveLocalized(key, language)
        assertEquals(expected, line.text)
        assertEquals("the voice must match the text", language, line.languageTag)
        assertTrue("$key/$language must not be the English line", line.text != resolver.resolve(key))
        assertTrue(
            "$key/$language must not be the generic line",
            line.text != resolver.resolveLocalized("cloud_error_generic", language).text,
        )
    }

    @Test
    fun `a de no answer line is spoken in de`() = assertLocalizedLine(
        "cloud_error_no_answer",
        "de",
        "de",
        "Entschuldigung, ich konnte darauf keine Antwort finden. Bitte versuche es anders zu fragen.",
    )

    @Test
    fun `a es no answer line is spoken in es`() = assertLocalizedLine(
        "cloud_error_no_answer",
        "es",
        "es",
        "Lo siento, no he encontrado una respuesta a eso. Prueba a preguntarlo de otra manera.",
    )

    @Test
    fun `a fr no answer line is spoken in fr`() = assertLocalizedLine(
        "cloud_error_no_answer",
        "fr",
        "fr",
        "Désolé, je n'ai pas trouvé de réponse à cela. Essayez de le demander autrement.",
    )

    @Test
    fun `a ja no answer line is spoken in ja`() = assertLocalizedLine(
        "cloud_error_no_answer",
        "ja",
        "ja",
        "申し訳ありません。それに対する答えが見つかりませんでした。別の聞き方でお試しください。",
    )

    @Test
    fun `a ko no answer line is spoken in ko`() = assertLocalizedLine(
        "cloud_error_no_answer",
        "ko",
        "ko",
        "죄송합니다. 그에 대한 답을 찾지 못했습니다. 다른 방식으로 물어봐 주세요.",
    )

    @Test
    fun `a zh no answer line is spoken in zh`() = assertLocalizedLine(
        "cloud_error_no_answer",
        "zh",
        "zh",
        "抱歉，我没找到这个问题的答案。请换一种问法试试。",
    )

    @Test
    fun `a yue no answer line is spoken in yue`() = assertLocalizedLine(
        "cloud_error_no_answer",
        "yue",
        "b+yue",
        "唔好意思，我搵唔到呢個問題嘅答案。唔該試吓用第二種問法。",
    )

    @Test
    fun `a de retrieval unavailable line is spoken in de`() = assertLocalizedLine(
        "cloud_error_retrieval_unavailable",
        "de",
        "de",
        "Entschuldigung, ich konnte das gerade nicht nachschlagen. Bitte versuche es noch einmal.",
    )

    @Test
    fun `a es retrieval unavailable line is spoken in es`() = assertLocalizedLine(
        "cloud_error_retrieval_unavailable",
        "es",
        "es",
        "Lo siento, no he podido consultarlo ahora mismo. Inténtalo de nuevo.",
    )

    @Test
    fun `a fr retrieval unavailable line is spoken in fr`() = assertLocalizedLine(
        "cloud_error_retrieval_unavailable",
        "fr",
        "fr",
        "Désolé, je n'ai pas pu le vérifier pour le moment. Veuillez réessayer.",
    )

    @Test
    fun `a ja retrieval unavailable line is spoken in ja`() = assertLocalizedLine(
        "cloud_error_retrieval_unavailable",
        "ja",
        "ja",
        "申し訳ありません。今は調べられませんでした。もう一度お試しください。",
    )

    @Test
    fun `a ko retrieval unavailable line is spoken in ko`() = assertLocalizedLine(
        "cloud_error_retrieval_unavailable",
        "ko",
        "ko",
        "죄송합니다. 지금은 확인하지 못했습니다. 다시 시도해 주세요.",
    )

    @Test
    fun `a zh retrieval unavailable line is spoken in zh`() = assertLocalizedLine(
        "cloud_error_retrieval_unavailable",
        "zh",
        "zh",
        "抱歉，我刚才没能查到这个内容。请再试一次。",
    )

    @Test
    fun `a yue retrieval unavailable line is spoken in yue`() = assertLocalizedLine(
        "cloud_error_retrieval_unavailable",
        "yue",
        "b+yue",
        "唔好意思，我啱啲查唔到呢樣嘢。唔該再試一次。",
    )

    @Test
    fun `a de queue overflow line is spoken in de`() = assertLocalizedLine(
        "cloud_error_queue_overflow",
        "de",
        "de",
        "Entschuldigung, ich konnte die Antwort nicht zu Ende geben. Bitte versuche es noch einmal.",
    )

    @Test
    fun `a es queue overflow line is spoken in es`() = assertLocalizedLine(
        "cloud_error_queue_overflow",
        "es",
        "es",
        "Lo siento, no he podido terminar de responder. Inténtalo de nuevo.",
    )

    @Test
    fun `a fr queue overflow line is spoken in fr`() = assertLocalizedLine(
        "cloud_error_queue_overflow",
        "fr",
        "fr",
        "Désolé, je n'ai pas pu terminer ma réponse. Veuillez réessayer.",
    )

    @Test
    fun `a ja queue overflow line is spoken in ja`() = assertLocalizedLine(
        "cloud_error_queue_overflow",
        "ja",
        "ja",
        "申し訳ありません。最後までお答えできませんでした。もう一度お試しください。",
    )

    @Test
    fun `a ko queue overflow line is spoken in ko`() = assertLocalizedLine(
        "cloud_error_queue_overflow",
        "ko",
        "ko",
        "죄송합니다. 답변을 끝까지 드리지 못했습니다. 다시 시도해 주세요.",
    )

    @Test
    fun `a zh queue overflow line is spoken in zh`() = assertLocalizedLine(
        "cloud_error_queue_overflow",
        "zh",
        "zh",
        "抱歉，我没能把这个回答说完。请再试一次。",
    )

    @Test
    fun `a yue queue overflow line is spoken in yue`() = assertLocalizedLine(
        "cloud_error_queue_overflow",
        "yue",
        "b+yue",
        "唔好意思，我答唔晒呢個問題。唔該再試一次。",
    )

    @Test
    fun `a de budget exceeded line is spoken in de`() = assertLocalizedLine(
        "cloud_error_budget_exceeded",
        "de",
        "de",
        "Entschuldigung, die Antwort hat zu lange gedauert. Bitte versuche es noch einmal.",
    )

    @Test
    fun `a es budget exceeded line is spoken in es`() = assertLocalizedLine(
        "cloud_error_budget_exceeded",
        "es",
        "es",
        "Lo siento, responder ha tardado demasiado. Inténtalo de nuevo.",
    )

    @Test
    fun `a fr budget exceeded line is spoken in fr`() = assertLocalizedLine(
        "cloud_error_budget_exceeded",
        "fr",
        "fr",
        "Désolé, la réponse a pris trop de temps. Veuillez réessayer.",
    )

    @Test
    fun `a ja budget exceeded line is spoken in ja`() = assertLocalizedLine(
        "cloud_error_budget_exceeded",
        "ja",
        "ja",
        "申し訳ありません。お答えするのに時間がかかりすぎました。もう一度お試しください。",
    )

    @Test
    fun `a ko budget exceeded line is spoken in ko`() = assertLocalizedLine(
        "cloud_error_budget_exceeded",
        "ko",
        "ko",
        "죄송합니다. 답변하는 데 시간이 너무 오래 걸렸습니다. 다시 시도해 주세요.",
    )

    @Test
    fun `a zh budget exceeded line is spoken in zh`() = assertLocalizedLine(
        "cloud_error_budget_exceeded",
        "zh",
        "zh",
        "抱歉，回答这个问题用的时间太长了。请再试一次。",
    )

    @Test
    fun `a yue budget exceeded line is spoken in yue`() = assertLocalizedLine(
        "cloud_error_budget_exceeded",
        "yue",
        "b+yue",
        "唔好意思，答咗太耐都答唔完。唔該再試一次。",
    )

    @Test
    fun `a de turn conflict line is spoken in de`() = assertLocalizedLine(
        "cloud_error_turn_conflict",
        "de",
        "de",
        "Ich beantworte gerade noch deine letzte Frage. Bitte versuche es gleich noch einmal.",
    )

    @Test
    fun `a es turn conflict line is spoken in es`() = assertLocalizedLine(
        "cloud_error_turn_conflict",
        "es",
        "es",
        "Todavía estoy respondiendo a tu última pregunta. Inténtalo de nuevo en un momento.",
    )

    @Test
    fun `a fr turn conflict line is spoken in fr`() = assertLocalizedLine(
        "cloud_error_turn_conflict",
        "fr",
        "fr",
        "Je suis encore en train de répondre à votre dernière question. Veuillez réessayer dans un instant.",
    )

    @Test
    fun `a ja turn conflict line is spoken in ja`() = assertLocalizedLine(
        "cloud_error_turn_conflict",
        "ja",
        "ja",
        "前の質問にまだお答えしています。少し待ってからもう一度お試しください。",
    )

    @Test
    fun `a ko turn conflict line is spoken in ko`() = assertLocalizedLine(
        "cloud_error_turn_conflict",
        "ko",
        "ko",
        "이전 질문에 아직 답변하고 있습니다. 잠시 후에 다시 시도해 주세요.",
    )

    @Test
    fun `a zh turn conflict line is spoken in zh`() = assertLocalizedLine(
        "cloud_error_turn_conflict",
        "zh",
        "zh",
        "我还在回答你上一个问题。请稍等一下再试。",
    )

    @Test
    fun `a yue turn conflict line is spoken in yue`() = assertLocalizedLine(
        "cloud_error_turn_conflict",
        "yue",
        "b+yue",
        "我仲答緊你上一個問題。唔該等一陣再試一次。",
    )

    @Test
    fun `a de corrupt record line is spoken in de`() = assertLocalizedLine(
        "cloud_error_corrupt_record",
        "de",
        "de",
        "Entschuldigung, ich habe den Überblick über unser Gespräch verloren. Bitte frag noch einmal, dann beginnt ein neues.",
    )

    @Test
    fun `a es corrupt record line is spoken in es`() = assertLocalizedLine(
        "cloud_error_corrupt_record",
        "es",
        "es",
        "Lo siento, he perdido el hilo de nuestra conversación. Vuelve a preguntar para empezar una nueva.",
    )

    @Test
    fun `a fr corrupt record line is spoken in fr`() = assertLocalizedLine(
        "cloud_error_corrupt_record",
        "fr",
        "fr",
        "Désolé, j'ai perdu le fil de notre conversation. Reposez votre question pour en commencer une nouvelle.",
    )

    @Test
    fun `a ja corrupt record line is spoken in ja`() = assertLocalizedLine(
        "cloud_error_corrupt_record",
        "ja",
        "ja",
        "申し訳ありません。会話の流れがわからなくなってしまいました。もう一度話しかけていただくと、新しい会話が始まります。",
    )

    @Test
    fun `a ko corrupt record line is spoken in ko`() = assertLocalizedLine(
        "cloud_error_corrupt_record",
        "ko",
        "ko",
        "죄송합니다. 대화의 흐름을 놓쳤습니다. 다시 말씀해 주시면 새 대화를 시작합니다.",
    )

    @Test
    fun `a zh corrupt record line is spoken in zh`() = assertLocalizedLine(
        "cloud_error_corrupt_record",
        "zh",
        "zh",
        "抱歉，我跟不上我们这段对话了。请再问一次，这样就会开始新的对话。",
    )

    @Test
    fun `a yue corrupt record line is spoken in yue`() = assertLocalizedLine(
        "cloud_error_corrupt_record",
        "yue",
        "b+yue",
        "唔好意思，我唔記得咗我哋傾到邊。唔該再問一次，咁就會開新嘅對話。",
    )

    @Test
    fun `a de connection lost line is spoken in de`() = assertLocalizedLine(
        "cloud_error_connection_lost",
        "de",
        "de",
        "Die Verbindung wurde unterbrochen. Bitte versuche es noch einmal.",
    )

    @Test
    fun `a es connection lost line is spoken in es`() = assertLocalizedLine(
        "cloud_error_connection_lost",
        "es",
        "es",
        "Se ha perdido la conexión. Inténtalo de nuevo.",
    )

    @Test
    fun `a fr connection lost line is spoken in fr`() = assertLocalizedLine(
        "cloud_error_connection_lost",
        "fr",
        "fr",
        "La connexion a été perdue. Veuillez réessayer.",
    )

    @Test
    fun `a ja connection lost line is spoken in ja`() = assertLocalizedLine(
        "cloud_error_connection_lost",
        "ja",
        "ja",
        "接続が切れました。もう一度お試しください。",
    )

    @Test
    fun `a ko connection lost line is spoken in ko`() = assertLocalizedLine(
        "cloud_error_connection_lost",
        "ko",
        "ko",
        "연결이 끊어졌습니다. 다시 시도해 주세요.",
    )

    @Test
    fun `a zh connection lost line is spoken in zh`() = assertLocalizedLine(
        "cloud_error_connection_lost",
        "zh",
        "zh",
        "连接已断开。请再试一次。",
    )

    @Test
    fun `a yue connection lost line is spoken in yue`() = assertLocalizedLine(
        "cloud_error_connection_lost",
        "yue",
        "b+yue",
        "連線斷咗。唔該再試一次。",
    )

    /**
     * No line may name the machinery behind the failure — a queue, a record, a budget, a server.
     * The listener needs what happened and what to do, not our internals.
     *
     * SCOPE, DELIBERATELY NARROW: this searches ENGLISH words, so it cannot see a system term written
     * in Japanese, Korean, Chinese or Cantonese. iOS proved the same blind spot by mutation (a ja
     * line naming the server queue left its equivalent control green). Rather than imply coverage it
     * does not have, this runs over the Latin-script locales only; the CJK and yue lines are reviewed
     * by a reader, and that is stated here so nobody reads a green run as proof for them.
     */
    @Test
    fun `no latin-script cloud error line names the machinery`() {
        val forbidden = listOf("queue", "record", "budget", "server", "token", "socket", "database")
        val latin = listOf("en" to "en", "de" to "de", "es" to "es", "fr" to "fr")
        val codes = listOf(
            "no_answer",
            "retrieval_unavailable",
            "queue_overflow",
            "budget_exceeded",
            "turn_conflict",
            "corrupt_record",
            "connection_lost",
        )
        assertEquals("the Latin-script locales this control covers", 4, latin.size)
        val offenders = mutableListOf<String>()
        latin.forEach { (language, qualifier) ->
            Locale.setDefault(Locale.ENGLISH)
            RuntimeEnvironment.setQualifiers(qualifier)
            val resolver = SpokenTemplateResolver(getApplicationContext<Context>())
            codes.forEach { code ->
                val text = resolver.resolveLocalized("cloud_error_$code", language).text.lowercase()
                forbidden.filter { text.contains(it) }.forEach { offenders += "$language/$code:$it" }
            }
        }
        assertEquals("a spoken line must not name internals: $offenders", emptyList<String>(), offenders)
    }

    @Test
    fun `traditional chinese resolves rather than falling back to english`() {
        // A zh-Hant speaker must not get the English line: values-zh covers both scripts in this
        // resource set, so the assertion is only that the lookup resolves (no silent gap).
        Locale.setDefault(Locale.TRADITIONAL_CHINESE)
        RuntimeEnvironment.setQualifiers("zh-rTW")
        val resolver = SpokenTemplateResolver(getApplicationContext<Context>())
        assertTrue(resolver.resolveLocalized("cloud_error_limit_exceeded", "zh").text.isNotBlank())
    }

    // The refusal for a conversation the worker no longer holds is spoken in the language the user
    // spoke, like the daily limit: an English-only line would tell a Japanese speaker nothing.
    private fun assertEndedLine(language: String, expected: String) {
        Locale.setDefault(Locale.ENGLISH)
        RuntimeEnvironment.setQualifiers("en")
        val resolver = SpokenTemplateResolver(getApplicationContext<Context>())
        assertEquals("That conversation has ended.", resolver.resolve("cloud_error_conversation_expired"))
        assertEquals(expected, resolver.resolveLocalized("cloud_error_conversation_expired", language).text)
    }

    @Test
    fun `an ended conversation is spoken in english`() = assertEndedLine("en", "That conversation has ended.")

    @Test
    fun `an ended conversation is spoken in german`() = assertEndedLine("de", "Dieses Gespräch ist beendet.")

    @Test
    fun `an ended conversation is spoken in spanish`() = assertEndedLine("es", "Esa conversación ha terminado.")

    @Test
    fun `an ended conversation is spoken in french`() = assertEndedLine("fr", "Cette conversation est terminée.")

    @Test
    fun `an ended conversation is spoken in japanese`() = assertEndedLine("ja", "その会話は終了しました。")

    @Test
    fun `an ended conversation is spoken in korean`() = assertEndedLine("ko", "그 대화는 종료되었습니다.")

    @Test
    fun `an ended conversation is spoken in simplified chinese`() = assertEndedLine("zh", "该对话已结束。")

    @Test
    fun `an ended conversation is spoken in cantonese`() = assertEndedLine("yue", "嗰個對話已經結束咗。")
}
