package au.com.shiftyjelly.pocketcasts.voicecontrol.speaker

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

sealed class EnrollmentState {
    data object NotEnrolled : EnrollmentState()
    data class Enrolling(val step: Int, val total: Int) : EnrollmentState()
    data object Enrolled : EnrollmentState()
}

/**
 * State machine for speaker enrollment.
 *
 * NotEnrolled → Enrolling(1/3) → Enrolling(2/3) → Enrolling(3/3) → Enrolled.
 * Averages N enrollment embeddings into a single voiceprint.
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
    private val totalSteps = 3

    fun isEnrolled(): Boolean = store.isEnrolled()

    fun startEnrollment() {
        pending.clear()
        _state.value = EnrollmentState.Enrolling(1, totalSteps)
        Timber.i("Enrollment started")
    }

    fun submitUtterance(audio: FloatArray): Result<Unit> {
        val embedding = embedder.embed(audio)
            ?: return Result.failure(Exception("Embedder not ready"))

        pending.add(embedding)
        return if (pending.size >= totalSteps) {
            val voiceprint = averageEmbeddings(pending)
            store.setVoiceprint(voiceprint)
            store.setThreshold(embedder.threshold)
            _state.value = EnrollmentState.Enrolled
            Timber.i("Enrollment complete")
            Result.success(Unit)
        } else {
            _state.value = EnrollmentState.Enrolling(pending.size + 1, totalSteps)
            Timber.i("Enrollment step %d/%d", pending.size, totalSteps)
            Result.success(Unit)
        }
    }

    fun clear() {
        store.clear()
        pending.clear()
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
