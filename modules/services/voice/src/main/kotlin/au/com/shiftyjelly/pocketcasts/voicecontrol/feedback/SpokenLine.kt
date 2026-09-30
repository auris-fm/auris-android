package au.com.shiftyjelly.pocketcasts.voicecontrol.feedback

import java.util.Locale

/**
 * A client-owned line, spoken only in the user's own language.
 *
 * The templates live in `res/values` (the source locale) and Android falls back to those for every
 * locale, so resolving one under a non-English locale would speak English. This module ships no
 * language-qualified resource directory at all (translations live in the localization module, and
 * nothing below is wired into it), so the guard is a durable behaviour rather than a temporary
 * shim: only the source locale speaks, and every other locale gets the error earcon instead of a
 * foreign sentence. If a `values-<lang>` set is ever added here, this condition is what has to
 * change to per-locale presence.
 *
 * Shared by the cloud sink and the pipeline, so both refuse to speak a foreign sentence alike
 * instead of each carrying their own copy of the rule.
 */
internal object SpokenLine {
    fun forKey(key: String, resolver: SpokenTemplateResolver, locale: Locale): String {
        if (!locale.language.equals(Locale.ENGLISH.language, ignoreCase = true)) return ""
        return resolver.resolve(key)
    }
}
