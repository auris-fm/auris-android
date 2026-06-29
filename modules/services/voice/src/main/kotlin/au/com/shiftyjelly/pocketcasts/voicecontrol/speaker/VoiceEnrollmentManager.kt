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
 * State machine for speaker enrollment. Exactly 3 phrases:
 *   - Monolingual: all 3 in one language.
 *   - Bilingual: 2 primary + 1 secondary.
 *   - Trilingual: 1 in each of the 3 languages.
 *
 * The voiceprint averages all 3 enrollment embeddings — including samples
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
     * Start enrollment with exactly 3 phrases across the given languages.
     *
     * @param languages Non-empty list of language tags. Splits are:
     *   1 language → [3], 2 languages → [2, 1], 3+ languages → [1, 1, 1].
     *   Example: ["en", "zh"] for a bilingual EN+ZH user → 2 en + 1 zh.
     */
    fun startEnrollment(languages: List<String> = listOf("")) {
        pending.clear()

        val nLangs = languages.size
        val counts = when {
            nLangs == 1 -> listOf(3)
            nLangs == 2 -> listOf(2, 1)
            else -> List(minOf(3, nLangs)) { 1 }
        }

        val sequence = mutableListOf<String?>()
        for (i in languages.indices) {
            val count = counts.getOrElse(i) { 0 }
            repeat(count) { sequence.add(if (languages[i].isEmpty()) null else languages[i]) }
        }
        languageSequence = sequence
        _state.value = EnrollmentState.Enrolling(
            step = 1,
            total = sequence.size,
            languageHint = sequence.firstOrNull(),
        )
        Timber.i(
            "Enrollment started: %d phrases across %d languages (split: %s)",
            sequence.size,
            nLangs,
            counts.joinToString(","),
        )
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
