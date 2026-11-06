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
 * 400 invalid_request RETAINS AND STOPS SERVING (spec ruling 5c811f83).
 *
 * iOS measured sixteen upload attempts each answered `400 invalid_request`, after which the app
 * DELETED the queued recordings. Spec's ruling: a REQUEST rejection says nothing about the bytes,
 * so the client must RETAIN the repairable private recording and STOP SERVING the entry - no
 * retry loop, no abandon/delete. These controls pin that on the client seam, reusing the
 * retention harness semantics of RetryableRefusalRetentionTest (fresh-owner recovery = in-process
 * restart, response supplied at the transport seam).
 *
 * NOT claimed: that the installed app process was killed, or that a real HTTP client produced the
 * bytes; the restart is the same in-process fresh-owner recreation the store's own recovery tests
 * use, and the exact Cloud body is injected at the classified-response seam.
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
    fun `exact 400 invalid_request retains identical wav and metadata and never serves the entry again`() {
        var now = 30_000_000L
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
        val audioBefore = sha256(audioFile(root).readBytes())
        val metaBefore = sha256(root.walkTopDown().first { it.isFile && it.name.endsWith(".metadata.json") }.readBytes())

        // FIRST ATTEMPT: the server answers the EXACT measured body, classified Unservable.
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

        // Retained on disk under the LIVE owner: bytes untouched.
        assertEquals("audio must be byte-identical after the 400", audioBefore, sha256(audioFile(root).readBytes()))
        assertEquals(
            "metadata must be byte-identical after the 400",
            metaBefore,
            sha256(root.walkTopDown().first { it.isFile && it.name.endsWith(".metadata.json") }.readBytes()),
        )

        // THE RESTART: a fresh owner over the SAME directory retains the recording.
        store.close()
        val fresh = newStore(root) { now }
        fresh.recover()
        assertEquals(
            "the retained recording must survive a fresh owner unchanged",
            audioBefore,
            sha256(audioFile(root).readBytes()),
        )
        assertEquals(
            "the retained metadata must survive a fresh owner unchanged",
            metaBefore,
            sha256(root.walkTopDown().first { it.isFile && it.name.endsWith(".metadata.json") }.readBytes()),
        )

        // STOPPED SERVING: never eligible now, at any representable time - the durable boundary
        // sits at its never-representable maximum, so a restart cannot reroll it.
        val boundary = fresh.retryBoundariesForTest()[id]
        assertNotNull("the unservable stop must be durable, not an in-memory skip", boundary)
        assertEquals(
            "the boundary must be the never-representable maximum, not a finite retry delay",
            Long.MAX_VALUE,
            boundary,
        )
        assertEquals("the entry must never be served again", 0, fresh.retryEligible(now).size)
        assertEquals("not even at the far future", 0, fresh.retryEligible(Long.MAX_VALUE / 2).size)

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
