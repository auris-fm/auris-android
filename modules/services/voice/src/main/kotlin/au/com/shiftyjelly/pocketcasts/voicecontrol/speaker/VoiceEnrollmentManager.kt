package au.com.shiftyjelly.pocketcasts.voicecontrol.speaker

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

sealed class EnrollmentState {
    data object NotEnrolled : EnrollmentState()
    data class Enrolling(
        val step: Int,
        val total: Int,
        val languageHint: String? = null, // e.g. "en", "zh" — null if monolingual
    ) : EnrollmentState()
    data object Enrolled : EnrollmentState()
}

/**
 * State machine for speaker enrollment.
 *
 * For bilingual users: 3 phrases from primary language + 1 phrase per
 * additional language (typically 4-5 total). For monolingual: 3 phrases.
 *
 * The voiceprint averages all enrollment embeddings — including samples
 * from multiple languages produces a language-robust voiceprint.
 */
@Singleton
class VoiceEnrollmentManager @Inject constructor(
    private val embedder: SpeakerEmbedder,
    private val store: SpeakerVerificationStore,
) {
    private val _state = MutableStateFlow<EnrollmentState>(
        if (store.isEnrolled()) EnrollmentState.Enrolled else EnrollmentState.NotEnrolled,
    )
    val state: StateFlow<EnrollmentState> = _state
    private val pending = mutableListOf<FloatArray>()

    /** Language sequence for current enrollment. Empty list = monolingual. */
    private var languageSequence: List<String?> = emptyList()

    fun isEnrolled(): Boolean = store.isEnrolled()

    /**
     * Start enrollment with per-language phrase counts.
     *
     * @param languageCounts Pairs of (languageTag, phraseCount). The first
     *   language is the primary and gets 3 phrases; each additional gets 1.
     *   Example: [("en", 3), ("zh", 1)] for a bilingual EN+ZH user.
     */
    fun startEnrollment(languageCounts: List<Pair<String, Int>>) {
        pending.clear()

        // Build the phrase sequence: 3 from primary + 1 from each additional
        val sequence = mutableListOf<String?>()
        for ((lang, count) in languageCounts) {
            repeat(count) { sequence.add(lang) }
        }
        languageSequence = sequence
        _state.value = EnrollmentState.Enrolling(
            step = 1,
            total = sequence.size,
            languageHint = sequence.firstOrNull(),
        )
        Timber.i("Enrollment started: %d phrases across %d languages", sequence.size, languageCounts.size)
    }

    /** Convenience: monolingual enrollment with 3 phrases. */
    fun startEnrollment() {
        startEnrollment(listOf(null to 3))
    }

    fun submitUtterance(audio: FloatArray): Result<Unit> {
        val embedding = embedder.embed(audio)
            ?: return Result.failure(Exception("Embedder not ready"))

        pending.add(embedding)
        val nextIdx = pending.size // 0-based: 0 completed → next is step 2

        return if (nextIdx >= languageSequence.size) {
            val voiceprint = averageEmbeddings(pending)
            store.setVoiceprint(voiceprint)
            store.setThreshold(embedder.threshold)
            _state.value = EnrollmentState.Enrolled
            Timber.i("Enrollment complete (%d phrases)", pending.size)
            Result.success(Unit)
        } else {
            _state.value = EnrollmentState.Enrolling(
                step = nextIdx + 1,
                total = languageSequence.size,
                languageHint = languageSequence.getOrNull(nextIdx),
            )
            Timber.i(
                "Enrollment step %d/%d (lang=%s)",
                nextIdx + 1,
                languageSequence.size,
                languageSequence.getOrNull(nextIdx) ?: "N/A",
            )
            Result.success(Unit)
        }
    }

    fun clear() {
        store.clear()
        pending.clear()
        languageSequence = emptyList()
        _state.value = EnrollmentState.NotEnrolled
    }

    private fun averageEmbeddings(embeddings: List<FloatArray>): FloatArray {
        val dim = embeddings[0].size
        val avg = FloatArray(dim)
        for (emb in embeddings) {
            for (i in 0 until dim) avg[i] += emb[i]
        }
        for (i in 0 until dim) avg[i] /= embeddings.size.toFloat()
        return l2Normalize(avg)
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var norm = 0.0
        for (x in v) norm += x.toDouble() * x.toDouble()
        norm = kotlin.math.sqrt(norm)
        if (norm > 0) {
            for (i in v.indices) v[i] = (v[i] / norm).toFloat()
        }
        return v
    }
}
