package au.com.shiftyjelly.pocketcasts.voicecontrol.model

/**
 * Immutable ASR/translation → intent-routing envelope.
 * Current `english_v1` releases consume [routerTranscript] only; source fields
 * must still survive unchanged for later format selection.
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
