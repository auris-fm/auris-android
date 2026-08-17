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
        matcher.add(0f, query, 8f)
        matcher.add(1f, longArrayOf(1111, 2222, 3333), 8f)
        matcher.add(2f, longArrayOf(4444, 5555, 6666), 8f)

        val matches = matcher.findTopMatches(query, 2)

        assertEquals(2, matches.size)
        assertEquals(0f, matches[0].timestampSeconds)
        assertEquals(1.0f, matches[0].score)
    }

    @Test
    fun `different audio scores near zero and fails the match floor`() {
        val matcher = CloudReferenceMatcher()
        matcher.add(0f, CloudFingerprintParityData.signal16kMonoWindows[0], 8f)
        matcher.add(1f, CloudFingerprintParityData.signal16kMonoWindows[1], 8f)

        // Hashes from a completely different signal (empty except one hash).
        val unrelated = longArrayOf(42, 99, 12345)
        val matches = matcher.findTopMatches(unrelated, 1)

        assertTrue(matches.isNotEmpty())
        assertTrue("score=${matches[0].score} must be below the 0.5 match floor", matches[0].score < 0.5f)
    }

    @Test
    fun `partial overlap scores proportionally`() {
        val a = longArrayOf(1, 2, 3, 4, 5)
        val b = longArrayOf(1, 2, 3, 99, 100) // 3 of 5 shared
        assertEquals(3f / 5f, CloudReferenceMatcher.overlapScore(a, b))
    }

    @Test
    fun `empty query never matches`() {
        val matcher = CloudReferenceMatcher()
        matcher.add(0f, CloudFingerprintParityData.signal16kMonoWindows[0], 8f)
        assertTrue(matcher.findTopMatches(LongArray(0), 1).isEmpty())
    }

    @Test
    fun `score is symmetric and min normalized`() {
        val a = longArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val b = longArrayOf(1, 2, 3, 4) // subset
        // min normalization: 4 shared / min(8,4) = 1.0
        assertEquals(1.0f, CloudReferenceMatcher.overlapScore(a, b))
        assertEquals(CloudReferenceMatcher.overlapScore(a, b), CloudReferenceMatcher.overlapScore(b, a))
    }
}
