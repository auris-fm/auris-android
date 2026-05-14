package au.com.shiftyjelly.pocketcasts.voicecontrol.model

import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.PcmAudioFrame
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

@Singleton
class VoiceEnrollmentManager @Inject constructor(
    private val store: SpeakerVerificationStore,
    private val embedder: SpeakerEmbedder,
    private val verifier: SpeakerVerifier,
) {
    private val _state = MutableStateFlow<VoiceEnrollmentState>(
        if (store.isEnrolled()) VoiceEnrollmentState.Enrolled(store.getEnrollmentTimestamp())
        else VoiceEnrollmentState.NotEnrolled
    )
    val state: StateFlow<VoiceEnrollmentState> = _state.asStateFlow()

    companion object {
        const val REQUIRED_UTTERANCES = 3
        private const val EMBEDDING_DIM = 192
    }

    fun enroll(utterances: List<VoiceUtteranceClip>) {
        require(utterances.size >= REQUIRED_UTTERANCES) {
            "Need >= $REQUIRED_UTTERANCES utterances, got ${utterances.size}"
        }
        _state.value = VoiceEnrollmentState.Enrolling

        val embeddings = utterances.take(REQUIRED_UTTERANCES).map { clip ->
            embedder.embed(pcmToFloat(clip))
                ?: throw IllegalStateException("SpeakerEmbedder failed")
        }
        val averaged = FloatArray(EMBEDDING_DIM) { i ->
            embeddings.sumOf { it[i].toDouble() }.toFloat() / embeddings.size
        }
        val now = System.currentTimeMillis()
        store.saveEmbedding(averaged)
        store.saveEnrollmentTimestamp(now)
        _state.value = VoiceEnrollmentState.Enrolled(now)
        Timber.i("Voice enrolled from $REQUIRED_UTTERANCES utterances")
    }

    fun verify(clip: VoiceUtteranceClip): Boolean {
        val enrolled = store.getEmbedding() ?: return false
        val candidate = embedder.embed(pcmToFloat(clip)) ?: return false
        return verifier.verify(enrolled, candidate)
    }

    fun clear() {
        store.clear()
        _state.value = VoiceEnrollmentState.NotEnrolled
    }

    private fun pcmToFloat(clip: VoiceUtteranceClip): FloatArray {
        val total = clip.frames.sumOf { it.samples.size }
        val result = FloatArray(total)
        var off = 0
        for (f in clip.frames) for (s in f.samples) result[off++] = s.toFloat() / Short.MAX_VALUE.toFloat()
        return result
    }
}
