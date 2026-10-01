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
    fun `a no_match is spoken only for a wake-detected segment`() {
        // core voice-intents.md: the short line is for a Detected segment. A NotDetected one is a
        // grace-period capture from the room, where a tone is all that is warranted — and the
        // default is the not-detected side, so the bare call must tone rather than speak.
        assertEquals(
            CloudEscalation.SPEAK_UNROUTED,
            CloudEscalationPolicy.decide(RouterStageDiagnostic.REASON_NO_MATCH, addressed = true),
        )
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
