package au.com.shiftyjelly.pocketcasts.voicecontrol.speaker

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Gates audio segments by speaker identity.
 *
 * Runs in parallel with wake word detection — both consume the same VAD
 * segment and neither depends on the other. Both must pass for the segment
 * to reach ASR.
 */
@Singleton
class SpeakerVerificationGate @Inject constructor(
    private val embedder: SpeakerEmbedder,
    private val verifier: SpeakerVerifier,
    private val store: SpeakerVerificationStore,
) {

    /** Whether the embedder model is loaded and an enrollment exists. */
    val isReady: Boolean
        get() = embedder.isLoaded && store.isEnrolled()

    /**
     * Verify a VAD segment against the enrolled voiceprint.
     *
     * @return true if the segment matches the enrolled voice, false if it
     *   should be dropped. Returns true (pass-through) if not enrolled or
     *   embedder not loaded — the enrollment gate blocks service start,
     *   so this is a safety fallback.
     */
    suspend fun verify(segment: FloatArray): Boolean = withContext(Dispatchers.IO) {
        val enrolled = store.getVoiceprint()
        if (enrolled == null) {
            Timber.w("No enrolled voiceprint, passing through")
            return@withContext true
        }

        val embedding = embedder.embed(segment)
        if (embedding == null) {
            Timber.w("Embedding failed, dropping segment")
            return@withContext false
        }

        val threshold = store.getThreshold()
        val match = verifier.verify(embedding, enrolled, threshold)
        if (!match) {
            Timber.d("Speaker verification: no match")
        }
        match
    }
}
