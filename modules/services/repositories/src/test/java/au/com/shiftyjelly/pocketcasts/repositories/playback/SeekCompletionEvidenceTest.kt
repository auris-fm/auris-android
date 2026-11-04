package au.com.shiftyjelly.pocketcasts.repositories.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EVIDENCE, NOT ASSUMPTION: the production seek-completion path assigns a landing to a request only
 * when the engine's own discontinuity can be PROVEN to belong to it. Overlapping seeks with delayed
 * discontinuity delivery are the decisive trace: ownership is ambiguous, the evidence is
 * INVALIDATED, and every outstanding operation settles unknown by its budget -- never on the newest
 * token.
 */
class SeekCompletionEvidenceTest {

    // A real engine PositionInfo: the watcher reads its landed item clock directly.
    @Suppress("DEPRECATION")
    private fun position(positionMs: Long): Player.PositionInfo = Player.PositionInfo(null, 0, null, 0, positionMs, positionMs, 0, 0)

    @Test
    fun `a sequential seek settles its own request`() {
        val evidence = SeekCompletionEvidence()
        evidence.onRequestIssued("token-A")
        assertEquals("token-A", evidence.onSeekApplied())
        evidence.onRequestIssued("token-B")
        assertEquals("token-B", evidence.onSeekApplied())
    }

    @Test
    fun `overlapping seeks make ownership ambiguous and the evidence is invalidated`() {
        val evidence = SeekCompletionEvidence()
        evidence.onRequestIssued("token-A")
        evidence.onRequestIssued("token-B")
        // The discontinuity arrives while BOTH are outstanding (delayed delivery). The engine
        // coalesces; which request landed cannot be proven. The newest token must NOT be assigned.
        assertNull(evidence.onSeekApplied())
        // Nothing is left addressable: a later seek starts clean and settles its own request.
        evidence.onRequestIssued("token-C")
        assertEquals("token-C", evidence.onSeekApplied())
    }

    @Test
    fun `an applied seek matching no issued request completes nothing`() {
        val evidence = SeekCompletionEvidence()
        assertNull(evidence.onSeekApplied())
        evidence.onRequestIssued("token-A")
        assertEquals("token-A", evidence.onSeekApplied())
        // A second discontinuity for the same (already settled) seek completes nothing.
        assertNull(evidence.onSeekApplied())
    }

    @Test
    fun `the production watcher emits the engine's landed clock, and ambiguity emits NO token`() {
        val watcher = SeekAppliedWatcherHarness()
        watcher.evidence.onRequestIssued("token-A")

        // A NON-SEEK discontinuity (skip, auto transition) is not a seek completion.
        watcher.watcher.onPositionDiscontinuity(position(0), position(10_000), Player.DISCONTINUITY_REASON_SKIP)
        assertTrue(watcher.emitted.isEmpty())

        // A single outstanding request: the engine's landed clock (10_000, NOT the requested 9_000)
        // is the completion, with that request's token.
        watcher.watcher.onPositionDiscontinuity(position(0), position(10_000), Player.DISCONTINUITY_REASON_SEEK)
        assertEquals(listOf(10_000 to "token-A"), watcher.emitted)
    }

    @Test
    fun `the production watcher emits no token when ownership is ambiguous`() {
        val watcher = SeekAppliedWatcherHarness()
        watcher.evidence.onRequestIssued("token-A")
        watcher.evidence.onRequestIssued("token-B")
        // Delayed discontinuity with both requests outstanding: no token is assignable, so the
        // completion cannot settle any operation -- unknown by budget, never a guessed assignment.
        watcher.watcher.onPositionDiscontinuity(position(0), position(10_000), Player.DISCONTINUITY_REASON_SEEK)
        assertEquals(listOf(10_000 to null), watcher.emitted)
    }

    /** The PRODUCTION listener, constructed exactly as SimplePlayer wires it. */
    private class SeekAppliedWatcherHarness {
        val evidence = SeekCompletionEvidence()
        val emitted = mutableListOf<Pair<Int, String?>>()
        val watcher = SeekAppliedWatcher(evidence) { positionMs, completionToken ->
            emitted += positionMs to completionToken
        }
    }
}
