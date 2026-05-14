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
        const val WHISPER_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-multilingual.bin"
        const val WHISPER_EXPECTED_SIZE = 150_000_000L
        const val SMOL_LM_URL = "https://huggingface.co/hugging-quants/SmolLM2-360M-Instruct-Q4_K_M-GGUF/resolve/main/smollm2-360m-instruct-q4_k_m.gguf"
        const val SMOL_LM_EXPECTED_SIZE = 200_000_000L
    }

    @VisibleForTesting
    internal var filesDir: File = context.filesDir

    private val whisperDir get() = File(filesDir, "whisper-model")
    private val smolLmDir get() = File(filesDir, "smol-lm-model")

    val whisperModelFile get() = File(whisperDir, "ggml-base-multilingual.bin")
    val smolLmModelFile get() = File(smolLmDir, "smolLM2-360M-instruct-Q4_K_M.gguf")

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.NotStarted)
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    fun areModelsReady(): Boolean {
        return whisperModelFile.exists() && whisperModelFile.length() > 0 &&
            smolLmModelFile.exists() && smolLmModelFile.length() > 0
    }

    suspend fun ensureModels(): Result<Unit> = withContext(Dispatchers.IO) {
        if (areModelsReady()) {
            _downloadState.value = ModelDownloadState.Ready
            return@withContext Result.success(Unit)
        }
        try {
            whisperDir.mkdirs()
            smolLmDir.mkdirs()
            downloadFile(WHISPER_URL, whisperModelFile, WHISPER_EXPECTED_SIZE, "whisper")
            downloadFile(SMOL_LM_URL, smolLmModelFile, SMOL_LM_EXPECTED_SIZE, "SmolLM")
            _downloadState.value = ModelDownloadState.Ready
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Model download failed")
            _downloadState.value = ModelDownloadState.Failed(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    private fun downloadFile(urlStr: String, dest: File, expectedSize: Long, label: String) {
        if (dest.exists() && dest.length() > 0) {
            Timber.i("$label model already downloaded: ${dest.length()} bytes")
            return
        }
        var maxRetries = 5
        var offset = 0L
        while (maxRetries > 0) {
            try {
                val connection = URL(urlStr).openConnection() as HttpURLConnection
                connection.connectTimeout = 60000
                connection.readTimeout = 60000
                if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                val code = connection.responseCode
                if (code != 200 && code != 206) throw Exception("HTTP $code")
                connection.inputStream.use { input ->
                    FileOutputStream(dest, code == 206).use { output ->
                        val buffer = ByteArray(65536)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            offset += read
                            val pct = if (expectedSize > 0) (offset * 100 / expectedSize).toInt() else 0
                            _downloadState.value = ModelDownloadState.Downloading(pct, label)
                        }
                    }
                }
                connection.disconnect()
                maxRetries = 0
            } catch (e: Exception) {
                maxRetries--
                if (maxRetries <= 0) throw e
                Timber.w("$label download interrupted, retrying ($maxRetries left): ${e.message}")
                offset = dest.length()
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
