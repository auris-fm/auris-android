package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.CanonicalJson
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import com.squareup.moshi.JsonReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
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
    fun upload(captureId: String, record: RecordNode.Obj, audio: File): Response = runCatching {
        val envelope = requireNotNull(planEnvelope(record, audio)) { "envelope" }
        send(captureId, envelope)
    }.getOrElse { Response.Ambiguous(null) }

    /**
     * The exact request body this transport emits for [record] + [audio], and the Content-Type carrying
     * its boundary. Exposed so a parity harness can feed the BYTES THIS CLIENT PRODUCES to the real
     * intake rather than re-deriving an envelope that only resembles it.
     */
    internal fun requestEnvelope(record: RecordNode.Obj, audio: File): Pair<ByteArray, String>? {
        val envelope = planEnvelope(record, audio) ?: return null
        return runCatching {
            val out = java.io.ByteArrayOutputStream()
            writeEnvelope(out, envelope)
            out.toByteArray() to envelope.contentType
        }.getOrElse { null }
    }

    /**
     * The envelope, described as the parts it is made of: the metadata head, the audio head, and the audio
     * FILE. The audio stays on disk - it is copied to the socket in chunks ([writeEnvelope]) rather than
     * read into memory - so what leaves the device is bounded by the copy buffer, not the capture size.
     */
    private data class Envelope(
        val head: ByteArray,
        val tail: ByteArray,
        val audio: File,
        val contentType: String,
    ) {
        /** Exact wire length, so the request still streams with a fixed Content-Length (no chunking). */
        val contentLength: Long get() = head.size.toLong() + audio.length() + tail.size.toLong()
    }

    private fun planEnvelope(record: RecordNode.Obj, audio: File): Envelope? {
        if (!audio.isFile) return null
        val recordBytes = CanonicalJson.canonicalBytes(record)
        val recordSha256 = java.security.MessageDigest.getInstance("SHA-256")
            .digest(recordBytes).joinToString("") { "%02x".format(it) }
        val metadata = "{\"record\":" + String(recordBytes, Charsets.UTF_8) + ",\"record_sha256\":\"$recordSha256\"}"
        val boundary = "----auris-contribution-${UUID.randomUUID()}"
        val head = buildString {
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"metadata\"\r\n")
            append("Content-Type: application/json\r\n\r\n")
            append(metadata)
            append("\r\n--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"audio\"; filename=\"recording.wav\"\r\n")
            append("Content-Type: audio/wav\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        return Envelope(
            head = head,
            tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8),
            audio = audio,
            contentType = "multipart/form-data; boundary=$boundary",
        )
    }

    /**
     * Writes the envelope to [out] in bounded chunks. The copy buffer is the only capture-sized buffer the
     * transport allocates; the audio itself is never held in memory.
     */
    private fun writeEnvelope(out: java.io.OutputStream, envelope: Envelope) {
        out.write(envelope.head)
        out.flush()
        envelope.audio.inputStream().use { input ->
            val chunk = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                out.write(chunk, 0, read)
            }
        }
        out.write(envelope.tail)
        out.flush()
    }

    private fun send(captureId: String, envelope: Envelope): Response {
        // Resolved BEFORE anything is read or sent: a device with no usable credential issues NO request.
        val credentials = authorization() ?: return Response.Unauthorized(-1)
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
            connection.setRequestProperty("Content-Type", envelope.contentType)
            connection.setRequestProperty("Authorization", credentials)
            connection.setFixedLengthStreamingMode(envelope.contentLength)
            connection.outputStream.use { writeEnvelope(it, envelope) }
            val status = connection.responseCode
            // `unauthorized` pairs with 401 in the owning table and needs no body: never read the error
            // stream here, so a server that closes it abruptly cannot turn a credential refusal into an
            // ambiguous outcome.
            if (status == 401) return Response.Unauthorized(status)
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.use { readBounded(it) }
            if (responseBody == null) Response.Ambiguous(status) else classify(status, responseBody)
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
        if (status in 200..299) return Response.Accepted(status, body)
        // An error body is NOT a receipt: read it strictly and BOUNDED. A malformed error body is an
        // unknown outcome, which retains.
        // A credential refusal needs no body to be actionable: it is a 401 by the owning table.
        if (status == 401) return Response.Unauthorized(status)
        // A body that is not exactly {schema_version, code, retry_after_s?} is an UNKNOWN outcome.
        val fields = errorFields(body) ?: return Response.Retryable(status, null)
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

    /**
     * Read the response with the cap enforced WHILE reading: the first byte past [MAX_BODY_BYTES] aborts
     * the read and the answer is refused as oversize. A truncated prefix is never returned - a valid
     * terminal-looking prefix followed by an excess of junk must not parse into a delete verdict. Null
     * means "no usable body": an oversize answer is unknown, never a disposition.
     */
    private fun readBounded(stream: InputStream): ByteArray? {
        val buffer = ByteArray(8 * 1024)
        val out = ByteArrayOutputStream()
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > MAX_BODY_BYTES) return null
            out.write(buffer, 0, read)
        }
    }

    private fun retryHint(retryAfterS: Long?): Long? = retryAfterS?.takeIf { it > 0 }

    /**
     * Strict error-body read: one pass, duplicate members REFUSED (never last-value-wins), string
     * members only, no trailing junk. Null on any deviation - a malformed error body is an unknown
     * outcome, which retains.
     */
    private fun errorFields(body: ByteArray): Map<String, String>? = ErrorBody.parse(body)

    // Codes this client does not know yet are RETRIED, never terminal disposal: deleting audio because a
    // server introduced a code we have not read is not a risk worth taking. 401 is the one status-class
    // fallback safe to act on, since it is a credential problem.
    private fun unknownFor(status: Int): Disposition = if (status == 401) Disposition.UNAUTHORIZED else Disposition.RETRY

    /**
     * The Worker's `UploadErrorBody` read with the SAME strict Moshi discipline as the receipt parser:
     * typed tokens are preserved, so a quoted `"schema_version":"1"` is a TYPE MISMATCH rather than a
     * string that merely equals "1" and authorizes a terminal delete.
     */
    private object ErrorBody {
        private val ALLOWED_KEYS = setOf("schema_version", "code", "retry_after_s")

        /** schema_version and retry_after_s are NUMBERS; a quoted number is a type error. */
        private val NUMERIC_KEYS = setOf("schema_version", "retry_after_s")

        fun parse(body: ByteArray): Map<String, String>? {
            val reader = JsonReader.of(Buffer().write(body))
            val fields = LinkedHashMap<String, String>()
            val numeric = HashSet<String>()
            return try {
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    if (key in fields) return null // decoded-key duplicate detection
                    when {
                        reader.peek() == JsonReader.Token.NUMBER && key in NUMERIC_KEYS -> {
                            fields[key] = reader.nextString()
                            numeric += key
                        }

                        // Everything else is a non-empty, control-character-free string.
                        reader.peek() == JsonReader.Token.STRING && key !in NUMERIC_KEYS -> {
                            val value = reader.nextString()
                            if (value.isEmpty() || value.any { it.isISOControl() }) return null
                            fields[key] = value
                        }

                        else -> return null // wrong token type for the key, or bool/null/object/array
                    }
                }
                reader.endObject()
                if (reader.peek() != JsonReader.Token.END_DOCUMENT) return null // trailing junk
                // Exactly the Worker's envelope: no member this client does not understand.
                if (fields.keys.any { it !in ALLOWED_KEYS }) return null
                if ("code" !in fields) return null
                if ("schema_version" !in numeric || fields["schema_version"] != "1") return null
                val hint = fields["retry_after_s"]
                if (hint != null) {
                    if ("retry_after_s" !in numeric) return null
                    val value = hint.toLongOrNull() ?: return null
                    if (value < 0) return null
                }
                fields
            } catch (malformed: Throwable) {
                null
            }
        }
    }

    private companion object {
        const val MAX_BODY_BYTES = 1 shl 20

        /** The only capture-sized buffer a send allocates; the audio itself stays on disk. */
        const val COPY_BUFFER_BYTES = 16 * 1024
    }
}
