package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.ContributionUploadTransport
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.UploadMetadataEnvelope
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.WavWriter
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BYTE-COMPATIBILITY RECIPE, not a feature.
 *
 * Emits ONE canonical contribution record built by the production serializer, with a small synthetic
 * audio payload, and prints the exact bytes and digests a second implementation must reproduce. The
 * output is the artifact: this test asserts only what it can check locally, and the recorded lines are
 * what a cross-platform comparison reads.
 *
 * Determinism is claimed for the RECORD and the AUDIO only: their inputs are literals or derived from
 * the payload, with no clock, no random ids and no locale-dependent formatting. The whole multipart
 * ENVELOPE is NOT deterministic: `requestEnvelope` asks OkHttp for a fresh random multipart boundary on
 * every call, so an envelope digest identifies ONE emission. The record, the metadata part (record +
 * digest) and the audio bytes are boundary-independent and must be reproducible; the envelope digest is
 * reported beside the boundary it belongs to, never as a constant.
 */
class CanonicalRecordVectorRecipeTest {

    private fun hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 640 mono 16 kHz samples: 1280 payload bytes, 1324 with the 44-byte WAV header. */
    private fun wav(sampleCount: Int = 640): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        WavWriter.writeHeader(out, sampleCount)
        WavWriter.writePcm(out, ShortArray(sampleCount) { i -> (i * 16).toShort() }, sampleCount)
        return out.toByteArray()
    }

    @Test
    fun `emit the canonical record vector`() {
        val audioBytes = wav()
        val audio = AudioBlock(
            sha256 = hex(audioBytes),
            byteCount = audioBytes.size.toLong(),
            retainedSamples = 640L,
            originalFormat = "pcm_s16le",
            conversionVersion = "v1",
            truncated = false,
            droppedSpans = emptyList(),
        )
        val record = ContributionRecordBuilder(
            captureId = "00000000-0000-4000-8000-000000000001",
            enrollmentEpoch = "epoch-vector-1",
            captureSessionId = "1",
        )
            .model("vad", ModelRef.Named("silero-vad", "4.0"))
            .model("wakeword", ModelRef.None)
            .model("asr", ModelRef.Named("whisper-small", "2024-11"))
            .model("translation", ModelRef.None)
            .model("router", ModelRef.None)
            .stage(StageName.Wake, StageEntry(StageState.Completed, mapOf("decision" to RecordNode.Str("detected"), "score" to RecordNode.Num(0.82), "threshold" to RecordNode.Num(0.5), "speech_offset_ms" to RecordNode.ofInt(120), "wake_offset_ms" to RecordNode.ofInt(340))))
            .stage(StageName.Bleed, StageEntry(StageState.Completed, mapOf("decision" to RecordNode.Str("no_overlap"), "overlap_ratio" to RecordNode.Num(0.01))))
            .stage(StageName.Asr, StageEntry(StageState.Completed, mapOf("text" to RecordNode.Str("skip this ad"), "source_language" to RecordNode.Str("en"))))
            .stage(StageName.Translation, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_text"))))
            .stage(StageName.Routing, StageEntry(StageState.Completed, mapOf("predicted_intent" to RecordNode.Str("skip_ad"), "validation" to RecordNode.Str("valid"), "execution_disposition" to RecordNode.Str("executed"))))
            .stage(StageName.Execution, StageEntry(StageState.Completed, mapOf("outcome" to RecordNode.Str("success"))))
            .build(platform = "android", appVersion = "8.20", build = "12345", audio = audio)

        val canonicalBytes = CanonicalJson.canonicalBytes(record)
        val canonicalText = canonicalBytes.toString(Charsets.UTF_8)
        val recordDigest = recordSha256(record)

        // The record digest is SHA-256 over the EXACT canonical bytes of the record the transport
        // embeds - not a recomputation from a different serialization path.
        assertEquals(
            "record digest must be SHA-256 over the emitted canonical bytes",
            hex(canonicalBytes),
            recordDigest,
        )
        // The metadata part the transport emits embeds those same canonical bytes, byte for byte.
        val metadataBytes = UploadMetadataEnvelope.bytes(record)
        assertTrue(
            "the transport's metadata part must embed the emitted canonical bytes",
            String(metadataBytes, Charsets.UTF_8).contains(canonicalText),
        )

        // The whole multipart envelope, from the production transport, over a real file on disk.
        val outDir = File("/tmp/p1run/upload_vector").apply { mkdirs() }
        val audioFile = File(outDir, "audio.wav").apply { writeBytes(audioBytes) }
        val transport = ContributionUploadTransport(
            endpoint = "http://127.0.0.1:1/v1/contributions",
            authorization = { "Bearer recipe-token" },
        )
        val envelope = requireNotNull(transport.requestEnvelope(record, audioFile)) {
            "the transport refused to build an envelope for the recipe record"
        }
        val (bodyBytes, contentType) = envelope
        File(outDir, "envelope.bin").writeBytes(bodyBytes)

        // Audio metadata in the record must describe the WAV the envelope actually ships.
        val audioNode = record.fields["audio"] as RecordNode.Obj
        assertEquals("audio.byte_count must match the WAV length", audioBytes.size.toLong(), (audioNode.fields["byte_count"] as RecordNode.Num).value.toLong())
        assertEquals("audio.sha256 must match the WAV digest", hex(audioBytes), (audioNode.fields["sha256"] as RecordNode.Str).value)
        assertTrue("the envelope must carry the WAV payload verbatim", bodyBytes.toString(Charsets.ISO_8859_1).contains(String(audioBytes, Charsets.ISO_8859_1)))

        val report = buildString {
            appendLine("RECIPE_VERSION 1")
            appendLine("AUDIO_BYTES ${audioBytes.size}")
            appendLine("AUDIO_SHA256 ${hex(audioBytes)}")
            appendLine("CANONICAL_BYTES ${canonicalBytes.size}")
            appendLine("RECORD_SHA256 $recordDigest")
            appendLine("CANONICAL_JSON $canonicalText")
            appendLine("AUDIO_HEX ${audioBytes.joinToString("") { "%02x".format(it) }}")
            appendLine("METADATA_PART_BYTES ${metadataBytes.size}")
            appendLine("METADATA_PART_SHA256 ${hex(metadataBytes)}")
            appendLine("METADATA_PART_JSON ${String(metadataBytes, Charsets.UTF_8)}")
            appendLine("METADATA_CONTENT_TYPE application/json")
            appendLine("ENVELOPE_BYTES ${bodyBytes.size}")
            appendLine("ENVELOPE_SHA256 ${hex(bodyBytes)}")
            appendLine("ENVELOPE_CONTENT_TYPE $contentType")
            appendLine("ENVELOPE_VERSION 1")
        }
        File("/tmp/p1run/upload_record_vector.txt").writeText(report)
        File(outDir, "manifest.txt").writeText(report)
    }
}
