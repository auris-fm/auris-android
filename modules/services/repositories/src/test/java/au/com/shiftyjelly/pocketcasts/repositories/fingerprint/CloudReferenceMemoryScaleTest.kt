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
 * The decode-peak bound is deferred until real payload numbers say decoding
 * matters; storage bound + score parity stay.
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

    /** Test-only longhand oracle: distinct-over-min-distinct set overlap. */
    private fun oracleScore(query: LongArray, checkpoint: LongArray): Float {
        val q = query.toHashSet()
        val c = checkpoint.toHashSet()
        if (q.isEmpty() || c.isEmpty()) return 0f
        return q.count { it in c }.toFloat() / minOf(q.size, c.size)
    }

    @Test
    fun `compact storage scores match a set-overlap oracle with guaranteed partial overlap`() {
        // Deduplicated inputs with GUARANTEED partial overlap: each query is
        // built from a checkpoint's own hashes plus foreign ones, so random
        // near-zero-overlap queries can't make this control vacuous.
        val payload = compactV2(checkpoints = 40, hashesPer = 24, seed = 3)
        val compact = buildMatcher(payload)!!
        val decoded = ReferenceFingerprint.decode(payload)!!.libraryCheckpoints()
        val checkpoints = decoded.map { cp -> cp.timestampSeconds to cp.hashes.map { it.toLong() and 0xFFFFFFFFL }.toLongArray() }

        val rnd = Random(5)
        var partialSeen = 0
        for ((idx, cp) in checkpoints.withIndex()) {
            // Query: 12 of this checkpoint's hashes + 12 foreign hashes.
            val own = cp.second.toList().shuffled(Random(idx)).take(12)
            val foreign = generateSequence { rnd.nextLong() and 0xFFFFFFFFL }
                .filter { it !in cp.second.toHashSet() }.distinct().take(12).toList()
            val query = (own + foreign).toLongArray()
            val matches = compact.findTopMatches(query, 3)
            assertEquals(3, matches.size)
            for (m in matches) {
                val expected = oracleScore(query, checkpoints.first { it.first == m.timestampSeconds }.second)
                assertEquals("checkpoint ${m.timestampSeconds}", expected, m.score)
                if (expected > 0f && expected < 1f) partialSeen++
            }
            // The queried checkpoint itself must appear with score 12/24 = 0.5.
            val self = matches.first { it.timestampSeconds == cp.first }
            assertEquals(0.5f, self.score)
        }
        assertTrue("control must exercise partial overlap, saw $partialSeen", partialSeen > 0)

        // Exact query: a checkpoint's own hashes score 1.0 at its timestamp.
        val first = checkpoints[7]
        val exact = compact.findTopMatches(first.second, 1)
        assertEquals(first.first, exact[0].timestampSeconds)
        assertEquals(1.0f, exact[0].score)
    }
}
