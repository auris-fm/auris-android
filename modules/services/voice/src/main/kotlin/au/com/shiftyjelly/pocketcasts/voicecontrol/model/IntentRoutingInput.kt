package au.com.shiftyjelly.pocketcasts.voicecontrol.model

/**
 * Immutable ASR/translation → intent-routing envelope.
 * Local `english_v1` matching consumes [routerTranscript] only. Cloud dispatch preserves
 * [sourceTranscript] when available and [sourceLanguage] independently of that translation.
 */
data class IntentRoutingInput(
    val sourceTranscript: String?,
    val sourceLanguage: String?,
    val routerTranscript: String,
    val translationKind: TranslationKind,
) {
    companion object {
        /** Same source/router text with [TranslationKind.NONE] (native English path). */
        fun english(
            transcript: String,
            language: String? = "en",
        ): IntentRoutingInput = IntentRoutingInput(
            sourceTranscript = transcript,
            sourceLanguage = language,
            routerTranscript = transcript,
            translationKind = TranslationKind.NONE,
        )
    }
}

enum class TranslationKind(val wireName: String) {
    NONE("none"),
    PLATFORM("platform"),
    BACKEND("backend"),
}
