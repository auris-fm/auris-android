package au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm

/** What the client does with a routing outcome it cannot act on locally. */
internal enum class CloudEscalation {
    /** Hand the utterance to the service: the client has no answer of its own. */
    DISPATCH,

    /** Nothing to say and nothing to send — a rejection, not a failure. */
    SILENT,

    /** Nothing to send, but the user should hear that the turn happened. */
    SPEAK_ERROR,
}

/**
 * Which routing outcomes go where.
 *
 * The line is deliberate rejection versus failure to decide:
 *
 * - **A failure to decide dispatches** — every reason that is not a listed local
 *   outcome, which covers the whole model path failing (`tokenize_failed`,
 *   `classify_failed`, `generate_failed`, `parse_or_repair_failed`,
 *   `mapper_or_dialog_failed`, `inference_exception`) and an unknown or missing
 *   reason. Nothing is overridden by asking the service, because the client
 *   never formed an answer; the list below is only the reasons that are *not*
 *   failures, so it cannot drift into a second copy of the vocabulary.
 * - **`no_match` is a decision, not a failure** — the router's own label for a
 *   non-command (ambient speech, podcast bleed, a bare wake word). It stays
 *   local and silent, which is what the label exists for. Widening it to
 *   escalate was tried and measured: a bare wake phrase is `no_match` on every
 *   capture, so it spent the window and errored on the service each time, while
 *   the one question we have observed the client could not route came back as a
 *   *failure* (`mapper_or_dialog_failed`), which still dispatches. That is one
 *   observation, not a guarantee: a real question that receives `no_match` stays
 *   local, and that is the accepted cost of not firing on every wake-only
 *   capture. `docs/specs/voice-intents.md`
 *   states the same contract: "Cloud-assistant questions should select
 *   `cloud_route`, not `no_match`".
 * - **Nothing to send, or nothing that could answer, speaks and stays local:**
 *   `blank_transcript` would post an empty question, and `model_not_loaded` /
 *   `unsupported_input_format` are capability failures — dispatching them would
 *   turn a broken install into cloud traffic and push local commands onto the
 *   network, while silence would hide a real fault from the user.
 */
internal object CloudEscalationPolicy {
    private val SPEAK_LOCALLY = setOf(
        RouterStageDiagnostic.REASON_BLANK_TRANSCRIPT,
        RouterStageDiagnostic.REASON_MODEL_NOT_LOADED,
        RouterStageDiagnostic.REASON_UNSUPPORTED_INPUT_FORMAT,
    )

    fun decide(reason: String?): CloudEscalation = when {
        reason in SPEAK_LOCALLY -> CloudEscalation.SPEAK_ERROR
        reason == RouterStageDiagnostic.REASON_NO_MATCH -> CloudEscalation.SILENT
        else -> CloudEscalation.DISPATCH
    }
}
