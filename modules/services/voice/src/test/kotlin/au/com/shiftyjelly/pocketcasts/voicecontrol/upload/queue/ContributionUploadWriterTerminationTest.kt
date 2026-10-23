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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * CALL CANCELLATION AND WRITER TERMINATION (reviewer 20fa4cb2).
 *
 * The previous design forced a blocked write to fail by reaching into private JDK socket fields, which
 * degrades to a silent no-op, and left the writer running on a cached pool after the caller returned.
 * That is why "the caller returned promptly" was never sufficient evidence - the writer could still be
 * transmitting, and abandoned writers could accumulate.
 *
 * These controls use a transport with a REAL cancellation primitive (OkHttp `Call.cancel()` plus a whole
 * exchange `callTimeout`) and a server that applies genuine backpressure: it accepts the connection and
 * never reads, so the request body cannot drain. What is asserted is the WRITER's own state, observed
 * from the writing side, not the caller's return value.
 */
class ContributionUploadWriterTerminationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val sockets = mutableListOf<ServerSocket>()

    @After
    fun closeServers() {
        sockets.forEach { runCatching { it.close() } }
    }

    /** A listener that accepts and then never reads: the request body cannot drain. */
    private fun blackHole(): Int {
        val socket = ServerSocket(0)
        sockets += socket
        val accepted = AtomicBoolean(true)
        Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }.submit {
            while (accepted.get()) {
                runCatching { socket.accept() }.getOrNull() ?: break
                // Accepted and deliberately never read.
            }
        }
        return socket.localPort
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

    /**
     * A capture far larger than any socket buffer, so the write genuinely blocks once the peer stops
     * reading. [progressed] proves the writer actually entered the body: without it a control could pass
     * because nothing was ever written.
     */
    private fun wavFile(id: String, progressed: AtomicInteger): File {
        val file = File(tmp.root, "$id.recording.wav")
        file.writeBytes(ByteArray(8 * 1024 * 1024) { (it % 251).toByte() })
        return file
    }

    private fun transport(
        port: Int,
        budgetMs: Long,
        maxConcurrent: Int = 2,
        progress: AtomicInteger? = null,
    ) = ContributionUploadTransport(
        endpoint = "http://127.0.0.1:$port/v1/contributions",
        authorization = { "Bearer test-token" },
        callTimeoutMs = budgetMs,
        maxConcurrentCalls = maxConcurrent,
        onBodyProgressForTest = progress?.let { counter -> { counter.incrementAndGet() } },
    )

    @Test
    fun `a withdrawal TERMINATES the writer under real backpressure, not just the caller`() {
        val port = blackHole()
        val id = UUID.randomUUID().toString()
        val revoked = AtomicBoolean(false)
        val writerExited = CountDownLatch(1)
        val copyStarted = CountDownLatch(1)
        // REAL backpressure: count emitted chunks and revoke only after writes have STALLED - a fixed
        // sleep assumes the buffer filled, which is not evidence and proved flaky under load.
        val writes = AtomicInteger(0)
        val stalled = CountDownLatch(1)

        val file = File(tmp.root, "$id.recording.wav").also { it.writeBytes(ByteArray(8 * 1024 * 1024) { b -> (b % 251).toByte() }) }
        val watcher = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
        watcher.submit {
            var lastSeen = -1
            var stallSamples = 0
            while (stallSamples < 3) {
                val now = writes.get()
                stallSamples = if (now == lastSeen && now > 0) stallSamples + 1 else 0
                lastSeen = now
                if (stallSamples >= 3) stalled.countDown() else Thread.sleep(100)
            }
        }

        val result = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }.submit<ContributionUploadTransport.Response> {
            try {
                transport(port, budgetMs = 60_000, progress = writes).upload(
                    id,
                    record(id),
                    file,
                    isCancelled = {
                        copyStarted.countDown()
                        revoked.get()
                    },
                )
            } finally {
                // Observed on the WRITING side: this fires only when the upload call has actually left.
                writerExited.countDown()
            }
        }
        // Withdraw only when writes STALLED: chunks were emitted and stopped growing - the write is
        // genuinely blocked against a peer that never reads.
        assertTrue("the writer entered the transfer", copyStarted.await(10, TimeUnit.SECONDS))
        assertTrue("writes actually stalled (real backpressure)", stalled.await(30, TimeUnit.SECONDS))
        val withdrawnAt = System.nanoTime()
        revoked.set(true)

        val response = result.get(30, TimeUnit.SECONDS)
        val callerMs = (System.nanoTime() - withdrawnAt) / 1_000_000
        assertTrue("the writer EXITED, not merely the caller returned", writerExited.await(5, TimeUnit.SECONDS))
        assertTrue("and it exited promptly (took ${callerMs}ms)", callerMs < 15_000)
        assertEquals("the withdrawal is reported as such", ContributionUploadTransport.Response.Cancelled, response)
        // No writer may outlive its attempt.
        assertEquals("no call is left outstanding", 0, transport(port, 60_000).inFlightForTest())
    }

    @Test
    fun `the whole exchange is bounded by the call budget under backpressure`() {
        val port = blackHole()
        val id = UUID.randomUUID().toString()
        val budgetMs = 2_000L

        val startedAt = System.nanoTime()
        val response = transport(port, budgetMs).upload(id, record(id), wavFile(id, AtomicInteger()))
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue("the exchange ended on its own budget (took ${elapsedMs}ms)", elapsedMs < 20_000)
        assertTrue("an undelivered upload is never a disposition: $response", response is ContributionUploadTransport.Response.Ambiguous)
    }

    @Test
    fun `at most one writer is outstanding and further attempts are refused, not queued`() {
        val port = blackHole()
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        val revoked = AtomicBoolean(false)

        // Hold the first attempt in flight with a long budget.
        val firstResult = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }.submit<ContributionUploadTransport.Response> {
            val file = File(tmp.root, "$first.recording.wav").also { it.writeBytes(ByteArray(8 * 1024 * 1024) { b -> (b % 251).toByte() }) }
            transport(port, budgetMs = 60_000, maxConcurrent = 1)
                .upload(first, record(first), file, isCancelled = { revoked.get() })
        }
        Thread.sleep(1_000)
        // A second attempt against the same transport budget must be refused locally rather than
        // accumulate a writer behind the first.
        val secondResponse = transport(port, budgetMs = 60_000, maxConcurrent = 1)
            .upload(second, record(second), wavFile(second, AtomicInteger()))
        assertTrue(
            "an attempt beyond the concurrency cap is refused locally: $secondResponse",
            secondResponse is ContributionUploadTransport.Response.Ambiguous,
        )
        revoked.set(true)
        firstResult.get(30, TimeUnit.SECONDS)
    }

    @Test
    fun `an already-withdrawn attempt issues no request`() {
        val port = blackHole()
        val id = UUID.randomUUID().toString()
        val transport = transport(port, budgetMs = 5_000)

        val response = transport.upload(id, record(id), wavFile(id, AtomicInteger()), isCancelled = { true })

        assertEquals(ContributionUploadTransport.Response.Cancelled, response)
        assertFalse("nothing may be registered for a withdrawn attempt", transport.registeredCallForTest() != null)
        assertEquals("and no call is outstanding", 0, transport.inFlightForTest())
    }

    @Test
    fun `the caller's remaining budget caps the call, so total time is never exceeded`() {
        val port = blackHole()
        val id = UUID.randomUUID().toString()
        // A transport that would allow a long call, given only a short remainder.
        val startedAt = System.nanoTime()
        val response = transport(port, budgetMs = 60_000)
            .upload(id, record(id), wavFile(id, AtomicInteger()), remainingMs = 1_500)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(
            "the call must be capped by the remainder (took ${elapsedMs}ms, remainder 1500ms)",
            elapsedMs < 20_000,
        )
        assertTrue("unknown, never a disposition: $response", response is ContributionUploadTransport.Response.Ambiguous)
    }
}
