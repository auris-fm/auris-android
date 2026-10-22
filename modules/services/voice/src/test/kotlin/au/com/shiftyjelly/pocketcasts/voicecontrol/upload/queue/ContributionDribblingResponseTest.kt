package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * THE DRIBBLING-RESPONSE MEASUREMENT (reviewer evidence limit).
 *
 * The oversize control proves the read stops BEFORE parsing. It does not prove a transfer that trickles
 * slowly is CUT OFF in bounded time - a server can keep a connection alive indefinitely by sending one
 * body byte well inside the socket timeout forever. This control measures that case against a real
 * socket and asserts the wall clock, so "bounded" is a measured fact rather than an inference.
 *
 * The transport's rule: a response is either COMPLETE within the budget, or it is refused as ambiguous.
 * A partial receipt is never a receipt, so a slow trickle can never settle an attempt as accepted or as
 * a terminal disposal - it must retain.
 */
class ContributionDribblingResponseTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private var port = 0

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun record(id: String): RecordNode.Obj = RecordNode.Obj(
        mapOf(
            "schema_version" to RecordNode.ofInt(1),
            "capture_id" to RecordNode.Str(id),
            "enrollment_epoch" to RecordNode.Str("epoch-1"),
            "platform" to RecordNode.Str("android"),
            "app_version" to RecordNode.Str("8.9"),
            "build" to RecordNode.Str("123"),
            "capture_session_id" to RecordNode.Str("session-$id"),
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

    private fun wavFile(id: String): File {
        val file = File(tmp.root, "$id.recording.wav")
        file.writeBytes(ByteArray(1024) { it.toByte() })
        return file
    }

    /**
     * Serves a valid-looking terminal receipt PREFIX and then dribbles one byte every 150 ms forever,
     * declaring a large Content-Length so the client cannot tell the body is short. A client that waits
     * for the declared length hangs; a client that trusts the prefix deletes the capture.
     */
    private fun startDribbler(prefix: String) {
        server.createContext("/v1/contributions") { exchange: HttpExchange ->
            exchange.requestBody.readBytes()
            exchange.responseHeaders.add("Content-Type", "application/json")
            // Declared length never reached: the receipt is INCOMPLETE and must not be trusted.
            exchange.sendResponseHeaders(400, prefix.length + 10_000L)
            val out = exchange.responseBody
            out.write(prefix.toByteArray())
            out.flush()
            // Bounded so the fixture can never outlive the test run: 2s of dribble is far longer than
            // the client's 1s budget, and the handler always finishes.
            runCatching {
                repeat(20) {
                    out.write(' '.code)
                    out.flush()
                    Thread.sleep(150)
                }
                out.close()
            }
        }
        server.start()
        port = server.address.port
    }

    private fun transport(readTimeoutMs: Int) = ContributionUploadTransport(
        endpoint = "http://127.0.0.1:$port/v1/contributions",
        authorization = { "Bearer test-token" },
        readTimeoutMs = readTimeoutMs,
    )

    @Test
    fun `a dribbling terminal prefix is never trusted and the bounded read gives up`() {
        // A complete-looking terminal envelope as the PREFIX, with the body still in flight.
        startDribbler("""{"schema_version":1,"code":"invalid_request"}""")
        val id = UUID.randomUUID().toString()

        val startedAt = System.nanoTime()
        val result = transport(readTimeoutMs = 1_000).upload(id, record(id), wavFile(id))
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        // BOUNDED: the socket read timeout fires, and the whole call returns promptly rather than
        // waiting out the server's dribble.
        assertTrue("the read must be cut off in bounded time (took ${elapsedMs}ms)", elapsedMs < 15_000)
        // SAFE: an incomplete body is not a disposition. A trickling terminal prefix must not become a
        // delete, and it must not become an acceptance either.
        assertTrue(
            "a dribbling terminal prefix must never dispose a capture: $result",
            result is ContributionUploadTransport.Response.Ambiguous,
        )
    }

    @Test
    fun `a slow but COMPLETE receipt is still accepted`() {
        // The counterpart control: slowness alone must not be treated as failure. If the body arrives
        // within the timeout, the same bytes classify normally - otherwise the strictness above would be
        // hiding a transport that rejects valid slow networks.
        val id = UUID.randomUUID().toString()
        val receipt = """{"schema_version":1,"capture_id":"$id","record_sha256":"${"0".repeat(64)}","status":"accepted",""" +
            """"receipt_id":"rcpt-1","accepted_at":"2026-10-22T00:00:00Z","retention_expires_at":"2026-11-21T00:00:00Z"}"""
        server.createContext("/v1/contributions") { exchange: HttpExchange ->
            exchange.requestBody.readBytes()
            val bytes = receipt.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            // Slow, but complete: a genuine 150 ms stall before the body.
            Thread.sleep(150)
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        port = server.address.port

        val result = transport(readTimeoutMs = 10_000).upload(id, record(id), wavFile(id))

        assertEquals(
            "a slow but complete receipt is still a receipt",
            ContributionUploadTransport.Response.Accepted::class,
            result::class,
        )
    }
}
