package au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm

/**
 * Which routing outcomes may be handed to the cloud service.
 *
 * The distinction is deliberate rejection versus failure to decide:
 *
 * - **A failure to decide** — the model path produced no usable call at all
 *   (`tokenize_failed`, `classify_failed`, `generate_failed`,
 *   `parse_or_repair_failed`, `mapper_or_dialog_failed`, `inference_exception`)
 *   — escalates. Nothing is overridden by asking the service, because the client
 *   never formed an answer.
 * - **`no_match`** escalates too: it is a judgment that the utterance was not
 *   addressed to us, and an unclear question is exactly the case where that
 *   judgment is the thing the user wants a second opinion on.
 * - **Nothing to send, or nothing that could answer, does not escalate.**
 *   `blank_transcript` would post an empty question. `model_not_loaded` and
 *   `unsupported_input_format` are local capability failures: escalating them
 *   would turn a broken install into cloud traffic, would make "the model never
 *   loaded" indistinguishable from a healthy turn, and would push local commands
 *   ("pause") onto the network.
 *
 * An unknown or missing reason escalates: the client has no answer, and that is
 * the condition being handed off.
 */
internal object CloudEscalationPolicy {
    private val LOCALLY_EXCLUDED = setOf(
        RouterStageDiagnostic.REASON_BLANK_TRANSCRIPT,
        RouterStageDiagnostic.REASON_MODEL_NOT_LOADED,
        RouterStageDiagnostic.REASON_UNSUPPORTED_INPUT_FORMAT,
    )

    fun escalates(reason: String?): Boolean = reason !in LOCALLY_EXCLUDED
}
