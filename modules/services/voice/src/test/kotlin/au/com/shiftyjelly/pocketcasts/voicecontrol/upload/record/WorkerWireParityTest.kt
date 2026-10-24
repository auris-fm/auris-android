package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.WavWriter
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WIRE PARITY: the Android builder's output must be accepted by the Cloud Worker's OWN validator.
 *
 * Why this test and not a locally derived one: the Android builder, its validator and its fixtures
 * can all agree on a shape the server refuses. That actually happened — the first decoder I wrote
 * shared the builder's constants and was internally consistent while the Worker rejected the same
 * record for `capture_session_id`, `models`, `observations` and most stage field names. Sharing
 * local constants proves nothing about the wire, so the authority here is the Worker's
 * `record_schema.ts`, executed directly.
 *
 * The authority is PINNED to [approvedWorkerRevision] and a failure — not a skip — is the correct
 * outcome when it is unavailable, dirty, or at another revision. The committed receipt
 * `artifacts/receipts/wire-parity-worker-intake.txt` records a real run against that revision.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class WorkerWireParityTest {

    /**
     * The Worker authority. Must be a PINNED revision, and absence is a FAILURE, not a skip.
     *
     * A parity control that green-skips when it cannot reach the thing it compares against reports
     * success it did not earn. `AURIS_CLOUD_WORKER_ROOT` should name a worktree at the reviewed
     * revision; the fallback path only exists so a local run without the variable is still explicit
     * about which checkout it used.
     */
    private fun cloudWorkerRoot(): File = System.getenv("AURIS_CLOUD_WORKER_ROOT")?.let(::File)
        ?: File("/Users/dev/code/auris/core/cloud-voice-uploads/worker")

    /**
     * Records the EXACT Worker revision and Android source head this run compared, so a result
     * archive identifies its own authority instead of leaving a reader to infer it (reviewer
     * fd258849, spec cc56440e).
     *
     * The test source permits any revision in [approvedWorkerRevisions] because each later pin is a
     * strict superset of the earlier ones. That is a convenience for not re-editing five call sites
     * on every clearance, and it is exactly why the run has to WRITE DOWN which pin it actually
     * checked: "some approved pin" is not a receipt.
     *
     * Written to build/worker-parity-receipt.txt, which the commit archives alongside the XML. Best
     * effort - a failure to write never fails the control, because a missing receipt is a reporting
     * gap and not a reason to red a passing comparison.
     */
    private fun recordRunReceipt(workerRoot: File, reportedRevision: String?, ruleScope: String) {
        try {
            val head = ProcessBuilder("git", "rev-parse", "HEAD")
                .directory(File(".").absoluteFile)
                .redirectErrorStream(true)
                .start()
                .inputStream.bufferedReader().readText().trim()
            val workerHead = ProcessBuilder("git", "-C", workerRoot.absolutePath, "rev-parse", "HEAD")
                .redirectErrorStream(true)
                .start()
                .inputStream.bufferedReader().readText().trim()
            // HEAD ALONE DOES NOT BIND THE TESTED BYTES (reviewer 7a2e10a6): a dirty checkout can run
            // code that matches no commit. Record whether the tree was clean, and a content digest of
            // the inputs the comparison actually reads, so the archive identifies what ran.
            val dirty = ProcessBuilder("git", "status", "--porcelain")
                .directory(File(".").absoluteFile)
                .redirectErrorStream(true)
                .start()
                .inputStream.bufferedReader().readText().trim()
            val probeSource = javaClass.classLoader!!
                .getResourceAsStream("worker-parity/probe_worker_intake.mjs")!!.readBytes()
            val contentDigest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(probeSource)
                .joinToString("") { "%02x".format(it) }
            val out = File("build/worker-parity-receipt.txt")
            out.parentFile?.mkdirs()
            out.writeText(
                buildString {
                    appendLine("android_source_head=$head")
                    appendLine("android_source_clean=${dirty.isEmpty()}")
                    if (dirty.isNotEmpty()) {
                        appendLine("android_source_dirty_paths=$dirty")
                    }
                    appendLine("probe_sha256=$contentDigest")
                    appendLine("worker_root=${workerRoot.absolutePath}")
                    appendLine("worker_head=$workerHead")
                    appendLine("worker_reported_revision=${reportedRevision?.trim()}")
                    appendLine("rule_scope=$ruleScope")
                },
            )
        } catch (e: Exception) {
            // Never fail the comparison over receipt bookkeeping.
        }
    }

    //
    // The Worker revision these controls compare against, and the SCOPE of that comparison.
    //
    // SCOPE, stated exactly: `d8fe823c` is pinned for INTAKE GUARD behaviour only — the two digest
    // checks and the record schema this fixture exercises. It is NOT a whole-server approval: the
    // reviewer blocked that same revision's route changes (empty-digest reserve placeholders), so
    // nothing here says anything about upload_route.ts, budgeting or delivery. Advancing this value
    // is a deliberate edit made when a specific, named behaviour is re-cleared — not a general
    // blessing of the server revision.
    //
    // Both controls must compare against the SAME revision, or their combined evidence describes two
    // different servers. Advancing this value is a deliberate, reviewable edit made when a new
    // revision is cleared.
    //

    /**
     * BOTH approved pinned authorities, each for a named behaviour scope (advancing this list is a
     * deliberate, reviewable edit):
     *  - d8fe823c: pre-delta INTAKE GUARD baseline (digest checks + record schema fixtures);
     *  - c569b319: delta736d3180 strict intake (observation_unavailable with state unknown).
     * c569b319 is pushed source for fixture/intake parity — NOT a deployed live intake (spec).
     */
    private val emptyResultRevision = "a030ff31d95dd703fab6c5c976f36826ef77f499"

    private val approvedWorkerRevisions = setOf(
        "d8fe823cbe43ebb310d632b482312c7b282ef018",
        "c569b319e19b5971ada44a0f53245630688c1305",
        // a030ff31 is the c6 `empty_result` intake pin, independently cleared by Reviewer (42 schema
        // tests, both typechecks, explicit text:null/router_input:null negatives).
        "a030ff31d95dd703fab6c5c976f36826ef77f499",
        "3dd0c91d095512e3c49108c25b726a3b66a66eef",
        "862c7ed3fdc540eccc9fdacd6819c97a9e679536",
    )

    private fun requireAuthority(root: File) {
        assertTrue(
            "INTEGRITY: the Worker schema authority is missing at ${root.absolutePath}. Point " +
                "AURIS_CLOUD_WORKER_ROOT at a worktree at one of $approvedWorkerRevisions; a skipped " +
                "parity check is not parity.",
            File(root, "src/contributions/record_schema.ts").isFile,
        )
        assertTrue("INTEGRITY: node is required to execute the Worker validator", nodeAvailable())
    }

    /** A clean checkout of some other revision is a different server; that is a refusal, not a pass. */
    private fun assertApprovedRevision(output: String) {
        val line = output.lines().firstOrNull { it.startsWith("# worker-revision") }
            ?: error("probe did not report its Worker revision; refusing to claim parity")
        val reported = line.substringAfter("\t").trim()
        assertTrue("INTEGRITY: authority reports '$reported' — dirty is not an authority", reported.endsWith("CLEAN"))
        assertTrue(
            "INTEGRITY: parity ran against '${reported.removeSuffix("CLEAN").trim()}' which is not an " +
                "approved authority: $approvedWorkerRevisions",
            reported.removeSuffix("CLEAN").trim() in approvedWorkerRevisions,
        )
    }

    private fun nodeAvailable(): Boolean = runCatching {
        ProcessBuilder("node", "--version").start().waitFor() == 0
    }.getOrDefault(false)

    private fun audioFor(sampleCount: Int): Pair<AudioBlock, ByteArray> {
        val out = java.io.ByteArrayOutputStream()
        WavWriter.writeHeader(out, sampleCount)
        WavWriter.writePcm(out, ShortArray(sampleCount) { i -> i.toShort() }, sampleCount)
        val bytes = out.toByteArray()
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return AudioBlock(
            sha256 = sha,
            byteCount = bytes.size.toLong(),
            retainedSamples = sampleCount.toLong(),
            originalFormat = "pcm_s16le",
            conversionVersion = "v1",
            truncated = false,
            droppedSpans = emptyList(),
        ) to bytes
    }

    /** A complete, spec-conforming record: every stage and every model key declared. */
    private fun fullyPopulatedRecord(captureId: String = UUID.randomUUID().toString()): RecordNode {
        val (audio, _) = audioFor(1600)
        return ContributionRecordBuilder(captureId, "epoch-1", "42")
            .model("vad", ModelRef.Named("silero-vad", "4.0"))
            .model("wakeword", ModelRef.Named("auris-wake", "1.2"))
            .model("asr", ModelRef.Named("whisper-small", "2024-11"))
            .model("translation", ModelRef.None)
            .model("router", ModelRef.Named("lfm-intent", "0.3"))
            .stage(
                StageName.Wake,
                StageEntry(
                    StageState.Completed,
                    mapOf(
                        "decision" to RecordNode.Str("detected"),
                        "score" to RecordNode.Num(0.82),
                        "threshold" to RecordNode.Num(0.5),
                        "speech_offset_ms" to RecordNode.ofInt(120),
                        "wake_offset_ms" to RecordNode.ofInt(340),
                    ),
                ),
            )
            .stage(StageName.Bleed, StageEntry(StageState.Completed, mapOf("decision" to RecordNode.Str("no_overlap"), "overlap_ratio" to RecordNode.Num(0.01))))
            .stage(StageName.Asr, StageEntry(StageState.Completed, mapOf("text" to RecordNode.Str("skip this ad"), "source_language" to RecordNode.Str("en"))))
            .stage(StageName.Translation, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_text"))))
            .stage(
                StageName.Routing,
                StageEntry(
                    StageState.Completed,
                    mapOf(
                        "predicted_intent" to RecordNode.Str("skip_ad"),
                        "validation" to RecordNode.Str("valid"),
                        "execution_disposition" to RecordNode.Str("executed"),
                    ),
                ),
            )
            .stage(StageName.Execution, StageEntry(StageState.Completed, mapOf("outcome" to RecordNode.Str("success"))))
            .build(platform = "android", appVersion = "8.20", build = "12345", audio = audio)
    }

    /** A record using only declared-absence forms: null models, not_run stages with reasons. */
    private fun minimalValidRecord(): RecordNode {
        val (audio, _) = audioFor(160)
        return ContributionRecordBuilder(UUID.randomUUID().toString(), "epoch-2", "7")
            .model("vad", ModelRef.None)
            .model("wakeword", ModelRef.None)
            .model("asr", ModelRef.None)
            .model("translation", ModelRef.None)
            .model("router", ModelRef.None)
            .stage(StageName.Wake, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_wake_detected"))))
            .stage(StageName.Bleed, StageEntry(StageState.Unknown, mapOf("reason" to RecordNode.Str("outcome_wait_expired"))))
            .stage(StageName.Asr, StageEntry(StageState.Cancelled, mapOf("reason" to RecordNode.Str("cancelled_by_user"))))
            .stage(StageName.Translation, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_text"))))
            .stage(StageName.Routing, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_candidates"))))
            .stage(StageName.Execution, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("outcome_wait_expired"))))
            .build(platform = "android", appVersion = "8.20", build = "12345", audio = audio)
    }

    @Test
    fun `android-built records are accepted by the actual Worker validator`() {
        val workerRoot = cloudWorkerRoot()
        requireAuthority(workerRoot)

        // Completed translation with BOTH observed fields (reviewer 57719ad2): the Worker's
        // schema owns translated_text AND target_language; this case proves strict intake
        // accepts a record where both are present AND distinct, so the client fix is not a
        // client-only wire field.
        val translated = ContributionRecordBuilder(UUID.randomUUID().toString(), "epoch-1", "42")
            .model("vad", ModelRef.Named("silero-vad", "4.0"))
            .model("wakeword", ModelRef.Named("auris-wake", "1.2"))
            .model("asr", ModelRef.Named("whisper-small", "2024-11"))
            .model("translation", ModelRef.Named("whisper-translate", "2024-11"))
            .model("router", ModelRef.Named("lfm-intent", "0.3"))
            .stage(StageName.Wake, StageEntry(StageState.Completed, mapOf("decision" to RecordNode.Str("detected"))))
            .stage(StageName.Bleed, StageEntry(StageState.Completed, mapOf("decision" to RecordNode.Str("no_overlap"))))
            .stage(StageName.Asr, StageEntry(StageState.Completed, mapOf("text" to RecordNode.Str("les étagères sont vides"), "source_language" to RecordNode.Str("fr"))))
            .stage(
                StageName.Translation,
                StageEntry(
                    StageState.Completed,
                    mapOf(
                        "translated_text" to RecordNode.Str("the shelves are empty"),
                        "target_language" to RecordNode.Str("en"),
                    ),
                ),
            )
            .stage(StageName.Routing, StageEntry(StageState.Completed, mapOf("predicted_intent" to RecordNode.Str("skip_ad"), "validation" to RecordNode.Str("valid"), "execution_disposition" to RecordNode.Str("executed"))))
            .stage(StageName.Execution, StageEntry(StageState.Completed, mapOf("outcome" to RecordNode.Str("success"))))
            .build("android", "8.20", "12345", audioFor(1600).first)
        val records = listOf(
            "fully-populated" to fullyPopulatedRecord(),
            "declared-absence" to minimalValidRecord(),
            "completed-translation-both-fields" to translated,
        )
        val recordsFile = Files.createTempFile("android-records", ".jsonl").toFile()
        val probe = File(recordsFile.parentFile, "probe_worker_intake.mjs")
        val probeSource = javaClass.classLoader!!.getResourceAsStream("worker-parity/probe_worker_intake.mjs")
        check(probeSource != null) { "worker parity probe resource missing" }
        probe.writeBytes(probeSource.readBytes())

        recordsFile.writeText(
            records.joinToString("\n") { (id, record) ->
                val json = record.let { CanonicalJson.canonicalBytes(it).toString(Charsets.UTF_8) }
                // The record is embedded as a JSON value, not a string: build the envelope by hand
                // so the Worker sees the exact object shape.
                """{"id":"$id","record":$json}"""
            },
        )

        val process = ProcessBuilder("node", probe.absolutePath, workerRoot.absolutePath, recordsFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("probe failed: $output", 0, process.waitFor())

        assertApprovedRevision(output)
        val verdicts = output.lines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
            val parts = line.split('\t')
            parts[0] to parts.drop(1).joinToString("\t")
        }
        for ((id, _) in records) {
            assertEquals("Android record '$id' was NOT accepted by the Worker: ${verdicts[id]}", "ACCEPT", verdicts[id])
        }
        assertTrue("no verdicts produced", verdicts.isNotEmpty())
    }

    /**
     * PARITY ON THE REAL EMITTED RECORD (spec 52e0f608).
     *
     * Every other control here builds its own record, so agreement with the Worker says only that the
     * schema agrees with a fixture I wrote. This one submits the EXACT bytes the joined engine path
     * durably published (see VoiceUploadCompositionPathTest, which hands them over through
     * EmittedJoinedRecordHolder), so the Worker's verdict is about the record the engine produced. The
     * holder carries a sha256 of those bytes, and this control refuses if the bytes it submits do not
     * hash to it — a truncated or re-serialized payload cannot pass as "the emitted record".
     *
     * Ordering: the holder is filled by the composition control. When it is empty (this control ran
     * first) the test FAILS rather than skipping — an unproduced record is not a passing parity claim.
     */
    @Test
    fun `the record the joined engine path actually emitted is accepted by the pinned Worker`() {
        // PRODUCED BY THE SAME EMITTER, not read from a fixture. Robolectric gives each test class its
        // own sandbox classloader, so an in-memory handoff between classes does not exist here; instead
        // this control calls the joined-path emitter directly (it is the same code the composition
        // control uses) and then checks the record it produced is byte-identical to the one that
        // control persisted. Same emitter + identical bytes = the Worker is being asked about the
        // engine's record, and a divergence between the two controls fails loudly instead of passing.
        val produced = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.VoiceUploadCompositionPaths.emitOnce()
        val emitted = produced.bytes
        val declaredSha = produced.sha256
        val actualSha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(emitted.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals("the emitted record bytes do not hash to the recorded digest", declaredSha, actualSha)
        val handoff = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.VoiceUploadCompositionPaths.handoffFile()
        if (handoff.isFile) {
            assertEquals(
                "the composition control persisted different bytes than this control re-emitted — the " +
                    "two controls would be measuring different records",
                handoff.readText(),
                emitted,
            )
        }
        val node = CanonicalJson.parse(emitted)
        assertEquals("the emitted record must be already canonical", emitted, CanonicalJson.canonicalBytes(node).toString(Charsets.UTF_8))

        // Named delta authority for this control; independently verified against the Worker itself.
        val deltaRevision = "c569b319e19b5971ada44a0f53245630688c1305"
        val workerRoot = cloudWorkerRoot()
        requireAuthority(workerRoot)
        val recordsFile = Files.createTempFile("android-emitted-record", ".jsonl").toFile()
        val probe = File(recordsFile.parentFile, "probe_worker_intake.mjs")
        probe.writeBytes(javaClass.classLoader!!.getResourceAsStream("worker-parity/probe_worker_intake.mjs")!!.readBytes())
        recordsFile.writeText("""{"id":"joined-emitted","record":$emitted}""")
        val process = ProcessBuilder("node", probe.absolutePath, workerRoot.absolutePath, recordsFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("probe failed: $output", 0, process.waitFor())
        val revisionLine = output.lines().firstOrNull { it.startsWith("# worker-revision") }
        assertTrue(
            "this control requires an intake that implements delta736d3180 — $deltaRevision or any " +
                "later approved pin, each a strict superset (got: $revisionLine)",
            approvedWorkerRevisions.any { revisionLine?.contains(it) == true },
        )
        assertAuthorityPinsReported(output, deltaRevision)
        val verdict = output.lines().firstOrNull { it.startsWith("joined-emitted") }
        assertEquals(
            "the Worker did not accept the record the engine actually emitted: $verdict",
            "joined-emitted\tACCEPT",
            verdict,
        )
        println("JOINED-EMITTED-RECORD sha256=$actualSha")
        println("  worker-verdict -> ACCEPT")
    }

    /** Each pin the probe prints must name an exact CLEAN revision AND a purpose, not a bare hash. */
    private fun assertAuthorityPinsReported(output: String, revisionInUse: String) {
        val pins = output.lines().filter { it.startsWith("# authority-pin") }.map { it.split('\t') }
        assertTrue("the probe reported no authority pins: $output", pins.isNotEmpty())
        for (pin in pins) {
            assertTrue("a pin must name its revision and purpose: $pin", pin.size == 3 && pin[2].isNotBlank())
            assertTrue("pin revision must be a full sha: ${pin[1]}", Regex("^[0-9a-f]{40}$").matches(pin[1]))
            assertTrue("pin ${pin[1]} is not an approved authority", pin[1] in approvedWorkerRevisions)
        }
        assertTrue(
            "the authority actually in use ($revisionInUse) must be one of the reported pins",
            pins.any { it[1] == revisionInUse },
        )
    }

    /**
     * delta736d3180 STRICT INTAKE PARITY against a pinned Cloud revision (c569b319 — pushed source
     * for fixture/intake parity, NOT a deployed live intake): the Worker accepts
     * `observation_unavailable` ONLY with state `unknown` and refuses the reason with any other
     * state, on ALL SIX stages (spec 52e0f608), so a per-stage divergence cannot hide behind the one
     * stage I happened to test.
     *
     * Android builder records carry the ACCEPT cells (one per stage). The REJECT cells flip the state
     * on the canonical bytes of the SAME record: the client builder refuses that pair locally (proven
     * in ContributionRecordTest), so the wire-level variant is the only way to ask the Worker.
     */
    @Test
    fun `the pinned Worker accepts the combined c6 f8 4d bytes the builder emits`() {
        // SPEC 20:53Z: 3dd0c91d is the cleared COMBINED c6/f8/4d intake pin. This control sends the
        // builder's own canonical bytes and asks the real Worker about all three rules at once, which
        // is the composition that actually ships - separate per-rule pins could each pass while the
        // combination diverges.
        val pin = "3dd0c91d095512e3c49108c25b726a3b66a66eef"
        val workerRoot = cloudWorkerRoot()
        requireAuthority(workerRoot)

        fun baseBuilder(): Pair<ContributionRecordBuilder, AudioBlock> {
            val (audio, _) = audioFor(160)
            return ContributionRecordBuilder(UUID.randomUUID().toString(), "epoch-2", "7")
                .model("vad", ModelRef.None)
                .model("wakeword", ModelRef.None)
                .model("asr", ModelRef.None)
                .model("translation", ModelRef.None)
                .model("router", ModelRef.None)
                .stage(StageName.Wake, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_wake_detected"))))
                .stage(StageName.Bleed, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_playback_reference"))))
                .stage(StageName.Asr, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("local_only_action"))))
                .stage(StageName.Translation, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_text"))))
                .stage(StageName.Routing, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_candidates"))))
                .stage(StageName.Execution, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("client_refused"))))
                .let { b -> b to audio }
        }

        fun emit(stage: StageName, entry: StageEntry): String {
            val (builder, audio) = baseBuilder()
            return CanonicalJson.canonicalBytes(
                builder.replaceStage(stage, entry)
                    .build(platform = "android", appVersion = "8.20", build = "12345", audio = audio),
            ).toString(Charsets.UTF_8)
        }

        // THE THREE POSITIVES, each a real shape this client can produce.
        val emptyAsr = emit(StageName.Asr, StageEntry(StageState.Completed, mapOf("reason" to RecordNode.Str("empty_result"))))
        val rawTranscript = emit(
            StageName.Asr,
            StageEntry(StageState.Completed, mapOf("text" to RecordNode.Str("hey auris"), "source_language" to RecordNode.Str("en"))),
        )
        val translationSkip = emit(
            StageName.Translation,
            StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("backend_handles_translation"))),
        )

        // Sanity: the emitted bytes really contain each shape, so a silently-absent field cannot make
        // the corresponding ACCEPT below meaningless.
        assertTrue(
            "the asr bytes must contain the empty_result pair: $emptyAsr",
            emptyAsr.contains("\"asr\":{\"reason\":\"empty_result\",\"state\":\"completed\"}"),
        )
        assertTrue(
            "the wake-only bytes must carry the raw transcript: $rawTranscript",
            rawTranscript.contains("\"text\":\"hey auris\""),
        )
        assertTrue(
            "the translation bytes must contain the skip reason: $translationSkip",
            translationSkip.contains("\"reason\":\"backend_handles_translation\""),
        )

        val cases = linkedMapOf(
            "accept-empty-asr" to emptyAsr,
            "accept-raw-transcript" to rawTranscript,
            "accept-translation-skip" to translationSkip,
        )

        // REJECT cells, perturbed on canonical output (sorted keys, stable shape), each asserted to
        // have actually changed the bytes.
        fun perturb(id: String, source: String, from: String, to: String) {
            val changed = source.replace(from, to)
            assertNotEquals("the perturbation $id must actually change the wire bytes", source, changed)
            cases["reject-$id"] = changed
        }
        // f8cac147: a completed non-ASR stage may not carry a reason.
        perturb(
            "completed-wake-with-reason",
            rawTranscript,
            "\"wake\":{\"reason\":\"no_wake_detected\",\"state\":\"not_run\"}",
            "\"wake\":{\"reason\":\"no_wake_detected\",\"state\":\"completed\"}",
        )
        // c6f3bdf7: empty_result may not carry text.
        perturb(
            "empty-asr-with-text",
            emptyAsr,
            "\"asr\":{\"reason\":\"empty_result\",\"state\":\"completed\"}",
            "\"asr\":{\"reason\":\"empty_result\",\"state\":\"completed\",\"text\":\"hi\"}",
        )
        // 4d10275f: a skip reason is not_run-only, `failed` included.
        perturb(
            "translation-skip-at-failed",
            translationSkip,
            "\"translation\":{\"reason\":\"backend_handles_translation\",\"state\":\"not_run\"}",
            "\"translation\":{\"reason\":\"backend_handles_translation\",\"state\":\"failed\"}",
        )
        // 4d10275f: and it belongs to translation, not any other stage.
        perturb(
            "translation-skip-on-wake",
            translationSkip,
            "\"wake\":{\"reason\":\"no_wake_detected\",\"state\":\"not_run\"}",
            "\"wake\":{\"reason\":\"backend_handles_translation\",\"state\":\"not_run\"}",
        )

        val recordsFile = Files.createTempFile("android-combined", ".jsonl").toFile()
        val probe = File(recordsFile.parentFile, "probe_worker_intake.mjs")
        probe.writeBytes(javaClass.classLoader!!.getResourceAsStream("worker-parity/probe_worker_intake.mjs")!!.readBytes())
        recordsFile.writeText(cases.entries.joinToString("\n") { (id, json) -> """{"id":"$id","record":$json}""" })
        val process = ProcessBuilder("node", probe.absolutePath, workerRoot.absolutePath, recordsFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("probe failed: $output", 0, process.waitFor())
        val revisionLine = output.lines().firstOrNull { it.startsWith("# worker-revision") }
        assertTrue(
            "this control requires the combined intake pin $pin (got: $revisionLine)",
            revisionLine?.contains(pin) == true,
        )
        assertAuthorityPinsReported(output, pin)
        // The receipt names the pin this run actually checked, so the archived result identifies its
        // own authority rather than leaving a reader to infer it from a permitted-set check.
        recordRunReceipt(
            workerRoot = workerRoot,
            reportedRevision = revisionLine,
            ruleScope = "combined c6f3bdf7/f8cac147/4d10275f intake: empty_result, completed-stage " +
                "reason rule, translation skip reasons - emitted-byte parity only",
        )
        val verdicts = output.lines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
            val parts = line.split('\t')
            parts[0] to parts.drop(1).joinToString("\t")
        }
        for (id in listOf("accept-empty-asr", "accept-raw-transcript", "accept-translation-skip")) {
            assertEquals(
                "$id must be ACCEPTed by the pinned Worker: ${verdicts[id]}",
                "ACCEPT",
                verdicts[id],
            )
        }
        for (id in cases.keys.filter { it.startsWith("reject-") }) {
            assertTrue(
                "$id must be REFUSED by the pinned Worker, got: ${verdicts[id]}",
                verdicts[id]?.startsWith("REJECT") == true,
            )
        }
    }

    @Test
    fun `the pinned Worker accepts exactly the empty_result bytes the builder emits`() {
        // ACTUAL EMITTED-BYTE PARITY (spec 20:08Z, c6f3bdf7 / pin a030ff31).
        //
        // This is the half I previously could not prove: no intake revision implemented empty_result,
        // so I stated the gap rather than papering over it. a030ff31 closes it, so the control now
        // sends the BUILDER'S OWN CANONICAL BYTES to the real Worker and asserts the verdicts.
        val pin = "a030ff31d95dd703fab6c5c976f36826ef77f499"
        val workerRoot = cloudWorkerRoot()
        requireAuthority(workerRoot)

        fun buildAsr(vararg extra: Pair<String, RecordNode>, state: StageState = StageState.Completed): String {
            val (audio, _) = audioFor(160)
            return CanonicalJson.canonicalBytes(
                ContributionRecordBuilder(UUID.randomUUID().toString(), "epoch-2", "7")
                    .model("vad", ModelRef.None)
                    .model("wakeword", ModelRef.None)
                    .model("asr", ModelRef.None)
                    .model("translation", ModelRef.None)
                    .model("router", ModelRef.None)
                    .stage(StageName.Wake, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_wake_detected"))))
                    .stage(StageName.Bleed, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_playback_reference"))))
                    .stage(StageName.Asr, StageEntry(state, (mapOf("reason" to RecordNode.Str("empty_result")) + extra.toMap())))
                    .stage(StageName.Translation, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_text"))))
                    .stage(StageName.Routing, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_candidates"))))
                    .stage(StageName.Execution, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("client_refused"))))
                    .build(platform = "android", appVersion = "8.20", build = "12345", audio = audio),
            ).toString(Charsets.UTF_8)
        }

        // ACCEPT: the legitimate empty recognition, straight from the builder.
        val accepted = buildAsr()
        assertTrue(
            "the approved bytes must actually contain the empty_result pair: $accepted",
            accepted.contains("\"asr\":{\"reason\":\"empty_result\",\"state\":\"completed\"}"),
        )

        // REJECT: the same bytes perturbed to each contradiction. Perturb on canonical output (sorted
        // keys, stable shape) and assert each perturbation changed the bytes, so a no-op substitution
        // cannot make a negative pass vacuously.
        val cases = linkedMapOf("accept-empty-result" to accepted)
        fun perturb(id: String, from: String, to: String) {
            val changed = accepted.replace(from, to)
            assertNotEquals("the perturbation $id must actually change the wire bytes", accepted, changed)
            cases["reject-$id"] = changed
        }
        val emptyPair = "\"asr\":{\"reason\":\"empty_result\",\"state\":\"completed\"}"
        perturb("empty-result-with-text", emptyPair, "\"asr\":{\"reason\":\"empty_result\",\"state\":\"completed\",\"text\":\"hi\"}")
        perturb("empty-result-with-router-input", emptyPair, "\"asr\":{\"reason\":\"empty_result\",\"state\":\"completed\",\"router_input\":\"hi\"}")
        for (state in listOf("not_run", "unknown", "failed", "cancelled")) {
            perturb("empty-result-$state", emptyPair, "\"asr\":{\"reason\":\"empty_result\",\"state\":\"$state\"}")
        }
        perturb(
            "empty-result-on-wake",
            "\"wake\":{\"reason\":\"no_wake_detected\",\"state\":\"not_run\"}",
            "\"wake\":{\"reason\":\"empty_result\",\"state\":\"completed\"}",
        )

        val recordsFile = Files.createTempFile("android-empty-result", ".jsonl").toFile()
        val probe = File(recordsFile.parentFile, "probe_worker_intake.mjs")
        probe.writeBytes(javaClass.classLoader!!.getResourceAsStream("worker-parity/probe_worker_intake.mjs")!!.readBytes())
        recordsFile.writeText(cases.entries.joinToString("\n") { (id, json) -> """{"id":"$id","record":$json}""" })
        val process = ProcessBuilder("node", probe.absolutePath, workerRoot.absolutePath, recordsFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("probe failed: $output", 0, process.waitFor())
        val revisionLine = output.lines().firstOrNull { it.startsWith("# worker-revision") }
        assertTrue(
            "this control requires an intake implementing empty_result — $pin or any later approved " +
                "pin (got: $revisionLine)",
            approvedWorkerRevisions.any { revisionLine?.contains(it) == true },
        )
        assertAuthorityPinsReported(output, pin)
        val verdicts = output.lines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
            val parts = line.split('\t')
            parts[0] to parts.drop(1).joinToString("\t")
        }
        assertEquals(
            "the approved empty_result bytes must be ACCEPTed by the pinned Worker: ${verdicts["accept-empty-result"]}",
            "ACCEPT",
            verdicts["accept-empty-result"],
        )
        for (id in cases.keys - "accept-empty-result") {
            assertTrue(
                "$id must be REFUSED by the pinned Worker, got: ${verdicts[id]}",
                verdicts[id]?.startsWith("REJECT") == true,
            )
        }
    }

    @Test
    fun `observation_unavailable is accepted only with unknown on every stage by the pinned Worker`() {
        // The rule exists only from the delta revision onward; the pre-delta head would (correctly)
        // refuse the unknown pair, so this control pins the delta and fails if pointed elsewhere.
        // The LATER empty_result pin contains the delta, so it is an equally valid authority here and
        // running against it keeps both rules measured on one checkout.
        val deltaRevision = "c569b319e19b5971ada44a0f53245630688c1305"
        val workerRoot = cloudWorkerRoot()
        requireAuthority(workerRoot)
        val stages = listOf(
            StageName.Wake,
            StageName.Bleed,
            StageName.Asr,
            StageName.Translation,
            StageName.Routing,
            StageName.Execution,
        )
        val accepted = stages.associateWith { stage ->
            val (audio, _) = audioFor(160)
            ContributionRecordBuilder(UUID.randomUUID().toString(), "epoch-2", "7")
                .model("vad", ModelRef.None)
                .model("wakeword", ModelRef.None)
                .model("asr", ModelRef.None)
                .model("translation", ModelRef.None)
                .model("router", ModelRef.None)
                .stage(StageName.Wake, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_wake_detected"))))
                .stage(StageName.Bleed, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_playback_reference"))))
                .stage(StageName.Asr, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("local_only_action"))))
                .stage(StageName.Translation, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_text"))))
                .stage(StageName.Routing, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_candidates"))))
                .stage(StageName.Execution, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("client_refused"))))
                .replaceStage(stage, StageEntry(StageState.Unknown, mapOf("reason" to RecordNode.Str("observation_unavailable"))))
                .build(platform = "android", appVersion = "8.20", build = "12345", audio = audio)
        }.mapValues { (_, record) -> CanonicalJson.canonicalBytes(record).toString(Charsets.UTF_8) }

        // REJECT cells: the same canonical bytes with the state flipped to a known state. Built by
        // string replacement on canonical output (keys are sorted, so the shape is stable) and then
        // asserted to have actually changed.
        val rejectedStates = listOf("completed", "not_run", "failed", "cancelled")
        val cases = linkedMapOf<String, String>()
        for (stage in stages) {
            cases["accept-${stage.wire}-unknown"] = accepted.getValue(stage)
            for (state in rejectedStates) {
                val flipped = accepted.getValue(stage).replace(
                    "\"${stage.wire}\":{\"reason\":\"observation_unavailable\",\"state\":\"unknown\"}",
                    "\"${stage.wire}\":{\"reason\":\"observation_unavailable\",\"state\":\"$state\"}",
                )
                assertNotEquals(
                    "the perturbation for ${stage.wire}/$state must actually change the wire bytes",
                    accepted.getValue(stage),
                    flipped,
                )
                cases["reject-${stage.wire}-$state"] = flipped
            }
        }

        val recordsFile = Files.createTempFile("android-delta-matrix", ".jsonl").toFile()
        val probe = File(recordsFile.parentFile, "probe_worker_intake.mjs")
        probe.writeBytes(javaClass.classLoader!!.getResourceAsStream("worker-parity/probe_worker_intake.mjs")!!.readBytes())
        recordsFile.writeText(cases.entries.joinToString("\n") { (id, json) -> """{"id":"$id","record":$json}""" })
        val process = ProcessBuilder("node", probe.absolutePath, workerRoot.absolutePath, recordsFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("probe failed: $output", 0, process.waitFor())
        val revisionLine = output.lines().firstOrNull { it.startsWith("# worker-revision") }
        assertTrue(
            "this control requires an intake that implements delta736d3180 — $deltaRevision or any " +
                "later approved pin, each a strict superset (got: $revisionLine)",
            approvedWorkerRevisions.any { revisionLine?.contains(it) == true },
        )
        assertAuthorityPinsReported(output, revisionLine?.substringAfter('\t')?.substringBefore(' ')?.trim() ?: "")
        val verdicts = output.lines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
            val parts = line.split('\t')
            parts[0] to parts.drop(1).joinToString("\t")
        }
        for (stage in stages) {
            assertEquals(
                "unknown + observation_unavailable must be ACCEPTed on ${stage.wire}: ${verdicts["accept-${stage.wire}-unknown"]}",
                "ACCEPT",
                verdicts["accept-${stage.wire}-unknown"],
            )
            for (state in rejectedStates) {
                val verdict = verdicts["reject-${stage.wire}-$state"]
                assertTrue(
                    "observation_unavailable + $state must be REJECTed on ${stage.wire} by the pairing " +
                        "rule, was: $verdict",
                    verdict?.startsWith("REJECT invalid_reason") == true,
                )
            }
        }
        assertEquals("every cell must produce a verdict", cases.size, verdicts.size)
        println("DELTA-MATRIX-VERDICTS cells=${cases.size} accepts=${stages.size} rejects=${stages.size * rejectedStates.size}")
    }

    /**
     * The DECODER's refusals must match the WORKER's, case for case.
     *
     * The decoder rules added for review findings 1-3 are only correct if the Worker refuses the same
     * inputs. Without this test, "the decoder refuses X" is my opinion of the contract; with it, the
     * Worker's own validator is the authority and the decoder is measured against it. Each record here
     * is well-formed except for exactly one rule, so a single ACCEPT identifies the rule I got wrong.
     */
    @Test
    fun `the Worker refuses the same malformed records the decoder refuses`() {
        val workerRoot = cloudWorkerRoot()
        requireAuthority(workerRoot)

        fun perturbed(mutate: (MutableMap<String, Any?>) -> Unit): String {
            val base = mutableMapOf<String, Any?>(
                "schema_version" to 1L,
                "capture_id" to UUID.randomUUID().toString(),
                "enrollment_epoch" to "epoch-1",
                "capture_session_id" to "42",
                "platform" to "android",
                "app_version" to "8.20",
                "build" to "12345",
                "models" to mapOf(
                    "vad" to mapOf("id" to "silero-vad", "version" to "4.0"),
                    "wakeword" to mapOf("id" to "auris-wake", "version" to "1.2"),
                    "asr" to mapOf("id" to "whisper-small", "version" to "2024-11"),
                    "translation" to null,
                    "router" to mapOf("id" to "lfm-intent", "version" to "0.3"),
                ),
                "audio" to mapOf(
                    "sha256" to "a".repeat(64),
                    "byte_count" to 3244L,
                    "retained_samples" to 1600L,
                    "sample_rate_hz" to 16000L,
                    "channels" to 1L,
                    "encoding" to "pcm_s16le",
                    "original_format" to "pcm_s16le",
                    "conversion_version" to "1",
                    "truncated" to false,
                    "dropped_spans" to emptyList<Any>(),
                ),
                "observations" to mutableMapOf<String, Any?>(
                    "wake" to mutableMapOf<String, Any?>("state" to "completed", "decision" to "detected"),
                    "bleed" to mutableMapOf<String, Any?>("state" to "completed", "decision" to "no_overlap"),
                    "asr" to mutableMapOf<String, Any?>("state" to "completed", "text" to "skip this ad", "source_language" to "en"),
                    "translation" to mutableMapOf<String, Any?>("state" to "not_run", "reason" to "no_text"),
                    "routing" to mutableMapOf<String, Any?>(
                        "state" to "completed",
                        "predicted_intent" to "track.play",
                        "arguments" to mutableMapOf<String, Any?>("track_hint" to "queen"),
                    ),
                    "execution" to mutableMapOf<String, Any?>("state" to "completed", "outcome" to "success"),
                ),
                "links" to mapOf("conversation_id" to UUID.randomUUID().toString()),
            )
            mutate(base)
            return CanonicalJson.canonicalBytes(jsonNode(base)).toString(Charsets.UTF_8)
        }

        val cases = linkedMapOf<String, String>(
            // Finding 1: enum values outside the enumeration.
            "wake-decision-bogus" to perturbed { stageOf(it, "wake")["decision"] = "bogus" },
            "routing-validation-bogus" to perturbed { stageOf(it, "routing")["validation"] = "bogus" },
            "execution-outcome-bogus" to perturbed { stageOf(it, "execution")["outcome"] = "bogus" },
            // Finding 3: language above the SHORT bound but below the text bound.
            "source-language-over-short-bound" to perturbed { stageOf(it, "asr")["source_language"] = "x".repeat(WireBounds.SHORT_FIELD_BYTES + 1) },
            // Finding 2: argument authorization and content boundary.
            "arguments-unauthorized-intent" to perturbed {
                stageOf(it, "routing")["predicted_intent"] = "unknown.intent"
                stageOf(it, "routing")["arguments"] = mapOf("anything" to "value")
            },
            "arguments-unapproved-member" to perturbed {
                stageOf(it, "routing")["arguments"] = mapOf("track_hint" to "queen", "access_token" to "abc")
            },
            "arguments-structured-value" to perturbed {
                stageOf(it, "routing")["arguments"] = mapOf("track_hint" to mapOf("nested" to "x"))
            },
        )

        val recordsFile = Files.createTempFile("android-negative-records", ".jsonl").toFile()
        val probe = File(recordsFile.parentFile, "probe_worker_intake.mjs")
        probe.writeBytes(javaClass.classLoader!!.getResourceAsStream("worker-parity/probe_worker_intake.mjs")!!.readBytes())
        recordsFile.writeText(cases.entries.joinToString("\n") { (id, json) -> """{"id":"$id","record":$json}""" })

        val process = ProcessBuilder("node", probe.absolutePath, workerRoot.absolutePath, recordsFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("probe failed: $output", 0, process.waitFor())

        assertApprovedRevision(output)
        val verdicts = output.lines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
            val parts = line.split('\t')
            parts[0] to parts.drop(1).joinToString("\t")
        }
        for (id in cases.keys) {
            assertTrue(
                "the Worker ACCEPTED '$id', but the decoder refuses it — the decoder is over-strict: ${verdicts[id]}",
                verdicts[id]?.startsWith("REJECT") == true,
            )
        }
        println("WORKER-NEGATIVE-VERDICTS")
        verdicts.forEach { (id, v) -> println("  $id -> $v") }
    }

    private fun stageOf(record: MutableMap<String, Any?>, name: String): MutableMap<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return (record["observations"] as MutableMap<String, MutableMap<String, Any?>>)[name]!!
    }

    private fun jsonNode(value: Any?): RecordNode = when (value) {
        null -> RecordNode.Null
        is String -> RecordNode.Str(value)
        is Long -> RecordNode.Num(value.toDouble())
        is Int -> RecordNode.Num(value.toDouble())
        is Double -> RecordNode.Num(value)
        is Boolean -> RecordNode.Bool(value)
        is Map<*, *> -> RecordNode.Obj(value.entries.associate { (k, v) -> k as String to jsonNode(v) })
        is List<*> -> RecordNode.Arr(value.map { jsonNode(it) })
        else -> error("unsupported: ${value::class}")
    }
}
