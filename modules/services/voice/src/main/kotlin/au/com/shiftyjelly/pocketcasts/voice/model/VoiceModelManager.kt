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
        private const val VOSK_MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        private const val VOSK_MODEL_DIR = "vosk-model"
        private const val VOSK_EXPECTED_DIR = "vosk-model-small-en-us-0.15"
        private const val VOSK_README_FILE = "README"

        private const val GEMMA4_MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
        private const val GEMMA4_MODEL_DIR = "gemma4-model"
        private const val GEMMA4_MODEL_FILE = "gemma-4-E2B-it.litertlm"
        private const val GEMMA4_MODEL_SIZE = 2_581_688_320L // ~2.58 GB
        private const val GEMMA4_READY_MARKER = ".gemma4_downloaded"
    }

    private val voskModelRoot = File(context.filesDir, VOSK_MODEL_DIR)
    private val voskModelPath = File(voskModelRoot, VOSK_EXPECTED_DIR)

    private val gemma4ModelDir = File(context.filesDir, GEMMA4_MODEL_DIR)
    private val gemma4ModelFile = File(gemma4ModelDir, GEMMA4_MODEL_FILE)
    private val gemma4ReadyMarker = File(gemma4ModelDir, GEMMA4_READY_MARKER)

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.NotStarted)
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    // Vosk model support

    fun getModelPath(): String? {
        return if (isModelReady()) voskModelPath.absolutePath else null
    }

    fun isModelReady(): Boolean {
        val readme = File(voskModelPath, VOSK_README_FILE)
        return readme.exists() && readme.length() > 0
    }

    suspend fun ensureModel(): Result<String> = withContext(Dispatchers.IO) {
        if (isModelReady()) {
            Timber.i("Vosk model already downloaded at: ${voskModelPath.absolutePath}")
            return@withContext Result.success(voskModelPath.absolutePath)
        }

        try {
            _downloadState.value = ModelDownloadState.Downloading(0)
            val zipFile = File(voskModelRoot, "model.zip")
            voskModelRoot.mkdirs()

            downloadFile(VOSK_MODEL_URL, zipFile)
            _downloadState.value = ModelDownloadState.Extracting

            extractZip(zipFile, voskModelRoot)
            zipFile.delete()

            if (isModelReady()) {
                Timber.i("Vosk model downloaded and extracted to: ${voskModelPath.absolutePath}")
                _downloadState.value = ModelDownloadState.Ready
                Result.success(voskModelPath.absolutePath)
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

    // Gemma 4 model support

    fun getGemma4ModelPath(): String? {
        return if (isGemma4ModelReady()) gemma4ModelFile.absolutePath else null
    }

    fun isGemma4ModelReady(): Boolean {
        return gemma4ReadyMarker.exists() && gemma4ModelFile.exists()
    }

    suspend fun ensureGemma4Model(): Result<String> = withContext(Dispatchers.IO) {
        if (isGemma4ModelReady()) {
            Timber.i("Gemma 4 model already downloaded at: ${gemma4ModelFile.absolutePath}")
            return@withContext Result.success(gemma4ModelFile.absolutePath)
        }

        try {
            gemma4ModelDir.mkdirs()
            val existingBytes = if (gemma4ModelFile.exists()) gemma4ModelFile.length() else 0L
            _downloadState.value = ModelDownloadState.Downloading(
                if (existingBytes > 0 && GEMMA4_MODEL_SIZE > 0) (existingBytes * 100 / GEMMA4_MODEL_SIZE).toInt() else 0,
            )

            downloadFileResumable(GEMMA4_MODEL_URL, gemma4ModelFile, GEMMA4_MODEL_SIZE)

            if (gemma4ModelFile.exists() && gemma4ModelFile.length() > 0) {
                gemma4ReadyMarker.createNewFile()
                Timber.i("Gemma 4 model downloaded to: ${gemma4ModelFile.absolutePath}")
                _downloadState.value = ModelDownloadState.Ready
                Result.success(gemma4ModelFile.absolutePath)
            } else {
                Timber.e("Gemma 4 model download incomplete: ${gemma4ModelFile.length()} bytes")
                _downloadState.value = ModelDownloadState.Failed("Download incomplete")
                Result.failure(Exception("Gemma 4 model download incomplete"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Gemma 4 model download failed at ${gemma4ModelFile.length()} bytes")
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
