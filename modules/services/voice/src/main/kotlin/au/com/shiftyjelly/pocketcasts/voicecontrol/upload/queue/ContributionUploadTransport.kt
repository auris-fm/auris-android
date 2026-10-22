package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.CanonicalJson
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import com.squareup.moshi.JsonReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
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

        /**
         * The OWNING table's terminal entry failure (docs/specs/voice-recording-uploads.md): 400
         * invalid_request, 409 capture_conflict, 410 record_gone|enrollment_retired, 413
         * payload_too_large, 415 unsupported_audio, 422 integrity_mismatch. Do not retry; the queued
         * entry takes its terminal disposition under token-scoped, epoch-gated release.
         */
        data class Terminal(val statusCode: Int, val code: String?) : Response()

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
    private enum class Disposition { RETRY, UNAUTHORIZED, TERMINAL, POLICY }

    // Authority: docs/specs/voice-recording-uploads.md response table; the Worker's statusFor is the
    // implementation to compare against, not the contract.
    private val byCode = mapOf(
        "invalid_request" to Disposition.TERMINAL,
        "unauthorized" to Disposition.UNAUTHORIZED,
        "not_enrolled" to Disposition.POLICY,
        "collection_disabled" to Disposition.POLICY,
        "capture_conflict" to Disposition.TERMINAL,
        "record_gone" to Disposition.TERMINAL,
        "enrollment_retired" to Disposition.TERMINAL,
        "payload_too_large" to Disposition.TERMINAL,
        "unsupported_audio" to Disposition.TERMINAL,
        "integrity_mismatch" to Disposition.TERMINAL,
        "rate_limited" to Disposition.RETRY,
        "quota_exceeded" to Disposition.RETRY,
        "upload_pending" to Disposition.RETRY,
        "policy_unavailable" to Disposition.RETRY,
        "storage_unavailable" to Disposition.RETRY,
    )

    /**
     * The metadata-first multipart envelope the Worker intake actually reads
     * (`src/contributions/stream_intake.ts`): part 1 named `metadata`, `application/json`, body exactly
     * `{"record":<canonical record>,"record_sha256":"<hex>"}` with the digest over the CLIENT's canonical
     * record bytes; part 2 named `audio`, `audio/wav`, filename `recording.wav`, the exact capture bytes.
     * Bearer authentication travels on the request. No side-channel headers carry identity.
     */
    fun upload(captureId: String, record: RecordNode.Obj, audio: File): Response {
        val recordBytes = CanonicalJson.canonicalBytes(record)
        val recordSha256 = java.security.MessageDigest.getInstance("SHA-256")
            .digest(recordBytes).joinToString("") { "%02x".format(it) }
        val metadata = "{\"record\":" + String(recordBytes, Charsets.UTF_8) + ",\"record_sha256\":\"$recordSha256\"}"
        val credentials = authorization() ?: return Response.Unauthorized(-1) // local refusal: nothing leaves
        val audioBytes = runCatching { audio.readBytes() }.getOrElse { return Response.Ambiguous(null) }
        val boundary = "----auris-contribution-${UUID.randomUUID()}"
        val body = Buffer()
        fun part(name: String, filename: String?, contentType: String, bytes: ByteArray) {
            body.writeUtf8("--$boundary\r\n")
            body.writeUtf8("Content-Disposition: form-data; name=\"$name\"")
            if (filename != null) body.writeUtf8("; filename=\"$filename\"")
            body.writeUtf8("\r\n")
            body.writeUtf8("Content-Type: $contentType\r\n\r\n")
            body.write(bytes)
            body.writeUtf8("\r\n")
        }
        part("metadata", null, "application/json", metadata.toByteArray(Charsets.UTF_8))
        part("audio", "recording.wav", "audio/wav", audioBytes)
        body.writeUtf8("--$boundary--\r\n")
        val payload = body.readByteArray()

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
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setRequestProperty("Authorization", credentials)
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.use { it.readBytes() } ?: ByteArray(0)
            classify(status, responseBody)
        } catch (io: Throwable) {
            Response.Ambiguous(null)
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    // The OWNING envelope pairs each code with ONE status; a body whose code disagrees with the HTTP
    // status is a malformed answer, not a disposition (reviewer d1dfe1cb).
    private val statusForCode = mapOf(
        "invalid_request" to 400,
        "unauthorized" to 401,
        "not_enrolled" to 403,
        "collection_disabled" to 403,
        "capture_conflict" to 409,
        "record_gone" to 410,
        "enrollment_retired" to 410,
        "payload_too_large" to 413,
        "unsupported_audio" to 415,
        "integrity_mismatch" to 422,
        "rate_limited" to 429,
        "quota_exceeded" to 429,
        "upload_pending" to 503,
        "policy_unavailable" to 503,
        "storage_unavailable" to 503,
    )

    private fun classify(status: Int, body: ByteArray): Response {
        if (status in 200..299) return Response.Accepted(status, readBounded(body))
        // An error body is NOT a receipt: read it strictly and BOUNDED. A malformed error body is an
        // unknown outcome, which retains.
        // A credential refusal needs no body to be actionable: it is a 401 by the owning table.
        if (status == 401) return Response.Unauthorized(status)
        // A body that is not exactly {schema_version, code, retry_after_s?} is an UNKNOWN outcome.
        val fields = errorFields(readBounded(body)) ?: return Response.Retryable(status, null)
        val code = fields["code"]
        val retryAfterS = fields["retry_after_s"]?.toLongOrNull()
        // A code whose status disagrees with the HTTP status is not a disposition this client acts on.
        if (code != null && statusForCode[code] != status) return Response.Retryable(status, retryHint(retryAfterS))
        return when (byCode[code] ?: unknownFor(status)) {
            Disposition.RETRY -> Response.Retryable(status, retryHint(retryAfterS))
            Disposition.UNAUTHORIZED -> Response.Unauthorized(status)
            Disposition.TERMINAL -> Response.Terminal(status, code)
            Disposition.POLICY -> Response.PolicyRefused(status, code)
        }
    }

    /** Receipts and error bodies are small; anything larger is a malformed answer, not data. */
    private fun readBounded(body: ByteArray): ByteArray = if (body.size > MAX_BODY_BYTES) body.copyOf(MAX_BODY_BYTES) else body

    private fun retryHint(retryAfterS: Long?): Long? = retryAfterS?.takeIf { it > 0 }

    /**
     * Strict error-body read: one pass, duplicate members REFUSED (never last-value-wins), string
     * members only, no trailing junk. Null on any deviation - a malformed error body is an unknown
     * outcome, which retains.
     */
    private fun errorFields(body: ByteArray): Map<String, String>? {
        val reader = JsonReader.of(Buffer().write(body))
        return try {
            val fields = LinkedHashMap<String, String>()
            reader.beginObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                if (key in fields) return null
                when (reader.peek()) {
                    JsonReader.Token.STRING -> {
                        val value = reader.nextString()
                        if (value.isEmpty() || value.any { it.isISOControl() }) return null
                        fields[key] = value
                    }

                    JsonReader.Token.NUMBER -> fields[key] = reader.nextString()

                    else -> return null
                }
            }
            reader.endObject()
            if (reader.peek() != JsonReader.Token.END_DOCUMENT) return null
            // Exactly the Worker's UploadErrorBody: a version, a code, an optional retry hint. Any other
            // member makes the answer one this client does not understand, so it is retained.
            if (!fields.containsKey("code")) return null
            if (fields.keys.any { it !in ERROR_BODY_KEYS }) return null
            if (fields["schema_version"] != "1") return null
            fields
        } catch (malformed: Throwable) {
            null
        }
    }

    // Codes this client does not know yet are RETRIED, never terminal disposal: deleting audio because a
    // server introduced a code we have not read is not a risk worth taking. 401 is the one status-class
    // fallback safe to act on, since it is a credential problem.
    private fun unknownFor(status: Int): Disposition = if (status == 401) Disposition.UNAUTHORIZED else Disposition.RETRY

    private companion object {
        const val MAX_BODY_BYTES = 1 shl 20
        val ERROR_BODY_KEYS = setOf("schema_version", "code", "retry_after_s")
    }
}
