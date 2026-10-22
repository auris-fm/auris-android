package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import com.squareup.moshi.JsonReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import okio.Buffer

/**
 * The REAL upload transport: it POSTs the immutable capture's exact bytes to the Worker's contributions
 * endpoint and classifies the response into the three outcomes local ownership can act on.
 *
 * The contract it reads is the Worker's own (`src/contributions/upload_route.ts`): a successful body is an
 * `UploadReceipt`, a refusal body is an `UploadErrorBody` with `code` and optional `retry_after_s`. This
 * class deliberately does NOT decide acceptance - it hands the body to
 * [ContributionAckResolver.AcceptValidator] by way of the caller, so a Boolean "the connection succeeded"
 * can never delete audio the server may not have stored.
 *
 * Status classification here is about RETRY, not acceptance:
 *  - 2xx (200/201) -> [Response.Accepted]: the body still has to validate before anything is released.
 *  - 408/429/5xx and any refusal carrying `retry_after_s` -> [Response.Retryable] with the server minimum.
 *  - any other non-2xx -> [Response.Refused]: a policy/authority refusal that is not a lost upload.
 *  - connection/IO failure, or a response we cannot read -> [Response.Ambiguous]: the upload MAY have
 *    succeeded, so ownership is retained and the identical bytes are re-posted later.
 */
class ContributionUploadTransport(
    private val endpoint: String,
    private val authorization: () -> String?,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) {

    sealed class Response {
        /** A 2xx response. [payload] is the raw body and MUST validate before any release. */
        data class Accepted(val statusCode: Int, val payload: ByteArray) : Response()

        /** The server is alive and asked for a later retry, or a transient failure happened. */
        data class Retryable(val statusCode: Int?, val retryAfterS: Long?) : Response()

        /** The server refused on policy/authority grounds; nothing was stored and no retry hint applies. */
        data class Refused(val statusCode: Int, val code: String?) : Response()

        /** The outcome is unknown: the bytes may or may not be stored. Ownership is retained. */
        data class Ambiguous(val statusCode: Int?) : Response()
    }

    fun upload(captureId: String, audio: File): Response {
        val body = runCatching { audio.readBytes() }.getOrElse { return Response.Ambiguous(null) }
        val connection = try {
            URI(endpoint).toURL().openConnection() as HttpURLConnection
        } catch (invalid: Throwable) {
            return Response.Ambiguous(null)
        }
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "audio/wav")
            connection.setRequestProperty("X-Capture-Id", captureId)
            authorization()?.let { connection.setRequestProperty("Authorization", it) }
            connection.setFixedLengthStreamingMode(body.size)

            connection.outputStream.use { it.write(body) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.use { it.readBytes() } ?: ByteArray(0)
            classify(status, responseBody)
        } catch (io: Throwable) {
            // A failure after the bytes were written is AMBIGUOUS: the server may have taken them.
            Response.Ambiguous(null)
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun classify(status: Int, body: ByteArray): Response {
        if (status in 200..299) return Response.Accepted(status, body)
        // An error body is NOT a receipt: it carries code/retry_after_s, so read just those strictly.
        val code = runCatching { CanonicalReceipt.parseFields(body) }.getOrNull()?.get("code")
            ?: errorField(body, "code")
        val retryAfterS = errorField(body, "retry_after_s")?.toLongOrNull()
        return when {
            retryAfterS != null && retryAfterS > 0 -> Response.Retryable(status, retryAfterS)
            status == 408 || status == 429 || status in 500..599 -> Response.Retryable(status, null)
            status in 400..499 -> Response.Refused(status, code)
            else -> Response.Ambiguous(status)
        }
    }

    /** Read ONE optional field from an arbitrary JSON object body (error bodies are not receipts). */
    private fun errorField(body: ByteArray, name: String): String? {
        val reader = JsonReader.of(Buffer().write(body))
        return try {
            var value: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                when {
                    key == name && reader.peek() == JsonReader.Token.STRING -> value = reader.nextString()
                    key == name && reader.peek() == JsonReader.Token.NUMBER -> value = reader.nextString()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (reader.peek() != JsonReader.Token.END_DOCUMENT) null else value
        } catch (malformed: Throwable) {
            null
        }
    }

    /** Adapts the transport to the caller's upload seam, so the caller and this class compose directly. */
    fun asAttemptUpload(): (Long, String, String) -> Boolean = { _, captureId, audioPath ->
        upload(captureId, File(audioPath)).let { response ->
            // Only a 2xx is a transport success; the RECEIPT is validated by the resolver, not here.
            response is Response.Accepted
        }
    }
}
