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
     * Serves a valid-looking terminal receipt PREFIX and then dribbles one byte every 150 ms, each one
     * well INSIDE the client's socket read timeout, and keeps doing it for far longer than the client's
     * total budget. Without a wall deadline this connection is unbounded: no individual read ever times
     * out. [produced] tells the test whether the server was STILL producing when the client gave up.
     */
    private fun startDribbler(prefix: String): () -> Boolean {
        val producing = java.util.concurrent.atomic.AtomicBoolean(true)
        server.createContext("/v1/contributions") { exchange: HttpExchange ->
            exchange.requestBody.readBytes()
            exchange.responseHeaders.add("Content-Type", "application/json")
            // Declared length never reached: the receipt is INCOMPLETE and must not be trusted.
            exchange.sendResponseHeaders(400, prefix.length + 1_000_000L)
            val out = exchange.responseBody
            out.write(prefix.toByteArray())
            out.flush()
            // The drips last ~30s of wall clock - an order of magnitude past the asserted deadline.
            runCatching {
                repeat(200) {
                    out.write(' '.code)
                    out.flush()
                    Thread.sleep(150)
                }
                out.close()
            }
            producing.set(false)
        }
        server.start()
        port = server.address.port
        return { producing.get() }
    }

    private fun transport(budgetMs: Long) = ContributionUploadTransport(
        endpoint = "http://127.0.0.1:$port/v1/contributions",
        authorization = { "Bearer test-token" },
        callTimeoutMs = budgetMs,
    )

    @Test
    fun `a still-productive drip is CUT OFF at the wall deadline, not waited out`() {
        // A complete-looking terminal envelope as the PREFIX, with the body still in flight.
        val stillProducing = startDribbler("""{"schema_version":1,"code":"invalid_request"}""")
        val id = UUID.randomUUID().toString()
        val budgetMs = 1_500L

        val startedAt = System.nanoTime()
        val result = transport(budgetMs = budgetMs).upload(id, record(id), ownedOf(wavFile(id)))
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        // THE CLAIM UNDER TEST: the client stopped on its OWN budget. The socket inactivity timeout was
        // 60s, far longer than this whole test, so only the wall deadline can explain the return.
        // A generous ceiling, because what this control must exclude is "waited for the whole transfer"
        // (~30s), not jitter of a second or two on a loaded CI box.
        assertTrue(
            "the upload must stop at its own deadline (took ${elapsedMs}ms, budget ${budgetMs}ms)",
            elapsedMs >= budgetMs && elapsedMs < 15_000,
        )
        // AND THE PEER WAS STILL PRODUCING: the drip runs ~30s, so the server had not finished. Waiting
        // the transfer out is therefore ruled out as the explanation.
        assertTrue("the server was still dripping when the client gave up", stillProducing())
        // SAFE: an incomplete body is not a disposition - it must never dispose a capture.
        assertTrue(
            "a cut-off body must never dispose a capture: $result",
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

        val result = transport(budgetMs = 30_000).upload(id, record(id), ownedOf(wavFile(id)))

        assertEquals(
            "a slow but complete receipt is still a receipt",
            ContributionUploadTransport.Response.Accepted::class,
            result::class,
        )
    }

    /** Wraps a fixture file as the owned source the transport now requires; the caller keeps it open. */
    private fun ownedOf(file: File): OwnedAudio = OwnedAudio(
        java.io.FileInputStream(file),
        file.length(),
        java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) },
    )
}
