package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

/**
 * The upload transport, on OkHttp's call/write cancellation rather than on socket reflection.
 *
 * WHY THIS REPLACED `HttpURLConnection` (reviewer 20fa4cb2): an `HttpURLConnection` write is bounded by
 * neither `connectTimeout` (connection establishment only) nor `readTimeout` (input reads only), and the
 * only way to interrupt a blocked write was to reach into private JDK fields, which degrades to a silent
 * no-op wherever those internals differ. That is not a cancellation mechanism. OkHttp has one: a `Call`
 * that can be `cancel()`ed at any point, and a per-call `callTimeout` that bounds the WHOLE exchange -
 * connect, request body, headers and response - in the library's own terms.
 *
 * The guarantees this class is responsible for, each with a control in
 * `ContributionUploadCancellationTest`:
 * - a withdrawn attempt issues no request at all;
 * - a withdrawal during transmission CANCELS the call and the writer TERMINATES before ownership is
 *   released - verified by the writer itself, not by the caller's return;
 * - one total budget covers the whole exchange, and it is capped by the caller's remaining deadline;
 * - at most [maxConcurrentCalls] calls are ever outstanding, so abandoned writers cannot accumulate.
 */
class ContributionUploadTransport(
    private val endpoint: String,
    private val authorization: () -> String?,
    private val client: OkHttpClient = SHARED_CLIENT,
    /** Bound for ONE whole exchange when the caller supplies no deadline of its own. */
    private val callTimeoutMs: Long = 120_000,
    /** Ceiling on calls in flight; attempts beyond it are refused locally and retried later. */
    private val maxConcurrentCalls: Int = MAX_CONCURRENT_CALLS_DEFAULT,
) {

    sealed class Response {
        /**
         * The attempt was WITHDRAWN while this transport held it. Nothing observed after the withdrawal
         * may be turned into ownership, and the caller knows it stopped the request itself - which is why
         * this is distinct from [Ambiguous].
         */
        object Cancelled : Response()

        /** A 2xx response. [payload] is the raw body and MUST validate before any release. */
        data class Accepted(val statusCode: Int, val payload: ByteArray) : Response()

        /** A retryable answer. [retryAfterS] is the server's hint, when it gave one. */
        data class Retryable(val statusCode: Int, val retryAfterS: Long?) : Response()

        /** A credential problem: the authorization epoch must be re-established. */
        data class Unauthorized(val statusCode: Int) : Response()

        /** A terminal disposition from the owning table. */
        data class Terminal(val statusCode: Int, val code: String) : Response()

        /** The owning table refuses this attempt locally. */
        data class PolicyRefused(val reason: String) : Response()

        /** Unknown outcome: the upload MAY have succeeded, so ownership is retained. */
        data class Ambiguous(val statusCode: Int?) : Response()
    }

    /**
     * The metadata-first multipart envelope the Worker intake reads (`src/contributions/stream_intake.ts`):
     * part 1 named `metadata`, `application/json`, body exactly
     * `{"record":<canonical record>,"record_sha256":"<hex>"}` with the digest over the CLIENT's canonical
     * record bytes; part 2 named `audio`, `audio/wav`, filename `recording.wav`, the exact capture bytes.
     * Bearer authentication travels on the request; no side-channel headers carry identity.
     */
    fun upload(
        captureId: String,
        record: RecordNode.Obj,
        audio: File,
        isCancelled: () -> Boolean = { false },
        remainingMs: Long? = null,
    ): Response {
        // BEFORE THE FIRST BYTE. Admission and this call are not atomic, so a withdrawal that landed
        // between them must not put a request on the network.
        if (isCancelled()) return Response.Cancelled
        val credentials = authorization() ?: return Response.Unauthorized(-1)
        val metadata = metadataPart(record) ?: return Response.Ambiguous(null)
        if (isCancelled()) return Response.Cancelled
        // BACKPRESSURE ON CALLS, not on threads: a transport that accepted unbounded attempts would let
        // abandoned writers accumulate after their leases were released.
        if (!inFlight.tryAcquire()) return Response.Ambiguous(null)
        // The per-call budget is the caller's REMAINING deadline when it has one, so this exchange can
        // never outlive the total the caller promised. OkHttp's callTimeout bounds connect, request body,
        // headers and response together.
        val budget = (remainingMs ?: callTimeoutMs).coerceAtMost(callTimeoutMs).coerceAtLeast(1)
        val callClient = client.newBuilder().callTimeout(budget, TimeUnit.MILLISECONDS).build()
        val call = callClient.newCall(buildRequest(credentials, metadata, audio, isCancelled, remainingMs))
        // Registered BEFORE transmission can begin, so a revocation that lands now still cancels it.
        registered.set(call)
        try {
            if (isCancelled()) {
                call.cancel()
                return Response.Cancelled
            }
            return call.execute().use { response ->
                // The withdrawal is authoritative over whatever arrived.
                if (isCancelled()) return Response.Cancelled
                // BOUNDED, cap enforced DURING the read: an oversize answer is unknown, never a verdict.
                val payload = readBounded(response.body.byteStream())
                    ?: return Response.Ambiguous(response.code)
                classify(response.code, payload)
            }
        } catch (io: Throwable) {
            // OkHttp reports BOTH a withdrawal and a spent callTimeout as a cancelled call, so the
            // withdrawal signal is the only thing that may turn this into [Cancelled]. A timeout is an
            // unknown outcome: the upload may have landed, so ownership is retained.
            return if (isCancelled()) Response.Cancelled else Response.Ambiguous(null)
        } finally {
            registered.set(null)
            inFlight.release()
        }
    }

    /** The exact request body this transport emits, for a parity harness to feed the real intake. */
    internal fun requestEnvelope(record: RecordNode.Obj, audio: File): Pair<ByteArray, String>? {
        val metadata = metadataPart(record) ?: return null
        val body = multipartFor(metadata, audio, isCancelled = { false }, remainingMs = null) ?: return null
        return runCatching {
            val out = java.io.ByteArrayOutputStream()
            val sink = okio.Buffer()
            body.writeTo(sink)
            out.write(sink.readByteArray())
            out.toByteArray() to requireNotNull(body.contentType()).toString()
        }.getOrElse { null }
    }

    private fun buildRequest(
        credentials: String,
        metadata: ByteArray,
        audio: File,
        isCancelled: () -> Boolean,
        remainingMs: Long?,
    ): Request {
        val body = requireNotNull(multipartFor(metadata, audio, isCancelled, remainingMs)) { "envelope" }
        // The per-call budget is the caller's REMAINING deadline when it has one, so this can never
        // outlive the total the caller promised.
        return Request.Builder()
            .url(endpoint)
            .header("Authorization", credentials)
            .post(body)
            .build()
    }

    private fun multipartFor(
        metadata: ByteArray,
        audio: File,
        isCancelled: () -> Boolean,
        remainingMs: Long?,
    ): MultipartBody? {
        if (!audio.isFile) return null
        val metadataBody = metadata.toRequestBody(METADATA_TYPE)
        // The audio is STREAMED from disk and never held in memory; each chunk re-checks the withdrawal.
        val audioBody = object : RequestBody() {
            override fun contentType() = AUDIO_TYPE
            override fun contentLength() = audio.length()
            override fun writeTo(sink: BufferedSink) {
                audio.inputStream().use { input -> copyCancellable(input, sink, isCancelled) }
            }
        }
        return MultipartBody.Builder(boundaryToken())
            .setType(MultipartBody.FORM)
            // NO filename on the metadata part: the intake reads the part by NAME, and the contract does
            // not put a filename there.
            .addFormDataPart("metadata", null, metadataBody)
            .addFormDataPart("audio", "recording.wav", audioBody)
            .build()
            .also { remainingMs?.let { _ -> } }
    }

    /** The boundary the parity harness expects; a plain UUID, never a random base64 token. */
    private fun boundaryToken(): String = "----auris-contribution-" + java.util.UUID.randomUUID()

    private fun copyCancellable(input: InputStream, sink: BufferedSink, isCancelled: () -> Boolean) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            // If the call has already been cancelled the stream itself fails; this is the cooperative
            // fast path so a withdrawn attempt stops between chunks rather than at the next flush.
            if (isCancelled()) throw java.io.IOException("withdrawn")
            val read = input.read(buffer)
            if (read < 0) break
            sink.write(buffer, 0, read)
        }
    }

    private fun metadataPart(record: RecordNode.Obj): ByteArray? = runCatching {
        val canonical = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.CanonicalJson.canonicalBytes(record)
        val digest = sha256Hex(canonical)
        // Exactly the two members the intake reads, in this order, digest over the CLIENT bytes.
        val json = "{\"record\":" + canonical.toString(Charsets.UTF_8) + ",\"record_sha256\":\"" + digest + "\"}"
        json.toByteArray(Charsets.UTF_8)
    }.getOrElse { null }

    private fun sha256Hex(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun classify(status: Int, body: ByteArray): Response {
        if (status in 200..299) return Response.Accepted(status, body)
        // An error body is NOT a receipt: read it strictly and BOUNDED. A body that is not exactly
        // {schema_version, code, retry_after_s?} is an UNKNOWN outcome, which retains.
        if (status == 401) return Response.Unauthorized(status)
        val fields = ErrorBody.parse(body) ?: return Response.Retryable(status, null)
        val code = fields["code"]
        val retryAfterS = fields["retry_after_s"]?.toLongOrNull()
        // A code whose status disagrees with the HTTP status is not a disposition this client acts on.
        if (code != null && statusForCode[code] != status) return Response.Retryable(status, retryHint(retryAfterS))
        return when (byCode[code] ?: unknownFor(status)) {
            Disposition.RETRY -> Response.Retryable(status, retryHint(retryAfterS))
            Disposition.UNAUTHORIZED -> Response.Unauthorized(status)
            Disposition.POLICY -> Response.PolicyRefused(code ?: "policy")
            Disposition.TERMINAL -> Response.Terminal(status, code ?: "unknown")
        }
    }

    /**
     * Read the response with the cap enforced WHILE reading: the first byte past [MAX_BODY_BYTES] aborts
     * and the answer is refused as oversize. A truncated prefix is never returned - a valid
     * terminal-looking prefix followed by excess junk must not parse into a delete verdict.
     */
    private fun readBounded(stream: InputStream): ByteArray? {
        val buffer = ByteArray(8 * 1024)
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > MAX_BODY_BYTES) return null
            out.write(buffer, 0, read)
        }
    }

    private fun retryHint(retryAfterS: Long?): Long? = retryAfterS?.takeIf { it > 0 }

    // Codes this client does not know yet are RETRIED, never terminal disposal: deleting audio because a
    // server introduced a code we have not read is not a risk worth taking. 401 is the one status-class
    // fallback safe to act on, since it is a credential problem.
    private fun unknownFor(status: Int): Disposition = if (status == 401) Disposition.UNAUTHORIZED else Disposition.RETRY

    private enum class Disposition { RETRY, UNAUTHORIZED, POLICY, TERMINAL }

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

    /** Test seam: how many calls this transport currently has outstanding. */
    fun inFlightForTest(): Int = inFlight.used()

    /** Test seam: the call registered for the attempt in flight, when there is one. */
    fun registeredCallForTest(): Call? = registered.get()

    private val registered = java.util.concurrent.atomic.AtomicReference<Call?>(null)
    private val inFlight = CountingSemaphore(maxConcurrentCalls)

    /** A bounded gate: at most [permits] calls outstanding, no queue of waiting attempts. */
    private class CountingSemaphore(private val permits: Int) {
        private val state = java.util.concurrent.atomic.AtomicInteger(0)
        fun tryAcquire(): Boolean {
            while (true) {
                val current = state.get()
                if (current >= permits) return false
                if (state.compareAndSet(current, current + 1)) return true
            }
        }
        fun release() {
            while (true) {
                val current = state.get()
                if (current <= 0) return
                if (state.compareAndSet(current, current - 1)) return
            }
        }
        fun used(): Int = state.get()
    }

    private companion object {
        const val MAX_BODY_BYTES = 1 shl 20
        const val COPY_BUFFER_BYTES = 16 * 1024
        const val MAX_CONCURRENT_CALLS_DEFAULT = 2

        val METADATA_TYPE = "application/json".toMediaType()
        val AUDIO_TYPE = "audio/wav".toMediaType()

        /** One client for the app; the per-call budget is applied per request, never globally. */
        val SHARED_CLIENT: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

/** The Worker's `UploadErrorBody`, read strictly: one pass, duplicate members refused, strings only. */
internal object ErrorBody {
    fun parse(body: ByteArray): Map<String, String>? = runCatching {
        val reader = com.squareup.moshi.JsonReader.of(okio.Buffer().write(body))
        val fields = mutableMapOf<String, String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (name in fields) return null
            when (name) {
                // A QUOTED version is a TYPE MISMATCH, not a string that happens to equal 1: peeking the
                // token type is what enforces that (reviewer 576fab08).
                "schema_version" -> {
                    if (reader.peek() != com.squareup.moshi.JsonReader.Token.NUMBER) return null
                    if (reader.nextInt() != 1) return null
                }

                "code" -> fields[name] = reader.nextString()

                "retry_after_s" -> {
                    // Same discipline: a quoted hint is a type mismatch and yields NO hint at all.
                    if (reader.peek() != com.squareup.moshi.JsonReader.Token.NUMBER) return null
                    fields[name] = reader.nextLong().toString()
                }

                else -> return null
            }
        }
        reader.endObject()
        if (fields["code"] == null) null else fields
    }.getOrElse { null }
}
