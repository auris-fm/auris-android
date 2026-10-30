package au.com.shiftyjelly.pocketcasts.voicecontrol.feedback

internal object SpokenLine {
    /**
     * Resolves a spoken line for the UTTERANCE's language, not the app UI locale: the user
     * hears the refusal in the language they spoke. Falls back to the default (English) line
     * when the language is unknown or this build has no translation for it.
     */
    fun forKey(key: String, resolver: SpokenTemplateResolver, language: String?): String {
        return resolver.resolveForLanguage(key, language)
    }
}
