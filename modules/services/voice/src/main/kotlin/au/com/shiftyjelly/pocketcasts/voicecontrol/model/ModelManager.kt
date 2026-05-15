package au.com.shiftyjelly.pocketcasts.voicecontrol.model

import android.content.Context
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
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
            downloadFile(WHISPER_URL, whisperModelFile, "whisper")
            downloadFile(SMOL_LM_URL, smolLmModelFile, "SmolLM")
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
    private fun downloadFile(urlStr: String, dest: File, label: String) {
        if (dest.exists()) {
            Timber.i("$label model already downloaded")
            return
        }
        val tmpFile = File(dest.parentFile, "${dest.name}.tmp")
        var maxRetries = 5
        while (maxRetries > 0) {
            try {
                val offset = if (tmpFile.exists()) tmpFile.length() else 0L
                val connection = URL(urlStr).openConnection() as HttpURLConnection
                connection.connectTimeout = 60000
                connection.readTimeout = 60000
                if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                val code = connection.responseCode
                if (code != 200 && code != 206) throw Exception("HTTP $code")
                val totalBytes = connection.contentLengthLong + offset
                connection.inputStream.use { input ->
                    FileOutputStream(tmpFile, offset > 0).use { output ->
                        val buffer = ByteArray(65536)
                        var read: Int
                        var downloaded = offset
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (totalBytes > 0) {
                                _downloadState.value = ModelDownloadState.Downloading(
                                    progressPercent = (downloaded * 100 / totalBytes).toInt(),
                                    modelLabel = label,
                                )
                            }
                        }
                    }
                }
                connection.disconnect()
                // Atomic rename ensures only complete files appear at dest
                tmpFile.renameTo(dest)
                maxRetries = 0
            } catch (e: Exception) {
                maxRetries--
                if (maxRetries <= 0) throw e
                Timber.w("$label download interrupted, retrying ($maxRetries left): ${e.message}")
                Thread.sleep(3000)
            }
        }
    }
}

sealed interface ModelDownloadState {
    data object NotStarted : ModelDownloadState
    data class Downloading(val progressPercent: Int, val modelLabel: String = "") : ModelDownloadState
    data object Ready : ModelDownloadState
    data class Failed(val reason: String) : ModelDownloadState
}
