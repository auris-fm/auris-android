package au.com.shiftyjelly.pocketcasts.voice.model

import au.com.shiftyjelly.pocketcasts.voice.intent.VoiceRecognitionResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

@Singleton
class VoskVoiceRecognizer @Inject constructor(
    private val modelManager: VoiceModelManager,
) : VoiceRecognizer {

    private var recognizer: org.vosk.Recognizer? = null
    private var model: org.vosk.Model? = null
    private var modelLoaded = false

    override suspend fun ensureReady(): Result<Unit> = withContext(Dispatchers.IO) {
        modelManager.ensureModel().map { Unit }
    }

    override suspend fun recognize(
        clip: VoiceUtteranceClip,
        context: VoiceRecognitionContext,
    ): VoiceRecognitionResult? = withContext(Dispatchers.IO) {
        try {
            ensureLoaded()
            val voskRecognizer = recognizer ?: return@withContext null

            for (frame in clip.frames) {
                if (voskRecognizer.acceptWaveForm(frame.samples, frame.samples.size)) {
                    val result = JSONObject(voskRecognizer.result)
                    val text = result.optString("text", "")
                    if (text.isNotBlank()) {
                        Timber.i("Vosk: '$text'")
                        voskRecognizer.reset()
                        return@withContext VoiceRecognitionResult(text, 0.8f)
                    }
                }
            }

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

    private fun ensureLoaded() {
        if (modelLoaded) return
        val path = modelManager.getModelPath()
        if (path != null) {
            model = org.vosk.Model(path)
            recognizer = org.vosk.Recognizer(model, 16000.0f)
            modelLoaded = true
            Timber.i("Vosk model loaded")
        }
    }

    fun isModelReady(): Boolean = modelLoaded || modelManager.isModelReady()

    fun release() {
        recognizer?.close()
        model?.close()
        recognizer = null
        model = null
        modelLoaded = false
    }
}
