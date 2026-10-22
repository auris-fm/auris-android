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

        /** The server is alive and asked for a later retry ([retryAfterS] is a MINIMUM, not a hint). */
        data class Retryable(val statusCode: Int, val retryAfterS: Long?) : Response()

        /** Credentials were refused: RECOVER, never delete. Ownership is retained. */
        data class Unauthorized(val statusCode: Int) : Response()

        /** The request or the capture is wrong and repeating it changes nothing. */
        data class Rejected(val statusCode: Int, val code: String?) : Response()

        /** The enrollment/collection is not currently accepting uploads: a policy state, not a fault. */
        data class PolicyRefused(val statusCode: Int, val code: String?) : Response()

        /** The outcome is unknown: the bytes may or may not be stored. Ownership is retained. */
        data class Ambiguous(val statusCode: Int?) : Response()
    }

    // The OWNING response table is the Worker's own (`src/contributions/upload_route.ts` statusFor +
    // refusal): 400 invalid_request, 401 unauthorized, 403 not_enrolled/collection_disabled,
    // 409 capture_conflict, 410 record_gone/enrollment_retired, 413 payload_too_large,
    // 415 unsupported_audio, 422 integrity_mismatch, 429 rate_limited/quota_exceeded,
    // 503 upload_pending/policy_unavailable/storage_unavailable. The client mirrors it code-first, so a
    // NEW server code does not silently become "terminal" on this side.
    private enum class Disposition { RETRY, UNAUTHORIZED, REJECTED, POLICY }

    private val byCode = mapOf(
        "invalid_request" to Disposition.REJECTED,
        "unauthorized" to Disposition.UNAUTHORIZED,
        "not_enrolled" to Disposition.POLICY,
        "collection_disabled" to Disposition.POLICY,
        "capture_conflict" to Disposition.REJECTED,
        "record_gone" to Disposition.REJECTED,
        "enrollment_retired" to Disposition.POLICY,
        "payload_too_large" to Disposition.REJECTED,
        "unsupported_audio" to Disposition.REJECTED,
        "integrity_mismatch" to Disposition.REJECTED,
        "rate_limited" to Disposition.RETRY,
        "quota_exceeded" to Disposition.RETRY,
        "upload_pending" to Disposition.RETRY,
        "policy_unavailable" to Disposition.RETRY,
        "storage_unavailable" to Disposition.RETRY,
    )

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
        val code = errorField(body, "code")
        val retryAfterS = errorField(body, "retry_after_s")?.toLongOrNull()
        // The server's own minimum is a MINIMUM: it wins over any default backoff.
        val retryHint = retryAfterS?.takeIf { it > 0 }
        // An UNKNOWN code is not a licence to delete: the capture is retained and retried.
        return when (byCode[code] ?: unknownFor(status)) {
            Disposition.RETRY -> Response.Retryable(status, retryHint)
            Disposition.UNAUTHORIZED -> Response.Unauthorized(status)
            Disposition.REJECTED -> Response.Rejected(status, code)
            Disposition.POLICY -> Response.PolicyRefused(status, code)
        }
    }

    // Codes this client does not know yet are RETRIED, never treated as terminal disposal: deleting audio
    // because a server introduced a code we have not read is not a risk worth taking (spec 76f09c72).
    // 401 is the one status-class fallback that is safe to act on, since it is a credential problem.
    private fun unknownFor(status: Int): Disposition = if (status == 401) Disposition.UNAUTHORIZED else Disposition.RETRY

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
