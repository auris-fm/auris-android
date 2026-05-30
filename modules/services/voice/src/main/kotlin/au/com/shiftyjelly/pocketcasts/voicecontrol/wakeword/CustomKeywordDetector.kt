package au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CustomKeywordDetector @Inject constructor() : WakeWordDetector {

    private var referenceEmbedding: FloatArray? = null

    override val isReady: Boolean
        get() = referenceEmbedding != null

    fun enroll(samples: List<FloatArray>): Boolean {
        // Average the enrollment samples' embeddings and L2-normalize
        // Placeholder: real implementation uses an audio embedding model
        // Stores the averaged, normalized vector as the reference template
        referenceEmbedding = FloatArray(128) { 0f }
        return true
    }

    fun isEnrolled(): Boolean = referenceEmbedding != null

    fun clearEnrollment() {
        referenceEmbedding = null
    }

    override suspend fun detect(segment: FloatArray, sampleRateHz: Int): WakeWordResult {
        // Placeholder: real implementation embeds the segment and compares
        // cosine similarity against referenceEmbedding
        return WakeWordResult(detected = false, confidence = 0f)
    }

    override fun release() {
        referenceEmbedding = null
    }
}
