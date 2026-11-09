package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue2

import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sends one recording as a single multipart request and reports the status code.
 *
 * The wire shape is the server's, exactly:
 * - `metadata` is a plain form field with NO filename. With a filename it arrives as a file and the
 *   server refuses the request, so the absence is part of the contract.
 * - `audio` is a file part named `recording.wav` with type `audio/wav`.
 * - `Content-Length` is sent explicitly. The server refuses a request that does not state its size.
 *
 * The whole call runs under one deadline. Connect and read timeouts alone do not bound a stalled
 * write, and one recording that never finishes would hold the loop; when the deadline expires the
 * connection is disconnected, which is what actually interrupts the blocked write.
 */
class RecordingUploader(
    private val endpoint: String,
    private val authorization: () -> String?,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) {
    suspend fun send(recording: FolderQueue.Recording): Int? {
        val bearer = authorization() ?: return null
        val metadata: ByteArray = recording.metadata.readBytes()
        val audioBytes: ByteArray = recording.audio.readBytes()
        if (metadata.size > PlainRecordLimits.MAX_METADATA_BYTES) return null
        if (audioBytes.size > PlainRecordLimits.MAX_AUDIO_BYTES) return null

        val boundary = "auris-" + UUID.randomUUID().toString().replace("-", "")
        val body = buildBody(boundary, metadata, audioBytes)

        // One budget for the whole call, so a stalled write cannot outlive it.
        return withTimeoutOrNull(WHOLE_CALL_BUDGET_MS) {
            var connection: HttpURLConnection? = null
            try {
                runInterruptible(Dispatchers.IO) {
                    val c = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        connectTimeout = connectTimeoutMs
                        readTimeout = readTimeoutMs
                        setRequestProperty("Authorization", "Bearer $bearer")
                        setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                        // Required by the server: an unstated size has no bound to check.
                        setFixedLengthStreamingMode(body.size)
                    }
                    connection = c
                    c.outputStream.use { it.write(body) }
                    c.responseCode
                }
            } catch (t: Throwable) {
                // No answer. The loop treats that as retryable, never as a refusal.
                null
            } finally {
                runCatching { connection?.disconnect() }
            }
        }
    }

    /** Exposed so a test can assert the exact bytes against the server's parser. */
    internal fun buildBodyForTest(boundary: String, metadata: ByteArray, audio: ByteArray): ByteArray = buildBody(boundary, metadata, audio)

    /** The exact multipart body, so its length is known before it is sent. */
    private fun buildBody(boundary: String, metadata: ByteArray, audio: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(metadata.size + audio.size + 512)
        // Metadata: a plain field. No filename, or the server sees a file and refuses the request.
        out.write(("--$boundary\r\n").toByteArray(Charsets.UTF_8))
        out.write("Content-Disposition: form-data; name=\"metadata\"\r\n".toByteArray(Charsets.UTF_8))
        out.write("Content-Type: application/json\r\n\r\n".toByteArray(Charsets.UTF_8))
        out.write(metadata)
        out.write("\r\n".toByteArray(Charsets.UTF_8))
        // Audio: a file part, with the filename the server requires.
        out.write(("--$boundary\r\n").toByteArray(Charsets.UTF_8))
        out.write(
            "Content-Disposition: form-data; name=\"audio\"; filename=\"recording.wav\"\r\n".toByteArray(Charsets.UTF_8),
        )
        out.write("Content-Type: audio/wav\r\n\r\n".toByteArray(Charsets.UTF_8))
        out.write(audio)
        out.write("\r\n".toByteArray(Charsets.UTF_8))
        out.write(("--$boundary--\r\n").toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    companion object {
        /** The whole call, including the upload write, gets this long and no longer. */
        const val WHOLE_CALL_BUDGET_MS = 90_000L
    }
}

/** The server's own bounds, so nothing here can send a request it would refuse on size alone. */
internal object PlainRecordLimits {
    const val MAX_METADATA_BYTES = 65_536
    const val MAX_AUDIO_BYTES = 1_920_044
}
