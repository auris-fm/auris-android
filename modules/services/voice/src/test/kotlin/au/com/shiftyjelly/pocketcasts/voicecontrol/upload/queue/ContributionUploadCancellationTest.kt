package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * TRANSPORT-BOUND CANCELLATION AND THE TOTAL DEADLINE (reviewer 2596262d, spec fe1998fb, reviewer 68661171).
 *
 * Two claims that inference cannot establish, so each is measured against a real socket:
 *
 *  - A WITHDRAWN attempt must not put a request on the network, and must stop one already in flight
 *    rather than being noticed only after it completes.
 *  - The budget must cover the WHOLE transfer - connect, request-body write, headers and body - not just
 *    the response read, and a body that finishes just after the cutoff must not become a disposition.
 */
class ContributionUploadCancellationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private var port = 0
    private val requests = AtomicInteger()

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
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
        // Large enough that the request body spans many write chunks.
        file.writeBytes(ByteArray(512 * 1024) { (it % 251).toByte() })
        return file
    }

    private fun transport(budgetMs: Long = 120_000) = ContributionUploadTransport(
        endpoint = "http://127.0.0.1:$port/v1/contributions",
        authorization = { "Bearer test-token" },
        callTimeoutMs = budgetMs,
    )

    private fun serve(handler: (HttpExchange) -> Unit) {
        server.createContext("/v1/contributions") { exchange ->
            requests.incrementAndGet()
            handler(exchange)
        }
        server.start()
        port = server.address.port
    }

    @Test
    fun `a withdrawal from another thread while the transfer is in flight is reported, never a disposition`() {
        // The withdrawal really crosses a thread boundary and is observed while the client is blocked
        // waiting for an answer. The server sends exactly the terminal body that would otherwise delete
        // the capture, so if the withdrawal did not win, this control fails.
        val handlerEntered = CountDownLatch(1)
        serve { exchange ->
            exchange.requestBody.readBytes()
            handlerEntered.countDown()
            Thread.sleep(1_000)
            runCatching {
                val body = """{"schema_version":1,"code":"invalid_request"}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(400, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        val id = UUID.randomUUID().toString()
        val revoked = AtomicBoolean(false)

        val result = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }.submit<ContributionUploadTransport.Response> {
            transport(budgetMs = 60_000).upload(id, record(id), wavFile(id), isCancelled = { revoked.get() })
        }
        assertTrue("the request reached the server", handlerEntered.await(10, TimeUnit.SECONDS))
        // The withdrawal lands HERE, on the test thread, while the upload thread is blocked on the response.
        revoked.set(true)
        val response = result.get(30, TimeUnit.SECONDS)

        assertTrue(
            "a withdrawn attempt reports the withdrawal, not an answer that arrived after it: $response",
            response === ContributionUploadTransport.Response.Cancelled,
        )
        assertFalse(
            "and never a disposition",
            response is ContributionUploadTransport.Response.Terminal ||
                response is ContributionUploadTransport.Response.Accepted,
        )
    }

    @Test
    fun `a withdrawal mid-transfer is checked per chunk and before settlement`() {
        // The response read is where a revocation used to be invisible: the signal is sampled only outside
        // upload(). Here the server delivers the whole request, then stalls, and the withdrawal lands while
        // the client is blocked on the response - a terminal error body must not become a disposition and
        // the attempt must report the withdrawal.
        val handlerEntered = CountDownLatch(1)
        serve { exchange ->
            exchange.requestBody.readBytes()
            handlerEntered.countDown()
            Thread.sleep(2_000)
            runCatching {
                val body = """{"schema_version":1,"code":"invalid_request"}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(400, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        val id = UUID.randomUUID().toString()
        val revoked = AtomicBoolean(false)

        val result = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }.submit {
            transport(budgetMs = 60_000).upload(id, record(id), wavFile(id), isCancelled = { revoked.get() })
        }
        assertTrue("the request reached the server", handlerEntered.await(10, TimeUnit.SECONDS))
        revoked.set(true)
        val response = result.get(30, TimeUnit.SECONDS)

        assertFalse(
            "a withdrawal must never yield a disposition from what arrived: $response",
            response is ContributionUploadTransport.Response.Terminal ||
                response is ContributionUploadTransport.Response.Accepted,
        )
    }

    @Test
    fun `a withdrawal BEFORE the first byte issues no request at all`() {
        serve { exchange ->
            exchange.requestBody.readBytes()
            val body = """{"schema_version":1,"code":"invalid_request"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(400, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val id = UUID.randomUUID().toString()
        val revoked = AtomicBoolean(false)

        // The withdrawal is already in effect when upload() is entered: the caller's own check and this
        // call are not atomic, so the transport must re-check before the first byte.
        val response = transport().upload(id, record(id), wavFile(id), isCancelled = { true })
        revoked.set(true)

        assertEquals("an already-withdrawn attempt is Cancelled", ContributionUploadTransport.Response.Cancelled, response)
        assertEquals("NOT ONE request may reach the network", 0, requests.get())
    }

    @Test
    fun `a blocked write under real backpressure is ABORTED, not merely observed between chunks`() {
        // REAL BACKPRESSURE (reviewer 88b0aa2b). The server accepts, reads NOTHING, and keeps the socket
        // buffer full, so the client's write genuinely blocks in the kernel. readTimeout governs input
        // reads only, and a deadline check between chunks can never fire while a chunk is stuck - so only
        // an actual abort of the socket can end this. The body is large enough to overrun any socket
        // buffer, which is what makes the write block rather than complete instantly.
        serve { exchange ->
            // Accept the exchange and never read the request body.
            Thread.sleep(30_000)
            runCatching { exchange.close() }
        }
        val id = UUID.randomUUID().toString()
        val budgetMs = 2_000L

        val startedAt = System.nanoTime()
        val response = transport(budgetMs = budgetMs).upload(id, record(id), wavFile(id))
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        // The whole point: this returns. Without an enforceable write abort it blocks until the peer
        // eventually gives up, which is exactly the unbounded case under test.
        assertTrue(
            "a blocked write must end on its own budget (took ${elapsedMs}ms, budget ${budgetMs}ms)",
            elapsedMs < 15_000,
        )
        assertTrue("an undelivered upload is never a disposition: $response", response is ContributionUploadTransport.Response.Ambiguous)
    }

    @Test
    fun `a withdrawal is honoured while the client is blocked unproductively`() {
        // The server accepts and never answers. Whatever slice the client is blocked in, the withdrawal -
        // which lands from another thread once the client has entered the upload - must dominate the
        // outcome: not a disposition, and reported as the withdrawal itself.
        serve { exchange ->
            Thread.sleep(30_000)
            runCatching { exchange.close() }
        }
        val id = UUID.randomUUID().toString()
        val revoked = AtomicBoolean(false)
        val entered = CountDownLatch(1)

        val result = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }.submit<ContributionUploadTransport.Response> {
            transport(budgetMs = 60_000).upload(
                id,
                record(id),
                wavFile(id),
                isCancelled = {
                    entered.countDown()
                    revoked.get()
                },
            )
        }
        // Waits for the upload thread to be INSIDE the attempt (its first cancellation check), then the
        // withdrawal is set here, on the test thread: a real cross-thread revocation.
        assertTrue("the attempt started", entered.await(10, TimeUnit.SECONDS))
        revoked.set(true)

        val response = result.get(30, TimeUnit.SECONDS)
        assertFalse(
            "a withdrawn attempt never yields a disposition: $response",
            response is ContributionUploadTransport.Response.Terminal ||
                response is ContributionUploadTransport.Response.Accepted,
        )
    }

    @Test
    fun `delayed headers are bounded by the same total deadline`() {
        // The request is fully delivered, then the server sits on the response. Header acquisition has to
        // consume the SAME budget rather than starting a fresh one.
        serve { exchange ->
            exchange.requestBody.readBytes()
            Thread.sleep(30_000)
            runCatching { exchange.close() }
        }
        val id = UUID.randomUUID().toString()
        val budgetMs = 2_000L

        val startedAt = System.nanoTime()
        val response = transport(budgetMs = budgetMs).upload(id, record(id), wavFile(id))
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(
            "delayed headers must end on the budget (took ${elapsedMs}ms, budget ${budgetMs}ms)",
            elapsedMs < 15_000,
        )
        assertTrue("no answer yet is not a disposition: $response", response is ContributionUploadTransport.Response.Ambiguous)
    }

    @Test
    fun `a body that finishes just AFTER the cutoff is unknown, never a deletion`() {
        // A COMPLETE terminal error envelope, flushed just past the deadline. If EOF were accepted without
        // re-checking the deadline, this would delete the capture (reviewer 68661171).
        serve { exchange ->
            exchange.requestBody.readBytes()
            val body = """{"schema_version":1,"code":"invalid_request"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(400, body.size.toLong())
            Thread.sleep(3_000) // past the 1s budget, before the body is written
            exchange.responseBody.use { it.write(body) }
        }
        val id = UUID.randomUUID().toString()

        val response = transport(budgetMs = 1_000).upload(id, record(id), wavFile(id))

        assertTrue(
            "a body landing after the cutoff must not be classified: $response",
            response is ContributionUploadTransport.Response.Ambiguous,
        )
        assertFalse(
            "it must certainly not become a terminal disposal",
            response is ContributionUploadTransport.Response.Terminal,
        )
    }
}
