package au.com.shiftyjelly.pocketcasts.voicecontrol.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Test seam over the native sherpa recognizer. The backend only needs the
 * mechanical stream→decode→result round-trip, so the unit tests can stub it.
 */
internal interface OfflineAsrRecognizer {
    /** Raw recognition output: transcript text (tags included) + structured language, if the model reported one. */
    data class RawResult(val text: String, val lang: String?)

    fun transcribe(samples: FloatArray, sampleRateHz: Int): RawResult

    fun release()
}

@Singleton
class SenseVoiceBackend @Inject constructor() : AsrBackend {

    private var recognizer: OfflineAsrRecognizer? = null

    /** Model dir the current [recognizer] was built for; null when none. */
    private var readyDir: File? = null
    private var modelDir: File? = null

    /**
     * Test seam: builds the native recognizer for the given model/tokens files.
     * Replaced in unit tests to avoid loading the native sherpa library.
     */
    internal var recognizerFactory: (modelFile: File, tokensFile: File) -> OfflineAsrRecognizer =
        { modelFile, tokensFile ->
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(model = modelFile.absolutePath),
                    tokens = tokensFile.absolutePath,
                    numThreads = 4,
                    provider = "cpu",
                ),
            )
            SherpaRecognizer(config)
        }

    override suspend fun ensureReady(): Result<Unit> = withContext(Dispatchers.IO) {
        val dir = modelDir
        if (dir == null || !dir.exists()) {
            return@withContext Result.failure(IllegalStateException("SenseVoice model directory not set"))
        }
        // Idempotent: rebuilding the native recognizer costs ~2s, and the
        // gate-restart path calls ensureReady on every engine start.
        if (recognizer != null && readyDir == dir) {
            return@withContext Result.success(Unit)
        }
        val modelFile = File(dir, SENSEVOICE_MODEL_FILENAME)
        val tokensFile = File(dir, SENSEVOICE_TOKENS_FILENAME)
        if (!modelFile.exists() || !tokensFile.exists()) {
            return@withContext Result.failure(IllegalStateException("SenseVoice model files missing"))
        }
        try {
            val previous = recognizer
            recognizer = null
            readyDir = null
            previous?.release()
            val created = recognizerFactory(modelFile, tokensFile)
            recognizer = created
            readyDir = dir
            Timber.i("SenseVoiceBackend ready")
            Result.success(Unit)
        } catch (e: Exception) {
            recognizer = null
            readyDir = null
            Timber.e(e, "SenseVoice initialization failed")
            Result.failure(e)
        }
    }

    override suspend fun transcribe(samples: FloatArray, sampleRateHz: Int): AsrResult = withContext(Dispatchers.IO) {
        val rec = recognizer
        if (rec == null) {
            return@withContext AsrResult(text = "", detectedLanguage = null)
        }
        try {
            val raw = rec.transcribe(samples, sampleRateHz)
            val trimmed = raw.text.trim()
            if (trimmed.isEmpty()) {
                AsrResult(text = "", detectedLanguage = null)
            } else {
                // Structured lang is the reliable LID source; the text tag is
                // only a fallback (some transcripts carry no tag).
                val lang = resolveDetectedLanguage(raw.lang, trimmed)
                AsrResult(text = stripLanguageTag(trimmed), detectedLanguage = lang)
            }
        } catch (e: Exception) {
            Timber.e(e, "SenseVoice transcription failed")
            AsrResult(text = "", detectedLanguage = null)
        }
    }

    /**
     * Sets the model directory path. Called by
     * [au.com.shiftyjelly.pocketcasts.voicecontrol.model.ModelManager] after download.
     */
    fun setModelDir(dir: File) {
        modelDir = dir
    }

    override val requiredModel: ModelSpec = ModelSpec(
        files = listOf(
            ModelFile(
                url = "$SENSEVOICE_BASE_URL/$SENSEVOICE_MODEL_FILENAME",
                filename = SENSEVOICE_MODEL_FILENAME,
                sha256 = "",
            ),
            ModelFile(
                url = "$SENSEVOICE_BASE_URL/$SENSEVOICE_TOKENS_FILENAME",
                filename = SENSEVOICE_TOKENS_FILENAME,
                sha256 = "",
            ),
        ),
        targetDir = "sensevoice-model",
    )

    override val capabilities: AsrCapabilities = AsrCapabilities(
        supportedLanguages = setOf("zh", "en", "ja", "ko", "yue"),
        canTranslateToEnglish = false,
        requiresSnapdragon = false,
    )

    override fun release() {
        recognizer?.release()
        recognizer = null
        readyDir = null
    }

    /** Thin adapter over the native sherpa recognizer. */
    private class SherpaRecognizer(config: OfflineRecognizerConfig) : OfflineAsrRecognizer {
        private val rec = OfflineRecognizer(config = config)

        override fun transcribe(samples: FloatArray, sampleRateHz: Int): OfflineAsrRecognizer.RawResult {
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(samples, sampleRateHz)
                rec.decode(stream)
                val result = rec.getResult(stream)
                return OfflineAsrRecognizer.RawResult(text = result.text, lang = result.lang)
            } finally {
                stream.release()
            }
        }

        override fun release() = rec.release()
    }

    companion object {
        private const val SENSEVOICE_BASE_URL =
            "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main"
        private const val SENSEVOICE_MODEL_FILENAME = "model.int8.onnx"
        private const val SENSEVOICE_TOKENS_FILENAME = "tokens.txt"

        // SenseVoice emits tags like <|zh|> / <|zh/en|>; structured `result.lang` is the same form.
        private val LANG_TAG = Regex("^<\\|([a-zA-Z0-9]+)(?:/[a-zA-Z0-9]+)?\\|>")
        private val PLAIN_LANG = Regex("^[a-zA-Z]{2,8}$")
        private val SUPPORTED_LANGS = setOf("zh", "en", "ja", "ko", "yue")

        /**
         * Prefer structured LID; fall back to a leading text tag.
         * Always returns a bare code (`zh`, `en`, …) so ML Kit can consume it —
         * never the raw `<|zh|>` token that sherpa puts in `result.lang`.
         */
        internal fun resolveDetectedLanguage(structuredLang: String?, text: String): String? {
            normalizeLanguageCode(structuredLang)?.let { return it }
            return detectLanguage(text)
        }

        /** Maps `<|zh|>` / `<|zh/en|>` / `zh` → `zh`; unrecognized / unsupported → null. */
        internal fun normalizeLanguageCode(raw: String?): String? {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val code = LANG_TAG.matchEntire(trimmed)?.groupValues?.get(1)?.lowercase()
                ?: trimmed.takeIf { PLAIN_LANG.matches(it) }?.lowercase()
                ?: return null
            return code.takeIf { it in SUPPORTED_LANGS }
        }

        private fun detectLanguage(text: String): String? {
            val code = LANG_TAG.find(text)?.groupValues?.get(1)?.lowercase() ?: return null
            return code.takeIf { it in SUPPORTED_LANGS }
        }

        private fun stripLanguageTag(text: String): String {
            return text.replace(LANG_TAG, "").trim()
        }
    }
}
