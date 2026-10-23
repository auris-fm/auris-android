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
            // The daily-limit line lives OUTSIDE the array as its own string resource, so each
            // values-* locale overrides this one key without redefining the whole array. The
            // overlay wins over any array entry: a locale's translation must be spoken, and a
            // missing locale entry falls through to the default values/ line, never to generic.
            templates[KEY_DAILY_LIMIT] = resources.getString(R.string.voice_cloud_error_limit_exceeded)
            return templates
        }

        /** Mirrors the sink's `cloud_error_<code>` construction for the one overlaid key. */
        private const val KEY_DAILY_LIMIT = "cloud_error_limit_exceeded"
    }
}
