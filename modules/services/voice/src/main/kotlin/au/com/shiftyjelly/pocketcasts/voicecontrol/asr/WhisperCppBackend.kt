package au.com.shiftyjelly.pocketcasts.voicecontrol.asr
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
private val annotationOnlyTranscript = Regex(
    pattern = """^(?:\s*(?:\[[^\]]+]|(?:\([^)]*\)))\s*)+$""",
)
internal fun normalizeWhisperTranscript(text: String): String? {
    val trimmed = text.trim()
    return trimmed.takeUnless { it.isEmpty() || annotationOnlyTranscript.matches(it) }
}
internal fun parseWhisperPayload(raw: String): AsrResult {
    if (!raw.trimStart().startsWith("{")) {
        val transcript = normalizeWhisperTranscript(raw)
        return if (transcript == null) {
            AsrResult(text = "", detectedLanguage = null)
        } else {
            AsrResult(text = transcript, detectedLanguage = "en")
        }
    }
    return try {
        val obj = org.json.JSONObject(raw)
        val transcript = normalizeWhisperTranscript(obj.optString("text"))
        if (transcript == null) {
            AsrResult(text = "", detectedLanguage = null)
        } else {
            val arr = obj.optJSONArray("tokens")
            val tokens = if (arr == null || arr.length() == 0) {
                null
            } else {
                List(arr.length()) { i ->
                    val t = arr.getJSONObject(i)
                    AsrToken(
                        text = t.getString("text"),
                        startMs = t.getInt("startMs"),
                        endMs = t.getInt("endMs"),
                    )
                }
            }
            AsrResult(text = transcript, detectedLanguage = "en", tokens = tokens)
        }
    } catch (_: Exception) {
        val transcript = normalizeWhisperTranscript(raw)
        if (transcript == null) {
            AsrResult(text = "", detectedLanguage = null)
        } else {
            AsrResult(text = transcript, detectedLanguage = "en")
        }
    }
}
@Singleton
class WhisperCppBackend @Inject constructor() : AsrBackend {
    private var modelFile: File? = null
    override suspend fun ensureReady(): Result<Unit> = withContext(Dispatchers.IO) {
        val file = modelFile
        if (file == null || !file.exists() || file.length() <= 0) {
            clearLoadEvidence()
            return@withContext Result.failure(IllegalStateException("Whisper model not found or empty"))
        }
        clearLoadEvidence()
        try {
            val cacheFile = java.io.File(file.parentFile, "vulkan_pipeline_cache.bin")
            WhisperNative.setPipelineCachePath(cacheFile.absolutePath)
            if (WhisperNative.init(file.absolutePath)) {
                loadVerified = true
                Result.success(Unit)
            } else {
                clearLoadEvidence()
                Result.failure(IllegalStateException("Whisper model init failed"))
            }
        } catch (e: UnsatisfiedLinkError) {
            clearLoadEvidence()
            Result.failure(IllegalStateException("Whisper native library not available", e))
        }
    }
    fun setModelFile(file: File) {
        modelFile = file
        clearProvenance()
    }
    fun setVerifiedModelFile(file: File, manifestId: String) {
        modelFile = file
        clearProvenance()
        handoffManifestId = manifestId
    }
    private fun clearProvenance() {
        loadVerified = false
        handoffManifestId = null
    }
    private fun clearLoadEvidence() {
        loadVerified = false
    }
    override suspend fun transcribe(samples: FloatArray, sampleRateHz: Int): AsrResult = withContext(Dispatchers.IO) {
        val path = modelFile?.absolutePath
            ?: return@withContext AsrResult(text = "", detectedLanguage = null)
        val shortSamples = ShortArray(samples.size) { i ->
            (samples[i] * 32768f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        try {
            val payload = WhisperNative.transcribe(path, shortSamples, sampleRateHz)
            Timber.i("Whisper ASR: '%s'", payload)
            parseWhisperPayload(payload)
        } catch (e: Exception) {
            Timber.e(e, "Whisper transcription failed")
            AsrResult(text = "", detectedLanguage = null)
        }
    }
    private var loadVerified = false
    private var handoffManifestId: String? = null
    override fun loadedModelRef(): au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef? {
        if (!loadVerified) return null
        val id = handoffManifestId ?: return null
        return au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef.Named(id, id)
    }
    override val requiredModel: ModelSpec = ModelSpec(
        files = listOf(
            ModelFile(
                url = "https://hf-mirror.com/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-small-q5_1.bin",
                filename = "ggml-small-q5_1.bin",
                sha256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
                manifestId = "whisper.cpp-ggml-small-q5_1@5359861c739e955e79d9a303bcbc70fb988958b1",
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
        clearProvenance()
        WhisperNative.freeModel()
    }
}
