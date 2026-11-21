package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudReferenceMatcherTest {

    private fun longArrayOfValues(vararg values: Long): LongArray = longArrayOf(*values)

    @Test
    fun `exact window match scores 1_0 at the correct checkpoint`() {
        val matcher = CloudReferenceMatcher()
        val query = CloudFingerprintParityData.signal16kMonoWindows[0]
        // Checkpoints at t=0 carry the query hashes; t=1 and t=2 carry
        // unrelated hashes so the timestamps are distinguishable.
        matcher.add(0f, query)
        matcher.add(1f, longArrayOf(1111, 2222, 3333))
        matcher.add(2f, longArrayOf(4444, 5555, 6666))

        val matches = matcher.findTopMatches(query, 2)

        assertEquals(2, matches.size)
        assertEquals(0f, matches[0].timestampSeconds)
        assertEquals(1.0f, matches[0].score)
    }

    @Test
    fun `different audio scores near zero and fails the match floor`() {
        val matcher = CloudReferenceMatcher()
        matcher.add(0f, CloudFingerprintParityData.signal16kMonoWindows[0])
        matcher.add(1f, CloudFingerprintParityData.signal16kMonoWindows[1])

        // Hashes from a completely different signal (empty except one hash).
        val unrelated = longArrayOf(42, 99, 12345)
        val matches = matcher.findTopMatches(unrelated, 1)

        assertTrue(matches.isNotEmpty())
        assertTrue("score=${matches[0].score} must be below the 0.5 match floor", matches[0].score < 0.5f)
    }

    @Test
    fun `partial overlap scores proportionally`() {
        val matcher = CloudReferenceMatcher()
        matcher.add(0f, longArrayOf(1, 2, 3, 99, 100))
        val matches = matcher.findTopMatches(longArrayOf(1, 2, 3, 4, 5), 1)
        assertEquals(3f / 5f, matches[0].score) // 3 of 5 shared
    }

    @Test
    fun `empty query never matches`() {
        val matcher = CloudReferenceMatcher()
        matcher.add(0f, CloudFingerprintParityData.signal16kMonoWindows[0])
        assertTrue(matcher.findTopMatches(LongArray(0), 1).isEmpty())
    }

    @Test
    fun `score is symmetric and min normalized`() {
        val a = longArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val b = longArrayOf(1, 2, 3, 4) // subset
        // min normalization: 4 shared / min(8,4) = 1.0, both directions.
        val ab = CloudReferenceMatcher().apply { add(0f, b) }.findTopMatches(a, 1)
        val ba = CloudReferenceMatcher().apply { add(0f, a) }.findTopMatches(b, 1)
        assertEquals(1.0f, ab[0].score)
        assertEquals(ab[0].score, ba[0].score)
    }

    /**
     * Shared cross-platform fixture (timeline-alignment "Reference matching"):
     * score = distinct query
     * hashes found in the checkpoint / min(distinct query count, distinct
     * checkpoint count). iOS pins the same numbers. Duplicates on either
     * side must not change the score.
     */
    @Test
    fun `duplicate fixture pins the distinct-count rule`() {
        // Checkpoint raw [7, 7, 9, 11, 11, 11] -> distinct {7, 9, 11} (3).
        // Query raw [7, 7, 9, 42] -> distinct {7, 9, 42} (3), found {7, 9} (2).
        // Score = 2 / min(3, 3) = 2/3.
        val matcher = CloudReferenceMatcher()
        matcher.add(0f, longArrayOf(7, 7, 9, 11, 11, 11))
        val matches = matcher.findTopMatches(longArrayOf(7, 7, 9, 42), 1)
        assertEquals(2f / 3f, matches[0].score)

        // Same fixture without duplicates scores identically.
        val deduped = CloudReferenceMatcher()
        deduped.add(0f, longArrayOf(7, 9, 11))
        assertEquals(2f / 3f, deduped.findTopMatches(longArrayOf(7, 9, 42), 1)[0].score)

        // Smaller distinct checkpoint side wins the min: checkpoint {7, 9},
        // query {7, 9, 42} -> 2 / min(3, 2) = 1.0.
        val small = CloudReferenceMatcher()
        small.add(0f, longArrayOf(7, 9, 9))
        assertEquals(1.0f, small.findTopMatches(longArrayOf(7, 9, 42), 1)[0].score)
    }
}
