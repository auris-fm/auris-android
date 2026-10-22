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

    private fun transport(token: String = "test-token") = ContributionUploadTransport(
        endpoint = "http://127.0.0.1:$port/v1/contributions",
        authorization = { "Bearer $token" },
        connectTimeoutMs = 5_000,
        readTimeoutMs = 10_000,
    )

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

        val result = transport().upload(id, record(id), file)

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
    fun `a real 201 also yields an acceptance`() {
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        responseStatus = 201
        responseBody = receipt(id, sha256(file), status = "duplicate")
        val result = transport().upload(id, record(id), file)
        assertEquals(201, (result as ContributionUploadTransport.Response.Accepted).statusCode)
    }

    @Test
    fun `a non-2xx error body is RETRYABLE when the Worker says retry_after_s`() {
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        responseStatus = 503
        responseBody = """{"schema_version":1,"code":"storage_unavailable","retry_after_s":30}"""
        val result = transport().upload(id, record(id), file)
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
        val result = transport().upload(id, record(id), file)
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
        val result = noAuth.upload(id, record(id), file)
        assertTrue("a local credential refusal is Unauthorized", result is ContributionUploadTransport.Response.Unauthorized)
        assertEquals("no bytes left the device", before, requestCount.get())
    }

    @Test
    fun `a code whose HTTP status disagrees is RETAINED, never terminal`() {
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        responseStatus = 503
        responseBody = """{"schema_version":1,"code":"invalid_request"}"""
        val result = transport().upload(id, record(id), file)
        assertTrue("a mismatched code/status pair is never a terminal delete", result is ContributionUploadTransport.Response.Retryable)
    }

    @Test
    fun `a duplicate error member is a malformed answer and retains`() {
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        responseStatus = 503
        responseBody = """{"schema_version":1,"code":"upload_pending","code":"storage_unavailable"}"""
        val result = transport().upload(id, record(id), file)
        assertTrue("duplicate members make the answer unknown", result is ContributionUploadTransport.Response.Retryable)
    }

    @Test
    fun `an oversized response is refused WHILE reading and never release`() {
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        responseStatus = 503
        // A perfectly valid terminal-looking prefix, then an excess that must not be truncated into it.
        responseBody = """{"schema_version":1,"code":"invalid_request"}""" + "x".repeat(2 shl 20)
        val result = transport().upload(id, record(id), file)
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
        val result = transport().upload(id, record(id), file)
        assertTrue("oversize is unknown, not terminal: $result", result is ContributionUploadTransport.Response.Ambiguous)
    }

    @Test
    fun `a QUOTED schema_version is a type mismatch, so nothing becomes terminal`() {
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        responseStatus = 400
        responseBody = """{"schema_version":"1","code":"invalid_request"}"""
        val result = transport().upload(id, record(id), file)
        assertTrue("a quoted version is not a numeric envelope: $result", result is ContributionUploadTransport.Response.Retryable)
    }

    @Test
    fun `a quoted retry_after_s is a type mismatch and is not honoured`() {
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        responseStatus = 503
        responseBody = """{"schema_version":1,"code":"storage_unavailable","retry_after_s":"30"}"""
        val result = transport().upload(id, record(id), file)
        assertTrue("a quoted hint is malformed: $result", result is ContributionUploadTransport.Response.Retryable)
        assertNull("no server minimum can be read from a type-mismatched field", (result as ContributionUploadTransport.Response.Retryable).retryAfterS)
    }

    @Test
    fun `the Worker status table maps each refusal to its own disposition`() {
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        val cases = listOf(
            Triple(400, "invalid_request", ContributionUploadTransport.Response.Terminal::class),
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
            val result = transport().upload(id, record(id), file)
            assertEquals("$status $code", expected, result::class)
        }
    }

    @Test
    fun `an UNKNOWN error code is never a terminal delete`() {
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        responseStatus = 400
        responseBody = """{"schema_version":1,"code":"some_future_code"}"""
        val result = transport().upload(id, record(id), file)
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
            connectTimeoutMs = 500,
            readTimeoutMs = 500,
        )
        val result = dead.upload(id, record(id), file)
        assertTrue("a failed connection is ambiguous", result is ContributionUploadTransport.Response.Ambiguous)
        assertNull("no status for a request that never landed", (result as ContributionUploadTransport.Response.Ambiguous).statusCode)
    }

    @Test
    fun `a 200 with an ERROR body is Ambiguous because validation rejects it`() {
        val id = UUID.randomUUID().toString()
        val file = wavFile(id)
        responseStatus = 200
        responseBody = """{"schema_version":1,"code":"storage_unavailable"}"""
        val validator = CaptureReceiptValidator { sha256(file) }
        val result = transport().upload(id, record(id), file)
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
        val accepted = transport().upload(id, record(id), file) as ContributionUploadTransport.Response.Accepted
        assertFalse("a forged digest must not validate", validator.validate(id, accepted.payload, accepted.statusCode))
    }
}
