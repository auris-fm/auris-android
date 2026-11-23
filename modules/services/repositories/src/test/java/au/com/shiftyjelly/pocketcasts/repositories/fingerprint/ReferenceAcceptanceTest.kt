package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.fingerprint_uniffi.MatchResult

/**
 * The acceptance rule for Auris-reference matches, over the ranked candidates the inherited
 * library's matcher returns.
 *
 * WHY A DISTANT RIVAL AND NOT THE RUNNER-UP: with one-second checkpoints the runner-up is almost
 * always the neighbouring checkpoint, which shares seven of the best's eight seconds and scores
 * nearly the same. Comparing against it rejects correct matches. Competition is the best candidate
 * more than two seconds away, and six candidates are asked for because at most five can lie within
 * two seconds of the best.
 *
 * `MatchResult` is a plain data class, so these lists are built directly: the rule is exercised as
 * production runs it, without loading the native library.
 */
class ReferenceAcceptanceTest {

    private fun candidate(timestampSeconds: Float, score: Float) = MatchResult(timestampSeconds, score)

    @Test
    fun `a confident best with a distant rival trailing by the gap is accepted`() {
        val matches = listOf(
            candidate(10f, 0.90f),
            candidate(11f, 0.88f), // neighbour: shares most of the window, not competition
            candidate(40f, 0.60f), // distant rival
        )

        val accepted = ReferenceAcceptance.accept(matches)

        assertEquals(10.0, accepted!!.referenceTimeSeconds, 0.0)
        assertEquals(0.90f, accepted.score, 0.0f)
    }

    @Test
    fun `a best below the floor is rejected even with a clear lead`() {
        val matches = listOf(
            candidate(10f, 0.50f),
            candidate(40f, 0.10f),
        )

        assertNull(
            "0.50 is above the old 0.30 mark but below the 0.65 floor",
            ReferenceAcceptance.accept(matches),
        )
    }

    @Test
    fun `a distant rival within the gap rejects the window`() {
        val matches = listOf(
            candidate(10f, 0.90f),
            candidate(40f, 0.88f), // only 0.02 behind: ambiguous
        )

        assertNull(
            "an ambiguous distant rival must reject, not fall back to the floor",
            ReferenceAcceptance.accept(matches),
        )
    }

    @Test
    fun `no candidate more than two seconds away rejects the window`() {
        // A reference shorter than about thirteen seconds has at most five checkpoints, all within
        // two seconds of the best. The rule fails closed rather than accepting on the floor alone.
        val matches = listOf(
            candidate(1f, 0.95f),
            candidate(2f, 0.93f),
            candidate(0f, 0.91f),
            candidate(3f, 0.80f),
        )

        assertNull(
            "with no distant candidate there is no evidence of a lead, so the window is not accepted",
            ReferenceAcceptance.accept(matches),
        )
    }

    @Test
    fun `an empty candidate list is rejected`() {
        assertNull(ReferenceAcceptance.accept(emptyList()))
    }

    @Test
    fun `a near neighbour scoring higher than the distant rival does not reject`() {
        // The mutation case: comparing against the runner-up (0.99, one second away) would reject
        // this window, while the distant rival (0.40) leaves a wide lead.
        val matches = listOf(
            candidate(10f, 1.00f),
            candidate(11f, 0.99f),
            candidate(40f, 0.40f),
        )

        assertEquals(10.0, ReferenceAcceptance.accept(matches)!!.referenceTimeSeconds, 0.0)
    }

    @Test
    fun `a lead of exactly the gap is accepted and a smaller one is rejected`() {
        // The scores are Floats, so the boundary is the one the subtraction actually produces:
        // 0.95f - 0.90f lands a hair ABOVE 0.05f, while 0.90f - 0.85f lands a hair below. Picking
        // "0.90 and 0.85" as the exact-boundary pair would assert a precision the arithmetic does
        // not have. These pairs sit either side of the mark by more than that noise.
        val atGap = listOf(candidate(10f, 0.95f), candidate(40f, 0.90f))
        val underGap = listOf(candidate(10f, 0.90f), candidate(40f, 0.86f))

        assertEquals(10.0, ReferenceAcceptance.accept(atGap)!!.referenceTimeSeconds, 0.0)
        assertNull(ReferenceAcceptance.accept(underGap))
    }

    @Test
    fun `exactly the floor is accepted and just under it is rejected`() {
        val atFloor = listOf(candidate(10f, 0.65f), candidate(40f, 0.10f))
        val underFloor = listOf(candidate(10f, 0.6499f), candidate(40f, 0.10f))

        assertEquals(10.0, ReferenceAcceptance.accept(atFloor)!!.referenceTimeSeconds, 0.0)
        assertNull(ReferenceAcceptance.accept(underFloor))
    }

    @Test
    fun `a candidate exactly two seconds away is not distant`() {
        // The boundary belongs to the neighbourhood: only MORE than two seconds counts as a rival.
        val matches = listOf(candidate(10f, 0.90f), candidate(12f, 0.10f))

        assertNull(
            "two seconds exactly is still a neighbour, so no distant rival exists",
            ReferenceAcceptance.accept(matches),
        )
    }

    @Test
    fun `the best distant rival is used, not the first one listed`() {
        val matches = listOf(
            candidate(10f, 0.90f),
            candidate(40f, 0.20f),
            candidate(70f, 0.88f), // the strongest distant rival, listed last
        )

        assertNull(
            "the lead is measured against the BEST distant candidate",
            ReferenceAcceptance.accept(matches),
        )
    }
}