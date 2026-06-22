package au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

/**
 * Wake word detector using an openWakeWord Conv-Attention classifier trained via livekit-wakeword.
 *
 * The deployment threshold is loaded from [assets/oww/auris_eval.json] (optimal_threshold),
 * falling back to a hardcoded default if the file is missing or unreadable.
 *
 * Known limitation: the model was trained on synthetic TTS audio augmented with room impulse
 * responses and background noise. Scores on real voice captured through the phone microphone
 * are significantly lower than on synthetic training clips (~0.05 vs ~0.95). Improving
 * real-voice detection requires fine-tuning with phone-recorded wake word examples.
 */
@Singleton
class OpenWakeWordDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) : WakeWordDetector {

    override val isReady: Boolean
        get() = ready

    @Volatile
    private var ready = false
    private val detectionThreshold: Float

    init {
        detectionThreshold = loadThreshold()
        try {
            val melModel = context.assets.open("oww/melspectrogram.onnx").use { it.readBytes() }
            val embedModel = context.assets.open("oww/embedding_model.onnx").use { it.readBytes() }
            val classifierModel = context.assets.open("oww/auris.onnx").use { it.readBytes() }

            ready = WakeWordJni.nativeInit(melModel, embedModel, classifierModel, detectionThreshold)
            if (ready) {
                Timber.i("OpenWakeWordDetector initialized (threshold=%.3f)", detectionThreshold)
            } else {
                Timber.e("OpenWakeWordDetector failed to initialize native pipeline")
            }
        } catch (e: Exception) {
            Timber.e(e, "OpenWakeWordDetector init failed")
            ready = false
        }
    }

    override suspend fun detect(segment: FloatArray, sampleRateHz: Int): WakeWordResult {
        if (!ready) return WakeWordResult(detected = false)
        if (sampleRateHz != 16000) return WakeWordResult(detected = false)

        return withContext(Dispatchers.IO) {
            try {
                val score = WakeWordJni.nativeDetect(segment, sampleRateHz)
                if (score < 0f) {
                    WakeWordResult(detected = false)
                } else if (score >= detectionThreshold) {
                    Timber.i("Wake word detected (score=%.3f)", score)
                    WakeWordResult(
                        detected = true,
                        confidence = score.coerceAtMost(1f),
                        remainderSamples = extractRemainder(segment, score),
                    )
                } else {
                    Timber.i("Wake word score: %.3f (threshold=%.3f)", score, detectionThreshold)
                    WakeWordResult(detected = false)
                }
            } catch (e: Exception) {
                Timber.w(e, "Wake word detection failed")
                WakeWordResult(detected = false)
            }
        }
    }

    override fun release() {
        try {
            WakeWordJni.nativeRelease()
            ready = false
        } catch (e: Exception) {
            Timber.w(e, "Failed to release wake word detector")
        }
    }

    private fun loadThreshold(): Float {
        return try {
            val json = context.assets.open("oww/auris_eval.json").use { it.readBytes() }
            val threshold = JSONObject(String(json)).optDouble("optimal_threshold", -1.0)
            if (threshold > 0.0) {
                Timber.i("Loaded wake word threshold from auris_eval.json: %.3f", threshold)
                threshold.toFloat()
            } else {
                Timber.w("auris_eval.json missing optimal_threshold, using default")
                DEFAULT_THRESHOLD
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to load auris_eval.json, using default threshold")
            DEFAULT_THRESHOLD
        }
    }

    /**
     * Estimate where the wake word ends in the segment to extract the command remainder.
     *
     * The wake word "Auris" is ~500ms. We take a conservative slice starting at
     * ~600ms into the segment. If the segment is shorter than that, no remainder is returned.
     */
    private fun extractRemainder(segment: FloatArray, score: Float): FloatArray? {
        val wakeWordEndMs = 600
        val wakeWordEndSample = (wakeWordEndMs * 16000 / 1000).coerceAtMost(segment.size - 1600)
        if (wakeWordEndSample <= 0 || wakeWordEndSample >= segment.size * 0.9f) return null
        return segment.copyOfRange(wakeWordEndSample, segment.size)
    }

    private companion object {
        const val DEFAULT_THRESHOLD = 0.80f
    }
}
