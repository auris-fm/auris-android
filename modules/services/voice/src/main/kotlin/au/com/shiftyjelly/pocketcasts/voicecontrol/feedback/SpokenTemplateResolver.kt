package au.com.shiftyjelly.pocketcasts.voicecontrol.feedback

import android.content.Context
import au.com.shiftyjelly.pocketcasts.voicecontrol.R

class SpokenTemplateResolver(
    private val templates: Map<String, String>,
) {
    constructor(context: Context) : this(loadTemplatesFromResources(context))

    fun resolve(intentKey: String, params: Map<String, String> = emptyMap()): String {
        val template = templates[intentKey] ?: return ""
        return params.entries.fold(template) { acc, (key, value) ->
            acc.replace("{$key}", value)
        }
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
