package au.com.shiftyjelly.pocketcasts.voicecontrol.model

import android.content.Context
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
class VoiceModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val GEMMA4_MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
        private const val GEMMA4_MODEL_DIR = "gemma4-model"
        private const val GEMMA4_MODEL_FILE = "gemma-4-E2B-it.litertlm"
        private const val GEMMA4_MODEL_SIZE = 2_581_688_320L // ~2.58 GB
        private const val GEMMA4_READY_MARKER = ".gemma4_downloaded"
    }

    private val modelDir = File(context.filesDir, GEMMA4_MODEL_DIR)
    private val modelFile = File(modelDir, GEMMA4_MODEL_FILE)
    private val readyMarker = File(modelDir, GEMMA4_READY_MARKER)

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.NotStarted)
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    fun getModelPath(): String? {
        return if (isModelReady()) modelFile.absolutePath else null
    }

    fun isModelReady(): Boolean {
        return readyMarker.exists() && modelFile.exists() && modelFile.length() > 0
    }

    suspend fun ensureModel(): Result<String> = withContext(Dispatchers.IO) {
        if (isModelReady()) {
            Timber.i("Gemma 4 model already downloaded at: ${modelFile.absolutePath}")
            return@withContext Result.success(modelFile.absolutePath)
        }

        try {
            modelDir.mkdirs()
            val existingBytes = if (modelFile.exists()) modelFile.length() else 0L
            _downloadState.value = ModelDownloadState.Downloading(
                if (existingBytes > 0 && GEMMA4_MODEL_SIZE > 0) (existingBytes * 100 / GEMMA4_MODEL_SIZE).toInt() else 0,
            )

            downloadFileResumable(GEMMA4_MODEL_URL, modelFile, GEMMA4_MODEL_SIZE)

            if (modelFile.exists() && modelFile.length() > 0) {
                readyMarker.createNewFile()
                Timber.i("Gemma 4 model downloaded to: ${modelFile.absolutePath}")
                _downloadState.value = ModelDownloadState.Ready
                Result.success(modelFile.absolutePath)
            } else {
                Timber.e("Gemma 4 model download incomplete: ${modelFile.length()} bytes")
                _downloadState.value = ModelDownloadState.Failed("Download incomplete")
                Result.failure(Exception("Gemma 4 model download incomplete"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Gemma 4 model download failed at ${modelFile.length()} bytes")
            _downloadState.value = ModelDownloadState.Failed(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    private fun downloadFileResumable(urlStr: String, dest: File, expectedSize: Long) {
        val existingBytes = if (dest.exists()) dest.length() else 0L
        var maxRetries = 5
        var offset = existingBytes
        var lastProgress = if (offset > 0 && expectedSize > 0) (offset * 100 / expectedSize).toInt() else 0

        while (offset < expectedSize && maxRetries > 0) {
            try {
                val url = URL(urlStr)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 60000
                connection.readTimeout = 60000
                connection.instanceFollowRedirects = true
                if (offset > 0) {
                    connection.setRequestProperty("Range", "bytes=$offset-")
                }

                val responseCode = connection.responseCode
                if (responseCode != 200 && responseCode != 206) {
                    throw Exception("HTTP $responseCode")
                }

                val append = responseCode == 206
                connection.inputStream.use { input ->
                    FileOutputStream(dest, append).use { output ->
                        val buffer = ByteArray(65536)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            offset += bytesRead
                            val progress = if (expectedSize > 0) (offset * 100 / expectedSize).toInt() else 0
                            if (progress != lastProgress) {
                                lastProgress = progress
                                _downloadState.value = ModelDownloadState.Downloading(progress)
                            }
                        }
                    }
                }
                connection.disconnect()
                maxRetries = 0 // success
            } catch (e: Exception) {
                maxRetries--
                if (maxRetries <= 0) throw e
                Timber.w("Download interrupted at $offset bytes, retrying ($maxRetries left): ${e.message}")
                offset = dest.length()
                Thread.sleep(3000)
            }
        }
        Timber.i("Download complete: ${dest.length()} bytes")
    }
}

sealed interface ModelDownloadState {
    data object NotStarted : ModelDownloadState
    data class Downloading(val progressPercent: Int) : ModelDownloadState
    data object Ready : ModelDownloadState
    data class Failed(val reason: String) : ModelDownloadState
}
