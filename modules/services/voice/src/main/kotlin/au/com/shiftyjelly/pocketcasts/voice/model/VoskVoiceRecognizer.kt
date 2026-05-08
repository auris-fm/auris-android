package au.com.shiftyjelly.pocketcasts.voice.model

import au.com.shiftyjelly.pocketcasts.voice.audio.PcmAudioFrame
import au.com.shiftyjelly.pocketcasts.voice.intent.VoiceRecognitionResult
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

class VoskVoiceRecognizer @Inject constructor() : VoiceRecognizer {

    private var recognizer: org.vosk.Recognizer? = null
    private var model: org.vosk.Model? = null
    private var isInitialized = false

    override suspend fun recognize(
        clip: VoiceUtteranceClip,
        context: VoiceRecognitionContext,
    ): VoiceRecognitionResult? = withContext(Dispatchers.IO) {
        try {
            ensureInitialized()
            val voskRecognizer = recognizer ?: return@withContext null

            // Feed each frame's samples to Vosk
            for (frame in clip.frames) {
                if (voskRecognizer.acceptWaveForm(frame.samples, frame.samples.size)) {
                    // Final result ready
                    val result = JSONObject(voskRecognizer.result)
                    val text = result.optString("text", "")
                    val confidence = result.optDouble("confidence", 0.0).toFloat()
                    if (text.isNotBlank()) {
                        Timber.i("Vosk: '$text' conf=$confidence")
                        voskRecognizer.reset()
                        return@withContext VoiceRecognitionResult(text, confidence)
                    }
                }
            }

            // Check partial result
            val partial = JSONObject(voskRecognizer.partialResult)
            val partialText = partial.optString("partial", "")
            voskRecognizer.reset()

            if (partialText.isNotBlank()) {
                Timber.i("Vosk partial: '$partialText'")
                VoiceRecognitionResult(partialText, 0.5f)
            } else {
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Vosk recognition error")
            null
        }
    }

    private fun ensureInitialized() {
        if (isInitialized) return
        // Model will be set externally via setModel()
        // For now, recognizer is created when model is available
    }

    fun setModelPath(path: String) {
        model = org.vosk.Model(path)
        recognizer = org.vosk.Recognizer(model, 16000.0f)
        isInitialized = true
        Timber.i("Vosk model loaded from: $path")
    }

    fun isModelReady(): Boolean = isInitialized

    fun release() {
        recognizer?.close()
        model?.close()
        recognizer = null
        model = null
        isInitialized = false
    }

    fun toShortArray(frames: List<PcmAudioFrame>): ShortArray {
        val totalSize = frames.sumOf { it.samples.size }
        val result = ShortArray(totalSize)
        var offset = 0
        for (frame in frames) {
            frame.samples.copyInto(result, offset)
            offset += frame.samples.size
        }
        return result
    }
}
