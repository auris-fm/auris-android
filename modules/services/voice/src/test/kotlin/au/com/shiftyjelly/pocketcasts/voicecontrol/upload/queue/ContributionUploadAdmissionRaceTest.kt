package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ADMISSION-TO-SEND (spec 540e3bbe, reviewer 80ed95c8 / fada834a).
 *
 * The previous "pre-first-byte" control was worthless in one specific way: it revoked BEFORE the caller
 * was entered, so `isCancelled()` was already true at the first statement and the control could not
 * distinguish "we consulted the signal at the send boundary" from "the attempt was cancelled on arrival".
 * A revocation that lands AFTER admission and AFTER the launch lease was granted, but BEFORE the request
 * is registered, is the window that actually matters - a request put on the network there would upload
 * under a retired epoch.
 *
 * These controls place a DETERMINISTIC BARRIER at actual call registration, inside the transport, and
 * assert on the server side whether a request ever arrived. No sleeping to hit a window by luck.
 */
class ContributionUploadAdmissionRaceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val sockets = mutableListOf<ServerSocket>()
    private val servers = mutableListOf<java.util.concurrent.ExecutorService>()

    @After
    fun closeServers() {
        sockets.forEach { runCatching { it.close() } }
        servers.forEach { it.shutdownNow() }
    }

    /** Counts requests that actually reached the network, and answers 200 with a receipt. */
    private class Recorder(val port: Int, val requests: AtomicInteger, val latch: CountDownLatch) {
        @Volatile var captureId: String? = null
        val arrived: CountDownLatch get() = latch
    }

    private fun recordingServer(): Recorder {
        val socket = ServerSocket(0)
        sockets += socket
        val requests = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val recorder = Recorder(socket.localPort, requests, latch)
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
        servers += pool
        pool.submit {
            while (true) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                pool.submit {
                    runCatching {
                        client.use { c ->
                            val input = c.getInputStream()
                            val head = StringBuilder()
                            while (!head.endsWith("\r\n\r\n")) {
                                val b = input.read()
                                if (b < 0) return@use
                                head.append(b.toChar())
                            }
                            // Drain the body so the client's write completes, then answer.
                            val length = Regex("(?i)content-length: (\\d+)")
                                .find(head)?.groupValues?.get(1)?.toInt() ?: 0
                            var remaining = length
                            val buffer = ByteArray(8192)
                            while (remaining > 0) {
                                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                                if (read < 0) break
                                remaining -= read
                            }
                            requests.incrementAndGet()
                            latch.countDown()
                            val body = "{\"schema_version\":1,\"capture_id\":\"" + (recorder.captureId ?: "") +
                                "\",\"disposition\":\"accepted\"}"
                            val out = c.getOutputStream()
                            out.write(
                                (
                                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " +
                                        body.length + "\r\nConnection: close\r\n\r\n" + body
                                    ).toByteArray(),
                            )
                            out.flush()
                        }
                    }
                }
            }
        }
        return recorder
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

    private fun wav(id: String): File = File(tmp.root, "$id.wav").also {
        it.writeBytes(ByteArray(1024) { b -> (b % 251).toByte() })
    }

    /**
     * The barrier: execution stops here, after the transport was entered and before the request is
     * registered, while a single controlling thread decides what lands in that window.
     */
    private fun transportAtBarrier(port: Int, onBarrier: () -> Unit) = ContributionUploadTransport(
        endpoint = "http://127.0.0.1:$port/v1/contributions",
        authorization = { "Bearer token" },
        callTimeoutMs = 10_000,
        onBeforeRegistrationForTest = onBarrier,
    )

    @Test
    fun `a revocation landing between admission and registration puts NO request on the network`() {
        val server = recordingServer()
        val id = UUID.randomUUID().toString()
        server.captureId = id
        val revoked = AtomicBoolean(false)
        val barrierReached = CountDownLatch(1)
        val decisionMade = CountDownLatch(1)

        val transport = transportAtBarrier(server.port) {
            // We are INSIDE the transport, past admission, immediately before registration.
            barrierReached.countDown()
            check(decisionMade.await(10, TimeUnit.SECONDS)) { "barrier never released" }
        }
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
        servers += pool
        val result = pool.submit<ContributionUploadTransport.Response> {
            transport.upload(id, record(id), wav(id), isCancelled = { revoked.get() })
        }

        assertTrue("the barrier was reached before registration", barrierReached.await(10, TimeUnit.SECONDS))
        // The window opens: revoke here, with the attempt already admitted and about to be registered.
        revoked.set(true)
        decisionMade.countDown()

        val response = result.get(20, TimeUnit.SECONDS)
        assertEquals("withdrawn, not ambiguous", ContributionUploadTransport.Response.Cancelled, response)
        assertEquals("NO request may reach the network in that window", 0, server.requests.get())
        assertTrue("and the server never even saw a connection body", !server.arrived.await(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a revocation landing after registration cancels the call that is already in flight`() {
        val server = ServerSocket(0).also { sockets += it }
        val id = UUID.randomUUID().toString()
        val revoked = AtomicBoolean(false)
        val registered = CountDownLatch(1)

        val transport = ContributionUploadTransport(
            endpoint = "http://127.0.0.1:${server.localPort}/v1/contributions",
            authorization = { "Bearer token" },
            callTimeoutMs = 30_000,
        )
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
        servers += pool
        val result = pool.submit<ContributionUploadTransport.Response> {
            transport.upload(id, record(id), wav(id), isCancelled = { revoked.get() })
        }
        // Wait until a call is actually registered, then withdraw - the opposite ordering.
        repeat(100) {
            if (transport.registeredCallForTest() != null) registered.countDown()
            if (registered.count == 0L) return@repeat
            Thread.sleep(20)
        }
        assertTrue("a call was registered", registered.await(5, TimeUnit.SECONDS))
        revoked.set(true)
        val response = result.get(30, TimeUnit.SECONDS)
        assertEquals("the registered call reports the withdrawal", ContributionUploadTransport.Response.Cancelled, response)
        assertEquals("and nothing is left outstanding", 0, transport.inFlightForTest())
    }

    @Test
    fun `a revocation cancels the REGISTERED call and the writer stops transmitting`() {
        // Reviewer d53d9c68 finding 1: on 26df33d22 the only place cancel() was called was the
        // pre-execute branch, so a call already transmitting was never actually cancelled. This exercises
        // the path the caller now uses: the revoker reaches the registered call directly.
        val socket = ServerSocket(0)
        sockets += socket
        val id = UUID.randomUUID().toString()
        val registered = CountDownLatch(1)
        // ONE transport instance for the whole control, so its own accounting is what is observed.
        val transport = ContributionUploadTransport(
            endpoint = "http://127.0.0.1:${socket.localPort}/v1/contributions",
            authorization = { "Bearer token" },
            callTimeoutMs = 60_000,
            onBeforeRegistrationForTest = null,
        )
        val big = File(tmp.root, "$id.big.wav").also { f ->
            f.writeBytes(ByteArray(8 * 1024 * 1024) { b -> (b % 251).toByte() })
        }
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
        servers += pool
        val result = pool.submit<ContributionUploadTransport.Response> {
            transport.upload(id, record(id), big, onRegistered = { registered.countDown() })
        }
        assertTrue("the call was registered", registered.await(15, TimeUnit.SECONDS))
        Thread.sleep(400) // let the socket buffer fill: the write is now genuinely blocked
        val handle = requireNotNull(transport.currentHandleForTest()) { "a handle was exposed" }
        val cancelledAt = System.nanoTime()
        transport.cancelRegistered(handle)

        val response = result.get(20, TimeUnit.SECONDS)
        val ms = (System.nanoTime() - cancelledAt) / 1_000_000
        assertTrue("cancel() reached the blocked write (took ${ms}ms)", ms < 10_000)
        assertTrue(
            "a cancelled call with no withdrawal signal is an UNKNOWN outcome: $response",
            response is ContributionUploadTransport.Response.Ambiguous,
        )
        assertEquals("this transport has no call left outstanding", 0, transport.inFlightForTest())
    }
}
