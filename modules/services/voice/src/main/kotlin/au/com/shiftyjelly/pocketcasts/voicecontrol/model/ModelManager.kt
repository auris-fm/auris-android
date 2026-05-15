package au.com.shiftyjelly.pocketcasts.voicecontrol.model

import android.content.Context
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber

@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val WHISPER_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin"
        const val SMOL_LM_URL = "https://huggingface.co/mfuntowicz/SmolLM2-360M-Instruct-Q4_K_M-GGUF/resolve/main/smollm2-360m-instruct-q4_k_m.gguf"

        @VisibleForTesting
        internal const val WHISPER_SHA256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"

        @VisibleForTesting
        internal const val SMOL_LM_SHA256 = "8856952e27c65a87618f8347d1d06328c3953af04e8327b6dd1fab6670358fd0"
    }

    @VisibleForTesting
    internal var filesDir: File = context.filesDir

    private val whisperDir get() = File(filesDir, "whisper-model")
    private val smolLmDir get() = File(filesDir, "smol-lm-model")

    val whisperModelFile get() = File(whisperDir, "ggml-base.bin")
    val smolLmModelFile get() = File(smolLmDir, "smolLM2-360M-instruct-Q4_K_M.gguf")

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.NotStarted)
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    fun areModelsReady(): Boolean {
        return whisperModelFile.exists() && smolLmModelFile.exists()
    }

    suspend fun ensureModels(): Result<Unit> = withContext(Dispatchers.IO) {
        if (areModelsReady()) {
            _downloadState.value = ModelDownloadState.Ready
            return@withContext Result.success(Unit)
        }
        try {
            whisperDir.mkdirs()
            smolLmDir.mkdirs()
            downloadFile(WHISPER_URL, whisperModelFile, "whisper", WHISPER_SHA256)
            downloadFile(SMOL_LM_URL, smolLmModelFile, "SmolLM", SMOL_LM_SHA256)
            _downloadState.value = ModelDownloadState.Ready
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Model download failed")
            _downloadState.value = ModelDownloadState.Failed(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    /**
     * Downloads [urlStr] to a temporary file, then atomically renames it to [dest]
     * on success. If the process is killed mid-download, only the `.tmp` file exists
     * and the download will be retried from scratch on the next launch.
     */
    private fun downloadFile(urlStr: String, dest: File, label: String, expectedSha256: String) {
        if (dest.exists()) {
            Timber.i("$label model already downloaded")
            return
        }
        Timber.i("$label model download starting from $urlStr")
        val tmpFile = File(dest.parentFile, "${dest.name}.tmp")
        var maxRetries = 5
        while (maxRetries > 0) {
            try {
                val offset = if (tmpFile.exists()) tmpFile.length() else 0L
                if (offset > 0) Timber.i("$label resuming from byte $offset")
                val connection = URL(urlStr).openConnection() as HttpURLConnection
                connection.connectTimeout = 60000
                connection.readTimeout = 60000
                if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                val code = connection.responseCode
                if (code != 200 && code != 206) throw Exception("HTTP $code")
                val totalBytes = connection.contentLengthLong + offset
                Timber.i("$label downloading %d bytes total", totalBytes)
                val lastProgressLog = mutableListOf(0)
                connection.inputStream.use { input ->
                    FileOutputStream(tmpFile, offset > 0).use { output ->
                        val buffer = ByteArray(65536)
                        var read: Int
                        var downloaded = offset
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            val pct = if (totalBytes > 0) (downloaded * 100 / totalBytes).toInt() else 0
                            val lastPct = lastProgressLog[0]
                            if (pct - lastPct >= 10) {
                                Timber.i("$label download: %d%% (%d/%d MB)", pct, downloaded / 1_000_000, totalBytes / 1_000_000)
                                lastProgressLog[0] = pct
                            }
                            if (totalBytes > 0) {
                                _downloadState.value = ModelDownloadState.Downloading(
                                    progressPercent = pct,
                                    modelLabel = label,
                                )
                            }
                        }
                    }
                }
                connection.disconnect()
                verifySha256(tmpFile, expectedSha256, label)
                tmpFile.renameTo(dest)
                Timber.i("$label model download complete")
                maxRetries = 0
            } catch (e: Exception) {
                maxRetries--
                if (maxRetries <= 0) {
                    Timber.e(e, "$label download failed after all retries")
                    throw e
                }
                Timber.w(e, "$label download interrupted, retrying (%d left)", maxRetries)
                Thread.sleep(3000)
            }
        }
    }

    private fun verifySha256(file: File, expected: String, label: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            file.delete()
            Timber.e("$label model hash mismatch — got $actual, expected $expected")
            throw Exception("$label model corrupt (SHA256 mismatch)")
        }
        Timber.i("$label model hash verified")
    }
}

sealed interface ModelDownloadState {
    data object NotStarted : ModelDownloadState
    data class Downloading(val progressPercent: Int, val modelLabel: String = "") : ModelDownloadState
    data object Ready : ModelDownloadState
    data class Failed(val reason: String) : ModelDownloadState
}
