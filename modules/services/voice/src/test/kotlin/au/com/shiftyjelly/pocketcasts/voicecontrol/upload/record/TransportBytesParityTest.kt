package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.ContributionUploadTransport
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.WavWriter
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PARITY OF THE BYTES THE TRANSPORT ACTUALLY EMITS.
 *
 * [WorkerIntakeParityE2ETest] builds an envelope that *resembles* the client's. This control does not:
 * it takes the exact request body [ContributionUploadTransport.requestEnvelope] produces and hands
 * those bytes, unchanged, to the clean pinned Worker intake. That is the only shape of parity that can
 * catch client and server each "validating" while disagreeing about the bytes on the wire.
 *
 * The authority is pinned to [approvedWorkerRevision] and this test FAILS - it does not skip - when the
 * authority is absent, dirty, or at another revision.
 */
class TransportBytesParityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun cloudWorkerRoot(): File = System.getenv("AURIS_CLOUD_WORKER_ROOT")?.let(::File)
        ?: File("/Users/dev/code/auris/core/cloud-voice-uploads/worker")

    /** Pinned intake revision; advancing it is a deliberate, reviewable edit - never incidental. */
    private val approvedWorkerRevision = "d8fe823cbe43ebb310d632b482312c7b282ef018"

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun nodeAvailable(): Boolean = runCatching {
        ProcessBuilder("node", "--version").start().waitFor() == 0
    }.getOrDefault(false)

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
            conversionVersion = "v1",
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

    private fun transport(): ContributionUploadTransport = ContributionUploadTransport(
        endpoint = "http://127.0.0.1:1/v1/contributions",
        authorization = { "Bearer parity-token" },
    )

    /** Writes [bytes] to a temp file, since the transport takes the capture as a file. */
    private fun file(name: String, bytes: ByteArray): File = File(tmp.root, name).also { it.writeBytes(bytes) }

    private data class Case(val id: String, val record: RecordNode, val audio: ByteArray)

    @Test
    fun `the bytes the transport emits are accepted by the clean pinned Worker intake`() {
        val workerRoot = cloudWorkerRoot()
        assertTrue(
            "INTEGRITY: the Worker authority is missing at ${workerRoot.absolutePath}. Point " +
                "AURIS_CLOUD_WORKER_ROOT at a worktree at the pinned revision. Without the authority " +
                "the parity claim is unearned, so this fails rather than green-skipping.",
            File(workerRoot, "src/contributions/stream_intake.ts").isFile,
        )
        assertTrue("INTEGRITY: node is required to execute the intake", nodeAvailable())

        val wav = wavBytes(1600)
        val accepted = Case(
            id = "transport-accepted",
            record = recordFor(audioSha = sha256Hex(wav), audioBytes = wav.size.toLong(), retainedSamples = 1600L),
            audio = wav,
        )

        // Audio tamper: the record describes the ORIGINAL bytes, the audio part carries different ones.
        val tamperedAudio = wav.copyOf().also { it[WavWriter.HEADER_BYTES + 2] = (it[WavWriter.HEADER_BYTES + 2].toInt() xor 0x01).toByte() }
        val audioTampered = accepted.copy(id = "transport-audio-tampered", audio = tamperedAudio)

        // Record tamper: the audio is original but the record claims a different digest for it, so the
        // record the client signs is not the record the audio belongs to.
        val recordTampered = Case(
            id = "transport-record-tampered",
            record = recordFor(audioSha = "11".repeat(32), audioBytes = wav.size.toLong(), retainedSamples = 1600L),
            audio = wav,
        )

        val casesFile = Files.createTempFile("transport-envelope-cases", ".jsonl").toFile()
        val probe = File(casesFile.parentFile, "probe_worker_envelope.mjs")
        val probeSource = javaClass.classLoader!!.getResourceAsStream("worker-parity/probe_worker_envelope.mjs")
        check(probeSource != null) { "transport-envelope probe resource missing" }
        probe.writeBytes(probeSource.readBytes())

        val transport = transport()
        val emitted = mutableMapOf<String, Pair<ByteArray, String>>()
        casesFile.writeText(
            listOf(accepted, audioTampered, recordTampered).joinToString("\n") { case ->
                val record = case.record as RecordNode.Obj
                val (body, contentType) = requireNotNull(transport.requestEnvelope(record, file("${case.id}.wav", case.audio))) {
                    "the transport refused to build an envelope for ${case.id}"
                }
                emitted[case.id] = body to contentType
                val b64 = Base64.getEncoder().encodeToString(body)
                """{"id":"${case.id}","bodyBase64":"$b64","contentType":"$contentType"}"""
            },
        )

        val process = ProcessBuilder("node", probe.absolutePath, workerRoot.absolutePath, casesFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("probe failed:\n$output", 0, process.waitFor())

        val provenance = output.lines().firstOrNull { it.startsWith("# worker-revision") }
            ?: error("probe did not report its Worker revision; refusing to claim parity")
        val parts = provenance.substringAfter("\t").split("\t")
        assertEquals("INTEGRITY: the authority must be a clean checkout: $provenance", "CLEAN", parts.getOrNull(2))
        assertEquals(
            "INTEGRITY: parity ran against '${parts[0]}' but the approved authority is $approvedWorkerRevision",
            approvedWorkerRevision,
            parts[0],
        )

        val verdicts = output.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
            .associate { it[0] to it.drop(1) }

        val ok = verdicts[accepted.id] ?: error("no verdict for the accepted case")
        assertEquals("the transport's own bytes were refused: $ok", "ACCEPT", ok[0])
        val recordBytes = CanonicalJson.canonicalBytes(accepted.record as RecordNode.Obj)
        assertEquals("the Worker derived a different record identity", sha256Hex(recordBytes), ok[1])
        assertEquals("the Worker derived a different audio identity", sha256Hex(accepted.audio), ok[2])
        assertEquals("byte count disagreement", accepted.audio.size.toString(), ok[3])
        assertEquals("sample count disagreement", "1600", ok[4])

        val audioReject = verdicts[audioTampered.id] ?: error("no verdict for the audio tamper")
        assertEquals("a tampered audio part must be refused", "REJECT", audioReject[0])
        assertEquals("the audio tamper was refused for the wrong reason", "integrity_mismatch", audioReject[1])

        val recordReject = verdicts[recordTampered.id] ?: error("no verdict for the record tamper")
        assertEquals("a tampered record must be refused", "REJECT", recordReject[0])
        assertEquals("the record tamper was refused for the wrong reason", "integrity_mismatch", recordReject[1])
    }
}
