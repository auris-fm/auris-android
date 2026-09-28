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
    fun `a deliberate no_match stays local and silent`() {
        // Widening this was tried and measured: a bare wake phrase is no_match on
        // every capture, so it spent the window and errored on the service each
        // time. The spec says a question selects cloud_route, not no_match.
        assertEquals(CloudEscalation.SILENT, CloudEscalationPolicy.decide(RouterStageDiagnostic.REASON_NO_MATCH))
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
