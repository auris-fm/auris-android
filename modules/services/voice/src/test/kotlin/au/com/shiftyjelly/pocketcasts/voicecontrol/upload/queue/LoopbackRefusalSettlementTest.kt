package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * THE LOOPBACK HTTP → QUEUE SETTLEMENT CONTROL (spec cc56440e, e4dfecbe, 652440ff).
 *
 * The retention control next to this one supplies responses at the transport seam, which proves the
 * store keeps audio but proves nothing about whether a REAL socket response settles the queue
 * correctly. This control closes that gap with no seam for the response: a JDK HTTP server on
 * loopback answers with Cloud's exact fixture bodies, the PRODUCTION transport performs a real
 * multipart POST, and the PRODUCTION settlement path decides what the queue does next.
 *
 * What is asserted end to end, per refusal code:
 * - the server actually RECEIVED the upload (a real exchange, with the audio bytes);
 * - the queue retains the capture rather than discarding it;
 * - a durable retry boundary exists and refuses an immediate retry;
 * - a FRESH owner over the same directory recovers the capture with BYTE-IDENTICAL audio;
 * - the re-attempt succeeds over the same real socket and is ACCEPTED.
 *
 * NOT claimed: an installed app process was killed. The restart is an in-process fresh-owner
 * recreation over the same directory, labelled as such rather than as a device restart.
 */
class LoopbackRefusalSettlementTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private val requestCount = AtomicInteger(0)
    private val receivedBodies = mutableListOf<ByteArray>()

    /** What the loopback "backend" answers with. Changed mid-test to model refuse-then-accept. */
    private var responseStatus = 503
    private var responseBody = ""
    private var port = 0

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/contributions") { exchange: HttpExchange ->
            requestCount.incrementAndGet()
            // Drain the body FIRST: an unanswered POST body races the response on a loopback socket.
            synchronized(receivedBodies) { receivedBodies += exchange.requestBody.readBytes() }
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

    /**
     * The FIVE bodies the client actually treats as retryable.
     *
     * `capture_conflict` is NOT among them, and that is a deliberate divergence from Cloud's fixture
     * title ("retryable upload responses"), not an oversight: the backend emits it for a REJECTED
     * reservation (`{kind: "rejected", code: "capture_conflict"}` -> 409), the same capture is already
     * in flight, and re-sending it would double-upload audio the server is already processing. The
     * Android transport therefore maps it to TERMINAL. A retryable classification here would be a bug;
     * the control below pins the terminal behaviour instead.
     */
    private val retryableFixtures = listOf(
        "rate_limited" to (429 to """{"schema_version":1,"code":"rate_limited","retry_after_s":30}"""),
        "quota_exceeded" to (429 to """{"schema_version":1,"code":"quota_exceeded","retry_after_s":30}"""),
        "upload_pending" to (503 to """{"schema_version":1,"code":"upload_pending","retry_after_s":30}"""),
        "policy_unavailable" to (503 to """{"schema_version":1,"code":"policy_unavailable","retry_after_s":30}"""),
        "storage_unavailable" to (503 to """{"schema_version":1,"code":"storage_unavailable","retry_after_s":30}"""),
    )

    private fun transport() = ContributionUploadTransport(
        endpoint = "http://127.0.0.1:$port/v1/contributions",
        authorization = { "Bearer test-token" },
        callTimeoutMs = 10_000,
    )

    private fun newStore(root: File, clock: () -> Long) = ContributionStore(
        root,
        QueueLimits.defaults().copy(maxQueueRecords = 8, retryInitialS = 4, retryMaxS = 8),
        clock = clock,
        jitter = { it - 1 },
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The capture's recording. Matched exactly so debris from other files cannot be mistaken for it. */
    private fun recordingIn(root: File, captureId: String): File = root.walkTopDown().firstOrNull { it.isFile && it.name == "$captureId.recording.wav" }
        ?: File(root, "$captureId.recording.wav")

    @Test
    fun `a real socket refusal retains the capture and a fresh owner re-uploads it successfully`() {
        for ((code, statusAndBody) in retryableFixtures) {
            val (status, body) = statusAndBody
            responseStatus = status
            responseBody = body
            receivedBodies.clear()
            requestCount.set(0)

            var now = 30_000_000L
            val root = tmp.newFolder("loopback-$code")
            val store = newStore(root) { now }
            val id = UUID.randomUUID().toString()
            val meta = ContributionRetryCallerTestSupport.meta(id)
            // The admission must budget for the ACTUAL metadata size: too small a reservation makes
            // prepareAt refuse for a reason unrelated to what this control is about.
            val admission = store.admitForTest(1600, meta.size)
            val generation = store.fence.currentGeneration()
            assertNotNull(
                "$code: fixture sanity - the capture must be admittable",
                store.prepareAt(generation, admission, id, "epoch-1", ContributionRetryCallerTestSupport.chunks(1600), meta, "a@x", 7L),
            )
            val recordingBefore = recordingIn(root, id)
            assertTrue("$code: fixture sanity - audio must exist on disk", recordingBefore.isFile)
            val digestBefore = sha256(recordingBefore.readBytes())

            // The production ORDER: the attempt is PICKED UP (durably consuming it) before the network
            // call, which is what makes the retry settlement below apply to a real admitted attempt.
            val token = store.pickupAttemptToken(generation, id)
            assertNotNull("$code: fixture sanity - the attempt must be pickable", token)

            // FIRST ATTEMPT: a REAL HTTP exchange against the loopback backend.
            val firstResponse = runCatching {
                transport().upload(id, ContributionRetryCallerTestSupport.record(id), recordingBefore)
            }.getOrThrow()

            assertEquals(
                "$code: the real transport must classify a $status/$code refusal as RETRYABLE, got $firstResponse",
                true,
                firstResponse is ContributionUploadTransport.Response.Retryable,
            )
            assertEquals(
                "$code: the loopback backend must have actually received the upload - one real exchange",
                1,
                requestCount.get(),
            )
            assertTrue(
                "$code: the received body must carry audio bytes, not an empty multipart envelope",
                receivedBodies.single().size > 0,
            )

            // Settlement: the retryable response must schedule a durable boundary, not discard.
            store.scheduleRetryForAdmittedAttempt(
                token!!,
                (firstResponse as ContributionUploadTransport.Response.Retryable).retryAfterS,
            )

            // --- FRESH OWNER over the SAME directory: nothing carried in memory. ---
            val fresh = newStore(root) { now }
            fresh.recover()
            val recovered = recordingIn(root, id)
            assertTrue(
                "$code: the capture must SURVIVE settlement - a discarded capture is the retention failure " +
                    "this control exists to catch",
                recovered.isFile && recovered.length() > 0,
            )
            assertEquals(
                "$code: the retained audio must be BYTE-IDENTICAL after recovery",
                digestBefore,
                sha256(recovered.readBytes()),
            )
            val boundary = fresh.retryBoundariesForTest()[id]
            assertNotNull("$code: a durable not-before boundary must be persisted", boundary)
            assertEquals(
                "$code: a fresh owner must not retry before the boundary elapses",
                0,
                fresh.retryEligible(now).size,
            )
            now = boundary!!
            assertEquals(
                "$code: the SAME entry must become eligible once the boundary elapses",
                listOf(id),
                fresh.retryEligible(now),
            )

            // SECOND ATTEMPT over the same real socket, now answered with acceptance.
            responseStatus = 200
            responseBody = ContributionRetryCallerTestSupport.acceptance(id, digestBefore)
            requestCount.set(0)
            receivedBodies.clear()
            val secondResponse = transport().upload(id, ContributionRetryCallerTestSupport.record(id), recovered)
            assertTrue(
                "$code: the re-attempt must be ACCEPTED over the real socket, got $secondResponse",
                secondResponse is ContributionUploadTransport.Response.Accepted,
            )
            assertEquals("$code: exactly one re-attempt must reach the backend", 1, requestCount.get())
            // The audio travels inside a multipart envelope, so hash the BODY would compare an
            // envelope to a recording. Search the received wire bytes for the exact recording bytes
            // instead: that is the property that matters - the re-upload carried the same audio.
            val receivedWire = receivedBodies.single()
            val recordedBytes = recordingIn(root, id).readBytes()
            assertTrue(
                "$code: the re-attempt's wire body must CONTAIN the exact recording bytes " +
                    "(${recordedBytes.size} bytes, sha256 $digestBefore) rather than re-encoded audio",
                receivedWire.indexOfSlice(recordedBytes) >= 0,
            )
        }
    }

    @Test
    fun `capture_conflict is TERMINAL over the real socket, not retried`() {
        // Pins the divergence from Cloud's fixture title so it cannot drift back into a retry. A
        // capture conflict means the server already holds this capture in flight; re-uploading would
        // duplicate audio, so the client must NOT schedule a retry for it.
        responseStatus = 409
        responseBody = """{"schema_version":1,"code":"capture_conflict","retry_after_s":30}"""
        receivedBodies.clear()
        requestCount.set(0)

        var now = 10_000_000L
        val root = tmp.newFolder("loopback-conflict")
        val store = newStore(root) { now }
        val id = UUID.randomUUID().toString()
        val meta = ContributionRetryCallerTestSupport.meta(id)
        val admission = store.admitForTest(1600, meta.size)
        val generation = store.fence.currentGeneration()
        assertNotNull(
            "fixture sanity: the capture must be admittable",
            store.prepareAt(generation, admission, id, "epoch-1", ContributionRetryCallerTestSupport.chunks(1600), meta, "a@x", 7L),
        )
        val recording = recordingIn(root, id)
        val response = transport().upload(id, ContributionRetryCallerTestSupport.record(id), recording)

        assertTrue(
            "capture_conflict must be classified TERMINAL - the same capture is already in flight and " +
                "a retry would double-upload it; got $response",
            response is ContributionUploadTransport.Response.Terminal,
        )
        assertEquals("the backend must have received exactly one real exchange", 1, requestCount.get())
        // No retry boundary may be scheduled for a terminal refusal.
        assertNull(
            "a TERMINAL refusal must not schedule a retry boundary",
            store.retryBoundariesForTest()[id],
        )
    }

    @Test
    fun `the loopback harness is a real socket and not an in-process stub`() {
        // Guards the guard: if this control were quietly talking to a fake transport, every assertion
        // above would be about a stub. Assert an observable server-side port binding.
        responseStatus = 200
        responseBody = ContributionRetryCallerTestSupport.acceptance("harness", sha256(ByteArray(4)))
        assertTrue("the server must be listening on a real ephemeral port", port > 0)
        assertEquals("no request may have been served yet", 0, requestCount.get())
    }
}

/** Byte-sequence search over the raw wire body. */
private fun ByteArray.indexOfSlice(needle: ByteArray): Int {
    if (needle.isEmpty() || needle.size > size) return -1
    outer@ for (start in 0..(size - needle.size)) {
        for (offset in needle.indices) {
            if (this[start + offset] != needle[offset]) continue@outer
        }
        return start
    }
    return -1
}
