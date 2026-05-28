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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val downloadMutex = Mutex()
    companion object {
        const val SMOL_LM_URL = "https://huggingface.co/bartowski/SmolLM2-360M-Instruct-GGUF/resolve/main/SmolLM2-360M-Instruct-Q4_K_M.gguf"

        @VisibleForTesting
        internal const val SMOL_LM_SHA256 = "2fa3f013dcdd7b99f9b237717fa0b12d75bbb89984cc1274be1471a465bac9c2"

        private const val MOONSHINE_BASE_URL =
            "https://download.moonshine.ai/model/small-streaming-en/quantized"

        private val MOONSHINE_FILES = listOf(
            "adapter.ort",
            "cross_kv.ort",
            "decoder_kv.ort",
            "decoder_kv_with_attention.ort",
            "encoder.ort",
            "frontend.ort",
            "streaming_config.json",
            "tokenizer.bin",
        )
    }

    @VisibleForTesting
    internal var filesDir: File = context.filesDir

    private val smolLmDir get() = File(filesDir, "smol-lm-model")
    val smolLmModelFile get() = File(smolLmDir, "smolLM2-360M-instruct-Q4_K_M.gguf")

    val moonshineDir get() = File(filesDir, "moonshine-model")

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.NotStarted)
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    fun isModelReady(): Boolean = smolLmModelFile.exists()

    fun isMoonshineModelReady(): Boolean = MOONSHINE_FILES.all { File(moonshineDir, it).exists() }

    suspend fun ensureMoonshineModel(): Result<Unit> = withContext(Dispatchers.IO) {
        if (isMoonshineModelReady()) return@withContext Result.success(Unit)
        downloadMutex.withLock {
            if (isMoonshineModelReady()) return@withContext Result.success(Unit)
            try {
                moonshineDir.mkdirs()
                for (file in MOONSHINE_FILES) {
                    val url = "$MOONSHINE_BASE_URL/$file"
                    val dest = File(moonshineDir, file)
                    downloadFile(url, dest, "Moonshine/$file", expectedSha256 = "")
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Timber.e(e, "Moonshine model download failed")
                Result.failure(e)
            }
        }
    }

    suspend fun ensureModel(): Result<Unit> = withContext(Dispatchers.IO) {
        if (isModelReady()) {
            _downloadState.value = ModelDownloadState.Ready
            return@withContext Result.success(Unit)
        }
        downloadMutex.withLock {
            if (isModelReady()) {
                _downloadState.value = ModelDownloadState.Ready
                return@withContext Result.success(Unit)
            }
            try {
                smolLmDir.mkdirs()
                downloadFile(SMOL_LM_URL, smolLmModelFile, "SmolLM", SMOL_LM_SHA256)
                _downloadState.value = ModelDownloadState.Ready
                Result.success(Unit)
            } catch (e: Exception) {
                Timber.e(e, "Model download failed")
                _downloadState.value = ModelDownloadState.Failed(e.message ?: "Unknown error")
                Result.failure(e)
            }
        }
    }

    private fun downloadFile(urlStr: String, dest: File, label: String, expectedSha256: String) {
        if (dest.exists()) {
            if (expectedSha256.isEmpty() || sha256Matches(dest, expectedSha256)) {
                Timber.i("$label model already downloaded (SHA256 verified)")
                return
            }
            Timber.w("$label model file exists but SHA256 mismatch, re-downloading")
            dest.delete()
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
                if (expectedSha256.isNotEmpty()) verifySha256(tmpFile, expectedSha256, label)
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

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Matches(file: File, expected: String): Boolean {
        return try {
            computeSha256(file) == expected
        } catch (e: Exception) {
            false
        }
    }

    private fun verifySha256(file: File, expected: String, label: String) {
        val actual = computeSha256(file)
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
