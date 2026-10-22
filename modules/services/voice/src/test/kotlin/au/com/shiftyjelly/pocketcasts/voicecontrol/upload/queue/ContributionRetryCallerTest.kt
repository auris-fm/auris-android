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
        assertEquals(1, store.recordAttempt(generation, id))
        assertTrue(store.scheduleRetryAfterFailure(generation, id, retryAfterS = null) != null)
        now += 10_000L // backoff elapsed

        val attempted = mutableListOf<String>()
        val caller = ContributionRetryCaller(
            store,
            clock = { now },
            policyAllows = { true },
            networkAvailable = { true },
            attemptUpload = { gen, captureId ->
                assertTrue("attempt runs under a VALID generation", store.fence.isValid(gen))
                attempted += captureId
                true
            },
        )
        val result = caller.pollOnce()
        assertEquals(listOf(id), attempted)
        assertEquals(1, result.attempted)
        assertEquals(0, result.deferredPolicy)
        assertEquals(0, result.deferredNetwork)
    }

    @Test
    fun `ONE attempt is in flight per entry - a re-entrant poll cannot double-attempt`() {
        var now = 12_500_000L
        val root = tmp.newFolder("caller-inflight")
        val id = UUID.randomUUID().toString()
        val store = newStore(root, { now })
        val generation = prepare(store, id)
        assertEquals(1, store.recordAttempt(generation, id))
        assertTrue(store.scheduleRetryAfterFailure(generation, id, retryAfterS = null) != null)
        now += 10_000L

        var attempts = 0
        lateinit var caller: ContributionRetryCaller
        caller = ContributionRetryCaller(
            store,
            clock = { now },
            policyAllows = { true },
            networkAvailable = { true },
            attemptUpload = { gen, captureId ->
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
    fun `a REVOKED generation between eligibility and attempt cancels the attempt`() {
        var now = 13_000_000L
        val root = tmp.newFolder("caller-revoked")
        val id = UUID.randomUUID().toString()
        val store = newStore(root, { now })
        val generation = prepare(store, id)
        assertEquals(1, store.recordAttempt(generation, id))
        assertTrue(store.scheduleRetryAfterFailure(generation, id, retryAfterS = null) != null)
        now += 10_000L

        var attempts = 0
        val caller = ContributionRetryCaller(
            store,
            clock = { now },
            policyAllows = { true },
            networkAvailable = { true },
            attemptUpload = { _, _ ->
                attempts++
                true
            },
        )
        // Account revocation lands AFTER the poll claimed the entry as eligible but BEFORE the attempt.
        store.purgeQueue("account revoked")
        val result = caller.pollOnce()
        assertEquals("a revoked epoch cancels the attempt", 0, attempts)
        assertEquals(0, result.attempted)
    }

    @Test
    fun `policy and network are rechecked BEFORE EVERY attempt`() {
        var now = 13_500_000L
        val root = tmp.newFolder("caller-gates")
        val id = UUID.randomUUID().toString()
        val store = newStore(root, { now })
        val generation = prepare(store, id)
        assertEquals(1, store.recordAttempt(generation, id))
        assertTrue(store.scheduleRetryAfterFailure(generation, id, retryAfterS = null) != null)
        now += 10_000L

        var attempts = 0
        var policy = false
        var caller = ContributionRetryCaller(store, clock = { now }, policyAllows = { policy }, networkAvailable = { true }, attemptUpload = { _, _ ->
            attempts++
            true
        })
        assertEquals(0, caller.pollOnce().attempted)
        assertEquals("policy gate blocks the attempt", 0, attempts)
        assertEquals(1, caller.pollOnce().deferredPolicy)

        policy = true
        var network = false
        caller = ContributionRetryCaller(store, clock = { now }, policyAllows = { true }, networkAvailable = { network }, attemptUpload = { _, _ ->
            attempts++
            true
        })
        assertEquals(0, caller.pollOnce().attempted)
        assertEquals("network gate blocks the attempt", 0, attempts)
        assertEquals(1, caller.pollOnce().deferredNetwork)

        network = true
        caller = ContributionRetryCaller(store, clock = { now }, policyAllows = { true }, networkAvailable = { true }, attemptUpload = { _, _ ->
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
        val generation = prepare(store, id)
        assertEquals(1, store.recordAttempt(generation, id))
        assertTrue(store.scheduleRetryAfterFailure(generation, id, retryAfterS = null) != null)
        now += 10_000L

        val caller = ContributionRetryCaller(
            store,
            clock = { now },
            policyAllows = { true },
            networkAvailable = { true },
            attemptUpload = { _, _ -> false }, // retryable failure
        )
        assertEquals(1, caller.pollOnce().attempted)
        // Backoff re-armed from the failure: not eligible immediately, eligible once it elapses.
        assertTrue(store.retryEligible(now + 1).isEmpty())
        assertTrue(store.retryEligible(now + 3_999L).isNotEmpty())

        // And the caller acts on it at the elapsed time.
        val second = ContributionRetryCaller(store, clock = { now + 3_999L }, policyAllows = { true }, networkAvailable = { true }, attemptUpload = { _, _ -> true })
        assertEquals(1, second.pollOnce().attempted)
    }
}
