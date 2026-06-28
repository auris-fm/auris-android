package au.com.shiftyjelly.pocketcasts.voicecontrol.speaker

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/** Stateless cosine similarity against the enrolled voiceprint. */
@Singleton
class SpeakerVerifier @Inject constructor() {

    fun verify(candidate: FloatArray, enrolled: FloatArray, threshold: Float): Boolean {
        return cosineSimilarity(candidate, enrolled) >= threshold
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denominator = sqrt(normA.toDouble()) * sqrt(normB.toDouble())
        return if (denominator > 0) (dot / denominator).toFloat() else 0f
    }
}
