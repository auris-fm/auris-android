package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

/**
 * Set-overlap matcher for cloud reference fingerprints. The server's
 * `fingerprint-compact-v2` reference checkpoints and the client's
 * `CloudFingerprinter` windows use identical hash schemes, so a simple
 * set-overlap score is the correct similarity measure.
 *
 * Replaces the Rust `CheckpointMatcher` for the cloud-alignment path only.
 * Acceptance is decided by the caller, not here: score at least
 * FingerprintConstants.CLOUD_REFERENCE_ACCEPTANCE_SCORE, leading by
 * DRIFT_SCORE_DOMINANCE_GAP over the best checkpoint more than
 * CLOUD_REFERENCE_LEAD_DISTANCE_SECONDS away. Ask for CLOUD_REFERENCE_CANDIDATES
 * results so such a checkpoint is in the list (docs/specs/timeline-alignment.md#reference-matching).
 *
 * Storage (delta: fingerprint OOM): one sorted, deduplicated primitive array
 * per checkpoint, membership by binary search — no boxed HashSet<Long>. The
 * boxed representation cost ~67 B/hash and pushed long episodes past the
 * 512 MB heap limit; this shape costs ~4 B/hash. Hashes are uint32 bit
 * patterns, stored as Ints and compared unsigned via toLong() masking only
 * at the query boundary. iOS uses the same shape so scores stay identical.
 */
class CloudReferenceMatcher {
    data class Match(val timestampSeconds: Float, val score: Float)

    private class Checkpoint(val timestampSeconds: Float, val sortedHashes: IntArray)

    private val checkpoints = mutableListOf<Checkpoint>()

    /** Adds a reference checkpoint (timestamp in seconds, hashes as uint32 bit patterns in Longs). */
    fun add(timestampSeconds: Float, hashes: LongArray) {
        val ints = IntArray(hashes.size) { hashes[it].toInt() }
        addInts(timestampSeconds, ints)
    }

    /**
     * Adds a checkpoint whose hashes are already uint32 bit patterns in Ints
     * (the streaming decoder's native form). Sorts and deduplicates in place;
     * the array is owned by the matcher afterwards.
     */
    internal fun addInts(timestampSeconds: Float, hashes: IntArray) {
        hashes.sort()
        var unique = 0
        for (i in hashes.indices) {
            if (unique == 0 || hashes[i] != hashes[unique - 1]) {
                hashes[unique++] = hashes[i]
            }
        }
        val compact = if (unique == hashes.size) hashes else hashes.copyOf(unique)
        checkpoints.add(Checkpoint(timestampSeconds, compact))
    }

    val count: Int get() = checkpoints.size

    /** Returns the top [maxResults] reference checkpoints by overlap score, descending. */
    fun findTopMatches(queryHashes: LongArray, maxResults: Int): List<Match> {
        if (queryHashes.isEmpty() || maxResults <= 0) return emptyList()
        // Query side: one sorted deduplicated int copy, reused for every
        // checkpoint. Intersection counts duplicate query hashes once per
        // occurrence, exactly as the previous HashSet-membership loop did.
        val query = IntArray(queryHashes.size) { queryHashes[it].toInt() }
        query.sort()
        var queryUnique = 0
        for (i in query.indices) {
            if (queryUnique == 0 || query[i] != query[queryUnique - 1]) {
                query[queryUnique++] = query[i]
            }
        }
        val top = ArrayList<Match>(minOf(maxResults, checkpoints.size))
        for (cp in checkpoints) {
            var intersection = 0
            for (i in 0 until queryUnique) {
                if (binarySearch(cp.sortedHashes, query[i])) intersection++
            }
            // Spec rule (timeline-alignment, both platforms): distinct query hashes
            // found in the checkpoint, over min(distinct query count,
            // distinct checkpoint count). sortedHashes is already deduped,
            // so its length IS the distinct checkpoint count. Server windows
            // are deduped, so real-reference scores equal the old ones.
            val smallest = minOf(queryUnique, cp.sortedHashes.size)
            val score = if (smallest == 0) 0f else intersection.toFloat() / smallest
            insertTop(top, Match(cp.timestampSeconds, score), maxResults)
        }
        // The caller treats firstOrNull() as best and looks further down the list for
        // competition — the result must be descending even when fewer than maxResults
        // checkpoints exist (buffer would otherwise stay unsorted).
        top.sortByDescending { it.score }
        return top
    }

    private fun binarySearch(sorted: IntArray, value: Int): Boolean {
        var lo = 0
        var hi = sorted.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = sorted[mid]
            when {
                v < value -> lo = mid + 1
                v > value -> hi = mid - 1
                else -> return true
            }
        }
        return false
    }

    private fun insertTop(top: ArrayList<Match>, candidate: Match, maxResults: Int) {
        if (top.size < maxResults) {
            top.add(candidate)
            if (top.size == maxResults) top.sortByDescending { it.score }
            return
        }
        if (candidate.score <= top.last().score) return
        top[top.lastIndex] = candidate
        var i = top.lastIndex
        while (i > 0 && top[i].score > top[i - 1].score) {
            val above = top[i - 1]
            top[i - 1] = top[i]
            top[i] = above
            i--
        }
    }

}
