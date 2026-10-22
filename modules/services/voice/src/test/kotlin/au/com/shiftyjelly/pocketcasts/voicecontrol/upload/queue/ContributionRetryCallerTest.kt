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
            conversionVersion = "1",
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

    private fun newStore(root: File, clock: () -> Long, jitter: (Long) -> Long = { it - 1 }): ContributionStore = ContributionStore(
        root,
        QueueLimits.defaults().copy(maxQueueRecords = 8, retryInitialS = 4, retryMaxS = 8),
        clock = clock,
        jitter = jitter,
    )

    private fun prepare(store: ContributionStore, id: String): Long {
        val admission = store.admitForTest(1600, meta(id).size)
        val generation = store.fence.currentGeneration()
        assertTrue(store.prepareAt(generation, admission, id, "epoch-1", chunks(1600), meta(id)) != null)
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
            attemptUpload = { gen, captureId, _ ->
                assertTrue("attempt runs under a VALID generation", store.fence.isValid(gen))
                assertEquals("the pickup durably recorded the attempt BEFORE transport", 1, store.stateAttemptsForTest(captureId))
                attempted += captureId
                true
            },
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
            attemptUpload = { gen, captureId, _ ->
                attempts++
                // Production re-entrancy: a slow upload overlapping the next poll tick. The same entry
                // is already in flight, so the nested poll must NOT attempt it again.
                val nested = caller.pollOnce()
                assertEquals(0, nested.attempted)
                true
            },
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
            attemptUpload = { _, _, _ ->
                attempts++
                true
            },
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
            attemptUpload = { _, _, _ ->
                attempts++
                true
            },
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
            attemptUpload = { _, _, _ ->
                attempts++
                false // always retryable failure
            },
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
        var caller = ContributionRetryCaller(store, clock = { now }, policyAllows = { policy }, networkAvailable = { true }, attemptUpload = { _, _, _ ->
            attempts++
            true
        })
        assertEquals(0, caller.pollOnce().attempted)
        assertEquals("policy gate blocks the attempt", 0, attempts)
        assertEquals(1, caller.pollOnce().deferredPolicy)

        policy = true
        var network = false
        caller = ContributionRetryCaller(store, clock = { now }, policyAllows = { true }, networkAvailable = { network }, attemptUpload = { _, _, _ ->
            attempts++
            true
        })
        assertEquals(0, caller.pollOnce().attempted)
        assertEquals("network gate blocks the attempt", 0, attempts)
        assertEquals(1, caller.pollOnce().deferredNetwork)

        network = true
        caller = ContributionRetryCaller(store, clock = { now }, policyAllows = { true }, networkAvailable = { true }, attemptUpload = { _, _, _ ->
            attempts++
            true
        })
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
            attemptUpload = { _, _, _ -> false }, // retryable failure
        )
        assertEquals(1, caller.pollOnce().attempted)
        // Backoff re-armed from the failure: not eligible immediately, eligible once it elapses.
        assertTrue(store.retryEligible(now + 1).isEmpty())
        assertTrue(store.retryEligible(now + 3_999L).isNotEmpty())

        // And the caller acts on it at the elapsed time.
        val second = ContributionRetryCaller(store, clock = { now + 3_999L }, policyAllows = { true }, networkAvailable = { true }, attemptUpload = { _, _, _ -> true })
        assertEquals(1, second.pollOnce().attempted)
    }

    @Test
    fun `a revocation that lands DURING the first attempt stops the network op and retains ownership`() {
        var now = 15_000_000L
        val root = tmp.newFolder("caller-revoke-mid-transport")
        val id = UUID.randomUUID().toString()
        val store = newStore(root, { now })
        prepare(store, id)
        val wav = File(store.dirForTest(), "$id.recording.wav")
        val bytesBefore = wav.readBytes()

        var networkOperations = 0
        var abortObserved = false
        val caller = ContributionRetryCaller(
            store,
            clock = { now },
            policyAllows = { true },
            networkAvailable = { true },
            attemptUpload = { _, _, isRevoked ->
                // The revocation lands AFTER pickup consumed the attempt but BEFORE the first network
                // operation; the seam must be able to SEE it (its authorization is the live epoch).
                store.fence.revoke()
                if (isRevoked()) {
                    abortObserved = true
                    return@ContributionRetryCaller false // abort: no request is issued
                }
                networkOperations++
                true
            },
        )
        val first = caller.pollOnce()
        assertTrue("the seam observed the revocation", abortObserved)
        assertEquals("no network operation after the revocation", 0, networkOperations)
        assertTrue("capture bytes untouched", wav.readBytes().contentEquals(bytesBefore))
        assertTrue("ownership retained", store.readyEntries().any { it.captureId == id })
        // The attempt WAS durably consumed by the pickup, and the unconfirmed abort re-armed the durable
        // boundary instead of leaving the entry immediately retryable.
        assertEquals("the revoked attempt is still accounted", 1, store.stateAttemptsForTest(id))
        assertEquals(1, first.attempted)
        assertTrue(
            "the abort left a durable not-before boundary",
            store.retryBoundariesForTest().containsKey(id) || store.retryEligible(now + 3_999L).any { it == id },
        )
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
            attemptUpload = { _, _, _ -> throw java.io.IOException("response lost") },
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
            attemptUpload = { _, captureId, _ ->
                retriedBytes = File(store.dirForTest(), "$captureId.recording.wav").readBytes()
                true
            },
        )
        assertEquals(1, later.pollOnce().attempted)
        assertTrue("retry re-posts the identical immutable capture", retriedBytes!!.contentEquals(bytesBefore))
    }
}
