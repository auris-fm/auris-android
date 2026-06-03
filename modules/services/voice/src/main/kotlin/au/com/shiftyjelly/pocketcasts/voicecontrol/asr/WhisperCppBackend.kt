package au.com.shiftyjelly.pocketcasts.voicecontrol.asr

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

@Singleton
class WhisperCppBackend @Inject constructor() : AsrBackend {

    private var modelFile: File? = null

    override suspend fun ensureReady(): Result<Unit> = withContext(Dispatchers.IO) {
        val file = modelFile
        if (file != null && file.exists() && file.length() > 0) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Whisper model not found or empty"))
        }
    }

    /**
     * Sets the model file path. Must be called before [ensureReady].
     * Called by [au.com.shiftyjelly.pocketcasts.voicecontrol.model.ModelManager] after download.
     */
    fun setModelFile(file: File) {
        modelFile = file
    }

    override suspend fun transcribe(samples: FloatArray, sampleRateHz: Int): AsrResult = withContext(Dispatchers.IO) {
        val path = modelFile?.absolutePath
            ?: return@withContext AsrResult(text = "", detectedLanguage = null)
        val shortSamples = ShortArray(samples.size) { i ->
            (samples[i] * 32768f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        try {
            val text = WhisperNative.transcribe(path, shortSamples, sampleRateHz)
            Timber.i("Whisper ASR: '%s'", text)
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                AsrResult(text = "", detectedLanguage = null)
            } else {
                // whisper.cpp translate mode always outputs English
                AsrResult(text = trimmed, detectedLanguage = "en")
            }
        } catch (e: Exception) {
            Timber.e(e, "Whisper transcription failed")
            AsrResult(text = "", detectedLanguage = null)
        }
    }

    override val requiredModel: ModelSpec = ModelSpec(
        files = listOf(
            ModelFile(
                url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
                filename = "ggml-small-q5_1.bin",
                sha256 = "",
            ),
        ),
        targetDir = "whisper-model",
    )

    override val capabilities: AsrCapabilities = AsrCapabilities(
        supportedLanguages = emptySet(), // All languages via translate-to-English
        canTranslateToEnglish = true,
        requiresSnapdragon = false,
    )

    override fun release() {
        // Model file persists; only native resources (if any) should be released here.
    }
}
