package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.WavWriter
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
 * The test is skipped when the cloud worktree or a node binary is unavailable (CI without the cloud
 * checkout). The committed receipt `artifacts/receipts/wire-parity-worker-intake.txt` records a real
 * run so the evidence is not lost when the test cannot execute.
 */
class WorkerWireParityTest {

    private fun cloudWorkerRoot(): File? {
        val env = System.getenv("AURIS_CLOUD_WORKER_ROOT")
        val candidates = listOfNotNull(
            env?.let(::File),
            File("/Users/dev/code/auris/core/cloud-voice-uploads/worker"),
        )
        return candidates.firstOrNull { File(it, "src/contributions/record_schema.ts").isFile }
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
        assumeTrue("cloud worker worktree not available", workerRoot != null)
        assumeTrue("node not available", nodeAvailable())

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

        val process = ProcessBuilder("node", probe.absolutePath, workerRoot!!.absolutePath, recordsFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("probe failed: $output", 0, process.waitFor())

        val verdicts = output.lines().filter { it.isNotBlank() }.associate { line ->
            val parts = line.split('\t')
            parts[0] to parts.drop(1).joinToString("\t")
        }
        for ((id, _) in records) {
            assertEquals("Android record '$id' was NOT accepted by the Worker: ${verdicts[id]}", "ACCEPT", verdicts[id])
        }
        assertTrue("no verdicts produced", verdicts.isNotEmpty())
    }
}
