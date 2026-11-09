package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue2

import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends one recording as a single multipart request and reports the status code.
 *
 * The body is `metadata` (application/json) then `audio` (audio/wav). Nothing is a digest or
 * canonical form: the server parses the JSON and validates the WAV itself.
 *
 * This returns the status and nothing else, because the status is the entire decision input - see
 * [AnswerTable]. Any failure to produce a status surfaces as null, which the loop treats as "no
 * answer" and retries.
 */
class RecordingUploader(
    private val endpoint: String,
    private val authorization: () -> String?,
    private val connectTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 60_000,
) {
    suspend fun send(recording: FolderQueue.Recording): Int? = withContext(Dispatchers.IO) {
        val bearer = authorization() ?: return@withContext null
        val boundary = "auris-" + UUID.randomUUID().toString().replace("-", "")
        val metadata: ByteArray = recording.metadata.readBytes()
        val audio: ByteArray = recording.audio.readBytes()

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Authorization", "Bearer $bearer")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            connection.outputStream.use { out ->
                writePart(out, boundary, "metadata", "record.json", METADATA_MIME, metadata)
                writePart(out, boundary, "audio", "recording.wav", "audio/wav", audio)
                out.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
                out.flush()
            }
            // The body is never read: the answer table has no use for it, and reading it would only
            // delay the next recording.
            connection.responseCode
        } catch (t: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun writePart(
        out: java.io.OutputStream,
        boundary: String,
        name: String,
        filename: String,
        contentType: String,
        bytes: ByteArray,
    ) {
        val header = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"").append(name)
                .append("\"; filename=\"").append(filename).append("\"\r\n")
            append("Content-Type: ").append(contentType).append("\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.write("\r\n".toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val METADATA_MIME = "application/json"
    }
}
