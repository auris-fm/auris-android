package au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which routing outcomes are handed to the cloud service (contract, not an implementation detail). */
class CloudEscalationPolicyTest {

    @Test
    fun `every failure of the model path dispatches`() {
        val failures = listOf(
            RouterStageDiagnostic.REASON_TOKENIZE_FAILED,
            RouterStageDiagnostic.REASON_CLASSIFY_FAILED,
            RouterStageDiagnostic.REASON_GENERATE_FAILED,
            RouterStageDiagnostic.REASON_PARSE_OR_REPAIR_FAILED,
            RouterStageDiagnostic.REASON_MAPPER_OR_DIALOG_FAILED,
            RouterStageDiagnostic.REASON_INFERENCE_EXCEPTION,
        )

        failures.forEach { reason ->
            assertEquals("$reason must dispatch", CloudEscalation.DISPATCH, CloudEscalationPolicy.decide(reason))
        }
    }

    @Test
    fun `a no_match from an utterance aimed at us speaks a line`() {
        // Widening no_match to *escalate* was tried and measured and still does not
        // happen; saying something locally is the other answer to the same problem,
        // and it costs no wall-clock window.
        assertEquals(
            CloudEscalation.SPEAK_UNROUTED,
            CloudEscalationPolicy.decide(RouterStageDiagnostic.REASON_NO_MATCH, addressed = true),
        )
    }

    @Test
    fun `a blank transcript speaks only when the user was not the one talking`() {
        // An addressed capture with no text is a bare wake word: the spec says silence, and the
        // engine drops the tidier version of this before routing. Anywhere else it is a fault.
        assertEquals(
            CloudEscalation.SILENT,
            CloudEscalationPolicy.decide(RouterStageDiagnostic.REASON_BLANK_TRANSCRIPT, addressed = true),
        )
        assertEquals(
            CloudEscalation.SPEAK_ERROR,
            CloudEscalationPolicy.decide(RouterStageDiagnostic.REASON_BLANK_TRANSCRIPT, addressed = false),
        )
    }

    @Test
    fun `a no_match from the room keeps the earcon alone`() {
        // Ambient speech and podcast bleed are not addressed to us, so a line would
        // be answering the room; a soft tone is not.
        assertEquals(
            CloudEscalation.EARCON,
            CloudEscalationPolicy.decide(RouterStageDiagnostic.REASON_NO_MATCH, addressed = false),
        )
        assertEquals(
            CloudEscalation.EARCON,
            CloudEscalationPolicy.decide(RouterStageDiagnostic.REASON_NO_MATCH),
        )
    }

    @Test
    fun `nothing to send, or nothing that could answer, speaks locally`() {
        val local = listOf(
            RouterStageDiagnostic.REASON_BLANK_TRANSCRIPT,
            RouterStageDiagnostic.REASON_MODEL_NOT_LOADED,
            RouterStageDiagnostic.REASON_UNSUPPORTED_INPUT_FORMAT,
        )

        local.forEach { reason ->
            assertEquals("$reason must speak locally", CloudEscalation.SPEAK_ERROR, CloudEscalationPolicy.decide(reason))
        }
    }

    @Test
    fun `an unknown reason dispatches rather than failing quietly`() {
        // No diagnostic at all means the client has no answer, which is the
        // condition being handed off.
        listOf(null, "none", "something_new").forEach { reason ->
            assertEquals("$reason must dispatch", CloudEscalation.DISPATCH, CloudEscalationPolicy.decide(reason))
        }
    }
}
