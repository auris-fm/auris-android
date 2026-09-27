package au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which routing outcomes are handed to the cloud service (contract, not an implementation detail). */
class CloudEscalationPolicyTest {

    @Test
    fun `every failure of the model path escalates`() {
        val failures = listOf(
            RouterStageDiagnostic.REASON_TOKENIZE_FAILED,
            RouterStageDiagnostic.REASON_CLASSIFY_FAILED,
            RouterStageDiagnostic.REASON_GENERATE_FAILED,
            RouterStageDiagnostic.REASON_PARSE_OR_REPAIR_FAILED,
            RouterStageDiagnostic.REASON_MAPPER_OR_DIALOG_FAILED,
            RouterStageDiagnostic.REASON_INFERENCE_EXCEPTION,
        )

        failures.forEach { reason ->
            assertTrue("$reason must escalate", CloudEscalationPolicy.escalates(reason))
        }
    }

    @Test
    fun `a deliberate no_match escalates`() {
        assertTrue(CloudEscalationPolicy.escalates(RouterStageDiagnostic.REASON_NO_MATCH))
    }

    @Test
    fun `nothing to send, or nothing that could answer, stays local`() {
        val local = listOf(
            RouterStageDiagnostic.REASON_BLANK_TRANSCRIPT,
            RouterStageDiagnostic.REASON_MODEL_NOT_LOADED,
            RouterStageDiagnostic.REASON_UNSUPPORTED_INPUT_FORMAT,
        )

        local.forEach { reason ->
            assertFalse("$reason must stay local", CloudEscalationPolicy.escalates(reason))
        }
    }

    @Test
    fun `an unknown reason escalates rather than failing quietly`() {
        // No diagnostic at all means the client has no answer, which is exactly
        // the condition being handed off.
        assertTrue(CloudEscalationPolicy.escalates(null))
        assertTrue(CloudEscalationPolicy.escalates("none"))
        assertTrue(CloudEscalationPolicy.escalates("something_new"))
    }
}
