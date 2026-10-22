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
    /**
     * TOTAL wall budget for one upload, enforced by an actual disconnect rather than by a socket
     * inactivity timeout. A server that drips steadily, each chunk inside [readTimeoutMs], never trips an
     * inactivity timeout - so without this a trickling response can hold the attempt open indefinitely.
     */
    private val totalDeadlineMs: Long = 120_000,
) {

    sealed class Response {
        /**
         * The attempt was WITHDRAWN while this transport held it (authorization epoch changed). What
         * arrived after the withdrawal is not trusted and no disposition may be taken from it: ownership
         * returns to the retry boundary. Distinct from [Ambiguous] because the client KNOWS it stopped it.
         */
        object Cancelled : Response()

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
    fun upload(
        captureId: String,
        record: RecordNode.Obj,
        audio: File,
        isCancelled: () -> Boolean = { false },
    ): Response {
        val result = sendAttempt(captureId, record, audio, isCancelled)
        // A withdrawal is authoritative over whatever the socket happened to return: nothing observed
        // after it may be turned into ownership, so the attempt reports the withdrawal itself.
        return if (isCancelled()) Response.Cancelled else result
    }

    private fun sendAttempt(captureId: String, record: RecordNode.Obj, audio: File, isCancelled: () -> Boolean): Response {
        // BEFORE THE FIRST BYTE. The caller's own check and this call are not atomic; a withdrawal that
        // lands in between must not put a request on the network at all.
        if (isCancelled()) return Response.Cancelled
        val envelope = try {
            planEnvelope(record, audio)
        } catch (io: Throwable) {
            null
        } ?: return Response.Ambiguous(null)
        // Planning opens and sizes the audio file, so the withdrawal is re-checked before sending.
        if (isCancelled()) return Response.Cancelled
        return send(captureId, envelope, isCancelled)
    }

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

    private fun send(captureId: String, envelope: Envelope, isCancelled: () -> Boolean): Response {
        // Resolved BEFORE anything is read or sent: a device with no usable credential issues NO request.
        val credentials = authorization() ?: return Response.Unauthorized(-1)
        val connection = try {
            URI(endpoint).toURL().openConnection() as HttpURLConnection
        } catch (invalid: Throwable) {
            return Response.Ambiguous(null)
        }
        // ONE TOTAL DEADLINE for the WHOLE upload, anchored before any network work and carried through
        // connect, request-body write, header acquisition and body read (reviewer 68661171: a deadline
        // created only inside the body read left a stalled request write with NO budget and then handed
        // the body a fresh full allowance on top of the time already spent). This thread owns it: an
        // inactivity timeout cannot bound a trickle, and cancelling the connection from another thread was
        // measured to be unreliable.
        val deadlineAt = System.nanoTime() + totalDeadlineMs * 1_000_000
        val expired = java.util.concurrent.atomic.AtomicBoolean(false)
        // Every blocking socket call is bounded by a slice shorter than the budget, so none can outlive the
        // deadline by more than one slice.
        val sliceMs = maxOf(1L, minOf(totalDeadlineMs / 4, 1_000L)).toInt()
        return try {
            // The withdrawal is re-checked at the LAST responsible moment, after the connection object
            // exists but before any network work: a caller racing an epoch change must not slip through.
            if (isCancelled()) return Response.Cancelled
            connection.requestMethod = "POST"
            connection.connectTimeout = sliceMs
            connection.readTimeout = sliceMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", envelope.contentType)
            connection.setRequestProperty("Authorization", credentials)
            connection.setFixedLengthStreamingMode(envelope.contentLength)
            // THE REQUEST BODY, under the same deadline and the same withdrawal check.
            writeWithinDeadline(connection, envelope, deadlineAt, isCancelled)?.let { return it }
            val status = connection.responseCode
            if (isCancelled()) return Response.Cancelled
            if (System.nanoTime() >= deadlineAt) {
                expired.lazySet(true)
                return Response.Ambiguous(null)
            }
            // `unauthorized` pairs with 401 in the owning table and needs no body: never read the error
            // stream here, so a server that closes it abruptly cannot turn a credential refusal into an
            // ambiguous outcome.
            if (status == 401) return Response.Unauthorized(status)
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = readWithinDeadline(stream, deadlineAt, isCancelled, expired)
            // A BODY WE ABANDONED IS NOT AN ANSWER. Once the deadline or a withdrawal cut the read short,
            // whatever arrived is a PREFIX, and a prefix can look exactly like a complete terminal
            // envelope. Classifying it would turn "we do not know" into a deletion.
            if (expired.get()) return Response.Ambiguous(status)
            if (responseBody == null) Response.Ambiguous(status) else classify(status, responseBody)
        } catch (io: Throwable) {
            // A withdrawal that tore this transfer down must not surface as "unknown": the client knows
            // exactly why it stopped, and the caller settles the two differently.
            if (isCancelled()) Response.Cancelled else Response.Ambiguous(null)
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /**
     * Write the request body under the shared deadline and the shared withdrawal signal.
     *
     * The socket's own timeout is a SLICE, not the budget, so a slice expiry mid-write is retried while
     * the budget still allows: a slow peer must not fail an attempt on the first slice, and a stalled one
     * must not walk past the deadline. Headers are part of the request body on a POST, so they are only
     * written once - a retry that finds itself able to open the stream again is still safe, because
     * nothing was delivered by the failed attempt.
     *
     * @return null to continue, or the [Response] that ends the attempt when the withdrawal landed or the
     *   budget was spent mid-write.
     */
    private fun writeWithinDeadline(
        connection: HttpURLConnection,
        envelope: Envelope,
        deadlineAt: Long,
        isCancelled: () -> Boolean,
    ): Response? {
        while (true) {
            if (isCancelled()) return Response.Cancelled
            if (System.nanoTime() >= deadlineAt) return Response.Ambiguous(null)
            try {
                connection.outputStream.use { sink -> writeChunks(sink, envelope, deadlineAt, isCancelled)?.let { return it } }
                return null
            } catch (slice: java.net.SocketTimeoutException) {
                // One slice expired. Loop: the budget check above decides whether to try again.
                continue
            } catch (io: java.io.IOException) {
                return if (isCancelled()) Response.Cancelled else Response.Ambiguous(null)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        return null
    }

    /** @return null when the whole envelope was written, or the [Response] that ends the attempt. */
    private fun writeChunks(
        sink: java.io.OutputStream,
        envelope: Envelope,
        deadlineAt: Long,
        isCancelled: () -> Boolean,
    ): Response? {
        sink.write(envelope.head)
        sink.flush()
        if (System.nanoTime() >= deadlineAt) return Response.Ambiguous(null)
        envelope.audio.inputStream().use { input ->
            val chunk = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                // Per chunk, so a revoked attempt stops MID-WRITE rather than at the next poll.
                if (isCancelled()) return Response.Cancelled
                if (System.nanoTime() >= deadlineAt) return Response.Ambiguous(null)
                val read = input.read(chunk)
                if (read < 0) break
                sink.write(chunk, 0, read)
                sink.flush()
            }
        }
        sink.write(envelope.tail)
        sink.flush()
        return null
    }

    /**
     * Read a response body under the SHARED upload deadline. Each individual read is bounded by the socket
     * slice and the loop re-checks the absolute end time and the withdrawal, so a peer that drips steadily
     * - every byte well inside the socket timeout - can neither hold this attempt open nor settle it.
     *
     * @return the COMPLETE body, or null when the deadline was spent or the attempt was withdrawn.
     */
    private fun readWithinDeadline(
        stream: InputStream?,
        deadlineAt: Long,
        isCancelled: () -> Boolean,
        expired: java.util.concurrent.atomic.AtomicBoolean,
    ): ByteArray? {
        if (stream == null) return null
        return stream.use { readBoundedAfter(it, deadlineAt, isCancelled, expired) }
    }

    private fun readBoundedAfter(
        stream: InputStream,
        deadlineAt: Long,
        isCancelled: () -> Boolean,
        expired: java.util.concurrent.atomic.AtomicBoolean,
    ): ByteArray? {
        val buffer = ByteArray(8 * 1024)
        val out = ByteArrayOutputStream()
        while (true) {
            // Checked BEFORE the blocking read, after every read, and again before accepting EOF: a final
            // chunk or an EOF that lands just after the cutoff must not become a disposition
            // (reviewer 68661171).
            if (cutOff(deadlineAt, isCancelled)) {
                expired.lazySet(true)
                return null
            }
            val read = try {
                stream.read(buffer)
            } catch (timeout: java.net.SocketTimeoutException) {
                // No byte inside this slice. Only a spent budget or a withdrawal makes that fatal.
                if (cutOff(deadlineAt, isCancelled)) {
                    expired.lazySet(true)
                    return null
                }
                continue
            } catch (io: java.io.IOException) {
                return null
            }
            if (read < 0) {
                if (cutOff(deadlineAt, isCancelled)) {
                    expired.lazySet(true)
                    return null
                }
                return out.toByteArray()
            }
            out.write(buffer, 0, read)
            if (out.size() > MAX_BODY_BYTES) return null
        }
    }

    private fun cutOff(deadlineAt: Long, isCancelled: () -> Boolean): Boolean = isCancelled() || System.nanoTime() >= deadlineAt

    private companion object {
        const val MAX_BODY_BYTES = 1 shl 20

        /** The only capture-sized buffer a send allocates; the audio itself stays on disk. */
        const val COPY_BUFFER_BYTES = 16 * 1024
    }

    // The OWNING envelope pairs    }

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
}
