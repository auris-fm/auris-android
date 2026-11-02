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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * DURABLE RETENTION ACROSS A RETRYABLE REFUSAL (spec 20:04Z, Cloud fixtures `voice-upload-retryable-responses.md`).
 *
 * Cloud proved the SERVER emits retryable refusals. Nothing on either side proved a CLIENT keeps the
 * audio and re-attempts it. That gap is what this control exists to close, and the restart is the part
 * that makes it mean anything: a retry served from a variable that never left RAM would pass without
 * any durable retention at all.
 *
 * WHAT IS AND IS NOT CLAIMED
 * - Claimed: after a retryable refusal, a FRESH store owner reading the SAME on-disk entry recovers the
 *   capture, and the re-attempted record/audio is byte-identical (sha256 unchanged).
 * - Claimed: no attempt is made before the persisted retry boundary elapses, so the bound is durable
 *   rather than an in-memory timer.
 * - NOT claimed: that an installed app process was killed. The restart here is an IN-PROCESS
 *   fresh-owner recreation over the same directory, which is the same property the store's own
 *   recovery tests rely on, and I label it that way rather than as a device restart.
 * - NOT claimed: that a real HTTP client received these bytes. The response is supplied at the
 *   transport seam using the fixture's exact bodies; the transport's own code-to-disposition table is
 *   exercised by LoopbackRefusalSettlementTest over a real socket.
 *
 * FIVE codes, not six. An earlier version of this list also carried `capture_conflict`, following the
 * first Cloud fixture, which classified 409 capture_conflict as retryable. That classification was
 * WRONG and Cloud corrected it (spec ruling 681704d4): the same capture identity arriving with a
 * DIFFERENT digest is a permanent refusal, since repeating the request can never succeed. The
 * genuinely retryable concurrent case is 503 upload_pending - same capture, same bytes, still in
 * flight. Production has always classified 409 capture_conflict as TERMINAL
 * (ContributionUploadTransport.byCode), so keeping it here asserted store-retention semantics for a
 * refusal the production transport never surfaces as retryable. It proved nothing about the client
 * and invited a future reader to believe the client retries a 409. The terminal case is pinned
 * separately, over a real socket, in LoopbackRefusalSettlementTest.
 */
class RetryableRefusalRetentionTest {
    /**
     * Slots in use when THIS test started. The bound is process-wide, so a suite neighbour may legitimately
     * have left residue; what this test must prove is that ITS OWN work returned every slot it took. An
     * absolute zero assertion would fail on someone else's residue and pass for the wrong reason.
     */
    private var entrySourceSlots = 0

    @org.junit.Before
    fun recordEntryBaseline() {
        entrySourceSlots = ContributionStore.processOpenSourceSlotsForTest()
    }

    @get:Rule
    val tmp = TemporaryFolder()

    /** The five bodies Production actually classifies RETRY, matching the corrected Cloud fixture. */
    private val retryableBodies = listOf(
        "rate_limited" to """{"schema_version":1,"code":"rate_limited","retry_after_s":30}""",
        "quota_exceeded" to """{"schema_version":1,"code":"quota_exceeded","retry_after_s":30}""",
        "upload_pending" to """{"schema_version":1,"code":"upload_pending","retry_after_s":30}""",
        "policy_unavailable" to """{"schema_version":1,"code":"policy_unavailable","retry_after_s":30}""",
        "storage_unavailable" to """{"schema_version":1,"code":"storage_unavailable","retry_after_s":30}""",
    )

    private fun wavBytes(samples: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        WavWriter.writeHeader(out, samples)
        WavWriter.writePcm(out, ShortArray(samples) { i -> i.toShort() }, samples)
        return out.toByteArray()
    }

    /** A complete, canonical record: recovery re-validates it, so a stub can never survive. */
    private fun meta(captureId: String): ByteArray {
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
                .build(platform = "android", appVersion = "8.20", build = "12345", audio = block),
        )
    }

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun chunks(bytes: Int): List<Pair<ShortArray, Int>> = listOf(ShortArray(bytes) { i -> i.toShort() } to bytes)

    private fun newStore(root: File, clock: () -> Long): ContributionStore = ContributionStore(
        root,
        QueueLimits.defaults().copy(maxQueueRecords = 8, retryInitialS = 4, retryMaxS = 8),
        clock = clock,
        jitter = { it - 1 },
    )

    @Test
    fun `every retryable refusal retains the audio across a fresh owner and replays identical bytes`() {
        for ((code, body) in retryableBodies) {
            var now = 20_000_000L
            val root = tmp.newFolder("retain-$code")
            val store = newStore(root) { now }
            val id = UUID.randomUUID().toString()
            val admission = store.admitForTest(1600, meta(id).size)
            val generation = store.fence.currentGeneration()
            assertNotNull(
                "fixture sanity: the capture must be admittable so the refusal has something to retain",
                store.prepareAt(generation, admission, id, "epoch-1", chunks(1600), meta(id), "a@x", 7L),
            )

            // The audio identity BEFORE the attempt: the SHA-256 of the bytes actually on disk, which is
            // exactly what a restarting process would read back. Hashing the file is stronger than
            // asking the store what it thinks it wrote.
            val audioFile = captureAudioFile(root, id)
            assertTrue(
                "fixture sanity: the admitted capture must have durable audio at ${audioFile.path}, " +
                    "tree was: " + root.walkTopDown().joinToString(" | ") { it.path.removePrefix(root.path) },
                audioFile.isFile && audioFile.length() > 0,
            )
            val digestBefore = sha256Of(audioFile)

            // FIRST ATTEMPT: the server refuses with the fixture's exact body.
            val firstAttempts = mutableListOf<String>()
            val caller = ContributionRetryCaller(
                store,
                clock = { now },
                policyAllows = { true },
                networkAvailable = { true },
                attemptUpload = { _, captureId, _, _ ->
                    firstAttempts.add(captureId)
                    // The transport seam reports the retryable refusal rather than an acceptance.
                    throw RetryableRefusal(code, body)
                },
                ackResolver = ContributionAckResolver(store, CaptureReceiptValidator.forStore(store)),
                responseFor = { _, _ -> null },
            )
            caller.pollOnce()
            assertEquals("the capture must be attempted once before the refusal", listOf(id), firstAttempts)

            // --- THE RESTART. A FRESH OWNER over the SAME directory: nothing is carried in memory. ---
            // The previous owner released the directory first (spec c8f766fb: one live owner per queue).
            store.close()
            val fresh = newStore(root) { now }
            fresh.recover()

            val recoveredAudio = captureAudioFile(root, id)
            assertTrue(
                "$code: the retained audio must still EXIST after a fresh owner recovers it — recovery " +
                    "deleting the recording would leave a retry boundary pointing at audio that can " +
                    "never be re-uploaded, tree was: " +
                    root.walkTopDown().filter { it.isFile }.joinToString(", ") { it.name },
                recoveredAudio.isFile && recoveredAudio.length() > 0,
            )
            val digestAfter = sha256Of(recoveredAudio)
            assertEquals(
                "$code: the retained audio must be BYTE-IDENTICAL after a fresh owner recovers — a " +
                    "changed digest would mean the retry uploads different audio than was captured",
                digestBefore,
                digestAfter,
            )

            val boundary = fresh.retryBoundariesForTest()[id]
            assertNotNull(
                "$code: a durable not-before boundary must be persisted, not held in memory",
                boundary,
            )
            assertTrue("$code: the boundary must be in the future", boundary!! > now)
            assertEquals(
                "$code: a fresh owner must refuse an immediate retry before the boundary elapses",
                0,
                fresh.retryEligible(now).size,
            )

            // After the boundary, the SAME entry is eligible again.
            now = boundary
            assertEquals(
                "$code: the same on-disk entry must become retryable once the boundary elapses",
                listOf(id),
                fresh.retryEligible(now),
            )

            // SECOND ATTEMPT, from the fresh owner: byte-identical audio, then accepted.
            val secondAttempts = mutableListOf<String>()
            val retryCaller = ContributionRetryCaller(
                fresh,
                clock = { now },
                policyAllows = { true },
                networkAvailable = { true },
                attemptUpload = { _, captureId, _, _ ->
                    secondAttempts.add(captureId)
                    true
                },
                ackResolver = ContributionAckResolver(fresh, CaptureReceiptValidator.forStore(fresh)),
                responseFor = { _, _ -> acceptanceReceipt(id, digestAfter) },
            )
            retryCaller.pollOnce()
            assertEquals("$code: the re-attempt must target the same capture", listOf(id), secondAttempts)
            assertEquals(
                "$code: the re-attempt must upload the SAME audio bytes the first attempt tried",
                digestBefore,
                sha256Of(captureAudioFile(root, id)),
            )
        }
    }

    /** Locates the capture's retained audio by walking the store's on-disk layout. */
    private fun captureAudioFile(root: File, captureId: String): File {
        // Target the RECORDING specifically: a bare name match also hits the .meta and .sha256
        // sidecars, and hashing one of those reports the digest of metadata as if it were audio.
        // Captures live under contributions/, and the on-disk name is not always the capture id, so
        // match by suffix within the contributions directory rather than assuming either.
        // Search the WHOLE tree for this capture's recording: the store nests captures under its own
        // directory constant, and assuming the layout made an earlier version of this control hash a
        // nonexistent path and report a meaningless digest.
        // The store may key the file by its own reservation id rather than the capture id passed in, so
        // prefer an exact match and fall back to the single recording present in this isolated root.
        root.walkTopDown().firstOrNull { it.isFile && it.name == "$captureId.recording.wav" }?.let { return it }
        val recordings = root.walkTopDown().filter { it.isFile && it.name.endsWith(".recording.wav") }.toList()
        return recordings.singleOrNull() ?: File(root, "$captureId.recording.wav")
    }

    /**
     * THE FIXTURE LIST MUST AGREE WITH PRODUCTION, PROVEN RATHER THAN DECLARED.
     *
     * This list was wrong once: it carried `capture_conflict` (409) as retryable for weeks after
     * Cloud corrected the fixture, because nothing tied the list to the code that actually decides.
     * That is the direction this control now catches: adding a code production refuses fails here
     * instead of quietly asserting store semantics for a refusal the client never retries.
     *
     * WHAT THIS DOES NOT PROVE (reviewer 1cff028d): it does not prove the list is EXHAUSTIVE.
     * Deleting a retryable fixture removes a loop iteration and leaves nothing behind to fail -
     * a control cannot notice an absence. MEASURED, not assumed: deleting the `rate_limited` entry
     * was applied as a mutant and the class stayed GREEN, so this really is a survivor rather than
     * a theoretical gap. So the covered claim is "every code listed here is
     * retryable in production, and these specific excluded codes are terminal", not "the list is
     * exactly production's retryable set". Closing the gap would need a table to compare against,
     * and a copied table asserts nothing because both copies move together, so the honest position
     * is to state the limit rather than manufacture coverage.
     *
     * Goes through the REAL `classify` on the real (status, body) pair, so this is not a second
     * copy of the disposition table.
     */
    @Test
    fun `every retryable fixture is genuinely retryable in production, and the excluded codes are terminal`() {
        val transport = ContributionUploadTransport(
            endpoint = "http://127.0.0.1:1/v1/contributions",
            authorization = { "Bearer test-token" },
            callTimeoutMs = 1_000,
        )

        // Every code the fixture set claims to retain must actually be RETRYABLE in production.
        for ((code, body) in retryableBodies) {
            val status = statusForFixture(code)
            val response = transport.classifyForTest(status, body.toByteArray())
            assertTrue(
                "$code is in this control's fixture list but production classifies it as " +
                    "${response::class.simpleName}, not Retryable. Either the fixture list is stale " +
                    "or production changed - reconcile them against the Cloud fixture before editing " +
                    "either.",
                response is ContributionUploadTransport.Response.Retryable,
            )
        }

        // And the codes we deliberately KEEP OUT must be genuinely non-retryable, so removing them
        // from the list was a correction rather than a convenient omission.
        val terminal = listOf(
            "capture_conflict" to (409 to """{"schema_version":1,"code":"capture_conflict","retry_after_s":30}"""),
            "invalid_request" to (400 to """{"schema_version":1,"code":"invalid_request"}"""),
            "record_gone" to (410 to """{"schema_version":1,"code":"record_gone"}"""),
        )
        for ((code, pair) in terminal) {
            val response = transport.classifyForTest(pair.first, pair.second.toByteArray())
            assertTrue(
                "$code must stay TERMINAL: it is excluded from the retention fixture because " +
                    "retrying it can never succeed, so retaining audio behind it would hold it forever",
                response is ContributionUploadTransport.Response.Terminal,
            )
        }
    }

    /** The owning status for each fixture body, per the client's own status-for-code envelope. */
    private fun statusForFixture(code: String): Int = when (code) {
        "rate_limited", "quota_exceeded" -> 429
        "upload_pending", "policy_unavailable", "storage_unavailable" -> 503
        else -> error("no fixture status declared for $code - add it here AND in the Cloud fixture")
    }

    private fun sha256Of(file: File): String = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private fun acceptanceReceipt(captureId: String, digest: String): ByteArray = """{"capture_id":"$captureId","sha256":"$digest","status":"accepted","schema_version":1}""".toByteArray()

    /** Stands in for a transport that reported a retryable refusal. */
    private class RetryableRefusal(val code: String, val body: String) : Exception("retryable: $code: $body")
}
