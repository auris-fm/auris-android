package au.com.shiftyjelly.pocketcasts.voicecontrol.feedback

import android.content.Context
import android.content.res.Configuration
import au.com.shiftyjelly.pocketcasts.voicecontrol.R
import java.util.Locale

class SpokenTemplateResolver(
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
    constructor(context: Context) : this(loadTemplatesFromResources(context), context = context)

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
        val lang = language?.trim()?.substringBefore('-')?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: return resolve(intentKey)
        languageTemplates[lang]?.get(intentKey)?.takeIf { it.isNotBlank() }?.let { return it }
        val ctx = context
        if (ctx != null && !lang.equals(Locale.ENGLISH.language, ignoreCase = true)) {
            val localized = runCatching {
                val config = Configuration(ctx.resources.configuration)
                config.setLocale(Locale.forLanguageTag(lang))
                SpokenTemplateResolver(loadTemplatesFromResources(ctx.createConfigurationContext(config)))
                    .resolve(intentKey)
            }.getOrNull()
            if (!localized.isNullOrBlank()) return localized
        }
        return resolve(intentKey)
    }

    private companion object {
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
