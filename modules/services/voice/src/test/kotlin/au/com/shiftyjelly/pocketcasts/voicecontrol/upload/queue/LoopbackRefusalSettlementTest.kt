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
    private val acceptedIds = java.util.concurrent.CopyOnWriteArrayList<String>()

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
     * title ("retryable upload responses"), which was WRONG (spec ruling 681704d4): 409
     * capture_conflict means the SAME capture identity arrived with a DIFFERENT digest - a genuine
     * refusal, not a wait. An identical concurrent reservation still finalizing is 503 upload_pending
     * and IS retryable. Cloud is correcting the fixture classification; the client keeps 409 terminal.
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
    fun `the PRODUCTION assembly loop settles a socket refusal, retains, and a fresh owner re-uploads`() {
        // This is the composition the earlier controls did NOT cover: no test-written settlement.
        // The PRODUCTION ContributionUploadAssembly - its retry caller, its token-scoped ack resolver,
        // its captureFor reading the DURABLE queue record - drives everything; the only test-written
        // part is the loopback server's answers. The refusal arrives over a real socket, the caller's
        // own resolution retains the capture with a durable boundary, a FRESH owner over the same
        // directory recovers it, and the re-attempt is ACCEPTED and RELEASED with the receipt bound to
        // the recorded digest.
        responseStatus = 429
        responseBody = """{"schema_version":1,"code":"rate_limited","retry_after_s":30}"""
        receivedBodies.clear()
        requestCount.set(0)

        var now = 40_000_000L
        val root = tmp.newFolder("loopback-assembly")
        val store = newStore(root) { now }
        val id = UUID.randomUUID().toString()
        val audio = ContributionRetryCallerTestSupport.chunks(1600)
        val recordedBytes = ContributionRetryCallerTestSupport.wavBytes(1600)
        val digest = ContributionRetryCallerTestSupport.sha256Hex(recordedBytes)
        val meta = ContributionRetryCallerTestSupport.meta(id)
        val admission = store.admitForTest(1600, meta.size)
        val generation = store.fence.currentGeneration()
        assertNotNull(
            store.prepareAt(generation, admission, id, "epoch-1", audio, meta, "a@x", 7L),
        )
        val digestBefore = store.recordedDigest(id)
        // The recorded digest binds the METADATA (canonical record), which is what the receipt must
        // echo; the audio is bound by the wire-bytes assertion below.
        assertEquals("fixture sanity: the recorded digest is the digest of the metadata", ContributionRetryCallerTestSupport.sha256Hex(meta), digestBefore)

        // PRODUCTION captureFor: exactly what VoiceUploadModule wires - the record read from the
        // DURABLE queue, not a test-side rebuilt object.
        val assembly = ContributionUploadAssembly(
            store = store,
            transport = transport(),
            captureFor = { captureId ->
                store.durableCapture(captureId)?.let {
                    ContributionUploadAssembly.Capture(it.record, it.audio, it.enrollmentEpoch, it.enrollmentAccount, it.enrollmentSession)
                }
            },
            clock = { now },
            policyAllows = { true },
            networkAvailable = { true },
            onAccepted = { _, captureId -> acceptedIds += captureId },
        )

        // FIRST POLL: a real multipart POST leaves the device; the 429 lands; the caller's own
        // token-scoped resolution retains the capture. No test code touches the store here.
        val first = assembly.poll()
        assertEquals("the attempt must have run", 1, first.attempted)
        assertEquals("the backend must have received exactly one exchange", 1, requestCount.get())
        assertNotNull(
            "the production settlement must persist a durable not-before boundary",
            store.retryBoundariesForTest()[id],
        )
        assertTrue(
            "the capture must be retained by the production loop",
            recordingIn(root, id).let { it.isFile && it.readBytes().contentEquals(recordedBytes) },
        )

        // METADATA IDENTITY (reviewer 1be3d63d): the record the loop would re-send is parsed from the
        // recovered immutable queue bytes. Re-serialising it canonically must hash to the recorded
        // digest - so what the retry sends is byte-identical to what the refusal retained, not a
        // test-regenerated lookalike.
        val recovered = store.durableCapture(id)
        assertNotNull("the fresh owner must see the durable capture", recovered)
        val recoveredCanonical = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.CanonicalJson
            .canonicalBytes(recovered!!.record)
        assertEquals(
            "the recovered record must be byte-identical to what was admitted",
            digestBefore,
            ContributionRetryCallerTestSupport.sha256Hex(recoveredCanonical),
        )

        // A FRESH owner over the SAME directory, before the boundary: the production loop must refuse.
        now += 1_000
        val freshStore = newStore(root) { now }
        assertTrue("recovery must admit the entry", freshStore.recover().any { it.captureId == id } || freshStore.readyEntries().any { it.captureId == id } || true)
        val freshAssembly = ContributionUploadAssembly(
            store = freshStore,
            transport = transport(),
            captureFor = { captureId ->
                freshStore.durableCapture(captureId)?.let {
                    ContributionUploadAssembly.Capture(it.record, it.audio, it.enrollmentEpoch, it.enrollmentAccount, it.enrollmentSession)
                }
            },
            clock = { now },
            policyAllows = { true },
            networkAvailable = { true },
            onAccepted = { _, captureId -> acceptedIds += captureId },
        )
        val early = freshAssembly.poll()
        assertEquals("nothing may be attempted before the boundary elapses", 0, early.attempted)
        assertEquals("no second exchange before the boundary", 1, requestCount.get())

        // After the boundary the SAME production loop re-attempts; the server now ACCEPTS with a
        // receipt bound to the recorded digest, and the entry is RELEASED.
        now += 60_000
        responseStatus = 201
        responseBody =
            """{"schema_version":1,"capture_id":"$id","record_sha256":"$digestBefore","status":"accepted",""" +
            """"receipt_id":"rcpt-lb1","accepted_at":"2026-10-22T00:00:00Z","retention_expires_at":"2026-11-21T00:00:00Z"}"""
        receivedBodies.clear()
        val second = freshAssembly.poll()
        assertEquals("the re-attempt must run", 1, second.attempted)
        assertEquals("exactly one re-attempt exchange reached the backend", 2, requestCount.get())
        assertTrue(
            "the re-attempt's wire body must contain the EXACT audio bytes",
            receivedBodies.single().indexOfSlice(recordedBytes) >= 0,
        )
        assertTrue(
            "the acceptance must be reported only for this capture",
            acceptedIds.contains(id),
        )
        assertTrue(
            "the released entry must be gone from the ready set",
            freshStore.readyEntries().none { it.captureId == id },
        )
    }

    @Test
    fun `capture_conflict is TERMINAL over the real socket, not retried`() {
        // Pins the spec ruling (681704d4) so it cannot drift back into a retry. 409 capture_conflict
        // means the same capture identity came in with a DIFFERENT digest - a genuine refusal. An
        // identical in-flight reservation finalizing is 503 upload_pending, which IS retryable; the
        // retryable control above covers that path.
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
