package au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm

/** What the client does with a routing outcome it cannot act on locally. */
internal enum class CloudEscalation {
    /** Hand the utterance to the service: the client has no answer of its own. */
    DISPATCH,

    /** Nothing to say and nothing to send — a rejection, not a failure. */
    SILENT,

    /** Nothing to send and no line to speak, but a tone should say we heard something. */
    EARCON,

    /** Nothing to send, and the user should hear that we did not catch what they said. */
    SPEAK_UNROUTED,

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
 *   non-command. It stays local, and what the user hears depends on whether they
 *   were addressing us: a wake-*Detected* segment gets a short line, while a NotDetected
 *   one keeps the error earcon. That includes a follow-up after the wake word, since only
 *   the segment that physically contained the wake is Detected — words would be the wrong
 *   answer there, and the tone is the honest signal. Escalating it was tried and
 *   measured and is not what happens here: a bare wake phrase is `no_match` on
 *   every capture, so it spent the window and errored on the service each time,
 *   while the one question we have observed the client could not route came back
 *   as a *failure* (`mapper_or_dialog_failed`), which still dispatches. A
 *   wake-word-only capture never reaches this decision at all: the engine drops it
 *   on the detector's own timing — the completion band covering the speech — and not
 *   because the transcript is empty. Silence for it needs nothing here.
 *   `docs/specs/voice-intents.md` states the contract.
 * - **Nothing to send, or nothing that could answer, speaks and stays local:**
 *   `blank_transcript` would post an empty question, and `model_not_loaded` /
 *   `unsupported_input_format` are capability failures — dispatching them would
 *   turn a broken install into cloud traffic and push local commands onto the
 *   network, while silence would hide a real fault from the user.
 */
internal object CloudEscalationPolicy {
    private val SPEAK_LOCALLY = setOf(
        RouterStageDiagnostic.REASON_MODEL_NOT_LOADED,
        RouterStageDiagnostic.REASON_UNSUPPORTED_INPUT_FORMAT,
    )

    /**
     * @param addressed whether this segment's own wake detection was positive — the user speaking
     * to us rather than the microphone catching the room. It changes the `no_match` outcome and the
     * defence-only `blank_transcript` one.
     */
    fun decide(reason: String?, addressed: Boolean = false): CloudEscalation = when {
        // Unroutable. Per core's voice-intents.md the short line is for a wake-*Detected* segment;
        // a NotDetected one — a grace-period capture from the room — keeps the earcon, because
        // answering the room is worse than a tone.
        reason == RouterStageDiagnostic.REASON_NO_MATCH ->
            if (addressed) CloudEscalation.SPEAK_UNROUTED else CloudEscalation.EARCON

        // Defence only: production drops a blank transcript before routing, so this arm is not
        // reached by the engine today. Kept so the policy is total over the reasons it is given.
        reason == RouterStageDiagnostic.REASON_BLANK_TRANSCRIPT ->
            if (addressed) CloudEscalation.SILENT else CloudEscalation.SPEAK_ERROR

        reason in SPEAK_LOCALLY -> CloudEscalation.SPEAK_ERROR

        else -> CloudEscalation.DISPATCH
    }
}
