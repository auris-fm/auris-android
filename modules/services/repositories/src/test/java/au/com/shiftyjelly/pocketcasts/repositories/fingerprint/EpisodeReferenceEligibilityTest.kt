package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 4235652501: the episode reference must be eligible during ACTIVE CAPTURE independently of the
// SYNCED_TRANSCRIPTS feature; the capture-inactive path keeps the existing flag constraints and the
// teardown boundary restores them. The composition is the production predicate SimplePlayer installs
// (EpisodeReferenceEligibility.tapEnabled).
class EpisodeReferenceEligibilityTest {
    @Test
    fun `flag off but capture active - the reference is eligible`() {
        assertTrue(
            "an active capture session needs the episode reference whatever the transcript toggle says",
            EpisodeReferenceEligibility.tapEnabled(captureActive = true, transcriptsEligible = false),
        )
    }

    @Test
    fun `capture inactive - the transcript constraints decide as before`() {
        assertFalse(
            "no capture and no transcripts eligibility means no tap",
            EpisodeReferenceEligibility.tapEnabled(captureActive = false, transcriptsEligible = false),
        )
        assertTrue(
            "the transcript path is unchanged when capture is inactive",
            EpisodeReferenceEligibility.tapEnabled(captureActive = false, transcriptsEligible = true),
        )
    }

    @Test
    fun `teardown restores flag-gated behaviour`() {
        val gate = EpisodeReferenceEligibility()
        gate.setCaptureActive(true)
        assertTrue(gate.isCaptureActive())
        gate.setCaptureActive(false)
        assertFalse(
            "capture teardown must end the capture-based eligibility",
            gate.isCaptureActive(),
        )
    }
}
