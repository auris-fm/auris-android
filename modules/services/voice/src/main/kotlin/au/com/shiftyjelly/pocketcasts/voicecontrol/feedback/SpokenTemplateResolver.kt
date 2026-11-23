package au.com.shiftyjelly.pocketcasts.voicecontrol.feedback

import android.content.Context
import android.content.res.Configuration
import au.com.shiftyjelly.pocketcasts.voicecontrol.R
import java.util.Locale

/** One resolved line: the text and the language tag the TTS voice must match. */
data class ResolvedSpokenLine(val text: String, val languageTag: String)

class SpokenTemplateResolver(
    /**
     * The DEFAULT (fallback) table. In production this is loaded from an EXPLICITLY ENGLISH
     * resource context (see the secondary constructor): an English utterance on a Chinese UI,
     * and any unknown/unsupported language, must resolve English text — never UI-derived text.
     */
    private val templates: Map<String, String>,
    /**
     * Per-language template tables, keyed by lowercase ISO language ("zh", "ja", "yue", ...).
     * Production loads these from the app's locale resources at runtime (see [context]);
     * tests supply them directly. Lookup order: utterance language -> default line.
     */
    private val languageTemplates: Map<String, Map<String, String>> = emptyMap(),
    /** Retained so a language missing from [languageTemplates] can still resolve from resources. */
    private val context: Context? = null,
) {
    constructor(context: Context) : this(
        // The fallback table is ENGLISH BY CONTRACT, independent of the UI locale the given
        // context carries: resolve it through an English-forced configuration.
        loadTemplatesFromResources(englishContext(context)),
        context = context,
    )

    /**
     * Resolves a line AND the language tag its TTS voice must use: the utterance's language when
     * a line exists for it, else "en" with the default English line. The two always match, so a
     * fallback can never pair one language's text with another's voice (spec ruling 7c7575f6).
     */
    fun resolveLocalized(intentKey: String, language: String?): ResolvedSpokenLine {
        val lang = language?.trim()?.substringBefore('-')?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: return ResolvedSpokenLine(resolve(intentKey), "en")
        languageTemplates[lang]?.get(intentKey)?.takeIf { it.isNotBlank() }?.let {
            return ResolvedSpokenLine(it, lang)
        }
        val defaultText = resolve(intentKey)
        val ctx = context
        if (ctx != null && !lang.equals(Locale.ENGLISH.language, ignoreCase = true)) {
            val localized = runCatching {
                SpokenTemplateResolver(loadTemplatesFromResources(localizedContext(ctx, lang)))
                    .resolve(intentKey)
            }.getOrNull()
            // A resource lookup for an UNSUPPORTED language still returns the base (English)
            // string. When the localized lookup produced exactly the default text, this build
            // has no real translation — report English so the text and the TTS voice stay
            // coherent instead of speaking English through an unsupported-language voice.
            if (!localized.isNullOrBlank() && localized != defaultText) {
                return ResolvedSpokenLine(localized, lang)
            }
        }
        return ResolvedSpokenLine(defaultText, "en")
    }

    fun resolve(intentKey: String, params: Map<String, String> = emptyMap()): String {
        val template = templates[intentKey] ?: return ""
        return params.entries.fold(template) { acc, (key, value) ->
            acc.replace("{$key}", value)
        }
    }

    /**
     * Resolves a line for the UTTERANCE's language (spec 54287f13): the refusal is spoken in the
     * language the user spoke, never the device UI locale. Order: an explicit per-language table,
     * then the app's locale resources for that language, then the default line. A language this
     * build does not carry falls back to the default (English) line — never to silence.
     */
    fun resolveForLanguage(intentKey: String, language: String?): String {
        return resolveLocalized(intentKey, language).text
    }

    private companion object {
        /** The given context with its locale FORCED to English, so UI locale cannot leak into the fallback table. */
        fun englishContext(context: Context): Context = localizedContext(context, "en")

        fun localizedContext(context: Context, language: String): Context {
            val config = Configuration(context.resources.configuration)
            config.setLocale(Locale.forLanguageTag(language))
            return context.createConfigurationContext(config)
        }

        fun loadTemplatesFromResources(context: Context): Map<String, String> {
            val resources = context.resources
            val array = resources.getStringArray(R.array.voice_spoken_templates)
            val templates = array.associate { entry ->
                val parts = entry.split("|", limit = 2)
                parts[0] to parts[1]
            }.toMutableMap()
            // The daily-limit and expired-conversation lines live OUTSIDE the array as their own
            // string resources, so each values-* locale overrides those keys without redefining the
            // whole array. The overlay wins over any array entry: a locale's translation must be
            // spoken, and a missing locale entry falls through to the default values/ line, never to
            // generic.
            templates[KEY_DAILY_LIMIT] = resources.getString(R.string.voice_cloud_error_limit_exceeded)
            templates[KEY_CONVERSATION_EXPIRED] = resources.getString(R.string.voice_cloud_error_conversation_expired)
            // Synthesis failure and internal failure share one apology: the user cannot act on the
            // difference, and two wordings for one experience would only sound inconsistent.
            val answerUnavailable = resources.getString(R.string.voice_cloud_error_answer_unavailable)
            templates[KEY_SPEECH_UNAVAILABLE] = answerUnavailable
            templates[KEY_INTERNAL_ERROR] = answerUnavailable
            // The catch-all is overlaid too. Left in the array alone it resolved to English for
            // every locale, because no values-* redefines the array.
            templates[KEY_GENERIC] = resources.getString(R.string.voice_cloud_error_generic)
            // EVERY cloud error line is overlaid from its own string resource, for the same reason:
            // an array entry cannot be overridden per locale without redefining the whole array, so
            // these codes spoke English to every non-English speaker while the lines above them were
            // translated. One entry per code, so adding a code without a resource fails to compile
            // rather than silently resolving to the generic line.
            CLOUD_ERROR_LINES.forEach { (key, resId) -> templates[key] = resources.getString(resId) }
            return templates
        }

        /**
         * The code-specific lines, keyed exactly as the sink builds them (`cloud_error_<code>`).
         *
         * A table rather than a loop over the codes: each line is its own resource with its own
         * wording, and two codes deliberately SHARE one string (speech_unavailable/internal_error),
         * so a mechanical `voice_cloud_error_<code>` lookup would need a resource per synonym and
         * would hide that sharing. Listing them keeps the pairing visible.
         *
         * Only codes the contract classifies as Required appear here. A Generic code -- among them
         * unauthorized and provider_error -- owes the listener the localized generic line, so it is
         * deliberately absent and falls through to it. Giving one its own English-only string would
         * be a regression: it would speak English to every non-English speaker.
         */
        private val CLOUD_ERROR_LINES: List<Pair<String, Int>> = listOf(
            KEY_NO_ANSWER to R.string.voice_cloud_error_no_answer,
            KEY_RETRIEVAL_UNAVAILABLE to R.string.voice_cloud_error_retrieval_unavailable,
            KEY_BUDGET_EXCEEDED to R.string.voice_cloud_error_budget_exceeded,
            KEY_CONNECTION_LOST to R.string.voice_cloud_error_connection_lost,
            KEY_QUEUE_OVERFLOW to R.string.voice_cloud_error_queue_overflow,
            KEY_TURN_CONFLICT to R.string.voice_cloud_error_turn_conflict,
            KEY_CORRUPT_RECORD to R.string.voice_cloud_error_corrupt_record,
        )

        /** Mirrors the sink's `cloud_error_<code>` construction for the overlaid keys. */
        private const val KEY_NO_ANSWER = "cloud_error_no_answer"
        private const val KEY_RETRIEVAL_UNAVAILABLE = "cloud_error_retrieval_unavailable"
        private const val KEY_BUDGET_EXCEEDED = "cloud_error_budget_exceeded"
        private const val KEY_CONNECTION_LOST = "cloud_error_connection_lost"
        private const val KEY_QUEUE_OVERFLOW = "cloud_error_queue_overflow"
        private const val KEY_TURN_CONFLICT = "cloud_error_turn_conflict"
        private const val KEY_CORRUPT_RECORD = "cloud_error_corrupt_record"
        private const val KEY_DAILY_LIMIT = "cloud_error_limit_exceeded"
        private const val KEY_CONVERSATION_EXPIRED = "cloud_error_conversation_expired"
        private const val KEY_SPEECH_UNAVAILABLE = "cloud_error_speech_unavailable"
        private const val KEY_INTERNAL_ERROR = "cloud_error_internal_error"
        private const val KEY_GENERIC = "cloud_error_generic"
    }
}
