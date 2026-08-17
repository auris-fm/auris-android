package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

/**
 * Set-overlap matcher for cloud reference fingerprints. The server's
 * `fingerprint-compact-v2` reference checkpoints and the client's
 * `CloudFingerprinter` windows use identical hash schemes, so a simple
 * set-overlap score is the correct similarity measure.
 *
 * Replaces the Rust `CheckpointMatcher` for the cloud-alignment path only.
 * Score semantics match the existing pipeline: [matchScoreThreshold] 0.5,
 * anchor threshold 0.65, dominance gap 0.05 (see FingerprintConstants).
 */
class CloudReferenceMatcher {
    data class Match(val timestampSeconds: Float, val score: Float)

    private data class Checkpoint(val timestampSeconds: Float, val hashes: LongArray)

    private val checkpoints = mutableListOf<Checkpoint>()

    /** Adds a reference checkpoint (timestamp in seconds, sorted hash set). */
    fun add(timestampSeconds: Float, hashes: LongArray, durationSeconds: Float) {
        checkpoints.add(Checkpoint(timestampSeconds, hashes))
    }

    fun clear() = checkpoints.clear()

    val count: Int get() = checkpoints.size

    /** Returns the top [maxResults] reference checkpoints by overlap score. */
    fun findTopMatches(queryHashes: LongArray, maxResults: Int): List<Match> {
        if (queryHashes.isEmpty()) return emptyList()
        return checkpoints
            .map { Match(it.timestampSeconds, overlapScore(queryHashes, it.hashes)) }
            .sortedByDescending { it.score }
            .take(maxResults)
    }

    companion object {
        /**
         * Overlap score = |A ∩ B| / min(|A|, |B|) ∈ [0, 1]. Exact matches on
         * the same audio span score 1.0; offset windows share only part of
         * their hash sets and score proportionally lower.
         */
        fun overlapScore(a: LongArray, b: LongArray): Float {
            if (a.isEmpty() || b.isEmpty()) return 0f
            val small = if (a.size < b.size) a else b
            val large = if (a.size < b.size) b else a
            val set = large.toHashSet()
            var intersection = 0
            for (h in small) if (h in set) intersection++
            return intersection.toFloat() / small.size
        }
    }
}
