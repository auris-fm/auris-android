package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.WavWriter
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * END-TO-END WIRE PARITY: an Android-built record PLUS its WAV, through the Cloud Worker's real
 * multipart intake (`intakeContributionUpload`).
 *
 * Why this is separate from [WorkerWireParityTest]: that one proves the record's SHAPE is acceptable.
 * Shape is the easy half. The intake additionally parses the envelope, bounds the metadata, recomputes
 * `record_sha256` over the exact bytes, streams and digests the audio, and validates the WAV header.
 * A client can agree with the server on schema and still disagree on every one of those, so this test
 * is the one that establishes wire identity rather than schema acceptance.
 *
 * The tamper controls matter as much as the happy path: an intake that accepted a record whose
 * `record_sha256` did not match its bytes, or an audio part whose digest did not match the record's
 * declared audio identity, would be broken in a way no schema check could reveal. Each negative case
 * asserts the Worker REFUSES for the intended reason.
 *
 * Skipped when the cloud worktree or node is unavailable; the committed receipt
 * `artifacts/receipts/wire-parity-intake-e2e.txt` preserves a real run.
 */
class WorkerIntakeParityE2ETest {

    private data class Case(
        val id: String,
        val record: RecordNode,
        val wav: ByteArray,
        /** The exact envelope `record` bytes the client embeds. */
        val recordText: ByteArray,
        /** The `record_sha256` the CLIENT claims for [recordText] (may be wrong, to model a bug). */
        val claimedSha: String,
        /** Audio bytes actually put in the audio part; may differ from [wav] to model a tamper. */
        val audioSent: ByteArray,
    )

    private fun cloudWorkerRoot(): File? = listOfNotNull(
        System.getenv("AURIS_CLOUD_WORKER_ROOT")?.let(::File),
        File("/Users/dev/code/auris/core/cloud-voice-uploads/worker"),
    ).firstOrNull { File(it, "src/contributions/stream_intake.ts").isFile }

    private fun nodeVersion(): String = runCatching {
        ProcessBuilder("node", "--version").start().inputStream.bufferedReader().readText().trim()
    }.getOrDefault("?")

    private fun nodeAvailable(): Boolean = runCatching {
        ProcessBuilder("node", "--version").start().waitFor() == 0
    }.getOrDefault(false)

    private fun sha256Hex(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun wavBytes(sampleCount: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        WavWriter.writeHeader(out, sampleCount)
        WavWriter.writePcm(out, ShortArray(sampleCount) { i -> i.toShort() }, sampleCount)
        return out.toByteArray()
    }

    private fun recordFor(
        captureId: String = UUID.randomUUID().toString(),
        session: String = "42",
        audioSha: String,
        audioBytes: Long,
        retainedSamples: Long,
    ): RecordNode {
        val audio = AudioBlock(
            sha256 = audioSha,
            byteCount = audioBytes,
            retainedSamples = retainedSamples,
            originalFormat = "pcm_s16le",
            conversionVersion = "1",
            truncated = false,
            droppedSpans = emptyList(),
        )
        return ContributionRecordBuilder(captureId, "epoch-1", session)
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

    private fun matchingCase(sampleCount: Int = 1600, id: String = "matching"): Case {
        val wav = wavBytes(sampleCount)
        val record = recordFor(
            audioSha = sha256Hex(wav),
            audioBytes = wav.size.toLong(),
            retainedSamples = sampleCount.toLong(),
        )
        val text = CanonicalJson.canonicalBytes(record)
        return Case(id = id, record = record, wav = wav, recordText = text, claimedSha = sha256Hex(text), audioSent = wav)
    }

    @Test
    fun `android record and WAV are accepted by the real Worker intake with matching identities`() {
        val workerRoot = cloudWorkerRoot()
        assumeTrue("cloud worker worktree not available", workerRoot != null)
        assumeTrue("node not available", nodeAvailable())

        val good = matchingCase(sampleCount = 1600, id = "matching")
        // Negative controls: each must be REFUSED for the intended reason.
        // The client computes record_sha256 over ITS bytes; the server recomputes over the bytes it
        // received. Model the disagreement by sending record text whose canonical digest differs
        // from the digest the client will claim (a whitespace-reordered copy parses to the same
        // object but hashes differently, which is exactly the class of bug this catches).
        // The client claims a digest that is not the digest of the record it is sending. The Worker
        // re-canonicalizes the received record and compares against the CLAIM, so a wrong claim is
        // the only way to reach integrity_mismatch — whitespace differences canonicalize away.
        val digestMismatch = good.copy(
            id = "record-digest-mismatch",
            claimedSha = "0".repeat(64),
        )
        val audioTampered = run {
            val tampered = good.wav.copyOf()
            tampered[WavWriter.HEADER_BYTES + 2] = (tampered[WavWriter.HEADER_BYTES + 2].toInt() xor 0x01).toByte()
            good.copy(id = "audio-tampered", audioSent = tampered)
        }
        val declaredByteCountWrong = run {
            val wav = good.wav
            val record = recordFor(
                audioSha = sha256Hex(wav),
                audioBytes = wav.size.toLong() + 2,
                retainedSamples = 1600L,
            )
            val text = CanonicalJson.canonicalBytes(record)
            good.copy(id = "declared-byte-count-wrong", record = record, recordText = text, claimedSha = sha256Hex(text), audioSent = good.wav)
        }

        val cases = listOf(good, digestMismatch, audioTampered, declaredByteCountWrong)
        val casesFile = Files.createTempFile("android-intake-cases", ".jsonl").toFile()
        val probe = File(casesFile.parentFile, "probe_worker_intake_e2e.mjs")
        val probeSource = javaClass.classLoader!!.getResourceAsStream("worker-parity/probe_worker_intake_e2e.mjs")
        check(probeSource != null) { "e2e intake probe resource missing" }
        probe.writeBytes(probeSource.readBytes())

        casesFile.writeText(
            cases.joinToString("\n") { case ->
                val recordText = case.recordText.toString(Charsets.UTF_8)
                val wavB64 = Base64.getEncoder().encodeToString(case.audioSent)
                // recordText is JSON-escaped into the case envelope (it is a STRING here; the probe
                // embeds it verbatim into the multipart metadata part).
                val escaped = recordText.replace("\\", "\\\\").replace("\"", "\\\"")
                """{"id":"${case.id}","recordText":"$escaped","recordSha256":"${case.claimedSha}","wavBase64":"$wavB64"}"""
            },
        )

        val process = ProcessBuilder("node", probe.absolutePath, workerRoot!!.absolutePath, casesFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("probe failed:\n$output", 0, process.waitFor())

        val verdicts = output.lines().filter { it.isNotBlank() }.map { it.split('\t') }
            .associate { it[0] to it.drop(1) }

        // The happy path must be accepted, and the identities the Worker derived must match the
        // ones the client computed — this is the actual wire-identity claim.
        val accepted = verdicts["matching"] ?: error("no verdict for the matching case")
        assertEquals("matching case refused: $accepted", "ACCEPT", accepted[0])
        assertEquals("record_sha256 disagreement", sha256Hex(good.recordText), accepted[1])
        assertEquals("audio digest disagreement", sha256Hex(good.audioSent), accepted[2])
        assertEquals("byte_count disagreement", good.audioSent.size.toString(), accepted[3])
        assertEquals("sample count disagreement", "1600", accepted[4])

        // Each tamper must be refused, AND refused for its own reason. Asserting only "REJECT" would
        // let one defect hide behind another: a control that fires for the wrong cause proves nothing
        // about the check it is supposed to exercise.
        val expectedRefusals = mapOf(
            "record-digest-mismatch" to "integrity_mismatch",
            "audio-tampered" to "integrity_mismatch",
            "declared-byte-count-wrong" to "integrity_mismatch",
        )
        for ((caseId, expectedCode) in expectedRefusals) {
            val verdict = verdicts[caseId] ?: error("no verdict for $caseId")
            assertEquals("$caseId was NOT refused by intake: $verdict", "REJECT", verdict[0])
            assertEquals("$caseId refused for the wrong reason: $verdict", expectedCode, verdict[1])
        }
        // The receipt must show the Worker's own derived identities, not just a pass.
        val receipt = buildString {
            appendLine("END-TO-END WIRE PARITY: Android-built record + WAV through Worker intakeContributionUpload")
            appendLine("cloud worker: ${workerRoot.absolutePath}")

            appendLine("node: ${nodeVersion()}")
            appendLine("client record_sha256 : ${sha256Hex(good.recordText)}")
            appendLine("client audio sha256  : ${sha256Hex(good.audioSent)}")
            appendLine("client byte_count    : ${good.audioSent.size}")
            appendLine("")
            verdicts.forEach { (caseId, v) -> appendLine("$caseId	${v.joinToString("\t")}") }
        }
        File("build/e2e-intake-receipt.txt").apply { parentFile?.mkdirs() }.writeText(receipt)
        println("E2E-INTAKE-VERDICTS")
        verdicts.forEach { (id, v) -> println("  $id -> ${v.joinToString(" | ")}") }

        assertTrue(verdicts.isNotEmpty())
    }
}
