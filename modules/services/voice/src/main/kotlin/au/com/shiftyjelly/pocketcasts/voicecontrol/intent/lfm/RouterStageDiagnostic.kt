package au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm

/**
 * One bounded, privacy-safe diagnostic per routing request.
 * Never includes transcripts, generated text, raw exception messages, or slot values.
 */
data class RouterStageDiagnostic(
    val modelRelease: String?,
    val quant: String?,
    val inputFormat: String?,
    val sourceLanguage: String?,
    val translationKind: String,
    val classifierLabel: String?,
    val finalOutcome: String,
    val failedStage: String?,
    val reason: String?,
    val totalLatencyMs: Long,
) {
    companion object {
        const val OUTCOME_INTENT = "intent"
        const val OUTCOME_NO_INTENT = "no_intent"

        const val STAGE_BLANK = "blank"
        const val STAGE_NOT_READY = "not_ready"
        const val STAGE_TOKENIZE = "tokenize"
        const val STAGE_CLASSIFY = "classify"
        const val STAGE_NO_MATCH = "no_match"
        const val STAGE_GENERATE = "generate"
        const val STAGE_PARSE_REPAIR = "parse_repair"
        const val STAGE_MAPPER_DIALOG = "mapper_dialog"
        const val STAGE_EXCEPTION = "exception"
        const val STAGE_UNSUPPORTED_FORMAT = "unsupported_format"

        const val REASON_BLANK_TRANSCRIPT = "blank_transcript"
        const val REASON_MODEL_NOT_LOADED = "model_not_loaded"
        const val REASON_TOKENIZE_FAILED = "tokenize_failed"
        const val REASON_CLASSIFY_FAILED = "classify_failed"
        const val REASON_NO_MATCH = "no_match"
        const val REASON_GENERATE_FAILED = "generate_failed"
        const val REASON_PARSE_OR_REPAIR_FAILED = "parse_or_repair_failed"
        const val REASON_MAPPER_OR_DIALOG_FAILED = "mapper_or_dialog_failed"
        const val REASON_INFERENCE_EXCEPTION = "inference_exception"
        const val REASON_UNSUPPORTED_INPUT_FORMAT = "unsupported_input_format"
    }
}
