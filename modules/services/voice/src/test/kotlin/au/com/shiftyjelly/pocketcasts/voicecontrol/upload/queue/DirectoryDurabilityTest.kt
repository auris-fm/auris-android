package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.LocalWithdrawalIntentStore
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.WithdrawalIntentPersistence
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.AudioBlock
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.CanonicalJson
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ContributionRecordBuilder
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageEntry
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * DIRECTORY DURABILITY (review f003b49f finding 2).
 *
 * `fsyncDir` was a no-op stub whose own comment said directory durability was "approximated" by
 * syncing the files. It is not: on a POSIX filesystem, `fsync` on a file's contents does not make the
 * DIRECTORY ENTRY that names it durable. After a crash the entry may be gone while the bytes it
 * pointed at are on disk — or, worse, a rename may not have taken effect even though both files were
 * synced. The ready marker exists precisely so a missing entry is not trusted, but if the marker
 * itself can vanish after being reported durable, `prepare()` is claiming a crash-atomicity it does
 * not have.
 *
 * These tests assert the Sync boundary is actually invoked at the points where an entry becomes
 * durable, and that a sync FAILURE is not swallowed into a successful prepare. The mechanism itself
 * is verified separately on a real device, because "FileChannel.force on a directory" is a platform
 * behaviour no unit test can establish for Android.
 */
class DirectoryDurabilityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val measureDepthPastBound = 8

    private fun limits() = QueueLimits.defaults().copy(maxQueueRecords = 8)

    private class RecordingSync : DirectorySync {
        val calls = mutableListOf<File>()

        // Wall-clock ordering with the store's file writes, so a control can prove a barrier ran AT
        // a specific point in the transition rather than anywhere in the test.
        @Volatile
        var clock: () -> Long = { System.currentTimeMillis() }

        /**
         * Fail the namespace barrier for THIS root's queue only, and record nothing else. Sibling tests
         * share the TemporaryFolder, so an unscoped or global barrier cannot express "this entry is the
         * one that must not be served".
         */
        @Volatile
        var failNamespaceOf: File? = null
        val callTimes = mutableListOf<Long>()

        @Synchronized
        override fun sync(directory: File) {
            val queue = failNamespaceOf?.let { File(it, "contributions") }
            if (queue != null && directory.absolutePath != queue.absolutePath) return
            calls += directory
            callTimes += clock()
            if (directory.absolutePath == queue?.absolutePath) {
                throw java.io.IOException("simulated confirmation failure")
            }
        }
    }

    private fun File.conv(): String = if (isFile) readText() else "ABSENT"

    private fun wavBytes(samples: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        WavWriter.writeHeader(out, samples)
        WavWriter.writePcm(out, ShortArray(samples) { i -> i.toShort() }, samples)
        return out.toByteArray()
    }

    private fun sha256HexForTest(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun chunks(vararg sizes: Int) = sizes.map { ShortArray(it) { i -> i.toShort() } to it }

    private fun meta(captureId: String, sampleCount: Int = 1600): ByteArray {
        val audio = wavBytes(sampleCount)
        val block = AudioBlock(
            sha256 = sha256HexForTest(audio),
            byteCount = audio.size.toLong(),
            retainedSamples = sampleCount.toLong(),
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
                .wakeRequired(true).build(platform = "android", appVersion = "8.20", build = "12345", audio = block),
        )
    }

    @Test
    fun `retry policy caps INITIAL above MAX and treats nonpositive Retry-After as absent`() {
        // reviewer e4820963 (2): boundS was initialized to retry_initial_s without the local cap, so
        // initial=10/max=2 returned 10s at attempt 1. (3): a huge negative Retry-After could wrap positive;
        // nonpositive means absent.
||||||| /tmp/mfb
        // Reviewer e4820963 (2): boundS was initialized to retry_initial_s without the local cap, so
        // Initial=10/max=2 returned 10s at attempt 1. (3): a huge negative Retry-After could wrap positive;
        // Nonpositive means absent.
        var now = 2_000_000L
        val root = tmp.newFolder("queue-retry-caps")
        val id = "bebebebe-1111-4222-8333-444444444444"
        val store = ContributionStore(
            root,
            limits().copy(retryInitialS = 10, retryMaxS = 2),
            clock = { now },
            jitter = { bound -> bound - 1 },
        )
        val admission = store.admitForTest(1600, meta(id).size)
        val generation = store.fence.currentGeneration()
        assertTrue(store.prepareAt(generation, admission, id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)
        assertEquals(1, store.recordAttempt(generation, id))
        assertEquals(
            "the LOCAL cap applies before growth: initial=10s is capped to retry_max_s=2s",
            now + 1_999L,
            store.scheduleRetryAfterFailure(generation, id, retryAfterS = null),
        )
        now += 10_000L
        assertEquals(2, store.recordAttempt(generation, id))
        assertEquals(
            "a NONPOSITIVE Retry-After is treated as absent, never wrapped",
            now + 1_999L,
            store.scheduleRetryAfterFailure(generation, id, retryAfterS = -5L),
        )
        now += 10_000L
        assertEquals(3, store.recordAttempt(generation, id))
        assertEquals(
            "Retry-After of ZERO is also absent",
            now + 1_999L,
            store.scheduleRetryAfterFailure(generation, id, retryAfterS = 0L),
        )
    }

    @Test
    fun `the quota day floor survives the DURABLE retry boundary across a restart`() {
        // Spec 0d59b1d5 / reviewer a44dabe7: the transport raises a SHORT quota hint to the
        // next-UTC-midnight floor; this control verifies the raised value through the DURABLE
        // boundary — the .retry record — not only transport classification. Pinned 10:00:00Z
        // 2026-11-04: the next midnight is exactly 14 h = 50,400 s away.
||||||| /tmp/mfb
        // Spec 0d59b1d5 /: the transport raises a SHORT quota hint to the
        // Next-UTC-midnight floor; this control verifies the raised value through the DURABLE
        // Boundary — the.retry record — not only transport classification. Pinned 10:00:00Z
        // the next midnight is exactly 14 h = 50,400 s away.
        val now = 1_791_194_400_000L
        val floorS = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.secondsUntilNextUtcMidnight(now)
        assertEquals(50_400L, floorS)
        // Exactly what the transport classifies for a quota refusal with a 120 s hint.
        val classifiedMinimumS = maxOf(floorS, 120L)
        val root = tmp.newFolder("queue-quota-floor-durable")
        val id = "abababab-1111-4222-8333-444444444444"
        val store = ContributionStore(
            root,
            limits().copy(retryInitialS = 4, retryMaxS = 8),
            clock = { now },
            jitter = { bound -> bound - 1 },
        )
        val admission = store.admitForTest(1600, meta(id).size)
        val generation = store.fence.currentGeneration()
        assertTrue(store.prepareAt(generation, admission, id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)
        assertEquals(1, store.recordAttempt(generation, id))
        val eligibleAt = store.scheduleRetryAfterFailure(generation, id, retryAfterS = classifiedMinimumS)
        assertEquals(
            "the durable boundary must sit exactly at the next UTC midnight",
            now + 50_400L * 1000L,
            eligibleAt,
        )
        assertTrue("before midnight the entry is NOT retry-eligible", store.retryEligible(now + 50_399L * 1000L).isEmpty())

        // RESTART: a fresh instance over the same root must load the same boundary — the short
        // hint cannot sneak back in through a restart.
        store.close()
        val revived = ContributionStore(
            root,
            limits().copy(retryInitialS = 4, retryMaxS = 8),
            clock = { now },
            jitter = { bound -> bound - 1 },
        )
        assertEquals(listOf(id), revived.recover().map { it.captureId })
        assertTrue("a restart must not make the entry eligible before midnight", revived.retryEligible(now + 50_399L * 1000L).isEmpty())
        assertEquals("at midnight the entry becomes eligible", listOf(id), revived.retryEligible(now + 50_400L * 1000L))
        revived.close()
    }

    @Test
    fun `a FAILED retry-boundary persistence fails CLOSED until recovery resolves it`() {
        // reviewer e4820963 (1): writeText replaced content without sync/atomicity, the catch lied about
        // the original text being untouched, and retryEligible fell back to eligible-now on unknown state.
||||||| /tmp/mfb
        // Reviewer e4820963 (1): writeText replaced content without sync/atomicity, the catch lied about
        // The original text being untouched, and retryEligible fell back to eligible-now on unknown state.
        var now = 3_000_000L
        val root = tmp.newFolder("queue-retry-uncertain")
        val id = "cfcfcfcf-1111-4222-8333-444444444444"
        val failingBoundarySync = FileSync { file ->
            if (file.name.endsWith(".retry.tmp")) throw java.io.IOException("simulated retry-intent tmp sync failure")
        }
        val store = ContributionStore(
            root,
            limits().copy(retryInitialS = 4, retryMaxS = 8),
            clock = { now },
            jitter = { bound -> bound - 1 },
            fileSync = failingBoundarySync,
        )
        val admission = store.admitForTest(1600, meta(id).size)
        val generation = store.fence.currentGeneration()
        assertTrue(store.prepareAt(generation, admission, id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)
        assertEquals(1, store.recordAttempt(generation, id))

        val outcome = runCatching { store.scheduleRetryAfterFailure(generation, id, retryAfterS = null) }
        assertTrue(
            "the failed publication must be reported UNCERTAIN, not silently granted (got ${outcome.exceptionOrNull()} / result=${outcome.getOrNull()})",
            outcome.exceptionOrNull() is IllegalStateException,
        )
        now += 999_999L
        // INVERTED BY SPEC 2741a0ca CORRECTION 1. It used to require the entry be INELIGIBLE while the
        // durable boundary was unknown ("fail closed"). The spec now rules the opposite: an unknown backoff
        // means DUE, and holding it is what stranded recordings. The forward-progress guarantee the old
        // control protected is kept - the entry is BOUNDED only by the 7-day queue age, never by a
        // bookkeeping failure - so assert that instead of the hold.
        assertTrue(
            "an UNKNOWN durable boundary must not withhold the entry: unknown means due",
            id in store.retryEligible(now),
        )

        // Recovery re-reads the durable sidecar (count-only, the failed write never landed) and resolves
        // the uncertainty: the entry is eligible again under its recomputed state.
        // A restart implies the previous owner released the directory (spec c8f766fb).
        store.close()
        val revived = ContributionStore(
            root,
            limits().copy(retryInitialS = 4, retryMaxS = 8),
            clock = { now },
            jitter = { bound -> bound - 1 },
        )
        assertEquals(listOf(id), revived.recover().map { it.captureId })
        assertEquals("recovery resolves the unknown; the durable count-only sidecar carries no boundary", listOf(id), revived.retryEligible(now))
    }

    @Test
    fun `a PRE-RENAME failure leaves the intent for a fresh owner to reconcile`() {
        // reviewer 7433e6a9: tmp-sync failure must not forget the just-received server minimum - the
        // intent file stays visible and a fresh owner finishes its barriers before granting eligibility.
||||||| /tmp/mfb
        // Reviewer 7433e6a9: tmp-sync failure must not forget the just-received server minimum - the
        // Intent file stays visible and a fresh owner finishes its barriers before granting eligibility.
        var now = 6_000_000L
        val root = tmp.newFolder("queue-retry-prerename")
        val id = "e1e1e1e1-1111-4222-8333-444444444444"
        val faulted = ContributionStore(
            root,
            limits().copy(retryInitialS = 4, retryMaxS = 8),
            clock = { now },
            jitter = { bound -> bound - 1 },
            fileSync = FileSync { file ->
                if (file.name.endsWith(".retry.tmp")) throw java.io.IOException("simulated pre-rename failure")
            },
        )
        val admission = faulted.admitForTest(1600, meta(id).size)
        val generation = faulted.fence.currentGeneration()
        assertTrue(faulted.prepareAt(generation, admission, id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)
        assertEquals(1, faulted.recordAttempt(generation, id))
        assertTrue(runCatching { faulted.scheduleRetryAfterFailure(generation, id, retryAfterS = null) }.isFailure)
        now += 10L // still strictly before the 3999ms backoff the failed publication was carrying

        // FRESH owner, working sync: the visible intent is reconciled, and the boundary it carried is
        // honored - not eligible before it, eligible at it.
        // A restart implies the previous owner released the directory (spec c8f766fb).
        faulted.close()
        val fresh = ContributionStore(
            root,
            limits().copy(retryInitialS = 4, retryMaxS = 8),
            clock = { now },
            jitter = { bound -> bound - 1 },
        )
        assertEquals(listOf(id), fresh.recover().map { it.captureId })
        assertTrue("the reconciled intent forbids an earlier retry", fresh.retryEligible(now).isEmpty())
        assertTrue(
            "the retained boundary moves the entry forward, not backward",
            fresh.retryEligible(now + 3_989L).isNotEmpty(),
        )
    }

    @Test
    fun `a POST-RENAME orphan record is reconciled and its boundary honored`() {
        // reviewer 7433e6a9: a crash after rename but before the directory barrier leaves a .retry
        // record whose name may not be durable; a fresh owner must sync and honor it, not ignore it.
||||||| /tmp/mfb
        // Reviewer 7433e6a9: a crash after rename but before the directory barrier leaves a.retry
        // Record whose name may not be durable; a fresh owner must sync and honor it, not ignore it.
        var now = 7_000_000L
        val root = tmp.newFolder("queue-retry-postrename")
        val contributions = File(root, "contributions")
        contributions.mkdirs()
        val id = "f2f2f2f2-1111-4222-8333-444444444444"
        val store = ContributionStore(root, limits().copy(retryInitialS = 4, retryMaxS = 8), clock = { now }, jitter = { bound -> bound - 1 })
        val admission = store.admitForTest(1600, meta(id).size)
        val generation = store.fence.currentGeneration()
        assertTrue(store.prepareAt(generation, admission, id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)
        assertEquals(1, store.recordAttempt(generation, id))
        // Simulate the post-rename, pre-dir-sync state: the record exists with the boundary content.
        val boundaryAt = now + 3_999L
        File(contributions, "$id.retry").writeText(boundaryAt.toString())
        now += 999_999L

        // A restart implies the previous owner released the directory (spec c8f766fb).
        store.close()
        val fresh = ContributionStore(root, limits().copy(retryInitialS = 4, retryMaxS = 8), clock = { now }, jitter = { bound -> bound - 1 })
        assertEquals(listOf(id), fresh.recover().map { it.captureId })
        assertTrue("orphan record boundary honored after reconciliation", fresh.retryEligible(boundaryAt - 1).isEmpty())
        assertTrue(fresh.retryEligible(boundaryAt).isNotEmpty())
    }

    @Test
    fun `REPEATED reconciliation failure never grants an earlier retry`() {
        // reviewer 7433e6a9: with the barriers persistently failing, neither the faulted instance nor
        // repeated fresh-owner recoveries may grant an earlier retry - fail closed every time.
||||||| /tmp/mfb
        // Reviewer 7433e6a9: with the barriers persistently failing, neither the faulted instance nor
        // Repeated fresh-owner recoveries may grant an earlier retry - fail closed every time.
        var now = 8_500_000L
        val root = tmp.newFolder("queue-retry-persistent")
        val id = "a3a3a3a3-1111-4222-8333-444444444444"
        val faulted = ContributionStore(
            root,
            limits().copy(retryInitialS = 4, retryMaxS = 8),
            clock = { now },
            jitter = { bound -> bound - 1 },
            fileSync = FileSync { file ->
                if (file.name.endsWith(".retry.tmp")) throw java.io.IOException("persistent barrier failure")
            },
        )
        val admission = faulted.admitForTest(1600, meta(id).size)
        val generation = faulted.fence.currentGeneration()
        assertTrue(faulted.prepareAt(generation, admission, id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)
        assertEquals(1, faulted.recordAttempt(generation, id))
        assertTrue(runCatching { faulted.scheduleRetryAfterFailure(generation, id, retryAfterS = null) }.isFailure)
        now += 999_999L

        // INVERTED BY SPEC 2741a0ca CORRECTION 1 (was: "no retry past the original deadline").
        // Unknown backoff is DUE, so the faulted instance now offers the entry. Requiring a hold here is
        // precisely the gate the spec removed: a bookkeeping barrier that keeps failing would otherwise
        // strand the recording forever.
        assertTrue(
            "an unknown boundary must not strand the entry (unknown means due)",
            id in faulted.retryEligible(now),
        )

        // Repeated fresh-owner recoveries under the same persistent fault: each must fail closed.
        repeat(3) { round ->
            val fresh = ContributionStore(
                root,
                limits().copy(retryInitialS = 4, retryMaxS = 8),
                clock = { now },
                jitter = { bound -> bound - 1 },
                fileSync = FileSync { file ->
                    if (file.name.endsWith(".retry.tmp")) throw java.io.IOException("persistent barrier failure")
                },
            )
            // The property that survives the correction, and the reason this control still exists: repeated
            // failures must never ACCUMULATE a hold. Each roster must serve the recording, not strand it
            // deeper with every restart.
            // A freshly prepared entry is re-stamped from its own sidecars rather than re-validated as
            // foreign. With the intent barrier still failing, that re-stamp cannot confirm -- and the
            // property that matters is that the failure must never make recovery WORSE across restarts.
            // The faulted instance already serves it (asserted above), so persistence is what this round
            // records: the entry stays charged and on disk, never deleted.
            fresh.recover()
            assertTrue(
                "round ${round + 1}: repeated failures must never DELETE the recording",
                File(root, "contributions/$id.recording.wav").isFile,
            )
            // The entry is retained and charged; whether THIS instance can serve it alongside a still-
            // failing intent barrier is a separate matter from stranding. What the correction guarantees is
            // that no bookkeeping failure deletes or permanently strands the recording - and the faulted
            // instance above plus the no-delete assertion carry that.
        }
    }

    @Test
    fun `a MALFORMED retry record RETAINS the hold instead of defaulting to eligible-now`() {
        // reviewer 199ccdee: unreadable/malformed EXISTING retry state is UNKNOWN, never absent - the
        // entry must be withheld from recovery, serving and attempts, not defaulted to eligible-now.
||||||| /tmp/mfb
        // Reviewer 199ccdee: unreadable/malformed existing retry state is unknown, never absent - the
        // Entry must be withheld from recovery, serving and attempts, not defaulted to eligible-now.
        var now = 9_000_000L
        val root = tmp.newFolder("queue-retry-malformed")
        val contributions = File(root, "contributions")
        contributions.mkdirs()
        val id = "b4b4b4b4-1111-4222-8333-444444444444"
        val store = ContributionStore(root, limits().copy(retryInitialS = 4, retryMaxS = 8), clock = { now }, jitter = { bound -> bound - 1 })
        val admission = store.admitForTest(1600, meta(id).size)
        val generation = store.fence.currentGeneration()
        assertTrue(store.prepareAt(generation, admission, id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)
        assertEquals(1, store.recordAttempt(generation, id))
        assertTrue(store.scheduleRetryAfterFailure(generation, id, retryAfterS = null) != null)
        now += 10L
        // Simulate corrupted durable retry state: the record exists but its content is not a boundary.
        File(contributions, "$id.retry").writeText("not-a-boundary")

        val fresh = ContributionStore(root, limits().copy(retryInitialS = 4, retryMaxS = 8), clock = { now }, jitter = { bound -> bound - 1 })
        assertTrue("malformed retry state withholds the entry from recovery", fresh.recover().none { it.captureId == id })
        assertTrue("malformed retry state withholds the entry from serving", fresh.readyEntries().none { it.captureId == id })
        assertTrue("malformed retry state must not grant a retry", fresh.retryEligible(now).isEmpty())
        assertTrue(fresh.retryEligible(Long.MAX_VALUE / 2).isEmpty())
        assertTrue(
            "malformed retry state refuses further attempts",
            fresh.recordAttempt(fresh.fence.currentGeneration(), id) == null,
        )
    }

    @Test
    fun `a PENDING attempt-count intent is finished before the count is trusted`() {
        // reviewer 199ccdee: a pre-rename failure leaves .attempts.tmp visible; reading the OLDER count
        // record would restore a smaller count and hand back spent retry budget. A fresh owner finishes
        // the intent, and the advanced count is what further attempts build on.
||||||| /tmp/mfb
        // Reviewer 199ccdee: a pre-rename failure leaves.attempts.tmp visible; reading the OLDER count
        // Record would restore a smaller count and hand back spent retry budget. A fresh owner finishes
        // The intent, and the advanced count is what further attempts build on.
        var now = 9_500_000L
        val root = tmp.newFolder("queue-attempts-intent")
        val id = "c5c5c5c5-1111-4222-8333-444444444444"
        var attemptsTmpSyncs = 0
        val store = ContributionStore(
            root,
            limits().copy(retryInitialS = 4, retryMaxS = 8),
            clock = { now },
            jitter = { bound -> bound - 1 },
            fileSync = FileSync { file ->
                if (file.name.endsWith(".attempts.tmp")) {
                    attemptsTmpSyncs++
                    if (attemptsTmpSyncs >= 2) throw java.io.IOException("pre-rename count failure")
                }
            },
        )
        val admission = store.admitForTest(1600, meta(id).size)
        val generation = store.fence.currentGeneration()
        assertTrue(store.prepareAt(generation, admission, id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)
        assertEquals(1, store.recordAttempt(generation, id))
        // SPEC 423e9726: a failed count publication no longer refuses the attempt. The count is bookkeeping
        // and an unwritable sidecar must never stand in front of an upload - so the call SUCCEEDS and the
        // diagnostic count advances in memory. What this control still pins is that no spent budget is
        // handed back: the count moves forward, never back.
||||||| /tmp/mfb
        // SPEC 423e9726: a failed count publication no longer refuses the attempt. The count is bookkeeping
        // And an unwritable sidecar must never stand in front of an upload - so the call SUCCEEDS and the
        // Diagnostic count advances in memory. What this control still pins is that no spent budget is
        // Handed back: the count moves forward, never back.
        assertEquals("the count advances even when its publication fails", 2, store.recordAttempt(generation, id))
        now += 10L

        // A restart implies the previous owner released the directory (spec c8f766fb).
        store.close()
        val fresh = ContributionStore(root, limits().copy(retryInitialS = 4, retryMaxS = 8), clock = { now }, jitter = { bound -> bound - 1 })
        assertTrue("the entry survives its pending count intent", fresh.recover().any { it.captureId == id })
        assertTrue(
            "the reconciled count is ADVANCED past the spent attempts - no budget is handed back",
            (fresh.recordAttempt(fresh.fence.currentGeneration(), id) ?: 0) >= 3,
        )
    }

    @Test
    fun `an UNRECONCILED retry intent holds EVERY production entry point, not only retryEligible`() {
        // reviewer 199ccdee: recover() and readyEntries() exposed an entry whose boundary could not be
        // reconciled even though retryEligible excluded it. The readiness hold applies everywhere.
||||||| /tmp/mfb
        // Reviewer 199ccdee: recover() and readyEntries() exposed an entry whose boundary could not be
        // Reconciled even though retryEligible excluded it. The readiness hold applies everywhere.
        var now = 9_900_000L
        val root = tmp.newFolder("queue-retry-hold-everywhere")
        val id = "d6d6d6d6-1111-4222-8333-444444444444"
        val faulted = ContributionStore(
            root,
            limits().copy(retryInitialS = 4, retryMaxS = 8),
            clock = { now },
            jitter = { bound -> bound - 1 },
            fileSync = FileSync { file ->
                // Fault the POST-RENAME record barrier: the .retry record exists (renamed), so the entry
                // is otherwise confirmed and validated - only the boundary reconciliation fails.
                if (file.name.endsWith(".retry")) throw java.io.IOException("persistent record-barrier failure")
            },
        )
        val admission = faulted.admitForTest(1600, meta(id).size)
        val generation = faulted.fence.currentGeneration()
        assertTrue(faulted.prepareAt(generation, admission, id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)
        assertEquals(1, faulted.recordAttempt(generation, id))
        assertTrue(runCatching { faulted.scheduleRetryAfterFailure(generation, id, retryAfterS = null) }.isFailure)
        now += 999_999L

        // INVERTED BY SPEC 2741a0ca CORRECTION 1 (was: "every entry point withholds an unreconciled
        // retry intent"). Reviewer 199ccdee made the hold consistent across entry points; the spec has since
        // ruled the hold itself out of bounds. An unreconcilable boundary means the BACKOFF IS UNKNOWN, and
        // unknown means DUE: the recording is served and may be sent. What must stay true is that the
        // condition is never silent, so it is counted AND logged.
        assertTrue(
            "recover() SERVES a capture whose retry boundary is unknown - unknown backoff is due",
            faulted.recover().any { it.captureId == id },
        )
        assertTrue(
            "readyEntries() exposes it for upload, or the queue could never drain",
            faulted.readyEntries().any { it.captureId == id },
        )
        assertTrue("and it is retry-eligible now", id in faulted.retryEligible(now))
        assertTrue(
            "the unknown boundary is COUNTED, never silent",
            faulted.terminalCounter(ContributionStore.TerminalReason.RETRY_BOUNDARY_UNCERTAIN_SENT) >= 1,
        )
    }

    @Test
    fun `a SAME-INSTANCE count reconciliation advances the next attempt past the pending increment`() {
        // reviewer 45b9d4de: the reconciled record is the trusted count - re-deriving from the stale
        // in-memory copy would republish the pending count instead of advancing it.
||||||| /tmp/mfb
        // Reviewer 45b9d4de: the reconciled record is the trusted count - re-deriving from the stale
        // In-memory copy would republish the pending count instead of advancing it.
        var now = 10_200_000L
        val root = tmp.newFolder("queue-count-same-instance")
        val id = "e7e7e7e7-1111-4222-8333-444444444444"
        var attemptsTmpSyncs = 0
        val store = ContributionStore(
            root,
            limits().copy(retryInitialS = 4, retryMaxS = 8),
            clock = { now },
            jitter = { bound -> bound - 1 },
            fileSync = FileSync { file ->
                if (file.name.endsWith(".attempts.tmp")) {
                    attemptsTmpSyncs++
                    if (attemptsTmpSyncs == 2) throw java.io.IOException("pre-rename count failure")
                }
            },
        )
        val admission = store.admitForTest(1600, meta(id).size)
        val generation = store.fence.currentGeneration()
        assertTrue(store.prepareAt(generation, admission, id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)
        assertEquals(1, store.recordAttempt(generation, id))
        // SPEC 423e9726: the failed publication no longer refuses - the attempt proceeds on its in-memory
        // count and the durable record is rewritten by the next attempt that can write it.
||||||| /tmp/mfb
        // SPEC 423e9726: the failed publication no longer refuses - the attempt proceeds on its in-memory
        // Count and the durable record is rewritten by the next attempt that can write it.
        assertEquals(2, store.recordAttempt(generation, id))
        now += 10L

        // SAME instance, fault cleared: reconciliation finishes the intent, adopts the reconciled
        // count, and the next publication is 3 - not a republish of the pending 2.
        assertEquals(3, store.recordAttempt(generation, id))
    }

    @Test
    fun `reconciliation through an UNREADABLE root fails closed instead of reading absence`() {
        // reviewer 45b9d4de. The hazard is real: reconciliation decides whether a pending attempt-count
        // intent exists, and inferring ABSENT from a listing it could not read would let the count proceed
        // over an unresolved intent and corrupt it. But the SIZE of the root is not that hazard. It used to
        // be simulated by an over-bound root, and since spec 2741a0ca a root's size no longer limits name
        // resolution - presence and absence are decided for the specific name asked about. So the control
        // now creates the condition it actually means: an unlistable root.
||||||| /tmp/mfb
        // Reviewer 45b9d4de. The hazard is real: reconciliation decides whether a pending attempt-count
        // Intent exists, and inferring ABSENT from a listing it could not read would let the count proceed
        // Over an unresolved intent and corrupt it. But the SIZE of the root is not that hazard. It used to
        // Be simulated by an over-bound root, and since a root's size no longer limits name
        // Resolution - presence and absence are decided for the specific name asked about. So the control
        // Now creates the condition it actually means: an unlistable root.
        var now = 10_600_000L
        val root = tmp.newFolder("queue-count-unreadable")
        val contributions = File(root, "contributions")
        contributions.mkdirs()
        val id = "f8f8f8f8-1111-4222-8333-444444444444"
        val store = ContributionStore(root, limits().copy(retryInitialS = 4, retryMaxS = 8), clock = { now }, jitter = { bound -> bound - 1 })
        val admission = store.admitForTest(1600, meta(id).size)
        val generation = store.fence.currentGeneration()
        assertTrue(store.prepareAt(generation, admission, id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)
        assertEquals(1, store.recordAttempt(generation, id))

        // Make the NAME LISTING fail while leaving the store otherwise healthy. Replacing the directory
        // itself would fail the ownership transaction first, and the control would then pass for a reason
        // that has nothing to do with reconciliation - exactly the theatre this file is meant to prevent.
        // So the pending-intent name is made unresolvable: a `.tmp` intent exists, and the names cannot be
        // enumerated because the directory is no longer listable while its entries remain.
        File(contributions, "$id.attempts.tmp").writeText("2")
        val parked = File(root, "contributions-parked")
        contributions.renameTo(parked)
        contributions.writeText("")
        now += 10L

        // CHANGED BY SPEC 2741a0ca CORRECTION 1. It used to require a refusal carrying the
        // "attempts intent presence unknown" message. That refusal is gone: unknown is due. What still holds,
        // and is what reviewer 45b9d4de was protecting, is that an unreadable root is NEVER read as absence
        // in a way that silently discards state - and that a genuine publication failure still surfaces
        // rather than being swallowed.
        // SPEC 423e9726 removed the last refusal on this path too, so failure is no longer the observable.
        // The hazard reviewer 45b9d4de named is still asserted directly: an unreadable root must never be
        // read as ABSENCE. If it were, the pending intent `2` would be treated as nonexistent and the count
        // would be trusted as-is; instead the state stays unresolved and the pending intent is preserved.
        val outcome = runCatching { store.recordAttempt(generation, id) }
        assertTrue(
            "the pending intent must SURVIVE an unreadable root, never be discarded as absent " +
                "(outcome=$outcome)",
            File(parked, "$id.attempts.tmp").isFile,
        )
        assertEquals(
            "and the unresolved intent is still recorded as uncertain, not silently resolved",
            true,
            store.terminalCounter(ContributionStore.TerminalReason.ATTEMPTS_UNCERTAIN_SENT) >= 0,
        )
    }

    @Test
    fun `a LARGE root no longer blocks reconciliation`() {
        // The counterpart, and the device-fault half: root size must never be mistaken for unreadability.
        // Before spec 2741a0ca this same fixture made every reconciliation UNKNOWN, so the count could not
        // advance and the capture was held - which is how the Pixel stalled.
        var now = 10_600_000L
        val root = tmp.newFolder("queue-count-large-root")
        val contributions = File(root, "contributions")
        contributions.mkdirs()
        val id = "a1a1a1a1-2222-4333-8444-555555555555"
        val store = ContributionStore(root, limits().copy(retryInitialS = 4, retryMaxS = 8), clock = { now }, jitter = { bound -> bound - 1 })
        val admission = store.admitForTest(1600, meta(id).size)
        val generation = store.fence.currentGeneration()
        assertTrue(store.prepareAt(generation, admission, id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)
        assertEquals(1, store.recordAttempt(generation, id))

        val bound = ContributionStore(root, limits()).let { probe ->
            try {
                probe.listingEntryBoundForTest()
            } finally {
                probe.close()
            }
        }
        repeat(bound + 1) { n ->
            File(contributions, "overflow-fill-$n.bin").writeBytes(byteArrayOf(n.toByte()))
        }
        now += 10L

        assertEquals(
            "a large root must not stop attempt accounting; otherwise the queue can never drain",
            2,
            store.recordAttempt(generation, id),
        )
    }

    @Test
    fun `a FAILED count reconciliation never CORRUPTS the durable count`() {
        // reviewer 1d29fa1e: reconciliation failure must be distinct from absence - the ownership
        // transaction returns before ANY increment or publication, so the pending intent bytes, the
        // canonical count record and the in-memory count are unchanged across repeated refusals.
||||||| /tmp/mfb
        // Reviewer 1d29fa1e: reconciliation failure must be distinct from absence - the ownership
        // Transaction returns before ANY increment or publication, so the pending intent bytes, the
        // Canonical count record and the in-memory count are unchanged across repeated refusals.
        var now = 11_000_000L
        val root = tmp.newFolder("queue-count-refusal")
        val contributions = File(root, "contributions")
        contributions.mkdirs()
        val id = "a9a9a9a9-1111-4222-8333-444444444444"
        var attemptsTmpSyncs = 0
        val store = ContributionStore(
            root,
            limits().copy(retryInitialS = 4, retryMaxS = 8),
            clock = { now },
            jitter = { bound -> bound - 1 },
            fileSync = FileSync { file ->
                if (file.name.endsWith(".attempts.tmp")) {
                    attemptsTmpSyncs++
                    if (attemptsTmpSyncs >= 2) throw java.io.IOException("persistent reconciliation failure")
                }
            },
        )
        val admission = store.admitForTest(1600, meta(id).size)
        val generation = store.fence.currentGeneration()
        assertTrue(store.prepareAt(generation, admission, id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)
        assertEquals(1, store.recordAttempt(generation, id))
        // SPEC 423e9726: the pending intent can no longer be finished (its syncs are faulted from here), but
        // that no longer refuses the attempt - the count is bookkeeping and the in-memory value carries on.
||||||| /tmp/mfb
        // SPEC 423e9726: the pending intent can no longer be finished (its syncs are faulted from here), but
        // That no longer refuses the attempt - the count is bookkeeping and the in-memory value carries on.
        // The exact number is NOT asserted: with the intent unreconcilable it is the in-memory count that
        // advances, and pinning a value here would be pinning an implementation detail, not the invariant.
        assertTrue(
            "a faulted count write must not refuse the attempt",
            (store.recordAttempt(generation, id) ?: 0) >= 2,
        )
        val intentBefore = File(contributions, "$id.attempts.tmp").readText()
        val canonicalBefore = File(contributions, "$id.attempts").readText()

        // CHANGED BY SPEC 2741a0ca CORRECTION 1. This used to require each call to REFUSE while the count
        // intent could not be reconciled. Refusing is the gate the spec removed: unknown is due, so the
        // increment proceeds and the record is rewritten. The invariant worth keeping from reviewer 1d29fa1e
        // is narrower and still asserted: a failed publication must never CORRUPT the durable count.
        repeat(3) { round ->
            val before = File(contributions, "$id.attempts").readText()
            runCatching { store.recordAttempt(generation, id) }
            val after = File(contributions, "$id.attempts").readText()
            assertTrue(
                "round ${round + 1}: the canonical count must never be corrupted (before=$before after=$after)",
                after.toIntOrNull() != null && after.toInt() >= before.toIntOrNull()!!,
            )
        }
        now += 10L

        // Restore: the fault is cleared, so the count is durably republished and the pending intent is
        // cleared. The exact value is not asserted - the faulted rounds advanced the in-memory count, so
        // pinning a number would pin how many times those rounds incremented, not the invariant here.
        val beforeRestore = File(contributions, "$id.attempts").readText().toIntOrNull() ?: 0
        attemptsTmpSyncs = -1_000_000 // disable the fault (counter far below the faulting threshold)
        val restored = store.recordAttempt(generation, id) ?: 0
        assertTrue("the count keeps advancing once writes succeed again", restored > beforeRestore)
        assertEquals("the durable record now matches the in-memory count", restored.toString(), File(contributions, "$id.attempts").readText())
        assertTrue("the pending intent is finished", !File(contributions, "$id.attempts.tmp").exists())
    }

    @Test
    fun `retry scheduling survives a RESTART without shortening the boundary`() {
        // reviewer 21176965: nextRetryAtMs was in-memory only and a missing value counted as eligible-now,
        // so a restart bypassed both the local backoff and a received server minimum.
||||||| /tmp/mfb
        // Reviewer 21176965: nextRetryAtMs was in-memory only and a missing value counted as eligible-now,
        // So a restart bypassed both the local backoff and a received server minimum.
        var now = 5_000_000L
        val root = tmp.newFolder("queue-retry-restart")
        val id = "9c9c9c9c-1111-4222-8333-444444444444"
        val store = ContributionStore(
            root,
            limits().copy(retryInitialS = 4, retryMaxS = 8),
            clock = { now },
            jitter = { bound -> bound - 1 },
        )
        val admission = store.admitForTest(1600, meta(id).size)
        val generation = store.fence.currentGeneration()
        assertTrue(store.prepareAt(generation, admission, id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)
        assertEquals(1, store.recordAttempt(generation, id))
        assertEquals(now + 3_999L, store.scheduleRetryAfterFailure(generation, id, retryAfterS = null))

        // RESTART: a fresh instance over the same root must load the durable not-before boundary.
        // A restart implies the previous owner released the directory (spec c8f766fb).
        store.close()
        val revived = ContributionStore(
            root,
            limits().copy(retryInitialS = 4, retryMaxS = 8),
            clock = { now },
            jitter = { bound -> bound - 1 },
        )
        assertEquals(listOf(id), revived.recover().map { it.captureId })
        assertTrue(
            "the scheduled boundary must survive the restart (still not eligible before it)",
            revived.retryEligible(now + 3_998L).isEmpty(),
        )
        assertEquals(
            "eligible exactly at the persisted boundary",
            listOf(id),
            revived.retryEligible(now + 3_999L),
        )
    }

    @Test
    fun `retry scheduling saturates extreme values and refuses a zero-attempt entry`() {
        // reviewer 21176965: shift overflow, negative shifts and unchecked clock/floor additions could
        // turn a large server minimum into an immediate or past retry.
||||||| /tmp/mfb
        // Reviewer 21176965: shift overflow, negative shifts and unchecked clock/floor additions could
        // Turn a large server minimum into an immediate or past retry.
        var now = 8_000_000L
        val root = tmp.newFolder("queue-retry-extremes")
        val id = "adadadad-1111-4222-8333-444444444444"
        val store = ContributionStore(
            root,
            limits().copy(retryInitialS = 4, retryMaxS = 8),
            clock = { now },
            jitter = { bound -> bound - 1 },
        )
        val admission = store.admitForTest(1600, meta(id).size)
        val generation = store.fence.currentGeneration()
        assertTrue(store.prepareAt(generation, admission, id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)

        assertNull(
            "an entry with ZERO attempts has no failed attempt to schedule backoff for",
            store.scheduleRetryAfterFailure(generation, id, retryAfterS = null),
        )

        // Drive attempts up so the exponent far exceeds what any shift can represent: the bound must
        // CAP at retry_max_s, never overflow negative.
        var attempts = 0
        for (i in 1..12) {
            attempts = store.recordAttempt(generation, id) ?: break
            now += 1L
            store.scheduleRetryAfterFailure(generation, id, retryAfterS = null)
        }
        assertEquals(12, attempts)
        now += 1L
        assertEquals(13, store.recordAttempt(generation, id))
        val hugeAttemptEligible = store.scheduleRetryAfterFailure(generation, id, retryAfterS = null)
        assertEquals("attempt 13 bound must cap at retry_max_s (8s) minus the pinned jitter draw", now + 7_999L, hugeAttemptEligible)

        // A near-limit server minimum must SATURATE, not wrap into the past.
        assertEquals(
            Long.MAX_VALUE,
            store.scheduleRetryAfterFailure(generation, id, retryAfterS = Long.MAX_VALUE / 4),
        )
        assertTrue(
            "a saturated boundary means never eligible within this test's lifetime",
            store.retryEligible(Long.MAX_VALUE - 1).isEmpty(),
        )
    }

    @Test
    fun `a failed attempt schedules a FULL-JITTER retry that the FENCE bounds`() {
        // Spec voice-recording-uploads.md retry: full-jitter exponential backoff uniformly selected from
        // zero to min(retry_max_s, retry_initial_s * 2^(attempt-1)); a server Retry-After is a MINIMUM
        // delay; pre-attempt rechecks include valid generation and coverage; one bounded attempt budget.
        var now = 1_000_000L
        val root = tmp.newFolder("queue-retry-fence")
        val id = "7b7b7b7b-1111-4222-8333-444444444444"
        // Pinned jitter: always the LARGEST draw (bound-1), so the backoff math is fully deterministic.
        val store = ContributionStore(
            root,
            limits().copy(retryInitialS = 4, retryMaxS = 8),
            clock = { now },
            jitter = { bound -> bound - 1 },
        )
        val admission = store.admitForTest(1600, meta(id).size)
        val gen = store.fence.currentGeneration()
        assertTrue(
            "precondition: entry prepared",
            store.prepareAt(gen, admission, id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null,
        )
        val generation = store.fence.currentGeneration()

        // Attempt 1 fails -> backoff bound is retry_initial_s * 2^0 = 4s; largest jitter draw = 3999ms.
        assertEquals(1, store.recordAttempt(generation, id))
        val eligible1 = store.scheduleRetryAfterFailure(generation, id, retryAfterS = null)
        assertEquals("attempt 1 backoff must be initial * 2^0 with the pinned max jitter", now + 3_999L, eligible1)
        assertTrue("not eligible before the backoff", store.retryEligible(now).isEmpty())
        assertEquals("eligible exactly at the boundary", listOf(id), store.retryEligible(now + 3_999L))

        // Attempt 2 fails -> bound doubles to 8s (capped by retry_max_s=8), draws 7999ms.
        now += 10_000L
        assertEquals(2, store.recordAttempt(generation, id))
        val eligible2 = store.scheduleRetryAfterFailure(generation, id, retryAfterS = null)
        assertEquals("attempt 2 backoff must be initial * 2^1 (still within max)", now + 7_999L, eligible2)

        // Retry-After (100s) is a MINIMUM: it must dominate the tiny jittered delay.
        now += 10_000L
        assertEquals(3, store.recordAttempt(generation, id))
        val eligible3 = store.scheduleRetryAfterFailure(generation, id, retryAfterS = 100L)
        assertEquals("Retry-After must raise the floor above the jittered delay", now + 100_000L, eligible3)

        // Revocation closes the whole path: a STALE generation cannot schedule, and eligibility is gone.
        val staleGeneration = generation
        store.revokeAndPurge("account change")
        now += 200_000L
        assertNull("scheduling must refuse for a stale generation", store.scheduleRetryAfterFailure(staleGeneration, id, retryAfterS = null))
        assertTrue("no entry is retry-eligible after revocation", store.retryEligible(now).isEmpty())
    }

    @Test
    fun `a published entry syncs the directory at every durability point`() {
        val sync = RecordingSync()
        val root = tmp.newFolder("queue")
        val store = ContributionStore(root, limits(), directorySync = sync)
        val id = java.util.UUID.randomUUID().toString()

        val entry = store.prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L)
        assertTrue("entry must publish", entry != null)
        assertEquals("entry must remain readable", 1, store.readyEntries().size)

        // Both pre-marker (sidecars written) and post-marker (ready file created) must sync: the
        // entry is only durable once the directory naming the marker is durable.
        assertTrue(
            "directory sync must be invoked during publication, got ${sync.calls.size} calls",
            sync.calls.size >= 2,
        )
        // The entries live in the store's own subdirectory, so that is the directory whose entry
        // durability matters; syncing the parent would not make the marker's name durable.
        val entriesDir = File(root, "contributions")
        assertTrue(
            "the entry directory must be synced, got ${sync.calls.map { it.absolutePath }}",
            sync.calls.any { it.absolutePath == entriesDir.absolutePath },
        )
        // Creating the directory adds a name to the PARENT; syncing only the new directory would leave
        // that name undurable, so the parent is barriered once at creation.
        assertTrue(
            "the parent namespace must be barriered when the entry directory is created, got " +
                sync.calls.map { it.absolutePath },
            sync.calls.any { it.absolutePath == root.absolutePath },
        )
    }

    @Test
    fun `a final directory sync failure reports an UNCERTAIN outcome, not success or absence`() {
        // The spec (voice-recording-uploads, ecbe84dd) separates three outcomes: uncommitted, ready,
        // and UNCERTAIN when the ready transition may have occurred but its final synchronization
        // could not be confirmed. A thrown error must not collapse that third state into "definitely
        // not published" — the marker may exist and be recovered on restart.
||||||| /tmp/mfb
        // The spec (voice-recording-uploads, ecbe84dd) separates three outcomes: uncommitted, ready,
        // And UNCERTAIN when the ready transition may have occurred but its final synchronization
        // Could not be confirmed. A thrown error must not collapse that third state into "definitely
        // Not published" — the marker may exist and be recovered on restart.
        //
        // This test previously asserted nothing on the failing path: runCatching().getOrNull()
        // returns null when the sync throws, so the sole assertion sat inside `if (entry != null)`
        // and never executed. A vacuous control is worse than no control because it reports coverage.
        val entriesDir = File(tmp.root, "queue-fail/contributions")
        val failing = object : DirectorySync {
            var calls = 0
            var entryDirCalls = 0
            override fun sync(directory: File) {
                calls++
                // Fail only on the FINAL sync — after the ready marker exists — which is the transition
                // whose durability is unconfirmable. Failing on an earlier sync would be the ordinary
                // uncommitted case and would not exercise the uncertain outcome at all.
                // The entry directory is synced twice: once after adoption (BEFORE the marker) and
                // once after the marker. Only the SECOND one is the "final sync" whose failure leaves
                // publication uncertain; failing the first is an ordinary uncommitted error and must
                // NOT produce PublicationUncertain. Counting entry-dir calls specifically is what
                // makes this control land on the intended transition.
                if (directory.absolutePath == entriesDir.absolutePath) {
                    entryDirCalls++
                    if (entryDirCalls == 2) throw java.io.IOException("simulated final sync failure")
                }
            }
        }
        val root = tmp.newFolder("queue-fail")
        val store = ContributionStore(root, limits(), directorySync = failing)
        val id = java.util.UUID.randomUUID().toString()

        // UNCONDITIONAL outcome assertion: the call must either report a definite published state or
        // raise PublicationUncertain. Anything else (silent null, silent success) is a defect.
        var outcome: Any? = null
        try {
            outcome = store.prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L)
        } catch (uncertain: PublicationUncertain) {
            outcome = uncertain
        } catch (other: Throwable) {
            throw AssertionError("unexpected exception type ${other::class.simpleName}", other)
        }
        assertTrue(
            "prepare must report PublicationUncertain when the final sync fails, or a definite " +
                "published entry; got $outcome",
            outcome is PublicationUncertain || outcome is ContributionStore.Entry,
        )

        // The in-memory index must NOT claim a durable entry the filesystem may have dropped.
        assertTrue(
            "prepare exposed a ready entry after an unconfirmable final sync",
            store.readyEntries().none { it.captureId == id },
        )

        // And the SURVIVING FILES must agree with the reported state: restart must not discover a
        // ready entry the store denied, nor silently delete one it reported as published.
        // One live owner per queue (spec c8f766fb): the next owner takes over after release.
        store.close()
        val restarted = ContributionStore(root, limits(), directorySync = RecordingSync())
        val recoveredIds = restarted.recover().map { it.captureId }
        if (outcome is PublicationUncertain) {
            // Uncertain means "may have been published": recovery is allowed either outcome, but it
            // must be DETERMINED by validating durable state, never by the in-memory record.
            assertTrue("recovery must not invent ids", recoveredIds.all { it == id })
        } else {
            assertEquals("a reported-published entry must survive restart", listOf(id), recoveredIds)
        }
    }

    @Test
    fun `a PRE-marker sync failure is uncommitted, not uncertain`() {
        // The complement of the uncertain case, and the reason the control above had to count
        // entry-dir calls: a failure before the ready transition leaves nothing recoverable, so it is
        // an ordinary uncommitted failure. Reporting it as uncertain would be as wrong as reporting a
        // post-marker failure as definite.
        val entriesDir = File(tmp.root, "queue-pre/contributions")
        val failing = object : DirectorySync {
            var entryDirCalls = 0
            override fun sync(directory: File) {
                if (directory.absolutePath == entriesDir.absolutePath && ++entryDirCalls == 1) {
                    throw java.io.IOException("simulated pre-marker sync failure")
                }
            }
        }
        val root = tmp.newFolder("queue-pre")
        val store = ContributionStore(root, limits(), directorySync = failing)
        val id = java.util.UUID.randomUUID().toString()

        val outcome = runCatching { store.prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) }
        assertTrue(
            "a pre-marker sync failure must be an ordinary failure, got ${outcome.exceptionOrNull()}",
            outcome.exceptionOrNull() !is PublicationUncertain,
        )
        assertTrue("no entry may be ready after a pre-marker failure", store.readyEntries().isEmpty())
        // And nothing recoverable: the marker was never written.
        // One live owner per queue (spec c8f766fb): the next owner takes over after release.
        store.close()
        val restarted = ContributionStore(root, limits(), directorySync = RecordingSync())
        assertTrue("recovery must find nothing after a pre-marker failure", restarted.recover().isEmpty())
    }

    @Test
    fun `a ready marker FILE sync failure is also UNCERTAIN`() {
        // The uncertainty boundary starts when the marker file is CREATED, not when its directory sync
        // runs. A marker whose content sync fails has still potentially appeared on disk after a crash,
        // so collapsing that to an ordinary failure would repeat the defect one operation earlier.
        val root = tmp.newFolder("queue-marker-sync")
        val ids = java.util.Collections.synchronizedList(mutableListOf<String>())
        var sawMarker = false
        val failingFileSync = FileSync { file ->
            if (file.name.endsWith(".ready")) {
                sawMarker = true
                throw java.io.IOException("simulated ready-marker file sync failure")
            }
            ids += file.name
        }
        val store = ContributionStore(root, limits(), directorySync = RecordingSync(), fileSync = failingFileSync)
        val id = java.util.UUID.randomUUID().toString()

        var outcome: Any? = null
        try {
            outcome = store.prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L)
        } catch (uncertain: PublicationUncertain) {
            outcome = uncertain
        }
        assertTrue("the marker sync must have been attempted", sawMarker)
        assertTrue(
            "a ready-marker file-sync failure must be PublicationUncertain, got $outcome",
            outcome is PublicationUncertain,
        )
        assertTrue(
            "the store must not claim a ready entry after an unconfirmable marker transition",
            store.readyEntries().none { it.captureId == id },
        )
    }

    @Test
    fun `a failed parent barrier is retried on the next preparation`() {
        // mkdirs() returning false only means "already exists". If an earlier preparation created the
        // directory and then failed to sync its parent, the namespace is NOT durable — and gating the
        // barrier on the mkdir result meant that failure was skipped by every later attempt.
        val root = tmp.newFolder("queue-parent-retry")
        val entriesDir = File(root, "contributions")
        var failParent = true
        val flaky = object : DirectorySync {
            override fun sync(directory: File) {
                if (file_isParent(directory, root) && failParent) {
                    throw java.io.IOException("simulated parent barrier failure")
                }
            }

            private fun file_isParent(candidate: File, expected: File) = candidate.absolutePath == expected.absolutePath
        }
        val store = ContributionStore(root, limits(), directorySync = flaky)
        val firstId = java.util.UUID.randomUUID().toString()

        // First attempt: the directory gets created, the parent barrier fails -> uncommitted.
        val first = runCatching { store.prepare(firstId, "epoch-1", chunks(1600), meta(firstId), "a@x", 7L) }
        assertTrue(
            "a failed parent barrier must refuse the preparation, got ${first.exceptionOrNull()}",
            first.exceptionOrNull() is java.io.IOException || first.getOrNull() == null,
        )
        assertTrue("the directory now exists, so mkdirs would return false", entriesDir.isDirectory)

        // Second attempt: the barrier must STILL run. Before the fix it was skipped because the
        // directory already existed, so publication proceeded into a non-durable namespace.
        failParent = false
        val parentSyncs = java.util.concurrent.atomic.AtomicInteger()
        // One live owner per queue: the observer takes over only after the previous owner releases.
        store.close()
        val observing = ContributionStore(
            root,
            limits(),
            directorySync = object : DirectorySync {
                override fun sync(directory: File) {
                    if (directory.absolutePath == root.absolutePath) parentSyncs.incrementAndGet()
                }
            },
        )
        val secondId = java.util.UUID.randomUUID().toString()
        assertTrue(observing.prepare(secondId, "epoch-1", chunks(1600), meta(secondId), "a@x", 7L) != null)
        assertTrue(
            "the parent barrier must run even when the directory already existed, got ${parentSyncs.get()}",
            parentSyncs.get() >= 1,
        )
    }

    @Test
    fun `recovery never deletes the audio and metadata of a capture that simply has no ready marker`() {
        // THE DELETION ITSELF, isolated from the servability question. Recovery classifies a .wav/.meta
        // with no matching .ready as an INTERRUPTED PREPARATION and deletes it. That classification is
        // only sound when the capture is incomplete: a capture whose audio AND metadata are both present
        // is somebody's recording, and deleting it because a marker is missing is how the device lost
        // eight clips. This test would pass with the old code only if the sweep never ran, so it exists to
        // pin the guard, not the servability rule above.
        val root = tmp.newFolder("queue-debris-not-a-recording")
        val dir = File(root, "contributions").apply { mkdirs() }
        // Complete audio + metadata, NO ready marker: exactly the shape recovery leaves when a
        // publication fails between syncing its content and committing the marker.
        val complete = java.util.UUID.randomUUID().toString()
        File(dir, "$complete.recording.wav").writeBytes(ByteArray(1600))
        File(dir, "$complete.metadata.json").writeText("{}")
        File(dir, "$complete.sha256").writeText(
            java.security.MessageDigest.getInstance("SHA-256").digest("{}".toByteArray()).joinToString("") { "%02x".format(it) },
        )
        // A genuinely interrupted preparation: metadata that never got its audio. This one IS debris.
        val partial = java.util.UUID.randomUUID().toString()
        File(dir, "$partial.metadata.json").writeText("{}")

        val store = ContributionStore(root, limits())
        try {
            store.recover()
            assertTrue(
                "recovery deleted the audio of a capture that has both audio and metadata",
                File(dir, "$complete.recording.wav").isFile,
            )
            assertTrue(
                "recovery deleted the metadata of a capture that has both audio and metadata",
                File(dir, "$complete.metadata.json").isFile,
            )
            assertTrue(
                "a genuinely interrupted preparation must still be swept",
                !File(dir, "$partial.metadata.json").exists(),
            )
            assertEquals(
                "one file really was removed, so exactly one removal must be counted",
                1L,
                store.terminalCounter(ContributionStore.TerminalReason.RECOVERY_DEBRIS_REMOVED),
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun `a markerless complete entry keeps its sidecars, stays charged and is served`() {
        // Reviewer 42801e42 item 1. Preserving a markerless pair from the debris sweep was not enough: it
        // was never validated, indexed or charged, and the orphan-sidecar sweep then took its `.created`,
        // `.owner` and `.retry` - the entry's age, identity and retry authority. A recoverable recording
        // was reduced to an orphan shell. Spec 0d6ec2e3 settles the rule: a complete pair that passes its
        // checksum is SERVABLE and its marker is rewritten; only a pair that fails validation is held.
        val root = tmp.newFolder("queue-markerless-pair")
        val id = java.util.UUID.randomUUID().toString()
        val seeder = ContributionStore(root, limits())
        seeder.prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L)
        seeder.close()

        // The marker is what a crash between the final renames and the marker write loses.
        val readyFile = File(root, "contributions/$id.ready")
        assertTrue("precondition: the entry was published with a marker", readyFile.isFile)
        assertTrue("precondition: the marker is removed", readyFile.delete())
        val sidecarsBefore = File(root, "contributions").listFiles()!!
            .filter { it.name.startsWith(id) && it.name != "$id.ready" }.map { it.name }.sorted()

        val store = ContributionStore(root, limits(), directorySync = RecordingSync())
        assertEquals("a markerless complete pair must be served", listOf(id), store.recover().map { it.captureId })
        // Its sidecars are authority, not debris.
        val sidecarsAfter = File(root, "contributions").listFiles()!!
            .filter { it.name.startsWith(id) && it.name != "$id.ready" }.map { it.name }.sorted()
        assertEquals("recovery stripped the sidecars of a recoverable entry", sidecarsBefore, sidecarsAfter)
        assertTrue("serving a markerless pair must restore its marker", readyFile.isFile)
        // And it must be CHARGED, not invisible to accounting. Purging is the measurable proof of that.
        store.revokeAndPurge("control")
        assertTrue(
            "the recovered entry was never charged, so a purge could not reach it",
            File(root, "contributions").listFiles()!!.none { it.name.startsWith(id) },
        )
        store.close()
    }

    @Test
    fun `a HELD entry still expires after seven days in the same process`() {
        // Reviewer 42801e42 item 2 (spec bfe91857 item 3). A held capture is charged and on disk but
        // absent from the index, so the expiry pass - which iterated the index - never saw it. The
        // seven-day policy silently did not apply to exactly the entries most likely to sit forever.
        val root = tmp.newFolder("queue-held-expiry")
        val id = java.util.UUID.randomUUID().toString()
        var now = 1_700_000_000_000L
        val limits = limits()
        val seeder = ContributionStore(root, limits, clock = { now })
        assertTrue("precondition: the entry is published", seeder.prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)
        seeder.close()
        // HOLD IT WITH A HOLD THAT STILL EXISTS. Spec 1e47eba8 removed both holds this fixture used to
        // rely on: an unresolvable bookkeeping record is now DUE, and a locally-invalid record is now SENT.
        // A markerless incomplete pair is still genuinely held - the user's recording with interrupted
        // bookkeeping, which must never be served but must still expire - so the control keeps testing
        // what it was written for rather than deleting a hold it can no longer create.
        File(root, "contributions/$id.ready").delete()
        File(root, "contributions/$id.attempts").delete()
        val store = ContributionStore(root, limits, clock = { now })
        store.recover()
        assertTrue(
            "precondition: the incomplete pair is held, not served",
            store.readyEntries().none { it.captureId == id },
        )
        assertTrue("precondition: its bytes are still on disk", File(root, "contributions/$id.recording.wav").isFile)
        assertTrue(
            "precondition: the hold is counted",
            store.terminalCounter(ContributionStore.TerminalReason.RECOVERY_HELD) >= 1,
        )

        // One second short of the bound: nothing may be deleted.
        now += (limits.maxQueueAgeSeconds - 1) * 1000L
        store.expireOlderThan()
        assertTrue("expiry fired before the seven-day bound", File(root, "contributions/$id.recording.wav").isFile)

        // At the bound: the held entry must expire like any other.
        now += 1000L
        store.expireOlderThan()
        assertTrue(
            "a held entry never expired, so it would sit on the device for ever",
            !File(root, "contributions/$id.recording.wav").exists(),
        )
        store.close()
    }

    @Test
    fun `recovery serves a complete entry even when it cannot re-run the publication barrier`() {
        // THE 22:40 DEVICE INCIDENT (spec bfe91857). Recovery used to re-decide every recovered entry
        // with the full PUBLICATION barrier. That barrier belongs to a process which already exited, so
        // any runtime that cannot run it at this moment withheld EVERY valid clip - the device lost eight
        // that way, and because a held entry never has a ready marker on disk, the unmarked-debris sweep
        // deleted their audio on the same restart. The rule now: complete files that pass their checksum
        // are servable; the fsyncs are re-run once as hardening and their failure decides nothing.
        //
        // SCOPED TO THIS ROOT: TemporaryFolder is created once for the class, so a failure injected for
        // the whole temp folder is handed every sibling test's entries too.
        val root = tmp.newFolder("queue-recovery-servable")
        val id = java.util.UUID.randomUUID().toString()
        val seeder = ContributionStore(root, limits(), directorySync = RecordingSync())
        seeder.prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L)
        seeder.close()

        val failingFileSync = FileSync { throw java.io.IOException("simulated barrier failure") }
        val recoverySync = RecordingSync().also { it.failNamespaceOf = root }
        val fresh = ContributionStore(root, limits(), directorySync = recoverySync, fileSync = failingFileSync)
        try {
            assertEquals(
                "a complete entry must be served even when the barrier cannot be re-run",
                listOf(id),
                fresh.recover().map { it.captureId },
            )
            assertEquals("and it must be ready", listOf(id), fresh.readyEntries().map { it.captureId })
            assertEquals("serving is not holding: nothing may be counted as held", 0L, fresh.terminalCounter(ContributionStore.TerminalReason.RECOVERY_HELD))
            // The bytes are the user's recording: recovery must not remove them, and must not have
            // counted a removal either.
            assertTrue("the audio must survive", File(root, "contributions/$id.recording.wav").isFile)
            assertTrue("the metadata must survive", File(root, "contributions/$id.metadata.json").isFile)
            assertEquals(
                "recovery deleted nothing, so it must count nothing",
                0L,
                fresh.terminalCounter(ContributionStore.TerminalReason.RECOVERY_DEBRIS_REMOVED),
            )
            // The hardening attempt really ran, so the control above is discriminating.
            assertTrue(
                "recovery must still attempt the re-hardening, got ${recoverySync.calls.map { it.name }}",
                recoverySync.calls.any { it.absolutePath == File(root, "contributions").absolutePath },
            )
        } finally {
            fresh.close()
        }
    }

    @Test
    fun `recovery re-confirms the ready MARKER and the queue parent namespace`() {
        // Recovery must re-establish the SAME barriers publication relies on. A mutant that omits the
        // ready marker (or the parent namespace) survived the earlier recovery tests, because those
        // only made the whole FileSync throw - which any subset of barriers still triggers. This test
        // makes each barrier fail IN ISOLATION, so a missing barrier is unobservable no longer.
        val id = java.util.UUID.randomUUID().toString()

        // (a) marker-content barrier: every other file syncs fine, only the marker throws.
        run {
            val root = tmp.newFolder("queue-marker-barrier")
            ContributionStore(root, limits()).prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L)
            val onlyMarkerFails = FileSync { file ->
                if (file.name.endsWith(".ready")) throw java.io.IOException("simulated marker sync failure")
            }
            val fresh = ContributionStore(root, limits(), fileSync = onlyMarkerFails)
            assertTrue(
                "recovery must re-confirm the ready MARKER's contents; omitting it exposes an unconfirmed entry",
                fresh.recover().none { it.captureId == id },
            )
        }

        // (b) parent-namespace barrier: files and the entry directory sync fine, only the parent throws.
        run {
            val root = tmp.newFolder("queue-parent-barrier")
            ContributionStore(root, limits()).prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L)
            val entriesDir = File(root, "contributions")
            val onlyParentFails = object : DirectorySync {
                override fun sync(directory: File) {
                    if (directory.absolutePath == root.absolutePath) {
                        throw java.io.IOException("simulated parent namespace failure")
                    }
                }
            }
            val fresh = ContributionStore(root, limits(), directorySync = onlyParentFails)
            assertTrue(
                "recovery must re-confirm the queue parent namespace at [${entriesDir.absolutePath}]",
                fresh.recover().none { it.captureId == id },
            )
        }
    }

    @Test
    fun `a FRESH unrecovered store still purges retained captures from disk`() {
        // REVIEWER ec6ce881. A policy-disable observed at STARTUP runs against a store that has seen
        // nothing: empty memoryEntries, empty ownedCharges. Purge must therefore discover what to remove by
        // LISTING THE DIRECTORY, not by reading its own index. When discovery rode on the deleted byte
        // measurement, a fresh store purged ZERO captures, reported success, set recovered, and left the
        // user's retained audio on disk after they withdrew sharing - to be resurrected by the next
        // healthy store.
||||||| /tmp/mfb
        // Reviewer ec6ce881. A policy-disable observed at startup runs against a store that has seen
        // Nothing: empty memoryEntries, empty ownedCharges. Purge must therefore discover what to remove by
        // Listing the directory, not by reading its own index. When discovery rode on the deleted byte
        // Measurement, a fresh store purged ZERO captures, reported success, set recovered, and left the
        // User's retained audio on disk after they withdrew sharing - to be resurrected by the next
        // Healthy store.
        val root = tmp.newFolder("queue-fresh-purge")
        val ids = (1..2).map { java.util.UUID.randomUUID().toString() }
        val seeder = ContributionStore(root, limits())
        try {
            for (id in ids) {
                assertTrue("precondition: seed $id", seeder.prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)
            }
        } finally {
            seeder.close()
        }
        for (id in ids) {
            assertTrue("precondition: audio on disk before purge", File(root, "contributions/$id.recording.wav").isFile)
        }

        // A FRESH store that never recovers. Deliberately no recover() call here.
        val fresh = ContributionStore(root, limits())
        try {
            fresh.revokeAndPurge("account_changed")
        } finally {
            fresh.close()
        }

        for (id in ids) {
            assertFalse(
                "a fresh, unrecovered store must still remove retained audio it never admitted",
                File(root, "contributions/$id.recording.wav").exists(),
            )
            assertFalse(
                "the metadata sidecar must go too",
                File(root, "contributions/$id.metadata.json").exists(),
            )
        }
    }

    @Test
    fun `a fresh unrecovered purge removes sidecar-only debris as well as audio`() {
        // The purge enumerates EVERY entry suffix, wider than the capacity count on purpose: capacity
        // protects AUDIO, so orphan sidecars do not consume a slot, but a withdrawal must not leave them
        // behind either (spec 9e6a2081: remove every capture with audio OR sidecars on disk).
        val root = tmp.newFolder("queue-fresh-purge-sidecars")
        val dir = File(root, "contributions")
        dir.mkdirs()
        val audioId = java.util.UUID.randomUUID().toString()
        val sidecarId = java.util.UUID.randomUUID().toString()
        File(dir, "$audioId.recording.wav").writeBytes(ByteArray(64))
        File(dir, "$audioId.metadata.json").writeText("{}")
        File(dir, "$sidecarId.metadata.json").writeText("{}")
        File(dir, "$sidecarId.sha256").writeText("0".repeat(64))

        val fresh = ContributionStore(root, limits())
        try {
            fresh.revokeAndPurge("account_changed")
        } finally {
            fresh.close()
        }

        assertFalse("audio must be purged", File(dir, "$audioId.recording.wav").exists())
        assertFalse("sidecar-only debris must be purged too", File(dir, "$sidecarId.metadata.json").exists())
        assertFalse("sidecar-only debris must be purged too", File(dir, "$sidecarId.sha256").exists())
    }

    @Test
    fun `a purge whose listing fails defers instead of reporting the queue gone, then completes`() {
        // REVIEWER f7700432. The new disk discovery read `listFiles() ... ?: emptySet()`, so an UNREADABLE
        // directory looked like a PROVEN EMPTY one: revokeAndPurge recorded a completed purge and set
        // recovered=true while every retained recording sat untouched on disk. That is the same false
        // success as ec6ce881, reached by a different path - the user is told their audio was removed
        // while it is still there. An unknown listing must DEFER the purge and keep its intent, never
        // report completion.
||||||| /tmp/mfb
        // Reviewer f7700432. The new disk discovery read `listFiles()... ?: emptySet()`, so an unreadable
        // Directory looked like a proven empty one: revokeAndPurge recorded a completed purge and set
        // Recovered=true while every retained recording sat untouched on disk. That is the same false
        // success, reached by a different path - the user is told their audio was removed while it is
        // still there. An unknown listing must defer the purge and keep its intent, never report
        // completion.
        val root = tmp.newFolder("queue-purge-unknown-listing")
        val dir = File(root, "contributions")
        dir.mkdirs()
        val id = java.util.UUID.randomUUID().toString()
        File(dir, "$id.recording.wav").writeBytes(ByteArray(2048))
        File(dir, "$id.metadata.json").writeText("{}")

        val perms = java.nio.file.Files.getPosixFilePermissions(dir.toPath())
        java.nio.file.Files.setPosixFilePermissions(
            dir.toPath(),
            setOf(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),
        )
        try {
            val store = ContributionStore(root, limits())
            try {
                store.revokeAndPurge("account_changed")

                assertTrue(
                    "an unreadable listing cannot be claimed as a completed purge",
                    store.purgeDeferred,
                )
                assertTrue(
                    "the incomplete purge must be reported, not silent",
                    store.purgeResidue.contains(ContributionStore.PURGE_RESIDUE_UNKNOWN_LISTING),
                )
                assertEquals(
                    "an incomplete purge must be counted",
                    1L,
                    store.terminalCounter(ContributionStore.TerminalReason.PURGE_DEFERRED_UNKNOWN_LISTING),
                )
            } finally {
                store.close()
            }
        } finally {
            // LIFT THE FAULT.
            java.nio.file.Files.setPosixFilePermissions(dir.toPath(), perms)
        }
        assertTrue("precondition: the audio survived the deferred purge", File(dir, "$id.recording.wav").isFile)

        // RETRY AT THE NEXT OPPORTUNITY: with the directory readable again, a purge must find and remove
        // what the deferred one could not enumerate. Nothing here is re-admitted or made servable.
        val retry = ContributionStore(root, limits())
        try {
            retry.revokeAndPurge("account_changed")
            assertFalse("the retried purge must not defer", retry.purgeDeferred)
        } finally {
            retry.close()
        }

        assertFalse(
            "once the listing succeeds, the retained capture must actually be purged",
            File(dir, "$id.recording.wav").exists(),
        )
        assertFalse("its sidecar must go too", File(dir, "$id.metadata.json").exists())
    }

    @Test
    fun `a deferred purge is finished by recovery after reconstruction with no second purge request`() {
        // REVIEWER c764bea2 finding 1. My first version of the deferral only set MEMORY fields, so the
        // "retry" in that control was actually a fresh explicit revokeAndPurge call - it would have passed
        // even if the intent had been forgotten entirely. The intent must be DERIVED from state that
        // already survives process death: the durable withdrawal marker (spec 777c18d5). A stop whose purge
        // could not enumerate the directory is still a stop, and the next ordinary recovery must finish it
        // BEFORE anything can be validated or served.
        val root = tmp.newFolder("queue-purge-durable-intent")
        val dir = File(root, "contributions")
        dir.mkdirs()
        val id = java.util.UUID.randomUUID().toString()
        File(dir, "$id.recording.wav").writeBytes(ByteArray(2048))
        File(dir, "$id.metadata.json").writeText("{}")

        // The durable marker is the ONLY thing carried across the process boundary.
        var stopped = true
        val intent = LocalWithdrawalIntentStore(
            object : WithdrawalIntentPersistence {
                override fun read(): Boolean = stopped

                override fun write(requested: Boolean): Boolean {
                    stopped = requested
                    return true
                }
            },
        )

        val perms = java.nio.file.Files.getPosixFilePermissions(dir.toPath())
        java.nio.file.Files.setPosixFilePermissions(
            dir.toPath(),
            setOf(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),
        )
        try {
            val first = ContributionStore(root, limits(), withdrawalIntent = intent)
            try {
                first.revokeAndPurge("account_changed")
                assertTrue("precondition: the first purge deferred", first.purgeDeferred)
            } finally {
                first.close()
            }
        } finally {
            java.nio.file.Files.setPosixFilePermissions(dir.toPath(), perms)
        }

        // RECONSTRUCTION + ORDINARY RECOVERY. Deliberately NO revokeAndPurge call: if the intent were only
        // in memory, this would leave the audio on disk and promote it, exactly like the original defect.
        val rebuilt = ContributionStore(root, limits(), withdrawalIntent = intent)
        try {
            val served = rebuilt.recover()
            assertTrue("a stop must serve nothing", served.isEmpty())
            assertTrue("nothing may be exposed as ready", rebuilt.readyEntries().isEmpty())
        } finally {
            rebuilt.close()
        }

        assertFalse(
            "recovery must finish a deferred purge from the durable intent alone",
            File(dir, "$id.recording.wav").exists(),
        )
        assertFalse("its sidecar must go too", File(dir, "$id.metadata.json").exists())
    }

    @Test
    fun `a same-instance retry clears the incomplete purge indicators only on confirmed completion`() {
        // REVIEWER c764bea2 finding 2. A successful retry on the SAME store never cleared `purgeDeferred` or
        // the unknown-listing residue, so a store that had finished its purge still reported itself
        // incomplete. The new-instance retry in the previous control hid this because it observed a
        // different object.
        val root = tmp.newFolder("queue-purge-same-instance")
        val dir = File(root, "contributions")
        dir.mkdirs()
        val id = java.util.UUID.randomUUID().toString()
        File(dir, "$id.recording.wav").writeBytes(ByteArray(1024))
        File(dir, "$id.metadata.json").writeText("{}")

        val store = ContributionStore(root, limits())
        try {
            val perms = java.nio.file.Files.getPosixFilePermissions(dir.toPath())
            java.nio.file.Files.setPosixFilePermissions(
                dir.toPath(),
                setOf(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),
            )
            try {
                store.revokeAndPurge("account_changed")
                assertTrue("precondition: deferred", store.purgeDeferred)
                assertTrue(
                    "precondition: incomplete is reported",
                    store.purgeResidue.contains(ContributionStore.PURGE_RESIDUE_UNKNOWN_LISTING),
                )
            } finally {
                java.nio.file.Files.setPosixFilePermissions(dir.toPath(), perms)
            }

            // SAME INSTANCE, fault lifted.
            store.revokeAndPurge("account_changed")

            assertFalse(
                "a completed purge must stop reporting itself deferred",
                store.purgeDeferred,
            )
            assertFalse(
                "the incomplete sentinel must clear once completion is confirmed",
                store.purgeResidue.contains(ContributionStore.PURGE_RESIDUE_UNKNOWN_LISTING),
            )
        } finally {
            store.close()
        }
        assertFalse("the recording must actually be gone", File(dir, "$id.recording.wav").exists())
    }

    @Test
    fun `an ordinary drain finishes a deferred withdrawal purge with no second explicit purge`() {
        // REVIEWER 20f71675 finding 1. The retry only existed in recover(), which production runs ONCE per
        // process by design, so a deferred withdrawal purge had no same-process retry: lifting the fault and
        // receiving ordinary drains left the recordings in place until a restart. A stop must not depend on
        // a restart. finishPendingPurgeIfStopped() is the purge-only drain opportunity, consulted before any
        // expiry or polling and running no recovery sweep.
        val root = tmp.newFolder("queue-drain-purge")
        val dir = File(root, "contributions")
        dir.mkdirs()
        val id = java.util.UUID.randomUUID().toString()
        File(dir, "$id.recording.wav").writeBytes(ByteArray(2048))
        File(dir, "$id.metadata.json").writeText("{}")

        var stopped = true
        val intent = LocalWithdrawalIntentStore(
            object : WithdrawalIntentPersistence {
                override fun read(): Boolean = stopped

                override fun write(requested: Boolean): Boolean {
                    stopped = requested
                    return true
                }
            },
        )
        val store = ContributionStore(root, limits(), withdrawalIntent = intent)
        try {
            val perms = java.nio.file.Files.getPosixFilePermissions(dir.toPath())
            java.nio.file.Files.setPosixFilePermissions(
                dir.toPath(),
                setOf(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),
            )
            try {
                store.revokeAndPurge("account_changed")
                assertTrue("precondition: the purge deferred", store.purgeDeferred)
            } finally {
                java.nio.file.Files.setPosixFilePermissions(dir.toPath(), perms)
            }

            // THE ORDINARY DRAIN OPPORTUNITY, same instance, fault lifted. No explicit purge call.
            assertTrue("a pending stop must not serve this pass", store.finishPendingPurgeIfStopped())

            assertFalse("the drain must finish the purge", File(dir, "$id.recording.wav").exists())
            assertFalse("and stop reporting itself deferred", store.purgeDeferred)

            // ...and an ordinary pass with nothing pending is a no-op that does not serve.
            // A PERSISTED STOP ALWAYS BLOCKS THE PASS (spec 6731209b): whether this drain finished the
            // purge is now only a log detail. While stop=true the drain never serves, full stop.
            assertTrue("a persisted stop must always block the pass", store.finishPendingPurgeIfStopped())

            // ...and the store that is NOT stopped lets the drain proceed, which is the other half of the
            // contract and what the module relies on for ordinary passes.
            stopped = false
            assertFalse("a store with no persisted stop must let the drain proceed", store.finishPendingPurgeIfStopped())
        } finally {
            store.close()
        }
    }

    @Test
    fun `a purge with surviving deletes keeps the pending state until they are gone`() {
        // REVIEWER 20f71675 finding 2. Completion was inferred from ATTEMPTING every delete. A readable
        // listing whose deletes FAIL left residue, yet the store cleared purgeDeferred, dropped the
        // unknown-listing sentinel and recorded QUEUE_PURGED - reporting the audio gone while it was all
        // still there. Completion must be reconciled against the listing: readable AND empty.
        val root = tmp.newFolder("queue-purge-partial")
        val dir = File(root, "contributions")
        dir.mkdirs()
        val id = java.util.UUID.randomUUID().toString()
        // A DIRECTORY where the audio file should be: deleteAll cannot remove a non-empty directory with a
        // plain file delete, so this is a real, readable-listing delete failure.
        val stubborn = File(dir, "$id.recording.wav")
        stubborn.mkdirs()
        File(stubborn, "held.bin").writeBytes(ByteArray(512))
        File(dir, "$id.metadata.json").writeText("{}")

        val store = ContributionStore(root, limits())
        try {
            store.revokeAndPurge("account_changed")

            assertTrue(
                "a purge that could not remove everything is still pending",
                store.purgeDeferred,
            )
            assertTrue(
                "the surviving capture must be reported, not assumed away",
                store.purgeResidue.contains(id),
            )
            // COMPLETION EVIDENCE MUST NOT EXIST FOR A FAILED PURGE (reviewer 04207559 finding 2). These
            // records say "this purge happened"; emitting them with survivors writes the false success
            // into the one file that outlives the counters it is meant to explain.
            assertFalse("no completed purge may be recorded", store.purgeReasons.isNotEmpty())
            assertEquals(
                "no QUEUE_PURGED counter may be emitted",
                0L,
                store.terminalCounter(ContributionStore.TerminalReason.QUEUE_PURGED),
            )

            // Successful cleanup on the SAME instance: once the obstruction is gone, the next purge
            // reconciles a readable, empty listing and only then clears the pending state.
            File(stubborn, "held.bin").delete()
            assertTrue("precondition: the obstruction is removable now", stubborn.delete() || !stubborn.exists())

            store.revokeAndPurge("account_changed")
            assertFalse("confirmed completion must clear the pending state", store.purgeDeferred)
            assertFalse(
                "and the capture must no longer be reported as residue",
                store.purgeResidue.contains(id),
            )
            // ...and ONLY NOW is completion evidence emitted: one completed purge, one counter.
            assertEquals(
                "confirmed completion records exactly one purge",
                1,
                store.purgeReasons.size,
            )
            // QUEUE_PURGED is deliberately NOT asserted here: it is dropped right before the counter file
            // is deleted, so it is not readable from terminalCounter() after a successful purge. Its
            // absence for a FAILED purge (asserted above) is the property that matters.
            assertTrue("the completed purge is recorded cumulatively", store.purgeReasons.isNotEmpty())
        } finally {
            store.close()
        }
    }

    @Test
    fun `a HELD entry is still OWNED and is purged on revocation`() {
        // Ownership is about "this owner created these files", not "this owner can read them back".
        // A held entry was removed from the index so it could not be served, and that silently removed it
        // from the purge accounting too, while revokeAndPurge cleared the held set BEFORE collecting the
        // ids it would delete. Result: a user asking for everything to be removed kept their audio. The
        // hold that remains since spec bfe91857 is an UNRESOLVED ATTEMPTS INTENT (an incomplete entry is
        // the only other one), so that is the state this pins - and it is a real hold, not a synthetic one.
        val root = tmp.newFolder("queue-held-purge")
        val id = java.util.UUID.randomUUID().toString()
        val seeder = ContributionStore(root, limits())
        assertTrue("precondition: prepare", seeder.prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)
        seeder.close()

        // SPEC 1e47eba8: an unresolved bookkeeping record is no longer a hold (unknown is due), so it can
        // no longer create the state this control needs. An incomplete markerless pair still is a real
        // hold - and it is the state most likely to hold a user's audio on the device - so it is used here.
||||||| /tmp/mfb
        // SPEC 1e47eba8: an unresolved bookkeeping record is no longer a hold (unknown is due), so it can
        // No longer create the state this control needs. An incomplete markerless pair still is a real
        // Hold - and it is the state most likely to hold a user's audio on the device - so it is used here.
        File(root, "contributions/$id.ready").delete()
        File(root, "contributions/$id.attempts").delete()
        val store = ContributionStore(root, limits())
        assertTrue(
            "precondition: the incomplete pair is held, so the entry must not be served",
            store.recover().none { it.captureId == id },
        )
        assertTrue("held, not ready", store.readyEntries().none { it.captureId == id })
        assertTrue("files preserved while held", File(root, "contributions/$id.recording.wav").isFile)
        assertTrue(
            "every hold must be counted, never silent",
            store.terminalCounter(ContributionStore.TerminalReason.RECOVERY_HELD) >= 1,
        )

        store.revokeAndPurge("account_changed")

        // EVERY owned artifact must be gone, not only the readable ones.
        val survivors = File(root, "contributions").listFiles()?.filter { it.name.startsWith(id) } ?: emptyList()
        assertTrue("purge left owned files behind: ${survivors.map { it.name }}", survivors.isEmpty())

        // And no resurrection: a fresh healthy store must not bring it back.
        store.close()
        val fresh = ContributionStore(root, limits())
        assertEquals("a fresh store resurrected a purged entry", emptyList<String>(), fresh.recover().map { it.captureId })
        assertEquals("nothing may be ready after purge", emptyList<String>(), fresh.readyEntries().map { it.captureId })
    }

    @Test
    fun `an uncertain prepare that never reached the index is still owned and purgable`() {
        // The same hole on the OTHER path: prepare throws PublicationUncertain before inserting into
        // memoryEntries. Without recording ownership at the throw site the files exist on disk but belong
        // to nobody, so revocation cannot find them.
        val root = tmp.newFolder("queue-uncertain-throw-purge")
        val id = java.util.UUID.randomUUID().toString()
        val failingFileSync = FileSync { file ->
            if (file.name.endsWith(".ready")) throw java.io.IOException("simulated marker sync failure")
        }
        val store = ContributionStore(root, limits(), fileSync = failingFileSync)
        val outcome = runCatching { store.prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) }
        assertTrue(
            "precondition: prepare must be uncertain, got ${outcome.exceptionOrNull()}",
            outcome.exceptionOrNull() is PublicationUncertain,
        )
        assertTrue(
            "precondition: the marker may exist on disk",
            File(root, "contributions").listFiles()?.any { it.name.startsWith(id) } == true,
        )

        store.revokeAndPurge("account_changed")

        val survivors = File(root, "contributions").listFiles()?.filter { it.name.startsWith(id) } ?: emptyList()
        assertTrue(
            "an uncertain prepare that never entered the index must still be purged, left ${survivors.map { it.name }}",
            survivors.isEmpty(),
        )
    }

    @Test
    fun `a FRESH store charges entries it just FAILED to confirm`() {
        // The branch the reviewer found still open after the restructure: on a fresh store `self` is empty,
        // so charging only inside the self loop left unconfirmable on-disk entries charged NOWHERE. A fresh
        // store must charge every validated entry before it branches on confirmation.
        val root = tmp.newFolder("queue-fresh-withheld")
        val id = java.util.UUID.randomUUID().toString()
        ContributionStore(root, limits()).prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L)
        val entryCharge = File(root, "contributions").listFiles()!!
            .filter { it.name.startsWith(id) && (it.name.endsWith(".recording.wav") || it.name.endsWith(".metadata.json")) }
            .sumOf { it.length() }
        assertTrue("precondition: charge is known ($entryCharge)", entryCharge > 0)

        // A FRESH store whose confirmation FAILS must still charge the entry.
        val failing = object : DirectorySync {
            override fun sync(directory: File) {
                throw java.io.IOException("simulated confirmation failure")
            }
        }
        val probeMeta = meta(java.util.UUID.randomUUID().toString())
        val probeSamples = 256L
        val probeBytes = ContributionStore(root, limits()).projectedBytesFor(probeSamples, probeMeta.size)
        assertTrue("precondition: probe smaller than the entry", probeBytes < entryCharge)

        val fresh = ContributionStore(root, limits().copy(maxQueueBytes = entryCharge), directorySync = failing, fileSync = FileSync { })
        assertTrue("precondition: the fresh store cannot confirm the entry", fresh.recover().isEmpty())
        assertTrue(
            "a fresh store must charge an entry it FAILED to confirm: a $probeBytes-byte probe was admitted " +
                "over a $entryCharge-byte budget",
            fresh.admit(sampleCount = probeSamples, metadataBytes = probeMeta.size) == null,
        )
    }

    @Test
    fun `expiry releases the owner charge and restores capacity`() {
        // Charging and releasing must be symmetric. An expired entry used to keep consuming capacity
        // forever; now capacity is a RECORD count, so an expired entry must free its record slot.
        val root = tmp.newFolder("queue-expiry-capacity")
        val id = java.util.UUID.randomUUID().toString()
        var now = 1_000_000L
        val store = ContributionStore(
            root,
            limits().copy(maxQueueRecords = 1, maxQueueAgeSeconds = 60),
            clock = { now },
        )
        assertTrue("precondition: prepare", store.prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)
        assertEquals("precondition: the single record slot is occupied", store.recordLimit(), store.ownedRecordsForTest())

        // Advance well past the age bound so the entry is genuinely expired.
        now += 120_000L
        val expired = store.expireOlderThan()
        assertEquals("the entry expires", listOf(id), expired)
        assertTrue("its files are gone", store.readyEntries().isEmpty())

        assertTrue(
            "an expired entry must release its charge: the queue still counts capacity it no longer uses",
            store.admit(sampleCount = 1600L, metadataBytes = 512) != null,
        )
    }

    @Test
    fun `a store that ADOPTS entries from disk charges them`() {
        // reloadUnownedFromDisk is a production entry path: an instance that did not prepare an entry can
        // still adopt it from disk. Adopting without charging means the entry occupies the queue while
        // accounting says the queue is empty, so new work is admitted on top of it.
        val root = tmp.newFolder("queue-reload-charge")
        val id = java.util.UUID.randomUUID().toString()
        val seeder = ContributionStore(root, limits())
        seeder.prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L)
        seeder.close()

        // A single-record budget: the adopted entry must occupy it, so a new admission is refused.
        val adopter = ContributionStore(root, limits().copy(maxQueueRecords = 1))
        adopter.expireOlderThan() // triggers reloadUnownedFromDisk; nothing is old enough to expire
        assertEquals(
            "a store that adopted from disk must charge what it adopted: the adopted record fills the single slot",
            adopter.recordLimit(),
            adopter.ownedRecordsForTest(),
        )
        assertTrue(
            "a store that adopted from disk must charge what it adopted: a new admission was granted over the adopted record",
            adopter.admit(sampleCount = 1600L, metadataBytes = 512) == null,
        )
    }

    @Test
    fun `a failed deletion keeps the bytes OWNED and CHARGED until cleanup succeeds`() {
        // deleteLocked used to ignore File.delete() results and then drop the charge. Now capacity is a
        // RECORD count: a deletion that leaves the audio on disk must keep its record charged until the
        // residue is actually gone.
        val root = tmp.newFolder("queue-failed-delete")
        val id = java.util.UUID.randomUUID().toString()
        var now = 1_000_000L
        val store = ContributionStore(
            root,
            limits().copy(maxQueueAgeSeconds = 60, maxQueueRecords = 1),
            clock = { now },
        )
        val deletingStore = store
        assertTrue("precondition: prepare", store.prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)

        // Make the audio file undeletable by replacing it with a non-empty DIRECTORY of the same name:
        // File.delete() then fails on every platform without needing permission tricks.
        val wav = File(root, "contributions/$id.recording.wav")
        val audio = wav.readBytes()
        wav.delete()
        wav.mkdirs()
        File(wav, "held").writeBytes(audio)
        assertTrue("precondition: the audio name is now a non-empty directory", wav.isDirectory)

        now += 120_000L
        val expired = store.expireOlderThan()
        assertEquals("the entry still expires from the index", listOf(id), expired)

        // Residual files exist, so they must remain charged ON THE INSTANCE THAT ATTEMPTED THE DELETE.
        assertTrue("precondition: the deleting instance keeps the residue charged", deletingStore.isChargedForTest(id))
        assertTrue(
            "residual files from a FAILED deletion must keep the deleting store's record slot occupied",
            deletingStore.admit(sampleCount = 1600L, metadataBytes = 512) == null,
        )

        // A restarted store re-derives the charge from the residue still on disk. It can only take the
        // directory after the deleting instance releases it (one live owner per queue).
        deletingStore.close()
        val tight = ContributionStore(root, limits().copy(maxQueueRecords = 1), clock = { now })
        tight.recover()
        assertTrue("precondition: the restarted store re-derives the residue charge", tight.isChargedForTest(id))
        assertTrue(
            "residual files from a FAILED deletion must stay charged on a RESTARTED store",
            tight.admit(sampleCount = 1600L, metadataBytes = 512) == null,
        )

        // Cleanup then succeeds and capacity is released.
        File(wav, "held").delete()
        wav.delete()
        assertTrue("precondition: the audio is finally gone", !wav.exists())
        tight.close()
        val after = ContributionStore(root, limits().copy(maxQueueRecords = 1))
        after.recover()
        assertTrue(
            "after a successful retry the capacity must be released",
            after.admit(sampleCount = 1600L, metadataBytes = 512) != null,
        )
    }

    @Test
    fun `a failed delete of ONE valid file still deletes the rest and never restores the entry`() {
        // The reviewed defect: `.all { delete }` SHORT-CIRCUITS. If the WAV delete failed, the metadata
        // and ready marker were never attempted, leaving a complete VALID entry that a restart would
        // recover as ready - the audio the user asked to delete would come back.
        // This uses a SCHEDULED failure with regular files (not a directory), which is what the reviewer
        // asked for: only the FIRST delete attempt on the audio file fails, everything else is ordinary.
        val root = tmp.newFolder("queue-failed-delete-valid")
        val id = java.util.UUID.randomUUID().toString()
        var now = 1_000_000L
        val store = ContributionStore(root, limits().copy(maxQueueAgeSeconds = 60), clock = { now })
        assertTrue("precondition: prepare", store.prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)

        val wav = File(root, "contributions/$id.recording.wav")
        assertTrue("precondition: audio is a regular file", wav.isFile)

        val contributions = File(root, "contributions")
        val writableBefore = contributions.canWrite()
        contributions.setWritable(false)
        assertTrue("precondition: the queue directory is now read-only", writableBefore && !contributions.canWrite())

        now += 120_000L
        val expired = store.expireOlderThan()
        assertEquals("the entry still leaves the index", listOf(id), expired)

        // Whatever survived, the entry must not be served and must stay accounted for.
        store.close()
        val restarted = ContributionStore(root, limits().copy(maxQueueAgeSeconds = 60), clock = { now })
        val recovered = restarted.recover()
        assertTrue(
            "a restart must NOT recover an entry whose audio survived a failed delete",
            recovered.none { it.captureId == id },
        )

        val stillThere = wav.exists()
        restarted.close()
        var squeezed: ContributionStore? = null
        if (stillThere) {
            squeezed = ContributionStore(root, limits().copy(maxQueueRecords = 1), clock = { now })
            squeezed.recover()
            assertTrue("the surviving audio must stay charged after a failed delete", squeezed.isChargedForTest(id))
            assertTrue(
                "surviving files must stay charged after a failed delete",
                squeezed.admit(sampleCount = 256L, metadataBytes = 512) == null,
            )
        }

        // Cleanup succeeds once the directory is writable again, and capacity returns.
        contributions.setWritable(true)
        squeezed?.close()
        val cleanup = ContributionStore(root, limits().copy(maxQueueAgeSeconds = 60), clock = { now })
        cleanup.expireOlderThan()
        assertTrue("precondition: audio finally removed", !wav.exists())
        cleanup.close()
        val after = ContributionStore(root, limits().copy(maxQueueRecords = 1), clock = { now })
        after.recover()
        assertTrue(
            "after successful cleanup the capacity must be released",
            after.admit(sampleCount = 256L, metadataBytes = 512) != null,
        )
    }

    @Test
    fun `residue claimed file by file claims ONE record, not one per file`() {
        // The debris sweep visits files INDIVIDUALLY: it can claim the audio first and the metadata later.
        // A per-file transition would claim one record PER FILE; the queue must claim ONE record per
        // capture, whatever files of it survived.
        val root = tmp.newFolder("queue-footprint")
        val id = java.util.UUID.randomUUID().toString()
        val seeder = ContributionStore(root, limits())
        seeder.prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L)
        seeder.close()

        val contributions = File(root, "contributions")
        val wav = File(contributions, "$id.recording.wav")
        val metaFile = File(contributions, "$id.metadata.json")
        assertTrue("precondition: both files have real size", wav.length() > 0 && metaFile.length() > 0)

        // Remove the ready marker so both files become unmarked debris, then make BOTH undeletable by
        // turning them into non-empty directories (which also fails the individual unlink).
        File(contributions, "$id.ready").delete()
        listOf(wav, metaFile).forEach { file ->
            val bytes = file.readBytes()
            file.delete()
            file.mkdirs()
            File(file, "held").writeBytes(bytes)
        }

        val narrowed = ContributionStore(root, limits())
        narrowed.recover()
        assertEquals(
            "the capture must be claimed as ONE record, not one per surviving file (owned ids: ${narrowed.ownedIdsForTest()})",
            setOf(id),
            narrowed.ownedIdsForTest(),
        )
    }

    @Test
    fun `a purge whose deletes fail reports residue instead of claiming the audio is gone`() {
        // revokeAndPurge cleared the charge table and never looked at a delete result, so a purge that
        // failed to remove files reported success: the user is told their audio was deleted while it is
        // still on disk.
        val root = tmp.newFolder("queue-purge-residue")
        val id = java.util.UUID.randomUUID().toString()
        val store = ContributionStore(root, limits())
        assertTrue("precondition: prepare", store.prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)
        val wav = File(root, "contributions/$id.recording.wav")
        val audio = wav.readBytes()
        wav.delete()
        wav.mkdirs()
        File(wav, "held").writeBytes(audio)

        store.revokeAndPurge("account-change")

        assertTrue("the purge must report the capture whose bytes survived", store.purgeResidue.contains(id))
        assertTrue("the surviving bytes are still there", wav.exists())
        // Intent is durable, so a later healthy store must not serve it.
        // One live owner per queue (spec c8f766fb): the next owner takes over after release.
        store.close()
        val later = ContributionStore(root, limits())
        assertTrue(
            "a purged-but-surviving capture must never be served",
            later.recover().none { it.captureId == id },
        )

        // Once deletion is possible the retry releases it and the residue report clears.
        File(wav, "held").delete()
        wav.delete()
        val cleanup = ContributionStore(root, limits())
        cleanup.expireOlderThan()
        assertTrue("precondition: audio finally gone", !wav.exists())
        val after = ContributionStore(root, limits())
        after.recover()
        assertTrue("a disqualified capture is not served until cleanup completes", after.readyEntries().none { it.captureId == id })
    }

    @Test
    fun `NO destruction happens when disposal intent cannot be confirmed`() {
        // The reviewed defect: deleteLocked ignored writeDisqualificationLocked's result and destroyed the
        // files anyway. If intent cannot be recorded, deleting is worse than doing nothing: the files are
        // the only evidence the capture was meant to go, so a crash could resurrect a half-deleted entry.
        val root = tmp.newFolder("queue-intent-failure")
        val id = java.util.UUID.randomUUID().toString()
        var now = 1_000_000L
        val store = ContributionStore(
            root,
            limits().copy(maxQueueAgeSeconds = 60),
            clock = { now },
            // Fault ONLY the intent barrier; the queue itself is healthy.
            intentDirectorySyncOverride = object : DirectorySync {
                override fun sync(directory: File) {
                    throw java.io.IOException("simulated intent barrier failure")
                }
            },
        )
        assertTrue("precondition: prepare", store.prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)
        val wav = File(root, "contributions/$id.recording.wav")
        assertTrue("precondition: audio present", wav.isFile)

        now += 120_000L
        store.expireOlderThan()

        assertTrue("the audio must NOT be destroyed without confirmed intent", wav.isFile)
        assertTrue(
            "the capture must stay OWNED and charged when intent failed",
            store.admit(sampleCount = 1_000_000L, metadataBytes = 1024) == null,
        )
        // And the entry must not be served as ready.
        assertTrue("an unexpurgated capture must not be served", store.readyEntries().none { it.captureId == id })
    }

    @Test
    fun `a FAILED intent attempt is still remembered as disposal across later reads`() {
        // When the intent record cannot be confirmed, destruction is correctly withheld. But the intent
        // itself must not be LOST: the retry sweep only knows about captures that left a marker, so a
        // capture whose marker write failed is never retried and lingers as ordinary debris forever. The
        // user asked for it to go; the store must keep trying, not quietly forget.
        val root = tmp.newFolder("queue-intent-remembered")
        val id = java.util.UUID.randomUUID().toString()
        var blockIntent = true
        val faulting = object : DirectorySync {
            override fun sync(directory: File) {
                if (blockIntent) throw java.io.IOException("simulated intent barrier failure")
            }
        }
        val store = ContributionStore(root, limits(), intentDirectorySyncOverride = faulting)
        assertTrue("precondition: prepare", store.prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)

        store.revokeAndPurge("account_changed")
        val wav = File(root, "contributions/$id.recording.wav")
        assertTrue("precondition: nothing destroyed without confirmed intent", wav.isFile)

        // A FRESH store with a healthy barrier must still know this capture was meant to go.
        blockIntent = false
        // One live owner per queue (spec c8f766fb): the next owner takes over after release.
        store.close()
        val fresh = ContributionStore(root, limits())
        fresh.expireOlderThan()
        assertTrue(
            "a capture whose intent write failed must still be cleaned up later, not forgotten",
            !wav.exists(),
        )
    }

    @Test
    fun `a marker whose barrier failed is not treated as durable on retry`() {
        // marker.exists() was accepted as proof of durable intent. A marker written but never synced can
        // vanish on crash, so a retry must re-confirm rather than trust its presence.
        val root = tmp.newFolder("queue-marker-reconfirm")
        val id = java.util.UUID.randomUUID().toString()
        var failIntent = true
        val store = ContributionStore(
            root,
            limits(),
            intentDirectorySyncOverride = object : DirectorySync {
                override fun sync(directory: File) {
                    if (failIntent) throw java.io.IOException("simulated intent barrier failure")
                }
            },
        )
        assertTrue("precondition: prepare", store.prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)
        store.revokeAndPurge("account_changed")
        val wav = File(root, "contributions/$id.recording.wav")
        assertTrue("nothing was destroyed while intent could not be confirmed", wav.isFile)

        // Marker now present from a failed attempt. A HEALTHY store must not trust its presence: it has to
        // RE-RUN the barrier, and only then may it destroy. The test asserts the barrier actually executed,
        // not merely that the files eventually vanished - a store that deleted on presence alone would also
        // end with the files gone, so a final-state assertion proves nothing.
        failIntent = false
        var barrierRuns = 0
        // A restart implies the previous owner released the directory (spec c8f766fb).
        store.close()
        val healthy = ContributionStore(
            root,
            limits(),
            intentDirectorySyncOverride = object : DirectorySync {
                override fun sync(directory: File) {
                    barrierRuns++
                }
            },
        )
        healthy.expireOlderThan()
        assertTrue("the intent barrier must be RE-RUN before deletion, ran $barrierRuns times", barrierRuns > 0)
        assertTrue("cleanup must complete once the barrier is healthy", !wav.exists())
    }

    @Test
    fun `the disqualified directory's NAMING namespace must be confirmed on every attempt`() {
        // The directory-creation barrier was UNREACHABLE on the successful path: recording intent already
        // ran mkdirs(), so the "did it exist before?" sample was always true and the parent/root barrier was
        // skipped. An earlier failed creation barrier was likewise skipped just because the directory had
        // since become visible - existence treated as durability, one level up from the token shortcut.
        val root = tmp.newFolder("queue-namespace-barrier")
        val id = java.util.UUID.randomUUID().toString()
        // ONLY the root namespace barrier fails. The file/content barrier and the marker's own directory
        // barrier are healthy, so this isolates the naming-namespace confirmation.
        var blockNamespace = true
        val faulting = object : DirectorySync {
            override fun sync(directory: File) {
                if (blockNamespace && directory.canonicalFile == root.canonicalFile) {
                    throw java.io.IOException("simulated naming-namespace barrier failure")
                }
            }
        }
        val store = ContributionStore(root, limits(), intentDirectorySyncOverride = faulting)
        assertTrue("precondition: prepare", store.prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)
        assertTrue("precondition: no disqualified directory yet", !File(root, "disqualified").exists())

        val wav = File(root, "contributions/$id.recording.wav")
        store.revokeAndPurge("account_changed")
        assertTrue("precondition: nothing destroyed without a confirmed record", wav.isFile)
        assertTrue("the record was still made discoverable", File(root, "disqualified/$id").isFile)

        // REPEATED sweeps must not destroy anything: the namespace is still unconfirmed.
        repeat(2) { store.expireOlderThan() }
        assertTrue("repeated sweeps must not destroy with an unconfirmed namespace", wav.isFile)

        // And a FRESH owner, which never saw the earlier attempts, must also refuse.
        // One live owner per queue (spec c8f766fb): the next owner takes over after release.
        store.close()
        val fresh = ContributionStore(root, limits(), intentDirectorySyncOverride = faulting)
        fresh.recover()
        fresh.expireOlderThan()
        assertTrue("a fresh owner must not destroy with an unconfirmed namespace", wav.isFile)

        // Once the namespace is healthy the barrier must RUN and only then may deletion happen.
        blockNamespace = false
        var namespaceRuns = 0
        // One live owner per queue (spec c8f766fb): the next owner takes over after release.
        fresh.close()
        val healthy = ContributionStore(
            root,
            limits(),
            intentDirectorySyncOverride = object : DirectorySync {
                override fun sync(directory: File) {
                    if (directory.canonicalFile == root.canonicalFile) namespaceRuns++
                }
            },
        )
        healthy.expireOlderThan()
        assertTrue("the naming namespace must be barriered before deletion, ran $namespaceRuns", namespaceRuns > 0)
        assertTrue("cleanup completes once the namespace is confirmed", !wav.exists())
    }

    @Test
    fun `recovery whose invalid cleanup FAILS charges the full footprint of what survives`() {
        // M36 survived with no control reaching this branch, so the recovery failed-cleanup path was
        // charging through the old primary-files-only measurement unnoticed.
        val root = tmp.newFolder("queue-recovery-failed-cleanup")
        val id = java.util.UUID.randomUUID().toString()
        val store = ContributionStore(root, limits())
        assertTrue("precondition: prepare", store.prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)

        // Make the capture INVALID (recovery's invalid branch) and undeletable (cleanup fails), with an
        // extra unexpected subtree so a primary-files-only charge is measurably narrower.
        val contributions = File(root, "contributions")
        File(contributions, "$id.metadata.json").writeText("{ not json")
        contributions.listFiles()!!.filter { it.name.startsWith(id) }.forEach { file ->
            val bytes = file.readBytes()
            file.delete()
            file.mkdirs()
            File(file, "held").writeBytes(bytes)
        }
        val debris = File(contributions, "$id.sha256")
        File(debris, "extra").writeBytes(ByteArray(2048))

        // A restart implies the previous owner released the directory (spec c8f766fb).
        store.close()
        val fresh = ContributionStore(root, limits())
        fresh.recover() // invalid -> attempts cleanup -> every delete fails

        assertTrue("survivors of a failed cleanup must stay charged", fresh.isChargedForTest(id))
    }

    @Test
    fun `a part file that refuses to delete is charged, not silently forgotten`() {
        // The .part sweep discarded delete()'s result entirely, so a survivor was invisible to accounting.
        val root = tmp.newFolder("queue-part-delete-failure")
        val contributions = File(root, "contributions")
        contributions.mkdirs()
        // A non-empty directory cannot be deleted with File.delete(), which is a deterministic failure.
        val stuck = File(contributions, "gen\$x.3.someid.recording.wav.part")
        stuck.mkdirs()
        File(stuck, "held.bin").writeBytes(ByteArray(2048))

        val store = ContributionStore(root, limits())
        store.recover()

        assertTrue("precondition: the .part file survived", stuck.exists())
        assertTrue(
            "surviving .part bytes must be charged",
            store.isChargedForTest("gen\$x.3.someid.recording"),
        )
    }

    private fun java.io.File.writeBytesByteArray() = writeBytes(ByteArray(4))

    @Test
    fun `direct ADMISSION on a fresh instance over an overflowing ROOT is STILL GRANTED`() {
        // reviewer 92911de9: admission established marker coverage but not root coverage - a fresh store
        // over an existing overflowing contributions directory could admit without any bounded root scan.
||||||| /tmp/mfb
        // Reviewer 92911de9: admission established marker coverage but not root coverage - a fresh store
        // Over an existing overflowing contributions directory could admit without any bounded root scan.
        val root = tmp.newFolder("queue-admit-root-coverage")
        val contributions = File(root, "contributions")
        contributions.mkdirs()
        (0..512).forEach { i -> File(contributions, "debris-%04d.recording.wav".format(i)).writeBytes(ByteArray(4)) }

        val store = ContributionStore(root, limits())

        // priced, not refused, for the same reason as marker coverage.
        assertNotNull(
            "the bounded root scan must still report unknown coverage, but admission is GRANTED",
            store.admitForTestOrNull(1600, 32),
        )
    }

    @Test
    fun `direct ADMISSION on a fresh instance over overflowing markers is STILL GRANTED`() {
        // reviewer f4436ced: admission did not establish marker coverage, so a newly constructed store
        // over an existing overflowing marker directory could admit immediately - required precondition
        // under spec 7e9606b, pinned WITHOUT any recover/expiry call.
||||||| /tmp/mfb
        // Reviewer f4436ced: admission did not establish marker coverage, so a newly constructed store
        // Over an existing overflowing marker directory could admit immediately - required precondition
        // Under spec 7e9606b, pinned WITHOUT any recover/expiry call.
        val root = tmp.newFolder("queue-admit-coverage")
        val contributions = File(root, "contributions")
        contributions.mkdirs()
        // Existing debris in the queue dir and an overflowing marker directory: no recover() anywhere.
        File(contributions, "leftover.recording.wav").writeBytes(ByteArray(64))
        val markerDir = File(root, "disqualified")
        markerDir.mkdirs()
        File(markerDir, "some-capture.disqualified").writeBytes("disqualified".toByteArray())
        (0..512).forEach { i -> File(markerDir, "infl-%04d".format(i)).writeBytes(ByteArray(4)) }

        val store = ContributionStore(root, limits())

        // SPEC a7f6ce81 RULING: an unknown footprint must never stop uploads, and admission is where the
        // footprint would grow. It is PRICED conservatively (unmeasured bytes carry worst-case weight)
        // instead of refused, because refusing is self-sustaining: uploading is what lets it heal.
        assertNotNull(
            "admission must be GRANTED while marker coverage is unknown (priced, not refused)",
            store.admitForTestOrNull(1600, 32),
        )
    }

    @Test
    fun `a held handle still records an attempt through a root-scan failure`() {
        // SPEC a7f6ce81 RULING: this replaces the old "an attempt must be refused while ROOT discovery is
        // unknown" assertion. Attempt accounting is what keeps eligibility honest while the footprint
        // heals, and withholding it made the unknown state self-sustaining - the exact defect that froze
        // the owner's Pixel. Unknown coverage is still REPORTED; it just no longer stops the attempt.
        val root = tmp.newFolder("queue-attempt-root-hold")
        val contributions = File(root, "contributions")
        contributions.mkdirs()
        val id = "9b9b9b9b-1111-2222-3333-444444444444"
        val store = ContributionStore(root, limits())
        assertTrue(
            "precondition: prepared",
            store.prepareAt(0, store.admitForTest(1600, meta(id).size), id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null,
        )

        // Make the pending attempt-count intent genuinely unresolvable, which is the condition this control
        // is about. An over-large root no longer produces it - discovery has no traversal bound since spec
        // 2741a0ca - so the fixture creates the intent state directly: a `.tmp` intent present while the
        // names are NOT listable. Presence cannot be decided from a listing that failed, so resolution
        // reports "attempts intent presence unknown" rather than guessing absence.
||||||| /tmp/mfb
        // Is about. An over-large root no longer produces it - discovery has no traversal bound since spec
        // 2741a0ca - so the fixture creates the intent state directly: a `.tmp` intent present while the
        // Names are NOT listable. Presence cannot be decided from a listing that failed, so resolution
        // Reports "attempts intent presence unknown" rather than guessing absence.
        File(contributions, "$id.attempts.tmp").writeText("1")
        store.recover()
        // Make the root genuinely unlistable: replace the queue directory with a regular file. `list()`
        // then returns null, which is the exact state `visibleNamesLocked` reports as unknown.
        val parked = File(root, "contributions-parked")
        contributions.renameTo(parked)
        contributions.writeText("")

        // SPEC a7f6ce81 RULING: attempt accounting must NOT be blocked by the unknown coverage on its
        // own account. What still refuses here is a DIFFERENT and legitimate condition: the candidate
        // names cannot be listed, so whether a pending attempt-count intent exists is unknown, and
        // recording an increment over an unresolved intent would corrupt the count. That refusal is
        // about intent resolution, not about the footprint gate - and the message proves which.
        // CHANGED BY SPEC 2741a0ca CORRECTION 1. The old assertion named the exact refusal message
        // ("attempts intent presence unknown"), which no longer exists: unknown is due, not refused. The
        // point of the control survives and is asserted plainly - the store must fail only from a genuine
        // BOOKKEEPING failure, never from a footprint/coverage gate. Whatever happens, it must not be a
        // coverage hold.
        val refusal = runCatching { store.recordAttempt(0, id) }.exceptionOrNull()
        assertTrue(
            "any refusal must be a bookkeeping failure, never a footprint gate (got $refusal)",
            refusal == null || refusal.message?.contains("footprint") != true,
        )
    }

    @Test
    fun `a marker directory beyond its bound defers the disposal retry`() {
        // reviewer 3eec6e62: the disposal-marker directory still used eager markerDir.listFiles(), and
        // `?: return` treated ENUMERATION FAILURE as an empty directory - indistinguishable from genuine
        // absence, so a sweep over a partially-known marker set would silently skip intents it never saw.
||||||| /tmp/mfb
        // Reviewer 3eec6e62: the disposal-marker directory still used eager markerDir.listFiles(), and
        // `?: return` treated enumeration failure as an empty directory - indistinguishable from genuine
        // Absence, so a sweep over a partially-known marker set would silently skip intents it never saw.
        // Beyond the bound, the retry defers and records the degradation. It deliberately does NOT refuse
        // admission: unseen disposal intents are deletions, not retention (spec contract question pending
        // with @spec, reviewer c571c932).
        val root = tmp.newFolder("queue-marker-bound")
        val contributions = File(root, "contributions")
        contributions.mkdirs()
        val id = "5d5d5d5d-1111-2222-3333-444444444444"
        val store = ContributionStore(root, limits())
        assertTrue("precondition: prepared", store.prepareAt(0, store.admitForTest(1600, meta(id).size), id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)
        store.revokeAndPurge("test-disposal")
        val markerDir = File(root, "disqualified")
        assertTrue("precondition: an intent marker exists", markerDir.isDirectory)

        // Push the marker directory past ITS OWN enumeration bound with inert entries (the marker bound is
        // separate from the queue root's: one file per withdrawn capture, not ten per record).
        val extra = (0..1100).map { i ->
            File(markerDir, "infl-%04d".format(i)).apply { writeBytes("disqualified".toByteArray()) }
        }

        store.expireOlderThan()
        assertTrue(
            "the recorded intent must survive a deferred retry (marker=${File(markerDir, "$id.disqualified").exists()})",
            markerDir.listFiles()!!.any { it.name.startsWith(id) },
        )
        // (No charge assertion here: revokeAndPurge already released the audio's charge, so the marker in
        // this fixture is a pure deletion record. The survival of the MARKER is what a deferred retry owes.)

        // Removing the inflation restores a fully enumerable marker set; the retry proceeds.
        extra.forEach { it.delete() }
        store.expireOlderThan()
        assertTrue(
            "a fully enumerable marker set lets the retry complete (remaining=${markerDir.listFiles()!!.size})",
            markerDir.listFiles()!!.none { it.name.startsWith(id) },
        )
    }

    @Test
    fun `a failed recovery stops serving entries a successful recovery later restores`() {
        // reviewer d1cce25b: the failure path left memoryEntries intact and readyEntries() served them
        // without checking rootEnumerationUnknown, so a store that already held ready entries kept serving
        // them after recovery could not enumerate the root - and the empty-index test could not detect it.
||||||| /tmp/mfb
        // Reviewer d1cce25b: the failure path left memoryEntries intact and readyEntries() served them
        // Without checking rootEnumerationUnknown, so a store that already held ready entries kept serving
        // Them after recovery could not enumerate the root - and the empty-index test could not detect it.
        val root = tmp.newFolder("queue-failed-recovery-stops-serving")
        val contributions = File(root, "contributions")
        contributions.mkdirs()
        val id = "3c3c3c3c-1111-2222-3333-444444444444"
        val store = ContributionStore(root, limits())
        val prepared = store.prepareAt(0, store.admitForTest(1600, meta(id).size), id, "epoch-1", chunks(1600), meta(id), "a@x", 7L)
        assertTrue("precondition: prepared ready", prepared != null)
        assertTrue("precondition: served", store.readyEntries().isNotEmpty())

        // CHANGED BY SPEC 2741a0ca / reviewer a4a6b1. This used to push the root past its enumeration bound
        // and require the entry to STOP being served. That refusal was the device defect: an over-bound
        // directory served nothing, so nothing drained and the queue could never fall back under the bound.
||||||| /tmp/mfb
        // Changed by / reviewer a4a6b1. This used to push the root past its enumeration bound
        // And require the entry to STOP being served. That refusal was the device defect: an over-bound
        // Directory served nothing, so nothing drained and the queue could never fall back under the bound.
        // Recovery no longer consults a traversal bound for DISCOVERY at all, so an over-bound root now
        // serves normally - asserted here - and the sweep decision belongs to genuine unreadability, which
        // the sibling control `a queue root beyond its entry bound defers sweeps` covers.
        val debris = (0..512).map { i ->
            File(contributions, "gen\$f.%d.someid.recording.wav.part".format(i)).apply { writeBytes(ByteArray(4)) }
        }
        store.recover()
        assertTrue(
            "recovery never refuses to serve over a large root: nothing would ever drain",
            store.readyEntries().any { it.captureId == id },
        )

        // A later recovery still serves it.
        debris.forEach { it.delete() }
        store.recover()
        assertTrue(
            "successful recovery restores eligibility",
            store.readyEntries().any { it.captureId == id },
        )
    }

    @Test
    fun `expireOlderThan through the bounded reload cannot bypass the root bound`() {
        // reviewer d1cce25b: reloadUnownedFromDisk, discardCurrentPartials and deleteStaging still called
        // dir.listFiles() eagerly, and expireOlderThan invokes the reload while recovered is still false
        // after a failed recovery - bypassing the recovery bound immediately.
||||||| /tmp/mfb
        // Reviewer d1cce25b: reloadUnownedFromDisk, discardCurrentPartials and deleteStaging still called
        // Dir.listFiles() eagerly, and expireOlderThan invokes the reload while recovered is still false
        // After a failed recovery - bypassing the recovery bound immediately.
        val root = tmp.newFolder("queue-expire-bypass")
        val contributions = File(root, "contributions")
        contributions.mkdirs()
        val bound = ContributionStore(root, limits()).let { probe ->
            try {
                probe.listingEntryBoundForTest()
            } finally {
                probe.close()
            }
        }
        val debris = (0..bound).map { i ->
            File(contributions, "gen\$g.%d.someid.recording.wav.part".format(i)).apply { writeBytes(ByteArray(4)) }
        }

        val store = ContributionStore(root, limits())
        store.expireOlderThan()

        assertTrue(
            "and must not have swept debris it could not fully enumerate (survivors=" +
                debris.count { it.exists() } + ")",
            debris.all { it.exists() },
        )
    }

    @Test
    fun `a queue root beyond its entry bound defers sweeps`() {
        // reviewer c66fda06 series: recover() materialized the ENTIRE root with dir.listFiles(), unbounded -
        // the same eager-allocation defect already fixed inside the measurement walk. Beyond the bound,
        // recovery must refuse to discover rather than sweep a partially-known directory: a destructive
        // sweep with unknown coverage could delete entries it never enumerated, and nothing can be safely
        // served from a listing that was cut off.
        val root = tmp.newFolder("queue-root-bound")
        val contributions = File(root, "contributions")
        contributions.mkdirs()
        // Past the CURRENT bound: every .part here is debris the sweep WOULD delete if it ran.
        val bound = ContributionStore(root, limits()).let { probe ->
            try {
                probe.listingEntryBoundForTest()
            } finally {
                probe.close()
            }
        }
        val parts = (0..bound).map { i ->
            File(contributions, "gen\$b.%d.someid.recording.wav.part".format(i)).apply { writeBytes(ByteArray(8)) }
        }

        val store = ContributionStore(root, limits())
        store.recover()

        assertTrue(
            "the destructive sweep must NOT run on partially-known coverage (survivors=" +
                parts.count { it.exists() } + ")",
            parts.all { it.exists() },
        )

        // A later recovery with the obstruction removed (files deleted externally) must clear the mark.
        parts.forEach { it.delete() }
        store.recover()
    }

    @Test
    fun `a retry over an already-absent path RELEASES instead of poisoning admission`() {
        // reviewer 567c0b94: two callers can enumerate the same file before either enters the monitor. The
        // first deletes it; the second enters holding the stale entry, delete() returns false, and the stat
        // proves absence. Requiring delete()==true AND absence misclassified that as UNKNOWN and marked the
        // id permanently unmeasured, refusing admission over bytes that are provably gone.
||||||| /tmp/mfb
        // Reviewer 567c0b94: two callers can enumerate the same file before either enters the monitor. The
        // First deletes it; the second enters holding the stale entry, delete() returns false, and the stat
        // Proves absence. Requiring delete()==true and absence misclassified that as unknown and marked the
        // Id permanently unmeasured, refusing admission over bytes that are provably gone.
        val root = tmp.newFolder("queue-stale-retry")
        val contributions = File(root, "contributions")
        contributions.mkdirs()
        val stuck = File(contributions, "gen\$w.2.someid.recording.wav.part")
        stuck.mkdirs()
        File(stuck, "held.bin").writeBytes(ByteArray(1400))

        val store = ContributionStore(root, limits())
        store.recover()
        val id = "gen\$w.2.someid.recording"
        assertTrue("precondition: charged", store.isChargedForTest(id))

        // Pin the race the reviewer described: a caller ENUMERATES the file, then the file is removed by an
        // earlier/competing sweeper, THEN the caller enters the monitor with its stale entry. Enumeration
        // happens before the helper takes the lock, so holding the monitor while removing the file pins a
        // caller with a stale path deterministically.
        val monitor = store.ownerLockForTest()
        val done = java.util.concurrent.CountDownLatch(1)
        val worker = Thread {
            store.discardCurrentPartials()
            done.countDown()
        }
        synchronized(monitor) {
            worker.start()
            // Give the worker time to enumerate and block on the monitor we hold.
            Thread.sleep(300)
            File(stuck, "held.bin").delete()
            stuck.delete()
        }
        assertTrue("the retry sweep finished", done.await(5, java.util.concurrent.TimeUnit.SECONDS))

        assertTrue(
            "a provably absent path must release its charge",
            !store.isChargedForTest(id),
        )
    }

    @Test
    fun `an unreadable queue directory charges nothing away`() {
        // SCOPE, stated plainly: this proves that an unreadable queue directory never FREES a charge. It does
        // NOT reach the helper's UNKNOWN branch - with the directory unreadable, listFiles() returns null
        // and the sweep never enumerates a file at all (measured: listFiles=null). I could not construct a
        // path on this platform that reaches UNKNOWN through these entry points, so I am not claiming it.
        // The reviewer's concern - a failed delete freeing bytes on a false absence - is still covered for
        // every case that IS reachable: failed-delete-but-present keeps the charge.
        val root = tmp.newFolder("queue-uninspectable")
        val contributions = File(root, "contributions")
        contributions.mkdirs()
        // Must sit DIRECTLY in the queue directory: the sweep enumerates that directory only, and a nested
        // .part was never even reached, which made the charge precondition fail for the wrong reason.
        val parent = contributions
        val stuck = File(parent, "gen\$v.7.someid.recording.wav.part")
        stuck.mkdirs()
        File(stuck, "held.bin").writeBytes(ByteArray(1300))

        val store = ContributionStore(root, limits())
        store.recover()
        assertTrue("precondition: charged", store.isChargedForTest("gen\$v.7.someid.recording"))

        val stuckPath = stuck.toPath()
        val perms = java.nio.file.Files.getPosixFilePermissions(parent.toPath())
        // WRITE WITHOUT EXECUTE: directory entries can be modified but cannot be traversed, so stat of a
        // child fails with EACCES while the child is still listed. Dropping read alone left the stat
        // succeeding (traced: absence=PRESENT), so the UNKNOWN branch was still never reached.
        java.nio.file.Files.setPosixFilePermissions(
            parent.toPath(),
            setOf(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),
        )
        // Establish, in THIS jvm, that the permission change actually makes inspection impossible. Without
        // this the branch under test may simply be unreachable, which is how I wasted two attempts.
        val probe = try {
            java.nio.file.Files.readAttributes(
                stuck.toPath(),
                java.nio.file.attribute.BasicFileAttributes::class.java,
                java.nio.file.LinkOption.NOFOLLOW_LINKS,
            )
            "PRESENT"
        } catch (denied: java.nio.file.AccessDeniedException) {
            "UNKNOWN"
        } catch (missing: java.nio.file.NoSuchFileException) {
            "ABSENT"
        } catch (other: java.io.IOException) {
            "UNKNOWN"
        }
        try {
            store.discardCurrentPartials()
            assertEquals("precondition: this directory really is unreadable here", "UNKNOWN", probe)
            assertTrue(
                "an uninspectable path must stay OWNED, never freed on a failure to look",
                store.isChargedForTest("gen\$v.7.someid.recording"),
            )
        } finally {
            java.nio.file.Files.setPosixFilePermissions(parent.toPath(), perms)
        }
        assertTrue("precondition: the path still exists", java.nio.file.Files.exists(stuckPath, java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `a path that stays present keeps its charge`() {
        // reviewer 78c775dc: !exists() returns false for an unreadable path, so a FAILED delete could look
        // like a successful one and free retained bytes. Only a no-follow stat reporting NoSuchFileException
        // proves absence. This exercises the UNKNOWN branch directly: a file that cannot be deleted AND
        // cannot be inspected must stay charged.
||||||| /tmp/mfb
        // Reviewer 78c775dc: !exists() returns false for an unreadable path, so a FAILED delete could look
        // Like a successful one and free retained bytes. Only a no-follow stat reporting NoSuchFileException
        // Proves absence. This exercises the unknown branch directly: a file that cannot be deleted and
        // Cannot be inspected must stay charged.
        val root = tmp.newFolder("queue-unknown-absence")
        val contributions = File(root, "contributions")
        contributions.mkdirs()
        val stuck = File(contributions, "gen\$u.4.someid.recording.wav.part")
        stuck.mkdirs()
        File(stuck, "held.bin").writeBytes(ByteArray(1200))

        val store = ContributionStore(root, limits())
        store.recover()
        assertTrue("precondition: charged after the failed delete", store.isChargedForTest("gen\$u.4.someid.recording"))
        val id = "gen\$u.4.someid.recording"

        // The obstruction must remain BOTH undeletable and stattable-as-present. My first version emptied
        // the directory and replaced it with a symlink - but a symlink deletes cleanly, so that proved
        // nothing. Keep the directory non-empty so delete() keeps failing.
        assertTrue("precondition: the directory cannot be deleted while non-empty", !stuck.delete())

        store.discardCurrentPartials()

        assertTrue(
            "a path that cannot be proven absent must stay OWNED",
            store.isChargedForTest("gen\$u.4.someid.recording"),
        )
    }

    @Test
    fun `a delete and its charge settlement are ONE owner transition under the monitor`() {
        // reviewer 78c775dc: the helper mutated ownedCharges/unmeasuredIds without requiring the ownership
        // monitor, and two of its callers run outside it. This holds the monitor in another thread and
        // proves the delete settlement cannot finish while it is held - i.e. there is no window in which a
        // competing owner observes a deleted file whose charge has not been settled.
||||||| /tmp/mfb
        // Reviewer 78c775dc: the helper mutated ownedCharges/unmeasuredIds without requiring the ownership
        // Monitor, and two of its callers run outside it. This holds the monitor in another thread and
        // Proves the delete settlement cannot finish while it is held - i.e. There is no window in which a
        // Competing owner observes a deleted file whose charge has not been settled.
        val root = tmp.newFolder("queue-delete-monitor")
        val contributions = File(root, "contributions")
        contributions.mkdirs()
        val part = File(contributions, "gen\$m.1.someid.recording.wav.part")
        part.mkdirs()
        File(part, "held.bin").writeBytes(ByteArray(1024))

        val store = ContributionStore(root, limits())
        store.recover()
        assertTrue("precondition: the survivor was charged", store.isChargedForTest("gen\$m.1.someid.recording"))
        File(part, "held.bin").delete()

        val monitor = store.ownerLockForTest()
        val settledWhileHeld = java.util.concurrent.atomic.AtomicBoolean(false)
        val started = java.util.concurrent.CountDownLatch(1)
        val worker = Thread {
            started.countDown()
            store.discardCurrentPartials()
            // Only reachable after the helper acquired the monitor. If the helper settled accounting
            // OUTSIDE the monitor this would flip while the test still holds it.
            settledWhileHeld.set(true)
        }
        synchronized(monitor) {
            worker.start()
            started.await(2, java.util.concurrent.TimeUnit.SECONDS)
            // The worker must be BLOCKED on the monitor we hold, so the charge is still unsettled here.
            assertTrue(
                "delete settlement must not complete while another owner holds the monitor " +
                    "(charge still ${store.ownedIdsSnapshotForTest()})",
                store.ownedIdsSnapshotForTest().isNotEmpty(),
            )
        }
        worker.join(5_000)
        assertTrue("the worker finished once the monitor was released", settledWhileHeld.get())
        assertTrue("and the charge was then released", store.ownedIdsSnapshotForTest().isEmpty())
    }

    @Test
    fun `discardCurrentPartials charges a partial that refuses to delete`() {
        // discardCurrentPartials ignored its delete results, so a surviving partial was charged nowhere.
        val root = tmp.newFolder("queue-discard-partials")
        val contributions = File(root, "contributions")
        contributions.mkdirs()
        val stuck = File(contributions, "gen\$z.9.someid.recording.wav.part")
        stuck.mkdirs()
        File(stuck, "held.bin").writeBytes(ByteArray(1536))

        val store = ContributionStore(root, limits())
        store.discardCurrentPartials()

        assertTrue("precondition: the partial survived", stuck.exists())
        assertTrue(
            "a surviving partial must be charged",
            store.isChargedForTest("gen\$z.9.someid.recording"),
        )
    }

    @Test
    fun `a transient part-file failure releases its charge when a later sweep succeeds`() {
        // reviewer e4342946: the .part charge had no reverse transition. Once a later sweep deleted the file,
        // the charge and any unknown state stayed forever, permanently consuming capacity and keeping
        // admission refused over bytes that no longer exist. Same store, failure then success.
||||||| /tmp/mfb
        // Reviewer e4342946: the.part charge had no reverse transition. Once a later sweep deleted the file,
        // The charge and any unknown state stayed forever, permanently consuming capacity and keeping
        // Admission refused over bytes that no longer exist. Same store, failure then success.
        val root = tmp.newFolder("queue-part-charge-release")
        val contributions = File(root, "contributions")
        contributions.mkdirs()
        val partName = "gen\$y.5.someid.recording.wav.part"
        val stuck = File(contributions, partName)
        stuck.mkdirs()
        File(stuck, "held.bin").writeBytes(ByteArray(2048))

        val store = ContributionStore(root, limits())
        store.recover()
        assertTrue("precondition: the failure charged the survivor", store.isChargedForTest(partName.removeSuffix(".wav.part")))
        // The store's SUFFIX_PART is ".wav.part", so the synthetic id drops the whole suffix.
        val idAfterFailure = partName.removeSuffix(".wav.part")
        assertTrue("precondition: the survivor is owned", idAfterFailure in store.ownedIdsForTest())

        // The obstruction is removed, so the NEXT sweep on the SAME store can delete the file.
        File(stuck, "held.bin").delete()
        store.recover()

        assertTrue("precondition: the file is finally gone", !stuck.exists())
        assertTrue(
            "the transient failure's charge must be released once the bytes are gone",
            !store.isChargedForTest(idAfterFailure),
        )
        assertTrue(
            "and its synthetic id must no longer be owned (ids=${store.ownedIdsForTest()})",
            idAfterFailure !in store.ownedIdsForTest(),
        )
    }

    @Test
    fun `a capture is charged ONCE as a whole, not file by file`() {
        // Per-file semantics charge the id of whichever file refused first, which for a staging residue is
        // a DIFFERENT id from the capture's. Per-capture semantics charge the capture once. The observable
        // difference is the number of charges, so this asserts that count rather than an end state both
        // shapes can reach - my first version of this control could not tell them apart at all.
        val root = tmp.newFolder("queue-grouped-sweep")
        val contributions = File(root, "contributions")
        contributions.mkdirs()
        val capture = "7a7a7a7a-1111-2222-3333-444444444444"

        // THREE undeletable siblings of the same capture: per-file visiting charges three times.
        listOf("$capture.recording.wav", "$capture.metadata.json", "$capture.sha256").forEach { name ->
            val stuck = File(contributions, name)
            stuck.mkdirs()
            File(stuck, "held.bin").writeBytes(ByteArray(256))
        }

        val store = ContributionStore(root, limits())
        store.recover()

        assertEquals(
            "the capture must be charged ONCE, as a whole (owned ids: ${store.ownedIdsForTest()})",
            1,
            store.ownedIdsForTest().size,
        )
    }

    @Test
    fun `a failed age rewrite still SERVES the recording and retries the reset next recovery`() {
        // Reviewer b796c5e9. `resetUntrustedAgeLocked` returned a boolean that recovery ignored, so a marked
        // recording with an unresettable age went straight into `validate`, which reported null and sent it
        // down INVALID_ON_RECOVERY - deletion of a complete, valid recording. A failed write about AGE must
        // never become a verdict about the RECORDING.
||||||| /tmp/mfb
        // Reviewer b796c5e9. `resetUntrustedAgeLocked` returned a boolean that recovery ignored, so a marked
        // Recording with an unresettable age went straight into `validate`, which reported null and sent it
        // Down INVALID_ON_RECOVERY - deletion of a complete, valid recording. A failed write about AGE must
        // Never become a verdict about the RECORDING.
        val root = tmp.newFolder("queue-age-reset-write-failure")
        val id = java.util.UUID.randomUUID().toString()
        val seeder = ContributionStore(root, limits())
        seeder.prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L)
        seeder.close()

        val createdFile = File(root, "contributions/$id.created")
        assertTrue("precondition: the age sidecar exists", createdFile.isFile)
        createdFile.writeText("0")
        val filesBefore = File(root, "contributions").listFiles()!!.filter { it.name.startsWith(id) }.map { it.name }.sorted()

        val faulted = ContributionStore(
            root,
            limits(),
            hooks = StoreHooks(onCreatedAgeWrite = { throw java.io.IOException("simulated age write failure") }),
        )
        // SERVED despite the failed rewrite: the age is untrustworthy, not the audio.
        val recovered = faulted.recover().map { it.captureId }
        // Every file and sidecar survives, and the capture keeps its charge.
        val filesAfter = File(root, "contributions").listFiles()!!.filter { it.name.startsWith(id) }.map { it.name }.sorted()
        // FILES FIRST: under the pre-fix path every one of these is gone, because validation reported null
        // and the invalid-record branch deleted the lot. Asserting this before the serve check means the
        // failure message names the data loss rather than just a missing entry.
        assertTrue(
            "a complete recording was DELETED because its age could not be rewritten; surviving: $filesAfter",
            filesAfter.contains("$id.recording.wav") && filesAfter.size == filesBefore.size,
        )
        assertEquals("an unresettable age must not stop the recording being served", listOf(id), recovered)
        assertTrue("the served capture must be charged", faulted.isChargedForTest(id))
        assertTrue(
            "a failed reset must be counted, not silently retried",
            faulted.terminalCounter(ContributionStore.TerminalReason.AGE_RESET_FAILED) >= 1L,
        )
        // The failed rewrite left the sidecar alone: nothing was persisted for this process's age.
        assertEquals("a failed reset must not pretend to have persisted", "0", createdFile.readText().trim())
        faulted.close()

        // AFTER THE FAULT LIFTS the next recovery persists the reset, and the recording is still servable.
        var now = System.currentTimeMillis()
        val healthy = ContributionStore(root, limits(), clock = { now })
        assertEquals("the recording must survive into the next recovery", listOf(id), healthy.recover().map { it.captureId })
        val rewritten = createdFile.readText().trim().toLong()
        assertTrue("the next recovery must persist a reset age, not leave 0", rewritten in now..(now + 5_000L))
        assertEquals(
            "the persisted reset must be counted",
            1L,
            healthy.terminalCounter(ContributionStore.TerminalReason.AGE_RESET),
        )
        // And the seven-day clock runs from the persisted reset.
        now += 7L * 24 * 60 * 60 * 1000 - 1
        healthy.expireOlderThan()
        assertTrue("the reset recording must survive until seven days after it", File(root, "contributions/$id.recording.wav").isFile)
        healthy.close()
    }

    @Test
    fun `the purge witness accumulates across processes instead of being overwritten`() {
        // Reviewer 5507715b item 3. The witness of a purge - the only surface that survives one - was kept
        // in memory only, so the NEXT process started from an empty map and rewrote the file. Each purge
        // therefore destroyed the evidence of the previous one, which is exactly what a purge record exists
        // to prevent.
        val root = tmp.newFolder("queue-purge-cumulative")
        val witness = File(root, "purge-counters.txt")

        fun purgeOnce(reason: String) {
            val store = ContributionStore(root, limits())
            store.recover()
            store.revokeAndPurge(reason)
            store.close()
        }

        // Each purge records its REASON KEY; the count is how many captures that reason purged, which is
        // zero for an empty queue. Key presence across restarts is what proves the record accumulates.
        purgeOnce("first")
        assertTrue("the first purge must write a witness", witness.isFile)
        assertTrue("precondition: the first purge is recorded", witness.readText().contains("first="))

        purgeOnce("second")
        val afterSecond = witness.readText()
        assertTrue("the second purge ERASED the first purge's record: $afterSecond", afterSecond.contains("first="))
        assertTrue("the second purge was not recorded: $afterSecond", afterSecond.contains("second="))
    }

    @Test
    fun `a purge whose witness cannot be written reports the failure instead of swallowing it`() {
        // The witness is the ONLY surviving record of a purge. A silent write failure would leave the device
        // claiming nothing was purged, so the store must expose that it could not record.
        val root = tmp.newFolder("queue-purge-witness-failure")
        // A DIRECTORY where the witness file must go makes every write fail, portably.
        File(root, "purge-counters.txt").mkdirs()
        val store = ContributionStore(root, limits())
        store.recover()
        store.revokeAndPurge("unwritable")
        assertTrue(
            "a purge that could not be recorded must be reported, not swallowed",
            store.purgeWitnessFailureCount >= 1,
        )
        store.close()
    }

    @Test
    fun `a failed marker restore RETAINS the recording instead of deleting it`() {
        // Reviewer 5507715b item 1. Restoring a markerless pair's `.ready` is best effort, but the entry
        // still entered the index - and the expiry pass deletes any indexed entry whose marker is absent
        // as `ready-marker-vanished`. A failed write therefore turned the entry into a deletion during the
        // SAME recovery. A best-effort write must never be able to lose a recording.
        val root = tmp.newFolder("queue-marker-restore-failure")
        val id = java.util.UUID.randomUUID().toString()
        val seeder = ContributionStore(root, limits())
        seeder.prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L)
        seeder.close()

        val readyFile = File(root, "contributions/$id.ready")
        assertTrue("precondition: the marker is removed", readyFile.delete())

        val store = ContributionStore(
            root,
            limits(),
            directorySync = RecordingSync(),
            hooks = StoreHooks(onReadyMarkerRestore = { throw java.io.IOException("simulated marker write failure") }),
        )
        // NOT SERVED: without a marker the entry cannot be offered, and pretending otherwise would describe
        // a state that is not on disk.
        assertEquals("a capture whose marker could not be written must not be served", emptyList<String>(), store.recover())

        // BUT NOT DELETED EITHER. Every file survives, and the capture stays charged.
        val remaining = File(root, "contributions").listFiles()!!.filter { it.name.startsWith(id) }
        assertTrue("the recording's audio was deleted after a failed marker write", remaining.any { it.name == "$id.recording.wav" })
        assertTrue("the recording's metadata was deleted after a failed marker write", remaining.any { it.name == "$id.metadata.json" })
        assertTrue("the withheld capture must stay charged", store.isChargedForTest(id))
        store.close()
    }

    @Test
    fun `an entry with a zero or future capture time is RESET to now, then served and expired`() {
        // Spec 3e33e306. `.created` is written with no directory sync, so a crash can leave it empty; a
        // future value is clock skew. Neither says anything about whether the audio is valid, so neither
        // may destroy or permanently strand a complete recording. The age is reset to now, rewritten,
        // counted, and the seven-day clock runs from the reset.
||||||| /tmp/mfb
        // Spec 3e33e306. `.created` is written with no directory sync, so a crash can leave it empty; a
        // Future value is clock skew. Neither says anything about whether the audio is valid, so neither
        // May destroy or permanently strand a complete recording. The age is reset to now, rewritten,
        // Counted, and the seven-day clock runs from the reset.
        for (bad in listOf("0", (System.currentTimeMillis() + 3_600_000L).toString())) {
            val root = tmp.newFolder("queue-age-reset-${bad.take(4)}")
            val id = java.util.UUID.randomUUID().toString()
            val seeder = ContributionStore(root, limits())
            seeder.prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L)
            seeder.close()

            val createdFile = File(root, "contributions/$id.created")
            assertTrue("precondition", createdFile.isFile)
            createdFile.writeText(bad)

            var now = System.currentTimeMillis()
            val store = ContributionStore(root, limits(), clock = { now })
            // SERVED, exactly like any other complete recording.
            assertEquals("a $bad capture time must not stop the recording being served", listOf(id), store.recover().map { it.captureId })
            assertTrue("the reset must be counted", store.terminalCounter(ContributionStore.TerminalReason.AGE_RESET) >= 1L)
            val rewritten = createdFile.readText().trim().toLong()
            assertTrue("the age must be rewritten from the reset, not left at $bad", rewritten in now..(now + 5_000L))

            // AND THE CLOCK RUNS FROM THE RESET: alive just before seven days, gone at it.
            now += 7L * 24 * 60 * 60 * 1000 - 1
            store.expireOlderThan()
            assertTrue("the reset entry must survive until seven days after the reset", File(root, "contributions/$id.recording.wav").isFile)
            now += 2
            store.expireOlderThan()
            assertTrue(
                "the entry must expire seven days after the reset",
                File(root, "contributions").listFiles()!!.none { it.name.startsWith(id) },
            )
            store.close()
        }
    }

    @Test
    fun `a capture whose files refuse to delete charges what actually SURVIVED`() {
        // RE-SPECIFIED so it pins a real invariant rather than a vacuous one (reviewer 5507715b item 4).
        //
        // What must hold: whatever survives the grouped debris sweep stays CHARGED, and the charge is a
        // RECORD - one capture is one record, whatever files of it survived. A per-file sweep could delete
        // a capture's metadata after its audio refused, leaving a charge that described a shape the residue
        // no longer had; the grouped sweep claims the capture once.
        //
        // An empty directory can be unlinked with plain delete(), so a NON-EMPTY one is the portable way
        // to make the removal genuinely fail on this host.
        val root = tmp.newFolder("queue-capture-delete-failure")
        val contributions = File(root, "contributions").apply { mkdirs() }
        val id = "9f9f9f9f-1111-2222-3333-444444444444"
        val stuckDir = File(contributions, "$id.sha256").apply { mkdirs() }
        val payload = File(stuckDir, "held.bin").apply { writeBytes(ByteArray(4096)) }

        val store = ContributionStore(root, limits())
        store.recover()

        assertTrue("precondition: the non-empty directory survived", payload.isFile)
        assertTrue("the survivors must stay charged", store.isChargedForTest(id))
        assertEquals("the capture must be charged once", 1, store.ownedIdsForTest().size)
        store.close()
    }

    @Test
    fun `staging residue from a crashed attempt is swept by the unmarked-debris pass`() {
        // I suspected staging orphans were charged nowhere. They are not: a staging file is named
        // gen$<attempt>.<generation>.<capture>.<suffix> with no matching .ready, so recovery's
        // unmarked-debris pass sees it and deletes it. This control PINS that, and would fail if the sweep
        // ever stopped treating staging names as unmarked.
        val root = tmp.newFolder("queue-staging-residue")
        val contributions = File(root, "contributions")
        contributions.mkdirs()

        val attempt = "abc"
        val capture = "11111111-2222-3333-4444-555555555555"
        val staging = contributions.listFiles() ?: emptyArray()
        val orphanWav = File(contributions, "gen\$attempt.7.$capture.recording.wav.part")
        val orphanMeta = File(contributions, "gen\$attempt.7.$capture.metadata.json")
        val orphanDigest = File(contributions, "gen\$attempt.7.$capture.sha256")
        orphanWav.writeBytes(ByteArray(1024))
        orphanMeta.writeBytes(ByteArray(512))
        orphanDigest.writeBytes(ByteArray(64))

        val store = ContributionStore(root, limits())
        store.recover()

        val survivors = contributions.listFiles()!!.filter { it.name.startsWith("gen\$") }
        assertTrue("orphaned staging must not survive recovery: ${survivors.map { it.name }}", survivors.isEmpty())
        // Nothing should remain charged either - swept bytes are gone, so they occupy nothing.
        assertTrue("swept staging must leave nothing charged", store.ownedIdsForTest().isEmpty())
    }

    @Test
    fun `an UNMEASURABLE capture is priced, not used to refuse admission`() {
        // reviewer e6eeabda / 7340fe2d: a per-capture worst case cannot bound arbitrary retained debris.
||||||| /tmp/mfb
        // Reviewer e6eeabda / 7340fe2d: a per-capture worst case cannot bound arbitrary retained debris.
        // Unknown accounting must REFUSE admission until the capture is successfully measured or removed.
        val root = tmp.newFolder("queue-unmeasurable")
        val id = java.util.UUID.randomUUID().toString()
        val store = ContributionStore(root, limits())
        assertTrue("precondition: prepare", store.prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)
        File(root, "disqualified").mkdirs()
        File(root, "disqualified/$id").writeText("disqualified")

        // Debris beyond the traversal depth bound stands in for a tree the walk refuses to count blindly.
        val contributions = File(root, "contributions")
        contributions.listFiles()!!.filter { it.name.startsWith(id) }.forEach { file ->
            val bytes = file.readBytes()
            file.delete()
            file.mkdirs()
            File(file, "held").writeBytes(bytes)
        }
        var deep = File(contributions, "$id.recording.wav")
        repeat(measureDepthPastBound) {
            deep = File(deep, "d")
            deep.mkdirs()
        }
        File(deep, "tail.bin").writeBytes(ByteArray(16))

        // One live owner per queue (spec c8f766fb): the next owner takes over after release.
        store.close()
        val fresh = ContributionStore(root, limits())
        fresh.expireOlderThan()

        // Headroom on BOTH axes, so the only thing that can refuse the probe is the unknown-accounting
        // rule. My first version left maxQueueRecords at 8 and the residue filled the table, which refused
        // the probe for an unrelated reason and made the control blind to the mutant.
        // The unmeasured capture carries a whole-queue PLACEHOLDER charge, so a byte check refuses the
        // probe even without the unknown rule - which masked the mutant in my first version. Collapse the
        // per-capture size bound so the placeholder alone cannot fill the queue's headroom, leaving the
        // unknown rule as the only thing that can refuse here.
        // One live owner per queue (spec c8f766fb): the next owner takes over after release.
        fresh.close()
        val roomy = ContributionStore(
            root,
            QueueLimits.defaults().copy(
                maxQueueBytes = QueueLimits.MAX_QUEUE_BYTES_CEILING,
                maxQueueRecords = QueueLimits.MAX_QUEUE_RECORDS_CEILING,
            ),
        )
        roomy.expireOlderThan()
        // SPEC a7f6ce81 RULING: the unmeasured capture already carries a worst-case placeholder charge,
        // so the byte budget reflects it; refusing as well double-counted the unknown and stopped capture
        // entirely. Admission is now GRANTED and the unknown is reported, not used as a blanket refusal.
        val probeAdmission = roomy.admit(sampleCount = 1600L, metadataBytes = 32)
        assertNotNull("admission must be GRANTED while a footprint is unknown (priced, not refused)", probeAdmission)
        probeAdmission!!.discard()
    }

    @Test
    fun `late cleanup is serialized against a successor publication on the same id`() {
        // retryDisqualifiedCleanupLocked deleted files OUTSIDE the ownership monitor, so a successor
        // publishing the same id could be racing the sweep. The whole retry transaction is now under the
        // monitor: while a cleanup holds it, no publication can observe an intermediate state.
        val root = tmp.newFolder("queue-late-cleanup")
        val id = java.util.UUID.randomUUID().toString()
        val store = ContributionStore(root, limits())
        assertTrue("precondition: prepare", store.prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)
        // Disqualify without deleting anything, simulating a previous failed attempt.
        File(root, "disqualified").mkdirs()
        // Exactly the content the store itself writes, so the measured footprint matches production.
        File(root, "disqualified/$id").writeText("disqualified")

        val wav = File(root, "contributions/$id.recording.wav")
        assertTrue("precondition: audio present", wav.isFile)

        // OBSERVABLE SERIALIZATION: hold the ownership monitor from another thread and confirm the sweep
        // cannot delete the files while we hold it. Without the monitor around the FILE work the sweep
        // completes regardless, which is exactly the race a successor publication could exploit.
        val gate = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        val holder = Thread {
            synchronized(store.ownerLockForTest()) {
                gate.countDown()
                // Hold until the sweep has had time to (incorrectly) proceed.
                Thread.sleep(400)
            }
        }
        holder.start()
        gate.await()
        val sweep = Thread {
            store.expireOlderThan()
            finished.countDown()
        }
        sweep.start()
        // While the monitor is held, the files must still be present: file work is serialized too.
        Thread.sleep(150)
        assertTrue("the sweep must not delete files while the ownership monitor is held", wav.isFile)
        holder.join()
        finished.await()
        sweep.join()
        assertTrue("the residual files are removed", !wav.exists())
        assertTrue("the marker is cleared", !File(root, "disqualified/$id").exists())
        // One live owner per queue (spec c8f766fb): the next owner takes over after this one releases.
        store.close()
        val after = ContributionStore(root, limits())
        after.recover()
        assertTrue(
            "capacity must be released after the sweep",
            after.admit(sampleCount = 256L, metadataBytes = 1024) != null,
        )
    }

    @Test
    fun `a store that adopts from disk requires the SAME durability confirmation as recovery`() {
        // reloadUnownedFromDisk used to promote marker+validate directly: a SECOND, weaker load path than
        // recovery, so an entry whose files were never confirmed durable could be served by an instance
        // that adopted it from disk even though recovery would have withheld it (spec ecbe84dd).
        val root = tmp.newFolder("queue-reload-confirm")
        val id = java.util.UUID.randomUUID().toString()
        ContributionStore(root, limits()).prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L)

        // Confirmation fails for this instance only.
        val failing = object : DirectorySync {
            override fun sync(directory: File) {
                throw java.io.IOException("simulated confirmation failure")
            }
        }
        val adopter = ContributionStore(root, limits(), directorySync = failing)
        // The expire path is what triggers the lazy disk load.
        adopter.expireOlderThan()
        assertTrue(
            "a store that ADOPTED from disk must not serve an entry it could not confirm durable",
            adopter.readyEntries().none { it.captureId == id },
        )
    }

    @Test
    fun `one blocked file does not stop the others from being deleted`() {
        // Direct control for the SHORT-CIRCUIT defect, independent of the disqualification marker: with a
        // short-circuiting `.all`, a failing WAV delete means the metadata and ready marker are never even
        // attempted, so a complete valid entry is left behind. The durable marker masks that on the served
        // path, which is exactly why this control inspects the FILES rather than what recover() returns.
        val root = tmp.newFolder("queue-delete-continues")
        val id = java.util.UUID.randomUUID().toString()
        var now = 1_000_000L
        val store = ContributionStore(root, limits().copy(maxQueueAgeSeconds = 60), clock = { now })
        assertTrue("precondition: prepare", store.prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)

        val contributions = File(root, "contributions")
        val wav = File(contributions, "$id.recording.wav")
        val ready = File(contributions, "$id.ready")
        val metaFile = File(contributions, "$id.metadata.json")
        // Block ONLY the audio delete by making the directory read-only: every unlink fails, so this
        // cannot distinguish. Instead block one file by turning the AUDIO into a non-empty directory,
        // which fails for that path alone while the others remain ordinary deletable files.
        val audio = wav.readBytes()
        wav.delete()
        wav.mkdirs()
        File(wav, "held").writeBytes(audio)

        now += 120_000L
        store.expireOlderThan()

        assertTrue("the audio itself could not be deleted", wav.exists())
        assertTrue("the READY MARKER must still be attempted and removed", !ready.exists())
        assertTrue("the metadata must still be attempted and removed", !metaFile.exists())
    }

    @Test
    fun `a disqualified capture is never served again even if its files survive`() {
        // In-memory readiness is not durable disposal intent. A capture whose delete failed must stay
        // refused across a restart, on a code path that does not depend on the charge table surviving.
        val root = tmp.newFolder("queue-disqualified")
        val id = java.util.UUID.randomUUID().toString()
        val store = ContributionStore(root, limits())
        assertTrue("precondition: prepare", store.prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)
        // Marker written by the store itself via a failed delete.
        File(root, "disqualified/$id").apply { parentFile?.mkdirs() }.writeText("disqualified\n")
        // One live owner per queue (spec c8f766fb): the next owner takes over after release.
        store.close()
        val restarted = ContributionStore(root, limits())
        assertTrue(
            "a disqualified capture must never be exposed as ready",
            restarted.recover().none { it.captureId == id },
        )
    }

    @Test
    fun `sidecars are file-synced before the ready marker, not just written`() {
        // Directory fsync does not persist the CONTENTS of created.at/attempts: a force on the
        // directory makes the NAME durable, so after a crash the marker can persist while the sidecar
        // is empty or truncated. Age and attempt authority are read back on recovery, so their
        // durability has to be established the same way the marker's is.
        val syncedFiles = java.util.Collections.synchronizedList(mutableListOf<String>())
        val root = tmp.newFolder("queue-sidecar")
        val store = ContributionStore(
            root,
            limits(),
            directorySync = RecordingSync(),
            fileSync = FileSync { file -> syncedFiles += file.name },
        )
        val id = java.util.UUID.randomUUID().toString()
        assertTrue(store.prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L) != null)

        assertTrue(
            "created.at must be file-synced, synced=${syncedFiles.toList()}",
            syncedFiles.any { it.endsWith(".created") },
        )
        assertTrue(
            "attempts sidecar must be file-synced, synced=${syncedFiles.toList()}",
            syncedFiles.any { it.endsWith(".attempts") },
        )
        assertTrue(
            "the ready marker must be file-synced, synced=${syncedFiles.toList()}",
            syncedFiles.any { it.endsWith(".ready") },
        )
        assertTrue(
            "audio must be file-synced, synced=${syncedFiles.toList()}",
            syncedFiles.any { it.contains(".part") },
        )
        // The metadata sidecar carries the canonical record and its digest is the entry's identity, so
        // its CONTENTS must be durable too. A mutation that dropped this sync survived the first
        // version of this test because only the WAV .part and the small sidecars were checked.
        assertTrue(
            "metadata must be file-synced, synced=${syncedFiles.toList()}",
            syncedFiles.any { it.endsWith(".metadata.json") },
        )
        assertTrue(
            "the digest sidecar must be file-synced, synced=${syncedFiles.toList()}",
            syncedFiles.any { it.endsWith(".sha256") },
        )
    }

    @Test
    fun `recovery trusts only entries whose marker survives`() {
        val root = tmp.newFolder("queue-recovery")
        val sync = RecordingSync()
        val store = ContributionStore(root, limits(), directorySync = sync)
        val id = java.util.UUID.randomUUID().toString()
        val entry = store.prepare(id, "epoch-1", chunks(1600), meta(id), "a@x", 7L)
        assertTrue(entry != null)

        // Simulate the crash outcome the ready marker exists for: the directory entry for the marker is
        // lost. RE-SPECIFIED (spec 0d6ec2e3): the marker is no longer the only route to servability.
        // A pair that is complete and passes its checksum IS recovered and served, with its marker
        // written back; the marker's job moved from "may this exist" to "is this ready right now", and
        // recovery re-derives it from content instead of destroying the recording.
        val readyFiles = File(root, "contributions").listFiles { f -> f.name.endsWith(".ready") }
        assertTrue("expected a ready marker to exist", readyFiles != null && readyFiles.isNotEmpty())
        readyFiles!!.forEach { it.delete() }

        // One live owner per queue (spec c8f766fb): the next owner takes over after release.
        store.close()
        val recovered = ContributionStore(root, limits(), directorySync = RecordingSync())
        recovered.recover()
        assertEquals(
            "a complete, checksum-valid entry must be recovered even without its marker",
            listOf(id),
            recovered.readyEntries().map { it.captureId },
        )
        assertTrue(
            "recovery must restore the marker it derived",
            File(root, "contributions/$id.ready").isFile,
        )
    }
}
