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
            // P1 (reviewer 98ed63b3): EVERY failed-init return drops prior evidence, including
            // the missing/empty-file path.
            clearLoadEvidence()
            return@withContext Result.failure(IllegalStateException("Whisper model not found or empty"))
        }
        // P1 (reviewer 98ed63b3): provenance cleared for THIS attempt BEFORE any native call, so
        // a native failure — including one that surfaces as an error rather than a return value —
        // cannot leave earlier evidence standing.
        clearLoadEvidence()
        try {
            val cacheFile = java.io.File(file.parentFile, "vulkan_pipeline_cache.bin")
            WhisperNative.setPipelineCachePath(cacheFile.absolutePath)
            if (WhisperNative.init(file.absolutePath)) {
                // P1 (reviewer be375ea1, a00bb77d): successful native init of THIS artifact is
                // this backend's own load evidence; the citable identity comes from the verified
                // handoff, and must have arrived WITH this same file.
                loadVerified = true
                Result.success(Unit)
            } else {
                // A failed initialization cannot cite the handed identity.
                clearLoadEvidence()
                Result.failure(IllegalStateException("Whisper model init failed"))
            }
        } catch (e: UnsatisfiedLinkError) {
            clearLoadEvidence()
            Result.failure(IllegalStateException("Whisper native library not available", e))
        }
    }

    /**
     * Sets the model file path. Must be called before [ensureReady].
     * Called by [au.com.shiftyjelly.pocketcasts.voicecontrol.model.ModelManager] after download.
     */
    fun setModelFile(file: File) {
        modelFile = file
        // A plain assignment carries no verification: the previous identity cannot survive it.
        clearProvenance()
    }

    /**
     * P1 (reviewer a00bb77d): the wiring boundary hands over the file TOGETHER WITH the manifest
     * identity ModelManager verified for it — no second hashing, no generic verification method.
     * Only this handoff can make the artifact citable, and only after a successful native init.
     */
    fun setVerifiedModelFile(file: File, manifestId: String) {
        modelFile = file
        clearProvenance()
        handoffManifestId = manifestId
    }

    /** Failed init, a changed file and release all drop the claim. */
    private fun clearProvenance() {
        loadVerified = false
        handoffManifestId = null
    }

    /**
     * P1 (reviewer 98ed63b3): a failed initialization drops the LOAD EVIDENCE on every return
     * path; the handed identity stays so a subsequent successful init of that same file can still
     * cite it, but nothing is citable while [loadVerified] is false.
     */
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

    /**
     * P1 (reviewer a00bb77d): the manifest identity that arrived WITH the file at the wiring
     * boundary, because ModelManager had already verified those bytes against it. This backend
     * does not hash anything itself; it records what the handoff asserted and may cite it only
     * after its own successful native initialization of THAT SAME file.
     */
    private var handoffManifestId: String? = null

    // P1 (reviewer a00bb77d): the artifact DOES have a pinned identity such as
    // `whisper.cpp-ggml-small-q5_1@5359861c…`; the identity is known only when the verified
    // handoff delivered it AND this backend's own native init of that same file succeeded.
    override fun loadedModelRef(): au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef? {
        if (!loadVerified) return null
        val id = handoffManifestId ?: return null
        return au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef.Named(id, id)
    }

    override val requiredModel: ModelSpec = ModelSpec(
        files = listOf(
            ModelFile(
                // PINNED REVISION (reviewer 57719ad2, 704ef15a): resolve/main is mutable, so the
                // artifact URL names the exact repo revision (ggerganov/whisper.cpp @ 5359861c…,
                // lastModified 2024-10-29), served through the mirror per workspace instruction.
                // sha256 is the file's LFS oid at that revision, so an EXISTING cache is
                // re-verified before it can be labeled with the pinned manifest identity.
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
        // P1 (reviewer 98ed63b3): drop provenance BEFORE the native free, so a throwing release
        // cannot leave a citable identity behind.
        clearProvenance()
        WhisperNative.freeModel()
    }
}
