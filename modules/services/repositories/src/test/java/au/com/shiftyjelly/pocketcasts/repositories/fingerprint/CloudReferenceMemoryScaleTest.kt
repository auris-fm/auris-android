package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import java.util.Base64
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Memory-scale controls for the cloud reference path (delta: fingerprint OOM).
 *
 * The Pixel crash buffer showed repeated OutOfMemoryError at the 512 MB heap
 * limit, two of them inside CloudReferenceMatcher.add's boxed HashSet while
 * buildMatcher parsed a long episode's reference. These controls pin the two
 * budgets that fix must hold at realistic scale:
 *
 *  - matcher storage: primitive, bounded bytes per hash (no boxed Long sets).
 *
 * Per spec delta 8c58e3f6 the decode-peak bound is dropped until cloud's real
 * payload numbers say decoding matters; storage bound + score parity stay.
 *
 * Budgets are allocation deltas measured on the current thread, so they are
 * deterministic (no GC-timing dependence). They are generous against the
 * compact target (~4 B/hash storage) and far below what the boxed-set
 * implementation allocates, so the old code fails RED here.
 */
class CloudReferenceMemoryScaleTest {

    /** The production entry point under test (internal, same module). No network is used. */
    private fun buildMatcher(payload: ByteArray): CloudReferenceMatcher? =
        CloudFingerprintReferenceFetcher(
            au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisTokenProvider(
                clientProvider = { null },
                credentialProvider = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisAccountCredentialProviding {
                    override fun credential(): String? = null
                },
            ),
        ).buildMatcher(payload)

    private fun allocatedBytes(body: () -> Unit): Long {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(id)
        body()
        return bean.getThreadAllocatedBytes(id) - before
    }

    /** A compact-v2 document: [checkpoints] checkpoints x [hashesPer] uint32 hashes. */
    private fun compactV2(checkpoints: Int, hashesPer: Int, seed: Long = 7): ByteArray {
        val rnd = Random(seed)
        val sb = StringBuilder(checkpoints * (hashesPer * 6 + 16))
        sb.append("""{"format":"fingerprint-compact-v2","total_duration":${checkpoints * 10.0},""")
        sb.append(""""checkpoint_interval":10,"checkpoint_duration":8,"timestamp_quantum":1,"checkpoints":[""")
        val bytes = ByteArray(hashesPer * 4)
        for (i in 0 until checkpoints) {
            if (i > 0) sb.append(',')
            rnd.nextBytes(bytes)
            sb.append('[').append(if (i == 0) 0 else 10).append(',')
            sb.append('"').append(Base64.getEncoder().encodeToString(bytes)).append('"').append(']')
        }
        sb.append("]}")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    @Test
    fun `matcher stores hashes within a primitive budget at realistic scale`() {
        // 2h29m episode shape: ~900 checkpoints x 100 hashes = 90k hashes.
        val checkpoints = 900
        val hashesPer = 100
        val totalHashes = checkpoints.toLong() * hashesPer
        val rnd = Random(11)
        val arrays = Array(checkpoints) { LongArray(hashesPer) { rnd.nextLong() and 0xFFFFFFFFL } }

        var matcher: CloudReferenceMatcher? = null
        val used = allocatedBytes {
            val m = CloudReferenceMatcher()
            for (i in 0 until checkpoints) m.add(i * 10f, arrays[i])
            matcher = m
        }
        assertEquals(checkpoints, matcher!!.count)
        // Compact target ~8 B/hash + per-checkpoint overhead; 24 B/hash leaves
        // room for open-addressing load factor but rejects boxed HashSet<Long>
        // (~50+ B/hash) plus any duplicate retained array.
        val budget = totalHashes * 24
        assertTrue("matcher allocated $used B for $totalHashes hashes (budget $budget B)", used <= budget)
    }

    @Test
    fun `streaming decode scores identically to the boxed reference implementation`() {
        // Parity: same timestamps, same scores, including duplicate hashes and
        // unsorted input. The compact structure must not change match semantics.
        val payload = compactV2(checkpoints = 40, hashesPer = 24, seed = 3)
        val compact = buildMatcher(payload)!!
        val legacy = ReferenceFingerprint.decode(payload)!!
        val legacyMatcher = CloudReferenceMatcher()
        for (cp in legacy.libraryCheckpoints()) {
            legacyMatcher.add(cp.timestampSeconds, cp.hashes.map { it.toLong() and 0xFFFFFFFFL }.toLongArray())
        }
        val rnd = Random(5)
        repeat(20) {
            val query = LongArray(24) { rnd.nextLong() and 0xFFFFFFFFL }
            val a = compact.findTopMatches(query, 3)
            val b = legacyMatcher.findTopMatches(query, 3)
            assertEquals(b.size, a.size)
            for (i in a.indices) {
                assertEquals(b[i].timestampSeconds, a[i].timestampSeconds)
                assertEquals(b[i].score, a[i].score)
            }
        }
        // A query drawn from an actual checkpoint must score 1.0 at its timestamp.
        val first = legacy.libraryCheckpoints()[7]
        val exact = compact.findTopMatches(first.hashes.map { it.toLong() and 0xFFFFFFFFL }.toLongArray(), 1)
        assertEquals(first.timestampSeconds, exact[0].timestampSeconds)
        assertEquals(1.0f, exact[0].score)
    }
}
