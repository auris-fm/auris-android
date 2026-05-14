package au.com.shiftyjelly.pocketcasts.voicecontrol.model

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
class SpeakerVerifier @Inject constructor() {
    companion object {
        const val DEFAULT_THRESHOLD = 0.70f
    }

    fun verify(enrolled: FloatArray, candidate: FloatArray, threshold: Float = DEFAULT_THRESHOLD): Boolean {
        return cosineSimilarity(enrolled, candidate) >= threshold
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            na += a[i].toDouble() * a[i]
            nb += b[i].toDouble() * b[i]
        }
        val d = sqrt(na) * sqrt(nb)
        return if (d == 0.0) 0f else (dot / d).toFloat()
    }
}
