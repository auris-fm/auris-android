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

    // Pinned intake revision; advancing it is a deliberate, reviewable edit - never incidental.

    /**
     * Approved pinned authorities (advancing is a deliberate, reviewable edit):
     * d8fe823c pre-delta intake-guard baseline; c569b319 delta736d3180 strict intake (pushed
     * source for fixture/intake parity, NOT a deployed live intake).
     */
    private val approvedWorkerRevisions = setOf(
        "d8fe823cbe43ebb310d632b482312c7b282ef018",
        "c569b319e19b5971ada44a0f53245630688c1305",
        // a030ff31 is the c6 empty_result intake pin, independently cleared by Reviewer. It is a
        // strict superset of c569b319, so every rule proven against the delta is still measured here.
        "a030ff31d95dd703fab6c5c976f36826ef77f499",
        "3dd0c91d095512e3c49108c25b726a3b66a66eef",
        "862c7ed3fdc540eccc9fdacd6819c97a9e679536",
    )

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

    private val metadataMutationId = "transport-metadata-mutated"

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

        // AUDIO-BINDING negative (renamed to say what it is): the audio is original but the record
        // declares a different audio digest, so the record the client signs does not belong to the audio
        // it ships. This is NOT metadata-digest proof - the record and its claim are still consistent.
        val audioBindingMismatch = Case(
            id = "transport-audio-binding-mismatch",
            record = recordFor(audioSha = "11".repeat(32), audioBytes = wav.size.toLong(), retainedSamples = 1600L),
            audio = wav,
        )

        // TRUE metadata mutation: the record TEXT is altered in flight (one stage value changed) while the
        // client's original `record_sha256` is still the one claimed. The bytes here are built by ANDROID -
        // the transport's own metadata head is rewritten - so the claim can stay the original digest while
        // the record no longer canonicalizes to it. This is the case that only a server recomputing the
        // digest can catch.
        val metadataMutated = accepted.copy(id = metadataMutationId)

        val casesFile = Files.createTempFile("transport-envelope-cases", ".jsonl").toFile()
        val probe = File(casesFile.parentFile, "probe_worker_envelope.mjs")
        val probeSource = javaClass.classLoader!!.getResourceAsStream("worker-parity/probe_worker_envelope.mjs")
        check(probeSource != null) { "transport-envelope probe resource missing" }
        probe.writeBytes(probeSource.readBytes())

        val transport = transport()
        val emitted = mutableMapOf<String, Pair<ByteArray, String>>()
        casesFile.writeText(
            listOf(accepted, audioTampered, audioBindingMismatch, metadataMutated).joinToString("\n") { case ->
                val record = case.record as RecordNode.Obj
                val built = requireNotNull(transport.requestEnvelope(record, file("${case.id}.wav", case.audio))) {
                    "the transport refused to build an envelope for ${case.id}"
                }
                // For the true metadata mutation, rewrite ONE value inside the record the transport already
                // embedded, leaving the claimed `record_sha256` untouched. Done on the client's own bytes,
                // so this is the wire state a mangling proxy or a faulty record writer would produce.
                val (body, contentType) = if (case.id == metadataMutationId) {
                    val text = String(built.first, Charsets.ISO_8859_1)
                    val mutated = text.replace("\"skip_ad\"", "\"skip_all_ads\"")
                    require(mutated != text) { "metadata mutation did not apply; the fixture changed" }
                    mutated.toByteArray(Charsets.ISO_8859_1) to built.second
                } else {
                    built
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
        assertTrue(
            "INTEGRITY: parity ran against '${parts[0]}' but the approved authorities are $approvedWorkerRevisions",
            parts[0] in approvedWorkerRevisions,
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

        val bindingReject = verdicts[audioBindingMismatch.id] ?: error("no verdict for the audio-binding mismatch")
        assertEquals("a record declaring a different audio digest must be refused", "REJECT", bindingReject[0])
        assertEquals("the audio-binding mismatch was refused for the wrong reason", "integrity_mismatch", bindingReject[1])

        // The distinguishing case: same audio, same claim, DIFFERENT record text. Only a server that
        // recomputes the digest over the received record can refuse this.
        val metadataReject = verdicts[metadataMutated.id] ?: error("no verdict for the metadata mutation")
        assertEquals("a record altered in flight must be refused", "REJECT", metadataReject[0])
        assertEquals("the metadata mutation was refused for the wrong reason", "integrity_mismatch", metadataReject[1])
    }
}
