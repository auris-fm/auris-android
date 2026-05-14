package au.com.shiftyjelly.pocketcasts.voicecontrol.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SpeakerVerifierTest {
    private val verifier = SpeakerVerifier()
    private val threshold = 0.70f

    @Test fun `identical embeddings match`() {
        val e = FloatArray(192) { kotlin.math.sin(it.toFloat()) }
        assertTrue(verifier.verify(e, e, threshold))
    }

    @Test fun `dissimilar embeddings do not match`() {
        val a = FloatArray(192) { 1.0f }
        val b = FloatArray(192) { -1.0f }
        assertFalse(verifier.verify(a, b, threshold))
    }

    @Test fun `orthogonal vectors have zero similarity`() {
        val s = verifier.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f))
        assertTrue(abs(s) < 1e-6f)
    }

    @Test fun `identical unit vectors have similarity one`() {
        val s = verifier.cosineSimilarity(floatArrayOf(0.6f, 0.8f), floatArrayOf(0.6f, 0.8f))
        assertTrue(abs(s - 1.0f) < 1e-6f)
    }
}
