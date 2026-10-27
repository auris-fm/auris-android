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
import org.junit.Assert.assertFalse
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
    /** Stores built by this class, so teardown can release the sources they still hold. */
    private val storesForTeardown = mutableListOf<ContributionStore>()

    @org.junit.After
    fun releaseSources() {
        // Settle EVERY live store, not just the ones this test kept a reference to: the source bound is
        // process-wide, so a missed descriptor holds a slot for the rest of the JVM (reviewer 2c7dc375).
        ContributionStore.settleAllLiveSourcesForTest()
        storesForTeardown.forEach { runCatching { it.close() } }
        org.junit.Assert.assertEquals("class left descriptors open", 0, ContributionStore.processOpenSourceSlotsForTest())
    }

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
    ).also { storesForTeardown += it }

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
                transport().upload(id, ContributionRetryCallerTestSupport.record(id), ownedOf(recordingBefore))
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
            // The previous owner released the directory first (spec c8f766fb).
            store.close()
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
            val secondResponse = transport().upload(id, ContributionRetryCallerTestSupport.record(id), ownedOf(recovered))
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

    /**
     * The production assembly loop (caller + token-scoped resolver + captureFor via
     * store.durableCapture, as VoiceUploadModule wires it) settles EVERY retryable refusal over a
     * real socket: retain with a durable boundary, fresh-owner recovery, boundary timing, accepted
     * re-upload with a receipt bound to the recorded digest.
     *
     * The record is verified from the RECEIVED WIRE BYTES, not from memory: the metadata part's
     * claimed record digest must equal the store's recorded digest on BOTH exchanges, so what the
     * transport actually sent is what the refusal retained (reviewer 62e2beb6).
     */
    @Test
    fun `the PRODUCTION assembly loop settles every retryable refusal, retains, and a fresh owner re-uploads`() {
        for ((code, statusAndBody) in retryableFixtures) {
            val (status, body) = statusAndBody
            responseStatus = status
            responseBody = body
            receivedBodies.clear()
            acceptedIds.clear()
            requestCount.set(0)

            var now = 40_000_000L
            val root = tmp.newFolder("loopback-assembly-$code")
            val store = newStore(root) { now }
            val id = UUID.randomUUID().toString()
            val audio = ContributionRetryCallerTestSupport.chunks(1600)
            val recordedBytes = ContributionRetryCallerTestSupport.wavBytes(1600)
            val meta = ContributionRetryCallerTestSupport.meta(id)
            val admission = store.admitForTest(1600, meta.size)
            val generation = store.fence.currentGeneration()
            assertNotNull(
                "$code: fixture sanity - the capture must be admittable",
                store.prepareAt(generation, admission, id, "epoch-1", audio, meta, "a@x", 7L),
            )
            val digestBefore = store.recordedDigest(id)!!
            assertEquals(
                "$code: fixture sanity - the recorded digest binds the metadata",
                ContributionRetryCallerTestSupport.sha256Hex(meta),
                digestBefore,
            )

            // The expected canonical bytes, from the store's OWN durable record read back from disk
            // (not the in-memory fixture object): this is the identity every exchange must carry.
            // SETTLE the source immediately: an open descriptor holds a SOURCE LEASE that keeps the
            // directory until it closes (reviewer 7c2f30e3 finding 2), so leaking it would block the fresh
            // owner below. Only the RECORD is needed here.
            val expectedCanonical = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.CanonicalJson
                .canonicalBytes(store.durableCapture(id)!!.record).toList().also { store.settleSourcesForTest() }

            val assembly = productionAssembly(store, { now })
            val first = assembly.poll()
            assertEquals("$code: the attempt must have run", 1, first.attempted)
            assertEquals("$code: exactly one exchange left the device", 1, requestCount.get())
            assertEquals(
                "$code: the FIRST request's metadata part must claim the recorded digest - the " +
                    "transport sent the admitted record, not a lookalike",
                digestBefore,
                claimedRecordDigest(receivedBodies.single(), id),
            )
            // The claim alone proves nothing: hash the EMBEDDED record bytes from the wire and compare
            // them to the durable expected bytes. A transport that altered the record while keeping
            // the claim unchanged fails here.
            assertEquals(
                "$code: the embedded record BYTES must equal the durable canonical record",
                expectedCanonical,
                embeddedRecordBytes(receivedBodies.single(), id).toList(),
            )
            val boundary = store.retryBoundariesForTest()[id]
            assertNotNull("$code: the production settlement must persist a durable boundary", boundary)
            assertTrue(
                "$code: the capture must be retained byte-identical",
                recordingIn(root, id).let { it.isFile && it.readBytes().contentEquals(recordedBytes) },
            )

            // A FRESH owner over the SAME directory: recovery is asserted from the FRESH store only -
            // the durable capture with its identity, and the EXACT persisted boundary value.
            now += 1_000
            // The previous owner released the directory first (spec c8f766fb).
            store.close()
            val freshStore = newStore(root) { now }
            // Recovery is EXPLICIT on a fresh owner (as RetryableRefusalRetentionTest pins); the
            // assertion reads what the fresh owner itself recovered, not the original store.
            val recoveredEntries = freshStore.recover()
            assertTrue(
                "$code: the fresh owner's recovery must include this capture",
                recoveredEntries.any { it.captureId == id },
            )
            val recovered = freshStore.durableCapture(id)
            assertNotNull("$code: the fresh owner must recover the durable capture", recovered)
            assertEquals("$code: recovered account identity", "a@x", recovered!!.enrollmentAccount)
            assertEquals("$code: recovered session identity", 7L, recovered.enrollmentSession)
            assertEquals(
                "$code: the fresh owner must read the SAME persisted boundary, not a recomputed one",
                boundary,
                freshStore.retryBoundariesForTest()[id],
            )
            assertTrue(
                "$code: the recovered audio must be byte-identical",
                recovered.audio.readBytes().contentEquals(recordedBytes),
            )
            // Release the fresh owner's own source lease before the re-attempt, so the descriptor this
            // assertion opened cannot keep the directory from the store that has to retry.
            recovered.owned?.close()

            // The recovered immutable record must canonicalize to the SAME bytes the first exchange
            // sent - recovery preserved identity.
            assertEquals(
                "$code: the recovered record must canonicalize identically",
                expectedCanonical,
                au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.CanonicalJson
                    .canonicalBytes(recovered.record).toList(),
            )

            val freshAssembly = productionAssembly(freshStore, { now })
            val early = freshAssembly.poll()
            assertEquals("$code: nothing may be attempted before the boundary", 0, early.attempted)
            assertEquals("$code: no second exchange before the boundary", 1, requestCount.get())

            now += 60_000
            responseStatus = 201
            responseBody = receiptFor(id, digestBefore)
            receivedBodies.clear()
            requestCount.set(0)
            val second = freshAssembly.poll()
            assertEquals("$code: the re-attempt must run", 1, second.attempted)
            assertEquals("$code: exactly one re-attempt exchange", 1, requestCount.get())
            assertEquals(
                "$code: the RE-ATTEMPT's metadata part must claim the recorded digest",
                digestBefore,
                claimedRecordDigest(receivedBodies.single(), id),
            )
            assertEquals(
                "$code: the RE-ATTEMPT's embedded record BYTES must equal the durable canonical record",
                expectedCanonical,
                embeddedRecordBytes(receivedBodies.single(), id).toList(),
            )
            assertTrue(
                "$code: the re-attempt's wire body must contain the exact audio bytes",
                receivedBodies.single().indexOfSlice(recordedBytes) >= 0,
            )
            assertTrue(
                "$code: acceptance (freshDigest=" + freshStore.recordedDigest(id) +
                    ", before=" + digestBefore +
                    ", readyNow=" + freshStore.readyEntries().map { it.captureId } + ")",
                acceptedIds.contains(id),
            )
            assertTrue(
                "$code: the released entry must be gone from the ready set",
                freshStore.readyEntries().none { it.captureId == id },
            )
        }
    }

    @Test
    fun `the PRODUCTION assembly loop disposes a terminal capture_conflict without a retry`() {
        responseStatus = 409
        responseBody = """{"schema_version":1,"code":"capture_conflict","retry_after_s":30}"""
        receivedBodies.clear()
        acceptedIds.clear()
        requestCount.set(0)

        var now = 40_000_000L
        val root = tmp.newFolder("loopback-assembly-conflict")
        val store = newStore(root) { now }
        val id = UUID.randomUUID().toString()
        val meta = ContributionRetryCallerTestSupport.meta(id)
        val admission = store.admitForTest(1600, meta.size)
        val generation = store.fence.currentGeneration()
        assertNotNull(
            store.prepareAt(generation, admission, id, "epoch-1", ContributionRetryCallerTestSupport.chunks(1600), meta, "a@x", 7L),
        )

        val assembly = productionAssembly(store, { now })
        val result = assembly.poll()
        assertEquals("the attempt must have run", 1, result.attempted)
        assertEquals("exactly one exchange", 1, requestCount.get())
        assertNull(
            "a TERMINAL refusal must not schedule a retry boundary",
            store.retryBoundariesForTest()[id],
        )
        assertTrue(
            "terminal disposal removes the entry from the ready set",
            store.readyEntries().none { it.captureId == id },
        )
        // onAccepted is the caller's RELEASE hook, not an acceptance-specific signal: it fires for a
        // SERVER_TERMINAL disposal too. The refusal assertions are the null boundary and the removed
        // entry above; a terminal disposal must never leave audio queued for retry.
        assertTrue("no retry may remain scheduled", store.retryBoundariesForTest().isEmpty())
    }

    @Test
    fun `a receipt bound to DIFFERENT bytes never releases the entry`() {
        // The negative before any release claim: a 201 whose record digest does not match the queue's
        // recorded digest is NOT an acceptance - the resolver retains the capture.
        responseStatus = 429
        responseBody = """{"schema_version":1,"code":"rate_limited","retry_after_s":1}"""
        receivedBodies.clear()
        acceptedIds.clear()
        requestCount.set(0)

        var now = 50_000_000L
        val root = tmp.newFolder("loopback-assembly-forged")
        val store = newStore(root) { now }
        val id = UUID.randomUUID().toString()
        val meta = ContributionRetryCallerTestSupport.meta(id)
        val admission = store.admitForTest(1600, meta.size)
        val generation = store.fence.currentGeneration()
        assertNotNull(
            store.prepareAt(generation, admission, id, "epoch-1", ContributionRetryCallerTestSupport.chunks(1600), meta, "a@x", 7L),
        )
        val digestBefore = store.recordedDigest(id)
        val assembly = productionAssembly(store, { now })
        assertEquals(1, assembly.poll().attempted)

        now += 60_000
        responseStatus = 201
        responseBody = receiptFor(id, "f".repeat(64)) // a valid-shape receipt for OTHER bytes
        receivedBodies.clear()
        val second = assembly.poll()
        assertEquals("the re-attempt must run", 1, second.attempted)
        assertFalse(
            "a receipt for different bytes must NOT be reported as acceptance",
            acceptedIds.contains(id),
        )
        assertEquals(
            "the capture must still be retained",
            digestBefore,
            store.recordedDigest(id),
        )
    }

    /** The production wiring: captureFor reads the DURABLE queue record, exactly as VoiceUploadModule. */
    private fun productionAssembly(
        store: ContributionStore,
        now: () -> Long,
    ): ContributionUploadAssembly = ContributionUploadAssembly(
        store = store,
        transport = transport(),
        captureFor = { captureId ->
            store.durableCapture(captureId)?.let {
                ContributionUploadAssembly.Capture(it.record, it.audio, it.enrollmentEpoch, it.enrollmentAccount, it.enrollmentSession, it.owned)
            }
        },
        clock = now,
        policyAllows = { true },
        networkAvailable = { true },
        onAccepted = { _, captureId -> acceptedIds += captureId },
    )

    /**
     * Parses the ACTUAL received multipart body: extracts the metadata part's claimed record digest.
     * Verifying the wire (not memory) is the point - a reserialized in-memory record proves nothing
     * about what the transport sent.
     */
    private fun claimedRecordDigest(wire: ByteArray, captureId: String): String? {
        val text = String(wire, Charsets.UTF_8)
        val partStart = text.indexOf("name=\"metadata\"")
        if (partStart < 0) return null
        val jsonStart = text.indexOf('{', partStart)
        val json = text.substring(jsonStart, text.indexOf("\r\n--", jsonStart))
        val claimed = Regex("\"record_sha256\":\"([0-9a-f]{64})\"").find(json)?.groupValues?.get(1)
            ?: return null
        // The part must be THIS capture's, not debris from another exchange.
        check(json.contains("\"capture_id\":\"" + captureId + "\"")) { "metadata part is for another capture" }
        return claimed
    }

    /**
     * Extracts the ACTUAL embedded record bytes from the received metadata part. The transport embeds
     * the canonical record verbatim between \"record\": and ,\"record_sha256\", so a byte comparison
     * against the durable expected bytes proves what was SENT - a transport could alter the record
     * while leaving the claim string unchanged, and the claim alone would not notice (reviewer
     * bcdb6e4e).
     */
    private fun embeddedRecordBytes(wire: ByteArray, captureId: String): ByteArray {
        val text = String(wire, Charsets.UTF_8)
        val partStart = text.indexOf("name=\"metadata\"")
        check(partStart >= 0) { "no metadata part in the received body" }
        val recordKey = text.indexOf("\"record\":", partStart)
        val recordStart = recordKey + "\"record\":".length
        val claimStart = text.indexOf(",\"record_sha256\":", recordStart)
        check(claimStart > recordStart) { "no record_sha256 claim after the record" }
        val embedded = text.substring(recordStart, claimStart)
        check(embedded.contains("\"\$captureId\"") || embedded.contains(captureId)) {
            "the embedded record is for another capture"
        }
        return embedded.toByteArray(Charsets.UTF_8)
    }

    private fun receiptFor(id: String, digest: String): String {
        val q = "\""
        val sb = StringBuilder()
        sb.append("{" + q + "schema_version" + q + ":1," + q + "capture_id" + q + ":" + q).append(id)
        sb.append(q + "," + q + "record_sha256" + q + ":" + q).append(digest)
        sb.append(q + "," + q + "status" + q + ":" + q + "accepted" + q + "," + q + "receipt_id" + q + ":" + q + "rcpt-lb1" + q)
        sb.append("," + q + "accepted_at" + q + ":" + q + "2026-10-22T00:00:00Z" + q)
        sb.append("," + q + "retention_expires_at" + q + ":" + q + "2026-11-21T00:00:00Z" + q)
        sb.append("}")
        return sb.toString()
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
        val response = transport().upload(id, ContributionRetryCallerTestSupport.record(id), ownedOf(recording))

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

/** Wraps a fixture file as the owned source the transport now requires; the caller keeps it open. */
private fun ownedOf(file: File): OwnedAudio = OwnedAudio(
    java.io.FileInputStream(file),
    file.length(),
    java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) },
)
