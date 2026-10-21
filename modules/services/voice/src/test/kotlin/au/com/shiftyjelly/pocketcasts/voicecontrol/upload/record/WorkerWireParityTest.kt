package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.WavWriter
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertEquals
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
     * The ONE approved Worker revision, shared with the end-to-end intake control.
     *
     * Both controls must compare against the SAME revision, or their combined evidence describes two
     * different servers. Advancing this value is a deliberate, reviewable edit made when a new
     * revision is cleared.
     */
    private val approvedWorkerRevision = "d8fe823cbe43ebb310d632b482312c7b282ef018"

    private fun requireAuthority(root: File) {
        assertTrue(
            "INTEGRITY: the Worker schema authority is missing at ${root.absolutePath}. Point " +
                "AURIS_CLOUD_WORKER_ROOT at a worktree at $approvedWorkerRevision; a skipped " +
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
        assertEquals(
            "INTEGRITY: parity ran against '${reported.removeSuffix("CLEAN").trim()}' but the approved " +
                "authority is $approvedWorkerRevision",
            approvedWorkerRevision,
            reported.removeSuffix("CLEAN").trim(),
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
            conversionVersion = "1",
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

        val records = listOf(
            "fully-populated" to fullyPopulatedRecord(),
            "declared-absence" to minimalValidRecord(),
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
