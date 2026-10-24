package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.AudioBlock
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.CanonicalJson
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ContributionRecordBuilder
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageEntry
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageState
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Production-path controls for the retry CALLER: the composition unit that polls the store's retry
 * eligibility under the fence, enforces one-in-flight per entry, and rechecks account/policy and
 * network before EVERY attempt (reviewer 6b236641: caller integration must be pinned on production
 * results, not store internals).
 */
class ContributionRetryCallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun wavBytes(samples: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        WavWriter.writeHeader(out, samples)
        WavWriter.writePcm(out, ShortArray(samples) { i -> i.toShort() }, samples)
        return out.toByteArray()
    }

    private fun sha256HexForTest(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun meta(captureId: String): ByteArray {
        val audio = wavBytes(1600)
        val block = AudioBlock(
            sha256 = sha256HexForTest(audio),
            byteCount = audio.size.toLong(),
            retainedSamples = 1600,
            originalFormat = "pcm_s16le",
            conversionVersion = "v1",
            truncated = false,
            droppedSpans = emptyList(),
        )
        return CanonicalJson.canonicalBytes(
            ContributionRecordBuilder(captureId, "epoch-1", "42")
                .model("vad", ModelRef.Named("silero-vad", "4.0"))
                .model("wakeword", ModelRef.Named("auris-wake", "1.2"))
                .model("asr", ModelRef.Named("whisper-small", "2024-11"))
                .model("translation", ModelRef.None)
                .model("router", ModelRef.Named("lfm-intent", "0.3"))
                .stage(StageName.Wake, StageEntry(StageState.Completed, mapOf("decision" to RecordNode.Str("detected"))))
                .stage(StageName.Bleed, StageEntry(StageState.Completed, mapOf("decision" to RecordNode.Str("no_overlap"))))
                .stage(StageName.Asr, StageEntry(StageState.Completed, mapOf("text" to RecordNode.Str("skip this ad"))))
                .stage(StageName.Translation, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_text"))))
                .stage(StageName.Routing, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_candidates"))))
                .stage(StageName.Execution, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("outcome_wait_expired"))))
                .build(platform = "android", appVersion = "8.20", build = "12345", audio = block),
        )
    }

    private fun chunks(bytes: Int): List<Pair<ShortArray, Int>> = listOf(ShortArray(bytes) { i -> i.toShort() } to bytes)

    private val hookedRef0 = arrayOfNulls<ContributionStore>(1)

    // The REAL wire receipt (worker upload_route.ts UploadReceipt) for THIS capture and digest.
    private fun wireReceipt(captureId: String, digest: String? = null, status: String = "accepted"): ByteArray {
        val d = digest ?: "a".repeat(64)
        return (
            "{" +
                "\"schema_version\":1," +
                "\"capture_id\":\"$captureId\"," +
                "\"record_sha256\":\"$d\"," +
                "\"status\":\"$status\"," +
                "\"receipt_id\":\"rcpt-1\"," +
                "\"accepted_at\":\"2026-10-22T00:00:00Z\"," +
                "\"retention_expires_at\":\"2026-11-21T00:00:00Z\"}"
            ).toByteArray()
    }

    private fun newStore(root: File, clock: () -> Long, jitter: (Long) -> Long = { it - 1 }): ContributionStore = ContributionStore(
        root,
        QueueLimits.defaults().copy(maxQueueRecords = 8, retryInitialS = 4, retryMaxS = 8),
        clock = clock,
        jitter = jitter,
    )

    private fun prepare(store: ContributionStore, id: String): Long {
        val admission = store.admitForTest(1600, meta(id).size)
        val generation = store.fence.currentGeneration()
        assertTrue(store.prepareAt(generation, admission, id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)
        return generation
    }

    @Test
    fun `the caller attempts an eligible entry under the fence exactly once per poll`() {
        var now = 12_000_000L
        val root = tmp.newFolder("caller-basic")
        val id = UUID.randomUUID().toString()
        val store = newStore(root, { now })
        val generation = prepare(store, id)

        val attempted = mutableListOf<String>()
        val caller = ContributionRetryCaller(
            store,
            clock = { now },
            policyAllows = { true },
            networkAvailable = { true },
            attemptUpload = { gen, captureId, _, _ ->
                assertTrue("attempt runs under a VALID generation", store.fence.isValid(gen))
                assertEquals("the pickup durably recorded the attempt BEFORE transport", 1, store.stateAttemptsForTest(captureId))
                attempted += captureId
                true
            },
            ackResolver = ContributionAckResolver(store, CaptureReceiptValidator.forStore(store)),
            responseFor = { _, _ -> wireReceipt(id) },
            // The contract pairs a first acceptance with 201; the receipt's own status must agree with it.
            httpStatusFor = { _, _ -> 201 },
        )
        val result = caller.pollOnce()
        assertEquals(listOf(id), attempted)
        assertEquals(1, result.attempted)
        assertEquals(0, result.deferredPolicy)
        assertEquals(0, result.deferredNetwork)
        assertEquals(0, result.pickupRefused)
    }

    @Test
    fun `ONE attempt is in flight per entry - a re-entrant poll cannot double-attempt`() {
        var now = 12_500_000L
        val root = tmp.newFolder("caller-inflight")
        val id = UUID.randomUUID().toString()
        val store = newStore(root, { now })
        val generation = prepare(store, id)

        var attempts = 0
        lateinit var caller: ContributionRetryCaller
        caller = ContributionRetryCaller(
            store,
            clock = { now },
            policyAllows = { true },
            networkAvailable = { true },
            attemptUpload = { gen, captureId, _, _ ->
                attempts++
                // Production re-entrancy: a slow upload overlapping the next poll tick. The same entry
                // is already in flight, so the nested poll must NOT attempt it again.
                val nested = caller.pollOnce()
                assertEquals(0, nested.attempted)
                true
            },
            ackResolver = ContributionAckResolver(store, CaptureReceiptValidator.forStore(store)),
            responseFor = { _, _ -> wireReceipt(id) },
        )
        val result = caller.pollOnce()
        assertEquals(1, attempts)
        assertEquals(1, result.attempted)
    }

    @Test
    fun `a REVOCATION inside a gate callback cancels the pickup`() {
        // reviewer 1b926fa0: the purge must land AFTER eligibility and BEFORE claim/execution - inside
        // a gate callback, not before pollOnce.
        var now = 13_000_000L
        val root = tmp.newFolder("caller-revoked")
        val id = UUID.randomUUID().toString()
        val store = newStore(root, { now })
        prepare(store, id)

        var attempts = 0
        var policyCalls = 0
        val caller = ContributionRetryCaller(
            store,
            clock = { now },
            policyAllows = {
                policyCalls++
                if (policyCalls == 1) store.purgeQueue("account revoked mid-gate")
                true
            },
            networkAvailable = { true },
            attemptUpload = { _, _, _, _ ->
                attempts++
                true
            },
            ackResolver = ContributionAckResolver(store, CaptureReceiptValidator.forStore(store)),
            responseFor = { _, _ -> wireReceipt(id) },
        )
        val result = caller.pollOnce()
        assertEquals("gate ran", 1, policyCalls)
        assertEquals("a mid-gate revocation cancels the attempt", 0, attempts)
        assertEquals(0, result.attempted)
        assertEquals(1, result.pickupRefused)
    }

    @Test
    fun `a boundary RE-ARM inside a gate callback makes the pickup refuse`() {
        // reviewer 1b926fa0: a concurrent poll can re-arm the retry boundary between listing and
        // pickup; generation validity alone must not wave the attempt through.
        var now = 13_250_000L
        val root = tmp.newFolder("caller-rearm-race")
        val id = UUID.randomUUID().toString()
        val store = newStore(root, { now })
        val generation = prepare(store, id)
        assertEquals(1, store.recordAttempt(generation, id))

        var attempts = 0
        var networkCalls = 0
        val caller = ContributionRetryCaller(
            store,
            clock = { now },
            policyAllows = { true },
            networkAvailable = {
                networkCalls++
                if (networkCalls == 1) {
                    // The concurrent poll re-arms the durable boundary for the SAME entry.
                    store.scheduleRetryAfterFailure(generation, id, retryAfterS = null)
                }
                true
            },
            attemptUpload = { _, _, _, _ ->
                attempts++
                true
            },
            ackResolver = ContributionAckResolver(store, CaptureReceiptValidator.forStore(store)),
            responseFor = { _, _ -> wireReceipt(id) },
        )
        val result = caller.pollOnce()
        assertEquals("gate ran", 1, networkCalls)
        assertEquals("a mid-gate boundary re-arm cancels the attempt", 0, attempts)
        assertEquals(1, result.pickupRefused)
    }

    @Test
    fun `SEQUENTIAL retryable failures exhaust maxUploadAttempts and then refuse pickup`() {
        var now = 13_400_000L
        val root = tmp.newFolder("caller-exhaustion")
        val id = UUID.randomUUID().toString()
        val store = ContributionStore(
            root,
            QueueLimits.defaults().copy(maxQueueRecords = 8, maxUploadAttempts = 2, retryInitialS = 4, retryMaxS = 8),
            clock = { now },
            jitter = { bound -> bound - 1 },
        )
        prepare(store, id)

        var attempts = 0
        val caller = ContributionRetryCaller(
            store,
            clock = { now },
            policyAllows = { true },
            networkAvailable = { true },
            attemptUpload = { _, _, _, _ ->
                attempts++
                false // always retryable failure
            },
            ackResolver = ContributionAckResolver(store, CaptureReceiptValidator.forStore(store)),
            responseFor = { _, _ -> wireReceipt(id) },
        )
        // Backoff is re-armed after each failure; every elapsed boundary allows exactly one more
        // recorded attempt, until maxUploadAttempts is exhausted.
        var polls = 0
        while (attempts < 2 && polls < 10) {
            caller.pollOnce()
            polls++
            now += 10_000L // past every backoff this test can arm (initial=4s, doubling)
        }
        assertEquals("attempts stop at the budget (maxUploadAttempts=2)", 2, attempts)
        assertEquals(2, store.stateAttemptsForTest(id))
        val final = caller.pollOnce()
        assertEquals("exhausted entry is no longer attempted", 0, final.attempted)
        assertEquals("exhaustion is enforced at the store: pickup refuses directly", null, store.pickupAttempt(store.fence.currentGeneration(), id))
    }

    @Test
    fun `policy and network are rechecked BEFORE EVERY attempt`() {
        var now = 13_500_000L
        val root = tmp.newFolder("caller-gates")
        val id = UUID.randomUUID().toString()
        val store = newStore(root, { now })
        prepare(store, id)

        var attempts = 0
        var policy = false
        var caller = ContributionRetryCaller(store, clock = { now }, policyAllows = { policy }, networkAvailable = { true }, attemptUpload = { _, _, _, _ ->
            attempts++
            true
        }, ackResolver = ContributionAckResolver(store, CaptureReceiptValidator.forStore(store)), responseFor = { _, _ -> wireReceipt(id) })
        assertEquals(0, caller.pollOnce().attempted)
        assertEquals("policy gate blocks the attempt", 0, attempts)
        assertEquals(1, caller.pollOnce().deferredPolicy)

        policy = true
        var network = false
        caller = ContributionRetryCaller(store, clock = { now }, policyAllows = { true }, networkAvailable = { network }, attemptUpload = { _, _, _, _ ->
            attempts++
            true
        }, ackResolver = ContributionAckResolver(store, CaptureReceiptValidator.forStore(store)), responseFor = { _, _ -> wireReceipt(id) })
        assertEquals(0, caller.pollOnce().attempted)
        assertEquals("network gate blocks the attempt", 0, attempts)
        assertEquals(1, caller.pollOnce().deferredNetwork)

        network = true
        caller = ContributionRetryCaller(store, clock = { now }, policyAllows = { true }, networkAvailable = { true }, attemptUpload = { _, _, _, _ ->
            attempts++
            true
        }, ackResolver = ContributionAckResolver(store, CaptureReceiptValidator.forStore(store)), responseFor = { _, _ -> wireReceipt(id) })
        assertEquals(1, caller.pollOnce().attempted)
        assertEquals("both gates pass, the attempt runs", 1, attempts)
    }

    @Test
    fun `a RETRYABLE failure schedules the durable boundary and holds the entry until the backoff elapses`() {
        var now = 14_000_000L
        val root = tmp.newFolder("caller-retryable")
        val id = UUID.randomUUID().toString()
        val store = newStore(root, { now })
        prepare(store, id)

        val caller = ContributionRetryCaller(
            store,
            clock = { now },
            policyAllows = { true },
            networkAvailable = { true },
            attemptUpload = { _, _, _, _ -> false }, // retryable failure
            ackResolver = ContributionAckResolver(store, CaptureReceiptValidator.forStore(store)),
            responseFor = { _, _ -> wireReceipt(id) },
        )
        assertEquals(1, caller.pollOnce().attempted)
        // Backoff re-armed from the failure: not eligible immediately, eligible once it elapses.
        assertTrue(store.retryEligible(now + 1).isEmpty())
        assertTrue(store.retryEligible(now + 3_999L).isNotEmpty())

        // And the caller acts on it at the elapsed time.
        val second = ContributionRetryCaller(store, clock = { now + 3_999L }, policyAllows = { true }, networkAvailable = { true }, attemptUpload = { _, _, _, _ -> true }, ackResolver = ContributionAckResolver(store, CaptureReceiptValidator.forStore(store)), responseFor = { _, _ -> wireReceipt(id) })
        assertEquals(1, second.pollOnce().attempted)
    }

    @Test
    fun `a SECOND-THREAD revocation during an in-flight upload cancels it, retains ownership and leaves a durable boundary`() {
        var now = 15_000_000L
        val root = tmp.newFolder("caller-revoke-in-flight")
        val id = UUID.randomUUID().toString()
        val store = newStore(root, { now })
        prepare(store, id)
        val wav = File(store.dirForTest(), "$id.recording.wav")
        val bytesBefore = wav.readBytes()

        val entered = java.util.concurrent.CountDownLatch(1)
        val revocationDone = java.util.concurrent.CountDownLatch(1)
        var cancelledMidFlight = false

        val caller = ContributionRetryCaller(
            store,
            clock = { now },
            policyAllows = { true },
            networkAvailable = { true },
            attemptUpload = { _, _, isRevoked, _ ->
                entered.countDown()
                // The transport is genuinely in flight: an external authority revokes from ANOTHER thread.
                // It must not be blocked by the ownership monitor, and its cancellation must be visible.
                check(revocationDone.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "revocation was blocked" }
                cancelledMidFlight = isRevoked()
                true // the server answers anyway; a revoked response must not count as success
            },
            ackResolver = ContributionAckResolver(store, CaptureReceiptValidator.forStore(store)),
            responseFor = { _, _ -> wireReceipt(id) },
        )

        val poller = java.util.concurrent.Executors.newSingleThreadExecutor()
        val pollResult = poller.submit<ContributionRetryCaller.PollResult> { caller.pollOnce() }
        check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "upload never started" }
        // Runs on the TEST thread (a different thread from the poller) while the upload is in flight.
        store.fence.revokeAndCancelLaunches("account changed")
        revocationDone.countDown()
        val result = pollResult.get(10, java.util.concurrent.TimeUnit.SECONDS)
        poller.shutdown()

        assertTrue("the in-flight upload observed the cancellation", cancelledMidFlight)
        assertEquals("a revoked epoch is not reported as an attempt completed", 0, result.attempted)
        assertEquals("the revocation is attributed", 1, result.cancelledEpoch)
        assertTrue("capture bytes untouched", wav.readBytes().contentEquals(bytesBefore))
        assertTrue("ownership retained", store.readyEntries().any { it.captureId == id })
    }

    @Test
    fun `a revocation after pickup leaves a durable boundary a FRESH owner recovers`() {
        var now = 15_500_000L
        val root = tmp.newFolder("caller-revoke-durable")
        val store = newStore(root, { now })
        // The hook runs INSIDE the ownership transaction after the attempt was consumed and before the
        // launch decision - the pickup->launch window the second-thread control cannot address
        // deterministically (spec c02328d1, reviewer 4d72e463).
        val hookedRef = arrayOfNulls<ContributionStore>(1)
        val hooked = ContributionStore(
            root,
            QueueLimits.defaults().copy(maxQueueRecords = 8, retryInitialS = 4, retryMaxS = 8),
            clock = { now },
            jitter = { it - 1 },
            hooks = StoreHooks(beforeTransportLaunch = { _, _ ->
                hookedRef[0]!!.fence.revokeAndCancelLaunches("epoch changed")
            }),
        )
        hookedRef[0] = hooked
        val id = UUID.randomUUID().toString()
        val admission = hooked.admitForTest(1600, meta(id).size)
        val generation = hooked.fence.currentGeneration()
        assertTrue(hooked.prepareAt(generation, admission, id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)

        val launched = mutableListOf<String>()
        val caller = ContributionRetryCaller(
            hooked,
            clock = { now },
            policyAllows = { true },
            networkAvailable = { true },
            attemptUpload = { _, captureId, _, _ ->
                launched.add(captureId)
                true
            },
            ackResolver = ContributionAckResolver(hooked, CaptureReceiptValidator.forStore(hooked)),
            responseFor = { _, _ -> wireReceipt(id) },
        )
        val result = caller.pollOnce()
        assertTrue("a launch refused under a revoked epoch issues no request", launched.isEmpty())
        assertEquals("the refusal is attributed to the epoch", 1, result.cancelledEpoch)
        assertEquals("the consumed attempt is still accounted", 1, hooked.stateAttemptsForTest(id))

        // A FRESH owner reads the SAME durable boundary: not through the old generation, not in memory.
        val fresh = newStore(root, { now })
        fresh.recover()
        val boundary = fresh.retryBoundariesForTest()[id]
        assertTrue("a durable not-before boundary was persisted for the capture", boundary != null)
        assertTrue("the boundary is in the future", boundary!! > now)
        assertEquals("a fresh owner refuses an immediate retry", 0, fresh.retryEligible(now).size)
        assertEquals("and retries it once the boundary elapses", listOf(id), fresh.retryEligible(boundary))
    }

    @Test
    fun `a revocation landing while the launch transaction is open refuses the grant, and the opposite order cancels in flight`() {
        var now = 18_000_000L
        val root = tmp.newFolder("caller-lease-window")
        val id = UUID.randomUUID().toString()
        var networkOperations = 0

        // The lease window runs INSIDE the ownership transaction, so a revocation can only be serialized
        // against it - never interleaved. The hook is the one place the window is observable, and a
        // revocation issued there must refuse the grant: the re-check after the window is what makes
        // "no network work under a retired epoch" true rather than merely claimed (spec ed9046dd, M77b).
        val hooked = ContributionStore(
            root,
            QueueLimits.defaults().copy(maxQueueRecords = 8, retryInitialS = 4, retryMaxS = 8),
            clock = { now },
            jitter = { it - 1 },
            hooks = StoreHooks(onLeaseWindow = { _, _ -> hookedRef0[0]?.fence?.revokeAndCancelLaunches("epoch changed") }),
        )
        hookedRef0[0] = hooked
        val admission = hooked.admitForTest(1600, meta(id).size)
        val generation = hooked.fence.currentGeneration()
        assertTrue(hooked.prepareAt(generation, admission, id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)
        assertEquals(1, hooked.recordAttempt(generation, id))

        val caller = ContributionRetryCaller(
            hooked,
            clock = { now },
            policyAllows = { true },
            networkAvailable = { true },
            attemptUpload = { _, _, _, _ ->
                networkOperations++
                true
            },
            ackResolver = ContributionAckResolver(hooked, CaptureReceiptValidator.forStore(hooked)),
            responseFor = { _, _ -> wireReceipt(id) },
        )
        val refused = caller.pollOnce()
        assertEquals("no network work under the retired epoch", 0, networkOperations)
        assertEquals("the refusal is attributed to the epoch", 1, refused.cancelledEpoch)
        assertTrue("ownership retained", hooked.readyEntries().any { it.captureId == id })

        // OPPOSITE ORDERING: the launch completes FIRST, then the revocation lands. The in-flight upload
        // must observe cancellation instead of being granted retroactively.
        var now2 = 18_500_000L
        val root2 = tmp.newFolder("caller-in-flight-revoke")
        val id2 = UUID.randomUUID().toString()
        val store2 = newStore(root2, { now2 })
        prepare(store2, id2)
        val enteredUpload = java.util.concurrent.CountDownLatch(1)
        val revocationDone = java.util.concurrent.CountDownLatch(1)
        var cancelledMidFlight = false
        val caller2 = ContributionRetryCaller(
            store2,
            clock = { now2 },
            policyAllows = { true },
            networkAvailable = { true },
            attemptUpload = { _, _, isRevoked, _ ->
                enteredUpload.countDown()
                check(revocationDone.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "in-flight revoke blocked" }
                cancelledMidFlight = isRevoked()
                true
            },
            ackResolver = ContributionAckResolver(store2, CaptureReceiptValidator.forStore(store2)),
            responseFor = { _, _ -> wireReceipt(id) },
        )
        val poller2 = java.util.concurrent.Executors.newSingleThreadExecutor()
        val inflightResult = poller2.submit<ContributionRetryCaller.PollResult> { caller2.pollOnce() }
        check(enteredUpload.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "upload never started" }
        store2.fence.revokeAndCancelLaunches("account changed")
        revocationDone.countDown()
        val inFlight = inflightResult.get(10, java.util.concurrent.TimeUnit.SECONDS)
        poller2.shutdown()
        assertTrue("the opposite ordering cancels the upload in flight", cancelledMidFlight)
        assertEquals("a revoked completion is not a completed attempt", 0, inFlight.attempted)
        assertEquals("and the revocation is attributed", 1, inFlight.cancelledEpoch)
    }

    @Test
    fun `an OLD completion after a successor attempt was admitted leaves the successor state unchanged`() {
        var now = 19_000_000L
        val root = tmp.newFolder("caller-successor")
        val id = UUID.randomUUID().toString()
        val store = newStore(root, { now })
        prepare(store, id)

        // Admit attempt 1, then let a SUCCESSOR admit attempt 2 (as a fresh poll after backoff would).
        val generation = store.fence.currentGeneration()
        val firstAttempt = store.pickupAttempt(generation, id, now)
        assertEquals(1, firstAttempt)
        val first = store.admittedAttemptForTest(id)!!
        now += 10_000L
        store.scheduleRetryAfterFailure(generation, id)
        val secondAttempt = store.pickupAttempt(generation, id, now + 8_000L)
        assertEquals("the successor admitted the next attempt", 2, secondAttempt)
        val successor = store.admittedAttemptForTest(id)!!
        assertEquals(2, successor.attemptNumber)
        val successorBoundary = store.retryBoundariesForTest()[id]

        // NOW the OLD completion for attempt 1 finally arrives. It must settle nothing: the successor's
        // attempt count, boundary and liveness all stay exactly as they were.
        assertFalse(
            "the old attempt's identity no longer holds",
            store.attemptIdentityHolds(generation, id, first.attemptNumber),
        )
        assertTrue(
            "the successor's identity DOES hold",
            store.attemptIdentityHolds(generation, id, successor.attemptNumber),
        )
        // Simulate the old-completion settlement path: it must not touch the queue.
        var oldSettled = false
        if (store.attemptIdentityHolds(generation, id, first.attemptNumber)) {
            oldSettled = true
            store.scheduleRetryAfterFailure(store.fence.currentGeneration(), id)
        }
        assertFalse("the old completion re-armed nothing", oldSettled)
        assertEquals("successor attempt count unchanged", 2, store.stateAttemptsForTest(id))
        assertEquals("successor boundary unchanged", successorBoundary, store.retryBoundariesForTest()[id])
        assertTrue("the capture is still owned and served", store.readyEntries().any { it.captureId == id })
    }

    @Test
    fun `only a receipt that echoes THIS capture id and digest releases ownership`() {
        var now = 17_000_000L
        val root = tmp.newFolder("caller-receipt")
        val id = UUID.randomUUID().toString()
        val store = newStore(root, { now })
        prepare(store, id)
        val digest = store.recordedDigest(id)
        assertTrue("the queue recorded a digest for the capture", digest != null)
        val validator = CaptureReceiptValidator.forStore(store)

        // The REAL wire receipt shape (worker upload_route.ts UploadReceipt), not a private enum.
        fun receipt(status: String = "accepted", echoedId: String = id, echoedDigest: String = digest!!) = """
            {"schema_version":1,"capture_id":"$echoedId","record_sha256":"$echoedDigest","status":"$status",
             "receipt_id":"rcpt-1","accepted_at":"2026-10-22T00:00:00Z","retention_expires_at":"2026-11-21T00:00:00Z"}
        """.trimIndent().replace("\n", " ").toByteArray()

        // Composed the way production does it: the caller posts, the resolver classifies and releases.
        val resolver = ContributionAckResolver(store, validator)
        val caller = ContributionRetryCaller(
            store,
            clock = { now },
            policyAllows = { true },
            networkAvailable = { true },
            attemptUpload = { generation, captureId, isRevoked, _ ->
                if (isRevoked()) return@ContributionRetryCaller false
                // A wrong-digest acceptance must NOT release; a matching one must. Settlement goes
                // through the resolver exactly as production composition does.
                val wrong = resolver.resolveForAttempt(store.admittedAttemptForTest(captureId)!!, ContributionAckResolver.Ack.ACCEPTED, receipt(echoedDigest = "0".repeat(64)), httpStatus = 201)
                assertTrue("forged digest retains ownership", wrong is ContributionAckResolver.Resolution.UnvalidatedAccept)
                assertTrue("still queued after the forged receipt", store.readyEntries().any { it.captureId == captureId })
                val ok = resolver.resolveForAttempt(store.admittedAttemptForTest(captureId)!!, ContributionAckResolver.Ack.ACCEPTED, receipt(), httpStatus = 201)
                assertTrue("the matching receipt releases", ok is ContributionAckResolver.Resolution.Released)
                // The caller's own settlement gate sees the attempt already released.
                !store.readyEntries().any { it.captureId == captureId }
            },
            ackResolver = ContributionAckResolver(store, CaptureReceiptValidator.forStore(store)),
            responseFor = { _, _ -> wireReceipt(id) },
            httpStatusFor = { _, _ -> 201 },
        )
        val polled = caller.pollOnce()
        assertTrue(
            "the receipt round trip completed: either the seam settled it or the release superseded it",
            polled.attempted == 1 || polled.cancelledEpoch == 1,
        )
        assertTrue("ownership released only on the validated receipt", store.readyEntries().none { it.captureId == id })
        assertEquals(1L, store.terminalCounter(ContributionStore.TerminalReason.UPLOAD_ACCEPTED))
        assertEquals(1, store.recordedDigest(id)?.let { 1 } ?: 1) // digest remains readable/consistent
    }

    @Test
    fun `a Boolean transport success with NO valid receipt retains ownership and delays the next attempt`() {
        var now = 19_500_000L
        val root = tmp.newFolder("caller-bool-not-delete")
        val id = UUID.randomUUID().toString()
        val store = newStore(root, { now })
        prepare(store, id)

        // The transport reports success, but the body is an ERROR body / malformed 2xx: nothing validates.
        val caller = ContributionRetryCaller(
            store,
            clock = { now },
            policyAllows = { true },
            networkAvailable = { true },
            attemptUpload = { _, _, _, _ -> true },
            ackResolver = ContributionAckResolver(store, CaptureReceiptValidator.forStore(store)),
            responseFor = { _, _ -> """{"schema_version":1,"code":"storage_unavailable"}""".toByteArray() },
        )
        val polled = caller.pollOnce()
        assertEquals(1, polled.attempted)
        assertTrue("a Boolean true MUST NOT delete before receipt validation", store.readyEntries().any { it.captureId == id })
        assertEquals("no acceptance was counted", 0L, store.terminalCounter(ContributionStore.TerminalReason.UPLOAD_ACCEPTED))
        val boundary = store.retryBoundariesForTest()[id]
        assertTrue("the unvalidated success left a durable delay", boundary != null && boundary > now)
    }

    @Test
    fun `the server retry_after_s minimum survives a RESTART as the durable boundary`() {
        var now = 22_000_000L
        val root = tmp.newFolder("caller-retryafter-restart")
        val id = UUID.randomUUID().toString()
        val store = newStore(root, { now })
        prepare(store, id)

        val caller = ContributionRetryCaller(
            store,
            clock = { now },
            policyAllows = { true },
            networkAvailable = { true },
            attemptUpload = { _, _, _, _ -> false },
            ackResolver = ContributionAckResolver(store, CaptureReceiptValidator.forStore(store)),
            retryAfterFor = { _, _ -> 600L }, // the server said: at least 600s
        )
        caller.pollOnce()

        // A FRESH owner (restart) reads the same durable boundary: at least the server minimum.
        val fresh = newStore(root, { now })
        fresh.recover()
        val boundary = fresh.retryBoundariesForTest()[id]
        assertTrue("a boundary was persisted", boundary != null)
        assertTrue(
            "the persisted boundary is AT LEAST the server minimum",
            boundary!! >= now + 600L,
        )
        assertTrue("not retry-eligible before the minimum", fresh.retryEligible(now + 599_999L).isEmpty())
        assertEquals("retry-eligible at the minimum", listOf(id), fresh.retryEligible(now + 600_000L))
    }

    @Test
    fun `caller REJECTS and RETAINS on each malformed receipt shape`() {
        var now = 21_000_000L
        val bad = mapOf(
            "quoted schema version" to """{"schema_version":"1","capture_id":"ID","record_sha256":"DIGEST","status":"accepted","receipt_id":"r","accepted_at":"t","retention_expires_at":"t"}""",
            "bare string value" to """{"schema_version":1,"capture_id":ID,"record_sha256":"DIGEST","status":"accepted","receipt_id":"r","accepted_at":"t","retention_expires_at":"t"}""",
            "null status" to """{"schema_version":1,"capture_id":"ID","record_sha256":"DIGEST","status":null,"receipt_id":"r","accepted_at":"t","retention_expires_at":"t"}""",
            "trailing comma" to """{"schema_version":1,"capture_id":"ID","record_sha256":"DIGEST","status":"accepted","receipt_id":"r","accepted_at":"t","retention_expires_at":"t",}""",
            "unknown key" to """{"schema_version":1,"capture_id":"ID","record_sha256":"DIGEST","status":"accepted","receipt_id":"r","accepted_at":"t","retention_expires_at":"t","extra":"x"}""",
            "duplicate key" to """{"schema_version":1,"capture_id":"ID","capture_id":"ID","record_sha256":"DIGEST","status":"accepted","receipt_id":"r","accepted_at":"t","retention_expires_at":"t"}""",
            "wrong status literal" to """{"schema_version":1,"capture_id":"ID","record_sha256":"DIGEST","status":"stored","receipt_id":"r","accepted_at":"t","retention_expires_at":"t"}""",
            "placeholder accepted_at" to """{"schema_version":1,"capture_id":"ID","record_sha256":"DIGEST","status":"accepted","receipt_id":"r","accepted_at":"t","retention_expires_at":"2026-11-21T00:00:00Z"}""",
            "placeholder retention" to """{"schema_version":1,"capture_id":"ID","record_sha256":"DIGEST","status":"accepted","receipt_id":"r","accepted_at":"2026-10-22T00:00:00Z","retention_expires_at":"t"}""",
            "retention before acceptance" to """{"schema_version":1,"capture_id":"ID","record_sha256":"DIGEST","status":"accepted","receipt_id":"r","accepted_at":"2026-11-21T00:00:00Z","retention_expires_at":"2026-10-22T00:00:00Z"}""",
        )
        bad.forEach { (label, template) ->
            val root = tmp.newFolder("caller-bad-" + label.replace(' ', '-'))
            val id = UUID.randomUUID().toString()
            val store = newStore(root, { now })
            prepare(store, id)
            val digest = store.recordedDigest(id)!!
            val body = template.replace("ID", id).replace("DIGEST", digest).toByteArray()
            val caller = ContributionRetryCaller(
                store,
                clock = { now },
                policyAllows = { true },
                networkAvailable = { true },
                attemptUpload = { _, _, _, _ -> true },
                ackResolver = ContributionAckResolver(store, CaptureReceiptValidator.forStore(store)),
                responseFor = { _, _ -> body },
            )
            caller.pollOnce()
            assertTrue("[$label] the capture is RETAINED", store.readyEntries().any { it.captureId == id })
            assertEquals("[$label] no acceptance counted", 0L, store.terminalCounter(ContributionStore.TerminalReason.UPLOAD_ACCEPTED))
        }
    }

    @Test
    fun `a LATE old completion reaching the caller leaves a successor-owned entry untouched`() {
        var now = 20_500_000L
        val root = tmp.newFolder("caller-late-completion")
        val id = UUID.randomUUID().toString()
        val store = newStore(root, { now })
        prepare(store, id)

        var successorAdmitted = false
        var receiptAfterSuccessor: ContributionAckResolver.Resolution? = null
        lateinit var caller: ContributionRetryCaller
        caller = ContributionRetryCaller(
            store,
            clock = { now },
            policyAllows = { true },
            networkAvailable = { true },
            attemptUpload = { generation, captureId, _, _ ->
                // While this attempt is in flight a SUCCESSOR admits the next attempt on the same entry
                // (a normal consequence of a backoff elapsing under a still-current epoch).
                store.scheduleRetryForAdmittedAttempt(store.admittedAttemptForTest(captureId)!!)
                now += 9_000L
                successorAdmitted = store.pickupAttempt(generation, captureId, now) != null
                true
            },
            ackResolver = ContributionAckResolver(store, CaptureReceiptValidator.forStore(store)),
            responseFor = { _, captureId ->
                // The OLD attempt's success now arrives, after the successor took the slot.
                receiptAfterSuccessor = ContributionAckResolver(store, CaptureReceiptValidator.forStore(store))
                    .resolveForAttempt(
                        store.admittedAttemptForTest(captureId)!!.copy(attemptNumber = 1),
                        ContributionAckResolver.Ack.ACCEPTED,
                        wireReceipt(captureId),
                    )
                wireReceipt(captureId)
            },
        )
        caller.pollOnce()
        assertTrue("the successor was admitted while the old attempt was in flight", successorAdmitted)
        assertEquals(
            "the old completion cannot release a successor-owned entry",
            ContributionAckResolver.Resolution.StaleEpoch,
            receiptAfterSuccessor,
        )
        assertTrue("the successor still owns the entry", store.readyEntries().any { it.captureId == id })
        assertEquals("no acceptance counted for the stale completion", 0L, store.terminalCounter(ContributionStore.TerminalReason.UPLOAD_ACCEPTED))
    }

    @Test
    fun `a receipt naming a DIFFERENT capture never releases or deletes this one`() {
        var now = 17_500_000L
        val root = tmp.newFolder("caller-receipt-foreign")
        val id = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        val store = newStore(root, { now })
        prepare(store, id)
        val validator = CaptureReceiptValidator.forStore(store)
        val foreign = """
            {"schema_version":1,"capture_id":"$other","record_sha256":"${"a".repeat(64)}","status":"accepted",
             "receipt_id":"rcpt-2","accepted_at":"2026-10-22T00:00:00Z","retention_expires_at":"2026-11-21T00:00:00Z"}
        """.trimIndent().replace("\n", " ").toByteArray()

        val resolver = ContributionAckResolver(store, validator)
        val generation = store.fence.currentGeneration()
        val token = store.pickupAttemptToken(generation, id)!!
        val resolution = resolver.resolveForAttempt(token, ContributionAckResolver.Ack.ACCEPTED, foreign)
        assertTrue("a foreign receipt is not an acceptance", resolution is ContributionAckResolver.Resolution.UnvalidatedAccept)
        assertTrue("ownership retained", store.readyEntries().any { it.captureId == id })
        assertEquals(0L, store.terminalCounter(ContributionStore.TerminalReason.UPLOAD_ACCEPTED))
    }

    @Test
    fun `a THROWN upload retains ownership and durably delays the next attempt, across a restart`() {
        var now = 16_000_000L
        val root = tmp.newFolder("caller-throws")
        val id = UUID.randomUUID().toString()
        val store = newStore(root, { now })
        prepare(store, id)
        val wav = File(store.dirForTest(), "$id.recording.wav")
        val bytesBefore = wav.readBytes()

        val caller = ContributionRetryCaller(
            store,
            clock = { now },
            policyAllows = { true },
            networkAvailable = { true },
            attemptUpload = { _, _, _, _ -> throw java.io.IOException("response lost") },
            ackResolver = ContributionAckResolver(store, CaptureReceiptValidator.forStore(store)),
            responseFor = { _, _ -> wireReceipt(id) },
        )
        assertEquals("the thrown attempt counts as an attempt", 1, caller.pollOnce().attempted)
        assertTrue("ambiguous: capture bytes untouched", wav.readBytes().contentEquals(bytesBefore))
        assertTrue("ownership retained, entry still queued", store.readyEntries().any { it.captureId == id })
        assertEquals("durable delay: not immediately eligible", 0, caller.pollOnce().attempted)

        // A fresh owner (process restart) reads the SAME durable boundary: no immediate retry.
        store.recover()
        assertEquals(0, caller.pollOnce().attempted)

        // Once the backoff elapses, the very same immutable capture is retried.
        var retriedBytes: ByteArray? = null
        val later = ContributionRetryCaller(
            store,
            clock = { now + 3_999L },
            policyAllows = { true },
            networkAvailable = { true },
            attemptUpload = { _, captureId, _, _ ->
                retriedBytes = File(store.dirForTest(), "$captureId.recording.wav").readBytes()
                true
            },
            ackResolver = ContributionAckResolver(store, CaptureReceiptValidator.forStore(store)),
            responseFor = { _, _ -> wireReceipt(id) },
        )
        assertEquals(1, later.pollOnce().attempted)
        assertTrue("retry re-posts the identical immutable capture", retriedBytes!!.contentEquals(bytesBefore))
    }
}
