package au.com.shiftyjelly.pocketcasts.voice.model

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
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
        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        private const val MODEL_DIR = "vosk-model"
        private const val EXPECTED_DIR = "vosk-model-small-en-us-0.15"
        private const val README_FILE = "README"
    }

    private val modelRoot = File(context.filesDir, MODEL_DIR)
    private val modelPath = File(modelRoot, EXPECTED_DIR)

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.NotStarted)
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    fun getModelPath(): String? {
        return if (isModelReady()) modelPath.absolutePath else null
    }

    fun isModelReady(): Boolean {
        val readme = File(modelPath, README_FILE)
        return readme.exists() && readme.length() > 0
    }

    suspend fun ensureModel(): Result<String> = withContext(Dispatchers.IO) {
        if (isModelReady()) {
            Timber.i("Vosk model already downloaded at: ${modelPath.absolutePath}")
            return@withContext Result.success(modelPath.absolutePath)
        }

        try {
            _downloadState.value = ModelDownloadState.Downloading(0)
            val zipFile = File(modelRoot, "model.zip")
            modelRoot.mkdirs()

            downloadFile(MODEL_URL, zipFile)
            _downloadState.value = ModelDownloadState.Extracting

            extractZip(zipFile, modelRoot)
            zipFile.delete()

            if (isModelReady()) {
                Timber.i("Vosk model downloaded and extracted to: ${modelPath.absolutePath}")
                _downloadState.value = ModelDownloadState.Ready
                Result.success(modelPath.absolutePath)
            } else {
                Timber.e("Model extraction failed - model dir not found")
                _downloadState.value = ModelDownloadState.Failed("Extraction failed")
                Result.failure(Exception("Model extraction failed"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Model download failed")
            _downloadState.value = ModelDownloadState.Failed(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    private fun downloadFile(urlStr: String, dest: File) {
        val url = URL(urlStr)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 30000
        connection.readTimeout = 30000

        val contentLength = connection.contentLength
        connection.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead: Long = 0
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    if (contentLength > 0) {
                        val progress = (totalRead * 100 / contentLength).toInt()
                        _downloadState.value = ModelDownloadState.Downloading(progress)
                    }
                }
            }
        }
        connection.disconnect()
        Timber.i("Download complete: ${dest.length()} bytes")
    }

    private fun extractZip(zipFile: File, destDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val entryFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    entryFile.mkdirs()
                } else {
                    entryFile.parentFile?.mkdirs()
                    FileOutputStream(entryFile).use { output ->
                        zis.copyTo(output)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        Timber.i("Extraction complete to: ${destDir.absolutePath}")
    }
}

sealed interface ModelDownloadState {
    data object NotStarted : ModelDownloadState
    data class Downloading(val progressPercent: Int) : ModelDownloadState
    data object Extracting : ModelDownloadState
    data object Ready : ModelDownloadState
    data class Failed(val reason: String) : ModelDownloadState
}
