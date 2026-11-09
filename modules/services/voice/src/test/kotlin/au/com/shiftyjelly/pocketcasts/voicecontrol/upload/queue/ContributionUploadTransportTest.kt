package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.CanonicalJson
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The REAL HTTP adapter, exercised against a local JDK HTTP server: no seam, no simulated response. The
 * server is the Worker's own contract shape (worker `src/contributions/upload_route.ts`): a 2xx body is an
 * `UploadReceipt`, a non-2xx body is an `UploadErrorBody`, and everything else is ambiguous.
 */
class ContributionUploadTransportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private val receivedBodies = mutableListOf<ByteArray>()
    private val receivedMethods = mutableListOf<String>()
    private val requestCount = AtomicInteger(0)

    /** What the fake Worker answers with; set per test. */
    private var responseStatus = 200
    private var responseBody = ""
    private var responseDelayMs = 0L

    private var port = 0

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/contributions") { exchange: HttpExchange ->
            requestCount.incrementAndGet()
            receivedMethods += exchange.requestMethod
            // Drain the request body: an unanswered POST body races the response on a loopback socket and
            // would make these controls flaky rather than diagnostic.
            receivedBodies += exchange.requestBody.readBytes()
            if (responseDelayMs > 0) Thread.sleep(responseDelayMs)
            val bytes = responseBody.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        port = server.address.port
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun receipt(captureId: String, digest: String, status: String = "accepted"): String = "{" +
        "\"schema_version\":1," +
        "\"capture_id\":\"$captureId\"," +
        "\"record_sha256\":\"$digest\"," +
        "\"status\":\"$status\"," +
        "\"receipt_id\":\"rcpt-1\"," +
        "\"accepted_at\":\"2026-10-22T00:00:00Z\"," +
        "\"retention_expires_at\":\"2026-11-21T00:00:00Z\"}"

    /**
     * The expected production envelope for [record] + [file]: metadata part FIRST (the Worker intake reads
     * the record before any audio byte reaches the sink), then the audio part with the exact capture bytes.
     */
    private fun envelope(captureId: String, file: File): ByteArray {
        val recordBytes = CanonicalJson.canonicalBytes(record(captureId))
        val digest = MessageDigest.getInstance("SHA-256").digest(recordBytes).joinToString("") { "%02x".format(it) }
        val metadata = "{\"record\":" + String(recordBytes, Charsets.UTF_8) + ",\"record_sha256\":\"$digest\"}"
        val body = Buffer()
        fun part(name: String, filename: String?, contentType: String, bytes: ByteArray) {
            body.writeUtf8("--BOUNDARY\r\n")
            body.writeUtf8("Content-Disposition: form-data; name=\"$name\"")
            if (filename != null) body.writeUtf8("; filename=\"$filename\"")
            body.writeUtf8("\r\n")
            body.writeUtf8("Content-Type: $contentType\r\n\r\n")
            body.write(bytes)
            body.writeUtf8("\r\n")
        }
        part("metadata", null, "application/json", metadata.toByteArray(Charsets.UTF_8))
        part("audio", "recording.wav", "audio/wav", file.readBytes())
        body.writeUtf8("--BOUNDARY--\r\n")
        return body.readByteArray()
    }

    /** A minimal VALID record so the transport builds its real metadata envelope. */
    private fun record(captureId: String): RecordNode.Obj = RecordNode.Obj(
        mapOf(
            "schema_version" to RecordNode.ofInt(1),
            "capture_id" to RecordNode.Str(captureId),
            "enrollment_epoch" to RecordNode.Str("epoch-1"),
            "platform" to RecordNode.Str("android"),
            "app_version" to RecordNode.Str("8.9"),
            "build" to RecordNode.Str("123"),
            "capture_session_id" to RecordNode.Str("session-$captureId"),
            "models" to RecordNode.Arr(emptyList()),
            "observations" to RecordNode.Arr(emptyList()),
            "audio" to RecordNode.Obj(
                mapOf(
                    "sha256" to RecordNode.Str("0".repeat(64)),
                    "byte_count" to RecordNode.ofInt(44),
                    "encoding" to RecordNode.Str("pcm_s16le"),
                    "sample_rate_hz" to RecordNode.ofInt(16000),
                    "channels" to RecordNode.ofInt(1),
                    "retained_samples" to RecordNode.ofInt(0),
                    "original_format" to RecordNode.Str("pcm_s16le"),
                    "conversion_version" to RecordNode.Str("v1"),
                    "truncated" to RecordNode.Bool(false),
                    "dropped_spans" to RecordNode.Arr(emptyList()),
                ),
            ),
        ),
    )

    private fun transport(token: String = "test-token", nowEpochMs: () -> Long = System::currentTimeMillis) = ContributionUploadTransport(
        endpoint = "http://127.0.0.1:$port/v1/contributions",
        authorization = { "Bearer $token" },
        callTimeoutMs = 10_000,
        nowEpochMs = nowEpochMs,
    )

    @Test
    fun `the transport sends the owned bytes even after the path is replaced underneath it`() {
        // The end-to-end byte-binding control (spec ee31a3cc). The store validates an open descriptor; a
        // successor then RENAMES different bytes onto the same path, swapping the inode. The transport must
        // still transmit the validated bytes, because it reads the descriptor and never re-opens the path.
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        val validatedBytes = file.readBytes()
        val validatedSha = MessageDigest.getInstance("SHA-256").digest(validatedBytes).joinToString("") { "%02x".format(it) }
        val owned = ownedOf(file)

        val replacementBytes = ByteArray(2048) { (it * 7).toByte() }
        val replacement = File(tmp.root, "$id.replacement")
        replacement.writeBytes(replacementBytes)
        assertTrue("PREMISE: the replacement is published by rename", replacement.renameTo(file))
        assertFalse(
            "PREMISE: the path now serves different bytes",
            MessageDigest.getInstance("SHA-256").digest(file.readBytes()).contentEquals(MessageDigest.getInstance("SHA-256").digest(validatedBytes)),
        )

        responseStatus = 200
        responseBody = receipt(id, validatedSha, "accepted")
        transport().upload(id, record(id), owned)

        val sent = requireNotNull(receivedBodies.firstOrNull()) { "PREMISE: the server received a body" }
        assertTrue("the transmitted body carries the VALIDATED audio", sent.contains(validatedBytes))
        assertFalse("the transmitted body carries the REPLACED audio", sent.contains(replacementBytes))
        owned.close()
    }

    /** True when this array contains [needle] as a contiguous run. */
    private fun ByteArray.contains(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (i in 0..(size - needle.size)) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    private fun wavFile(id: String): File {
        val file = File(tmp.root, "$id.recording.wav")
        // A minimal but structurally valid WAV: RIFF/WAVE header + one PCM data chunk.
        val data = ByteArray(64)
        val out = java.io.ByteArrayOutputStream()
        fun le32(v: Int) = out.write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()))
        fun le16(v: Int) = out.write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()))
        out.write("RIFF".toByteArray())
        le32(36 + data.size)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        le32(16)
        le16(1)
        le16(1)
        le32(16_000)
        le32(32_000)
        le16(2)
        le16(16)
        out.write("data".toByteArray())
        le32(data.size)
        out.write(data)
        file.writeBytes(out.toByteArray())
        return file
    }

    private fun sha256(file: File): String = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun `a real 200 with a matching receipt body uploads the exact bytes and is ACCEPTED`() {
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        val digest = sha256(file)
        responseStatus = 200
        responseBody = receipt(id, digest)

        val result = transport().upload(id, record(id), ownedOf(file))

        assertEquals("exactly one request", 1, requestCount.get())
        assertEquals("POST", receivedMethods.single())
        // The production envelope is multipart; the audio still reaches the server byte-for-byte.
        val sent = String(receivedBodies.single(), Charsets.ISO_8859_1)
            .replace(Regex("----auris-contribution-[0-9a-fA-F-]{36}"), "BOUNDARY")
        assertEquals(
            "the metadata-first multipart envelope reached the server intact",
            String(envelope(id, file), Charsets.ISO_8859_1),
            sent,
        )
        assertTrue("the audio part carries the exact capture bytes", sent.contains(String(file.readBytes(), Charsets.ISO_8859_1)))
        assertEquals(ContributionUploadTransport.Response.Accepted::class, result::class)
        val accepted = result as ContributionUploadTransport.Response.Accepted
        assertEquals(200, accepted.statusCode)
        assertTrue("the receipt body is handed to validation", accepted.payload.isNotEmpty())
    }

    @Test
    fun `an upload_pending receipt is RETAINED - staging is not acceptance`() {
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        // Cloud route contract (476feadc): this route can answer 201 with `upload_pending` until the
        // owner-side arc completes. That must retain, never release or report success.
        responseStatus = 201
        responseBody = receipt(id, sha256(file), status = "upload_pending")

        val result = transport().upload(id, record(id), ownedOf(file))

        val accepted = result as ContributionUploadTransport.Response.Accepted
        val validator = CaptureReceiptValidator { sha256(file) }
        assertFalse(
            "an upload_pending receipt never validates as acceptance",
            validator.validate(id, accepted.payload, accepted.statusCode),
        )
    }

    @Test
    fun `a real 201 also yields an acceptance`() {
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        responseStatus = 201
        responseBody = receipt(id, sha256(file), status = "duplicate")
        val result = transport().upload(id, record(id), ownedOf(file))
        assertEquals(201, (result as ContributionUploadTransport.Response.Accepted).statusCode)
    }

    @Test
    fun `a non-2xx error body is RETRYABLE when the Worker says retry_after_s`() {
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        responseStatus = 503
        responseBody = """{"schema_version":1,"code":"storage_unavailable","retry_after_s":30}"""
        val result = transport().upload(id, record(id), ownedOf(file))
        val retryable = result as ContributionUploadTransport.Response.Retryable
        assertEquals(503, retryable.statusCode)
        assertEquals(30L, retryable.retryAfterS)
    }

    @Test
    fun `401 unauthorized asks for credential recovery and is never an acceptance`() {
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        responseStatus = 401
        responseBody = """{"schema_version":1,"code":"unauthorized"}"""
        val result = transport().upload(id, record(id), ownedOf(file))
        assertTrue("a 401 is a credential problem, not a stored capture: $result", result is ContributionUploadTransport.Response.Unauthorized)
    }

    @Test
    fun `absent credentials refuse locally BEFORE any bytes leave`() {
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        val noAuth = ContributionUploadTransport(
            endpoint = "http://127.0.0.1:$port/v1/contributions",
            authorization = { null },
        )
        val before = requestCount.get()
        val result = noAuth.upload(id, record(id), ownedOf(file))
        assertTrue("a local credential refusal is Unauthorized", result is ContributionUploadTransport.Response.Unauthorized)
        assertEquals("no bytes left the device", before, requestCount.get())
    }

    @Test
    fun `a code whose HTTP status disagrees is RETAINED, never terminal`() {
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        responseStatus = 503
        responseBody = """{"schema_version":1,"code":"invalid_request"}"""
        val result = transport().upload(id, record(id), ownedOf(file))
        assertTrue("a mismatched code/status pair is never a terminal delete", result is ContributionUploadTransport.Response.Retryable)
    }

    @Test
    fun `a duplicate error member is a malformed answer and retains`() {
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        responseStatus = 503
        responseBody = """{"schema_version":1,"code":"upload_pending","code":"storage_unavailable"}"""
        val result = transport().upload(id, record(id), ownedOf(file))
        assertTrue("duplicate members make the answer unknown", result is ContributionUploadTransport.Response.Retryable)
    }

    @Test
    fun `an oversized response is refused WHILE reading and never release`() {
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        responseStatus = 503
        // A perfectly valid terminal-looking prefix, then an excess that must not be truncated into it.
        responseBody = """{"schema_version":1,"code":"invalid_request"}""" + "x".repeat(2 shl 20)
        val result = transport().upload(id, record(id), ownedOf(file))
        assertTrue(
            "a truncated prefix must never become a delete verdict: $result",
            result is ContributionUploadTransport.Response.Ambiguous,
        )
    }

    @Test
    fun `a body the server streams past the cap is never parsed as a terminal answer`() {
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        responseStatus = 400
        responseBody = """{"schema_version":1,"code":"invalid_request"}""" + " ".repeat(3 shl 20) + "}"
        val result = transport().upload(id, record(id), ownedOf(file))
        assertTrue("oversize is unknown, not terminal: $result", result is ContributionUploadTransport.Response.Ambiguous)
    }

    @Test
    fun `a QUOTED schema_version is a type mismatch, so nothing becomes terminal`() {
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        responseStatus = 400
        responseBody = """{"schema_version":"1","code":"invalid_request"}"""
        val result = transport().upload(id, record(id), ownedOf(file))
        assertTrue("a quoted version is not a numeric envelope: $result", result is ContributionUploadTransport.Response.Retryable)
    }

    @Test
    fun `a quoted retry_after_s is a type mismatch and is not honoured`() {
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        responseStatus = 503
        responseBody = """{"schema_version":1,"code":"storage_unavailable","retry_after_s":"30"}"""
        val result = transport().upload(id, record(id), ownedOf(file))
        assertTrue("a quoted hint is malformed: $result", result is ContributionUploadTransport.Response.Retryable)
        assertNull("no server minimum can be read from a type-mismatched field", (result as ContributionUploadTransport.Response.Retryable).retryAfterS)
    }

    @Test
    fun `the Worker status table maps each refusal to its own disposition`() {
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        val cases = listOf(
            Triple(400, "invalid_request", ContributionUploadTransport.Response.Unservable::class),
            Triple(409, "capture_conflict", ContributionUploadTransport.Response.Terminal::class),
            Triple(410, "record_gone", ContributionUploadTransport.Response.Terminal::class),
            Triple(413, "payload_too_large", ContributionUploadTransport.Response.Terminal::class),
            Triple(415, "unsupported_audio", ContributionUploadTransport.Response.Terminal::class),
            Triple(422, "integrity_mismatch", ContributionUploadTransport.Response.Terminal::class),
            Triple(410, "enrollment_retired", ContributionUploadTransport.Response.Terminal::class),
            Triple(403, "not_enrolled", ContributionUploadTransport.Response.PolicyRefused::class),
            Triple(403, "collection_disabled", ContributionUploadTransport.Response.PolicyRefused::class),
            Triple(429, "rate_limited", ContributionUploadTransport.Response.Retryable::class),
            Triple(429, "quota_exceeded", ContributionUploadTransport.Response.Retryable::class),
            Triple(503, "upload_pending", ContributionUploadTransport.Response.Retryable::class),
            Triple(503, "policy_unavailable", ContributionUploadTransport.Response.Retryable::class),
            Triple(503, "storage_unavailable", ContributionUploadTransport.Response.Retryable::class),
        )
        for ((status, code, expected) in cases) {
            responseStatus = status
            responseBody = """{"schema_version":1,"code":"$code"}"""
            val result = transport().upload(id, record(id), ownedOf(file))
            assertEquals("$status $code", expected, result::class)
        }
    }

    @Test
    fun `a quota_exceeded refusal without a server hint carries the client next-day floor`() {
        // Core c74d7e30: the daily limit is metered at acceptance, and its refusal
        // (quota_exceeded, @cloud dc264066: NO retry_after_s today) must not burn the bounded
        // attempt budget in hours of jittered backoff. When the server names no minimum, the
        // transport supplies the floor itself: seconds until the next UTC midnight.
||||||| /tmp/mfb
        // (quota_exceeded, @cloud dc264066: NO retry_after_s today) must not burn the bounded
        // Attempt budget in hours of jittered backoff. When the server names no minimum, the
        // Transport supplies the floor itself: seconds until the next UTC midnight.
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        responseStatus = 429
        responseBody = """{"schema_version":1,"code":"quota_exceeded"}"""
        val result = transport().upload(id, record(id), ownedOf(file))
        assertTrue("quota_exceeded retains", result is ContributionUploadTransport.Response.Retryable)
        val hint = (result as ContributionUploadTransport.Response.Retryable).retryAfterS
        assertNotNull("an absent server hint must become the client next-day floor", hint)
        assertTrue(
            "the floor must be within one day (got $hint s)",
            hint!! > 0L && hint <= 24L * 3600L + 1L,
        )
    }

    @Test
    fun `a SHORT quota hint cannot buy a pre-midnight retry - the day floor holds`() {
        // Spec 0d59b1d5: the retry minimum for quota_exceeded is the LARGER of the next-day
        // floor and the hint. A 120 s hint (the shape @cloud says ships today) must NOT permit
        // a retry before the accounting day rolls.
||||||| /tmp/mfb
        // Floor and the hint. A 120 s hint (the shape @cloud says ships today) must NOT permit
        // A retry before the accounting day rolls.
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        responseStatus = 429
        responseBody = """{"schema_version":1,"code":"quota_exceeded","retry_after_s":120}"""
        val result = transport(nowEpochMs = { 1_791_194_400_000L }).upload(id, record(id), ownedOf(file))
        val hint = (result as ContributionUploadTransport.Response.Retryable).retryAfterS
        // Pinned 10:00:00 UTC: the next midnight is exactly 14 h = 50,400 s away.
        val expectedFloor = secondsUntilNextUtcMidnight(1_791_194_400_000L)
        assertEquals(50_400L, expectedFloor)
        assertEquals("a short hint must be raised to the day floor", expectedFloor, hint)
    }

    @Test
    fun `a LONGER quota hint still wins over the day floor`() {
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        responseStatus = 429
        responseBody = """{"schema_version":1,"code":"quota_exceeded","retry_after_s":172800}"""
        val pinned = transport(nowEpochMs = { 1_791_194_400_000L })
        val result = pinned.upload(id, record(id), ownedOf(file))
        val hint = (result as ContributionUploadTransport.Response.Retryable).retryAfterS
        assertEquals("a longer server minimum is authoritative", 172_800L, hint)
    }

    @Test
    fun `a rate_limited refusal without a hint keeps the plain backoff`() {
        // The day floor is quota-specific (@cloud dc264066): rate limiting resolves on its own
        // backoff and must not be pushed to the next UTC midnight.
||||||| /tmp/mfb
        // The day floor is quota-specific (@cloud dc264066): rate limiting resolves on its own
        // Backoff and must not be pushed to the next UTC midnight.
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        responseStatus = 429
        responseBody = """{"schema_version":1,"code":"rate_limited"}"""
        val result = transport().upload(id, record(id), ownedOf(file))
        val hint = (result as ContributionUploadTransport.Response.Retryable).retryAfterS
        assertNull("rate_limited keeps the jittered backoff (no floor)", hint)
    }

    @Test
    fun `an UNKNOWN error code is never a terminal delete`() {
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        responseStatus = 400
        responseBody = """{"schema_version":1,"code":"some_future_code"}"""
        val result = transport().upload(id, record(id), ownedOf(file))
        assertTrue(
            "an unknown code retains rather than deletes",
            result !is ContributionUploadTransport.Response.Accepted && result !is ContributionUploadTransport.Response.Terminal,
        )
    }

    @Test
    fun `a THROWN transport failure is Ambiguous, never an acceptance`() {
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        // Point at a closed port: the connection genuinely fails.
        val dead = ContributionUploadTransport(
            endpoint = "http://127.0.0.1:1/v1/contributions",
            authorization = { "Bearer t" },
            callTimeoutMs = 5_000,
        )
        val result = dead.upload(id, record(id), ownedOf(file))
        assertTrue("a failed connection is ambiguous", result is ContributionUploadTransport.Response.Ambiguous)
        assertNull("no status for a request that never landed", (result as ContributionUploadTransport.Response.Ambiguous).statusCode)
    }

    @Test
    fun `a SENSITIVE exception message is never copied into the reason line`() {
        // Reviewer 459074a8: the cause must be the exception CLASS plus a fixed allowlisted CATEGORY, never
        // the exception's own message - which can carry a URL, a host address, a bearer value or
        // server-supplied body text. This plants exactly such a message on the path and asserts none of it
        // survives into the log.
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (tag == "VoiceUploadTransport") lines += message
            }
        }
        val secretHost = "10.11.12.13"
        val secretBearer = "eyJhbGciOiJIUzI1NiJ9.leaked-credential-value"
        val secretBody = "server-said-something-private"
        timber.log.Timber.plant(tree)
        try {
            val id = UUID.randomUUID().toString()
            // The HTTP client is a real OkHttp pointed at an unroutable address, with an interceptor that
            // throws an exception whose MESSAGE embeds all three secrets.
            val leaky = okhttp3.OkHttpClient.Builder()
                .addInterceptor { throw java.io.IOException("connect failed to http://$secretHost/api Bearer $secretBearer $secretBody") }
                .build()
            ContributionUploadTransport(
                endpoint = "http://127.0.0.1:1/v1/contributions",
                authorization = { "Bearer t" },
                client = leaky,
                callTimeoutMs = 5_000,
            ).upload(id, record(id), ownedOf(wavFile(id)))
        } finally {
            timber.log.Timber.uproot(tree)
        }

        assertTrue("a failing exchange must log a reason line, got ${lines.toList()}", lines.isNotEmpty())
        for (line in lines) {
            assertFalse("the reason line must not carry a host address: $line", line.contains(secretHost))
            assertFalse("the reason line must not carry a credential: $line", line.contains(secretBearer))
            assertFalse("the reason line must not carry server text: $line", line.contains(secretBody))
        }
        // And it still names something useful.
        assertTrue("the reason line still names the class and a category: ${lines.last()}", lines.last().contains("cause="))
    }

    @Test
    fun `the OUTER exit reports its own cause, not a stale one from another attempt`() {
        // Reviewer 6a82003a and 5c5bf89c item 1. The two no-status exits are DIFFERENT code paths, and a
        // shared slot meant the outer one could report an earlier failure. The first version of this control
        // was INERT: closing the descriptor does not fail request construction, because multipartFor only
        // borrows inside RequestBody.writeTo, which runs under call.execute() and the INNER catch. The loose
        // assertions accepted the inner line. This forces a construction-time failure instead: an endpoint
        // OkHttp refuses to parse, which throws while building the Request, so the outer catch is the only
        // exit that can run.
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (tag == "VoiceUploadTransport") lines += message
            }
        }
        timber.log.Timber.plant(tree)
        val id = UUID.randomUUID().toString()
        try {
            ContributionUploadTransport(
                endpoint = "http://[::malformed-endpoint",
                authorization = { "Bearer t" },
                callTimeoutMs = 5_000,
            ).upload(id, record(id), ownedOf(wavFile(id)))
        } finally {
            timber.log.Timber.uproot(tree)
        }

        assertEquals("exactly one exit must report: ${lines.toList()}", 1, lines.size)
        val line = lines.single()
        // The OUTER exit's own shape: it never reached a call, so no lifecycle event fired and the phase is
        // still the preparation marker. An inner-exit line would carry an observed transport phase instead.
        // NOT an observed transport phase: the request was never handed to a call, so OkHttp's listener
        // never fired. `request-built` is the marker set just before construction, which is exactly where
        // this exit caught the failure - an INNER-exit line would carry an observed phase such as
        // `connect`, `request-body` or `response-headers-received` instead.
        assertTrue("the outer exit must report the construction phase: $line", line.contains("phase=request-built"))
        assertTrue("the outer exit must name a cause: $line", line.contains("cause="))
        // The outcome must MATCH the response it returns (reviewer 5c5bf89c item 2).
        assertTrue("the outer line must report ambiguous, matching its response: $line", line.contains("outcome=ambiguous"))
        // Opaque capture ID, so a concurrent attempt can be joined to its own timeline.
        assertTrue("the reason line must carry the capture id: $line", line.contains(id))
    }

    @Test
    fun `a transport exit with no status NAMES its phase and cause in the log`() {
        // Reviewer 6a82003a and spec 7e5bbf53 point 2. An installed build sat refusing to upload with only
        // `http=none code=none` in the log, which reads identically for "never sent", "sent, outcome
        // unknown" and "cancelled". The two exits that produce no status are DIFFERENT code paths - the
        // inner catch around call.execute() and the outer catch - and the inner one used to discard its
        // throwable entirely, so the only recorded cause could come from a path that never ran. Both now
        // report their own phase and a content-free cause.
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (tag == "VoiceUploadTransport") lines += message
            }
        }
        timber.log.Timber.plant(tree)
        try {
            val id = UUID.randomUUID().toString()
            val file = wavFile(id)
            val dead = ContributionUploadTransport(
                endpoint = "http://127.0.0.1:1/v1/contributions",
                authorization = { "Bearer t" },
                callTimeoutMs = 5_000,
            )
            dead.upload(id, record(id), ownedOf(file))
        } finally {
            timber.log.Timber.uproot(tree)
        }

        assertTrue("a statusless exit must log a reason line, got ${lines.toList()}", lines.isNotEmpty())
        val line = lines.last()
        // The CAUSE is named: an exception class, and a normalized detail - not a bare "none".
        assertTrue("the reason line must name the exception class: $line", line.contains("cause=") && !line.endsWith("cause=other:other"))
        assertTrue("the reason line must name a phase: $line", line.contains("phase=") && !line.contains("phase=preparing"))
        // And it must stay CONTENT-FREE: no URL, no bearer, no body.
        assertFalse("a reason line must never carry the endpoint: $line", line.contains("127.0.0.1"))
        assertFalse("a reason line must never carry a credential: $line", line.contains("Bearer t"))
    }

    @Test
    fun `a 200 with an ERROR body is Ambiguous because validation rejects it`() {
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        responseStatus = 200
        responseBody = """{"schema_version":1,"code":"storage_unavailable"}"""
        val validator = CaptureReceiptValidator { sha256(file) }
        val result = transport().upload(id, record(id), ownedOf(file))
        val accepted = result as ContributionUploadTransport.Response.Accepted
        assertFalse("a 2xx error body must not validate", validator.validate(id, accepted.payload, accepted.statusCode))
    }

    @Test
    fun `a receipt echoing a DIFFERENT digest does not validate`() {
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        responseStatus = 200
        responseBody = receipt(id, "0".repeat(64))
        val validator = CaptureReceiptValidator { sha256(file) }
        val accepted = transport().upload(id, record(id), ownedOf(file)) as ContributionUploadTransport.Response.Accepted
        assertFalse("a forged digest must not validate", validator.validate(id, accepted.payload, accepted.statusCode))
    }

    /** Wraps a fixture file as the owned source the transport now requires; the caller keeps it open. */
    private fun ownedOf(file: File): OwnedAudio = OwnedAudio(
        java.io.FileInputStream(file),
        file.length(),
        java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) },
    )
}
