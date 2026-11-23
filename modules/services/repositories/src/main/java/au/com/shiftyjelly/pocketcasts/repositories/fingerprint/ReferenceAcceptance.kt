package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import uniffi.fingerprint_uniffi.MatchResult

/**
 * Whether a local window confidently matches an Auris reference, decided over the ranked candidates
 * the inherited library's matcher returns (highest score first).
 *
 * A window is accepted when its best score reaches [FingerprintConstants.REFERENCE_ACCEPTANCE_SCORE]
 * and leads the best candidate more than [FingerprintConstants.REFERENCE_LEAD_DISTANCE_SECONDS] away
 * by at least [FingerprintConstants.REFERENCE_LEAD_GAP]. With one-second checkpoints at most five
 * candidates lie within two seconds of the best, so asking for six guarantees a distant one exists on
 * a long enough reference.
 *
 * The runner-up is deliberately not the comparison: it is almost always the neighbouring checkpoint,
 * which shares seven of the best's eight seconds and scores nearly the same, so comparing against it
 * rejects correct matches.
 *
 * When no candidate lies far enough away — a reference shorter than about thirteen seconds — there is
 * no evidence of a lead at all, and the window is NOT accepted. Falling back to the floor alone would
 * accept a match nothing distinguishes from its neighbours.
 */
object ReferenceAcceptance {

    /** An accepted window's place on the reference timeline, in seconds, and the score that earned it. */
    data class Accepted(val referenceTimeSeconds: Double, val score: Float)

    fun accept(ranked: List<MatchResult>): Accepted? {
        val best = ranked.firstOrNull() ?: return null
        if (best.score < FingerprintConstants.REFERENCE_ACCEPTANCE_SCORE) return null

        val distant = ranked
            .drop(1)
            .filter { kotlin.math.abs(it.timestamp - best.timestamp) > FingerprintConstants.REFERENCE_LEAD_DISTANCE_SECONDS }
            .maxByOrNull { it.score }
            ?: return null

        if (best.score - distant.score < FingerprintConstants.REFERENCE_LEAD_GAP) return null

        return Accepted(best.timestamp.toDouble(), best.score)
    }
}
