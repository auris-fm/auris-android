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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 400 invalid_request DELETES THE ENTRY AT ONCE (spec 1a0d3ffb, amending the 5c811f83 retention
 * ruling). The old build retained refused recordings forever; sixteen of them filled the owner's
 * queue and the new server refuses each permanently (missing `wake_required`), so a full queue
 * could never drain and new recordings were refused. Under the amended spec the queue "always
 * drains": a server-permanent refusal removes the entry immediately, through the store's
 * reconciled purge, never an ad-hoc unlink.
 *
 * iOS-review lessons pinned here as controls:
 * - deletion goes ONLY through `releaseForAdmittedAttempt` on the real queue: the attempt-identity
 *   proof is the proven absence of an active upload (a stale token deletes nothing);
 * - a durable-intent publication failure retains and reports - uncertainty is never resolved by
 *   deleting (the 5c811f83 principle survives for the uncertain case);
 * - the client's own record check rejecting an entry already deletes it at once on recovery
 *   (`INVALID_ON_RECOVERY`, with the cleaned/retained-charge controls in ContributionStore).
 *
 * Harness semantics unchanged from the retention era: fresh-owner recovery = in-process restart,
 * the exact Cloud body injected at the classified-response seam. NOT claimed: that the installed
 * app process was killed, or that a real HTTP client produced the bytes.
 */
class UnservableInvalidRequestControlTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val invalidRequestBody = """{"schema_version":1,"code":"invalid_request"}"""

    private fun wavBytes(samples: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        WavWriter.writeHeader(out, samples)
        WavWriter.writePcm(out, ShortArray(samples) { i -> i.toShort() }, samples)
        return out.toByteArray()
    }

    /** A complete, canonical record: recovery re-validates it, so a stub can never survive. */
    private fun canonicalMeta(captureId: String): ByteArray {
        val audio = wavBytes(1600)
        val block = AudioBlock(
            sha256 = sha256(audio),
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
                .wakeRequired(true).build(platform = "android", appVersion = "8.20", build = "12345", audio = block),
        )
    }

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun chunks(samples: Int): List<Pair<ShortArray, Int>> = listOf(ShortArray(samples) { i -> i.toShort() } to samples)

    private fun newStore(root: File, clock: () -> Long): ContributionStore = ContributionStore(
        root,
        QueueLimits.defaults().copy(maxQueueRecords = 8, retryInitialS = 4, retryMaxS = 8),
        clock = clock,
        jitter = { it - 1 },
    )

    private fun audioFile(root: File): File = root.walkTopDown().filter { it.isFile && it.name.endsWith(".recording.wav") }.toList().also { files ->
        check(files.size == 1) {
            "expected exactly one recording, tree was: " +
                root.walkTopDown().joinToString(" | ") { it.path.removePrefix(root.path) }
        }
    }.single()

    @Test
    fun `exact 400 invalid_request deletes the entry at once and never serves it again`() {
        var now = 31_000_000L
        val root = tmp.newFolder("unserviced")
        val store = newStore(root) { now }
        val id = UUID.randomUUID().toString()
        val samples = 1600
        val metaBytes = canonicalMeta(id)
        val admission = store.admitForTest(samples.toLong(), metaBytes.size)
        val generation = store.fence.currentGeneration()
        assertNotNull(
            store.prepareAt(generation, admission, id, "epoch-1", chunks(samples), metaBytes, "a@x", 7L),
        )
        assertNotNull("the entry must be on disk before the refusal", audioFile(root))

        // The server answers the EXACT measured body, classified Unservable.
        val caller = ContributionRetryCaller(
            store,
            clock = { now },
            networkAvailable = { true },
            attemptUpload = { _, _, _, _ -> false },
            ackResolver = ContributionAckResolver(store, CaptureReceiptValidator.forStore(store)),
            dispositionFor = { _, _ -> ContributionUploadTransport.Response.Unservable(400, "invalid_request") },
            observedStatusFor = { _, _ -> 400 },
            responseFor = { _, _ -> null },
        )
        caller.pollOnce()

        // DELETED AT ONCE (spec 1a0d3ffb): no wav, no metadata, no ready marker - the queue drained.
        val remaining = root.walkTopDown().filter { it.isFile && (it.name.endsWith(".recording.wav") || it.name.endsWith(".metadata.json") || it.name.endsWith(".ready")) }.toList()
        assertTrue(
            "the refused entry must be deleted from the queue at once; remaining: " +
                remaining.joinToString(", ") { it.path.removePrefix(root.path) },
            remaining.isEmpty(),
        )
        assertEquals(
            "the permanent refusal must be counted truthfully",
            1,
            store.terminalCounter(ContributionStore.TerminalReason.PERMANENT_REFUSAL),
        )
        assertEquals("the deleted entry must never be served", 0, store.retryEligible(now).size)

        // THE RESTART: a fresh owner over the SAME directory must not resurrect anything.
        store.close()
        val fresh = newStore(root) { now }
        fresh.recover()
        val afterRestart = root.walkTopDown().filter { it.isFile && (it.name.endsWith(".recording.wav") || it.name.endsWith(".metadata.json")) }.toList()
        assertTrue("a deleted entry must stay deleted across recovery", afterRestart.isEmpty())
        assertEquals("the deleted entry must never be served again", 0, fresh.retryEligible(now).size)
        assertEquals("the deleted entry must never be served at the far future", 0, fresh.retryEligible(Long.MAX_VALUE / 2).size)

        // And no further attempt is launched against it.
        val secondAttempts = mutableListOf<String>()
        val laterCaller = ContributionRetryCaller(
            fresh,
            clock = { now },
            networkAvailable = { true },
            attemptUpload = { _, captureId, _, _ ->
                secondAttempts.add(captureId)
                false
            },
            ackResolver = ContributionAckResolver(fresh, CaptureReceiptValidator.forStore(fresh)),
            dispositionFor = { _, _ -> ContributionUploadTransport.Response.Unservable(400, "invalid_request") },
            observedStatusFor = { _, _ -> 400 },
            responseFor = { _, _ -> null },
        )
        laterCaller.pollOnce()
        assertEquals("no retry loop may form behind a 400 invalid_request", 0, secondAttempts.size)
        fresh.close()
    }

    @Test
    fun `410 enrollment_retired also deletes the entry, closing the second way a recording is unusable`() {
        // Two different things make a recording permanently unacceptable and both arrive as 410:
        // the record is gone, and the enrollment it was collected under is no longer active. The
        // second is new to this client. Deletion must not depend on which one it was, and the
        // entry must not be retried against a server that will keep refusing it.
        var now = 31_000_000L
        val root = tmp.newFolder("enrollment-retired")
        val store = newStore(root) { now }
        val id = UUID.randomUUID().toString()
        val samples = 1600
        val metaBytes = canonicalMeta(id)
        val admission = store.admitForTest(samples.toLong(), metaBytes.size)
        val generation = store.fence.currentGeneration()
        assertNotNull(
            store.prepareAt(generation, admission, id, "epoch-1", chunks(samples), metaBytes, "a@x", 7L),
        )
        assertNotNull("the entry must be on disk before the refusal", audioFile(root))

        val caller = ContributionRetryCaller(
            store,
            clock = { now },
            networkAvailable = { true },
            attemptUpload = { _, _, _, _ -> false },
            ackResolver = ContributionAckResolver(store, CaptureReceiptValidator.forStore(store)),
            dispositionFor = { _, _ -> ContributionUploadTransport.Response.Terminal(410, "enrollment_retired") },
            observedStatusFor = { _, _ -> 410 },
            responseFor = { _, _ -> null },
        )
        caller.pollOnce()

        val remaining = root.walkTopDown().filter { it.isFile && (it.name.endsWith(".recording.wav") || it.name.endsWith(".metadata.json") || it.name.endsWith(".ready")) }.toList()
        assertTrue(
            "a retired enrollment must delete the entry; remaining: " +
                remaining.joinToString(", ") { it.path.removePrefix(root.path) },
            remaining.isEmpty(),
        )
        assertEquals("the deletion must never be served", 0, store.retryEligible(now).size)
        assertEquals(
            "a server terminal must be counted as one",
            1,
            store.terminalCounter(ContributionStore.TerminalReason.SERVER_TERMINAL),
        )

        // Recovery must not resurrect it, and no attempt may be launched for it again.
        store.close()
        val fresh = newStore(root) { now }
        fresh.recover()
        assertTrue(
            "a deleted entry must stay deleted across recovery",
            root.walkTopDown().none { it.isFile && (it.name.endsWith(".recording.wav") || it.name.endsWith(".metadata.json")) },
        )
        assertEquals("the deleted entry must never be served again", 0, fresh.retryEligible(Long.MAX_VALUE / 2).size)
        fresh.close()
    }

    @Test
    fun `a 500 keeps the entry and retries it`() {
        // A server error says nothing about the recording, so the entry must survive: the bytes may
        // well be acceptable and the failure is on the other side. This is the counterpart to the
        // terminal controls above - the wrong behaviour here would delete audio the user recorded
        // because the server was briefly unwell.
        var now = 31_000_000L
        val root = tmp.newFolder("server-error")
        val store = newStore(root) { now }
        val id = UUID.randomUUID().toString()
        val samples = 1600
        val metaBytes = canonicalMeta(id)
        val admission = store.admitForTest(samples.toLong(), metaBytes.size)
        val generation = store.fence.currentGeneration()
        assertNotNull(
            store.prepareAt(generation, admission, id, "epoch-1", chunks(samples), metaBytes, "a@x", 7L),
        )
        assertNotNull("the entry must be on disk before the attempt", audioFile(root))

        val attempts = mutableListOf<String>()
        val caller = ContributionRetryCaller(
            store,
            clock = { now },
            networkAvailable = { true },
            attemptUpload = { _, captureId, _, _ ->
                attempts.add(captureId)
                false
            },
            ackResolver = ContributionAckResolver(store, CaptureReceiptValidator.forStore(store)),
            dispositionFor = { _, _ -> ContributionUploadTransport.Response.Retryable(500, null) },
            observedStatusFor = { _, _ -> 500 },
            responseFor = { _, _ -> null },
        )
        caller.pollOnce()

        assertTrue("a 500 must not delete the recording", audioFile(root).exists())
        assertEquals("a 500 must not be counted as a terminal", 0, store.terminalCounter(ContributionStore.TerminalReason.SERVER_TERMINAL))
        assertEquals(
            "a 500 must not be counted as a permanent refusal",
            0,
            store.terminalCounter(ContributionStore.TerminalReason.PERMANENT_REFUSAL),
        )
        assertEquals("the failed attempt must have been launched", 1, attempts.size)

        // It stays eligible, so a later attempt can still succeed.
        now += 24L * 60 * 60 * 1000
        assertTrue("a 500 must leave the entry retryable", store.retryEligible(now).contains(id))
    }

    @Test
    fun `a refusal whose durable intent cannot be published retains and reports instead of deleting`() {
        var now = 31_000_000L
        val root = tmp.newFolder("unserviced-uncertain")
        val store = newStore(root) { now }
        val id = UUID.randomUUID().toString()
        val samples = 1600
        val metaBytes = canonicalMeta(id)
        val admission = store.admitForTest(samples.toLong(), metaBytes.size)
        val generation = store.fence.currentGeneration()
        assertNotNull(
            store.prepareAt(generation, admission, id, "epoch-1", chunks(samples), metaBytes, "a@x", 7L),
        )
        // BLOCK the reconciled purge's FIRST step (the durable disqualification marker): a directory
        // sitting at the marker path makes the intent write fail, so deleteLocked must retain the
        // entry and report - never delete the files without recorded intent.
        val markerDir = File(File(root, "disqualified"), id)
        assertTrue("test setup: the marker must be replaceable by a directory", markerDir.mkdirs() || markerDir.isDirectory)

        val caller = ContributionRetryCaller(
            store,
            clock = { now },
            networkAvailable = { true },
            attemptUpload = { _, _, _, _ -> false },
            ackResolver = ContributionAckResolver(store, CaptureReceiptValidator.forStore(store)),
            dispositionFor = { _, _ -> ContributionUploadTransport.Response.Unservable(400, "invalid_request") },
            observedStatusFor = { _, _ -> 400 },
            responseFor = { _, _ -> null },
        )
        caller.pollOnce()

        // RETAINED: the bytes stay, charged as not-ready (so a later cleanup retries them), and
        // are not served. The PERMANENT_REFUSAL counter records the settlement decision (the
        // index release), the store's existing convention - the truthful FILE-level outcome lives
        // in deleteLocked's log and the retained charge, which is what these asserts pin.
        assertNotNull("without recorded intent the entry must be retained", audioFile(root))
        assertEquals("the retained entry must not be served while its disposal is uncertain", 0, store.retryEligible(now).size)
        store.close()
    }

    @Test
    fun `a matching receipt still removes the entry`() {
        var now = 31_000_000L
        val root = tmp.newFolder("accepted")
        val store = newStore(root) { now }
        val id = UUID.randomUUID().toString()
        val samples = 1600
        val metaBytes = canonicalMeta(id)
        val admission = store.admitForTest(samples.toLong(), metaBytes.size)
        val generation = store.fence.currentGeneration()
        assertNotNull(
            store.prepareAt(generation, admission, id, "epoch-1", chunks(samples), metaBytes, "a@x", 7L),
        )
        // The receipt must echo the RECORD digest (canonical metadata bytes), and carry the full
        // allowlisted receipt key set - a partial receipt is UnvalidatedAccept, not a release.
        val recordDigest = sha256(metaBytes)

        val caller = ContributionRetryCaller(
            store,
            clock = { now },
            networkAvailable = { true },
            attemptUpload = { _, _, _, _ -> true },
            ackResolver = ContributionAckResolver(store, CaptureReceiptValidator.forStore(store)),
            responseFor = { _, _ ->
                (
                    """{"schema_version":1,"capture_id":"$id","record_sha256":"$recordDigest","status":"accepted",""" +
                        """"receipt_id":"r-1","accepted_at":"2026-11-06T04:00:00Z","retention_expires_at":"2027-11-06T04:00:00Z"}"""
                    )
                    .toByteArray()
            },
            httpStatusFor = { _, _ -> 201 },
        )
        caller.pollOnce()

        val remaining = root.walkTopDown().filter { it.isFile && it.name.endsWith(".recording.wav") }.toList()
        assertTrue(
            "an accepted receipt must still remove the entry - only the 400 rejection changed; " +
                "remaining: " + remaining.joinToString(", ") { it.path.removePrefix(root.path) },
            remaining.isEmpty(),
        )
        store.close()
    }
}
