package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.AudioBlock
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.CanonicalJson
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ContributionRecordBuilder
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageEntry
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageState
import java.security.MessageDigest

/**
 * Shared fixtures for controls that need a REAL, recoverable queue entry.
 *
 * A record that only passes admission is not enough: recovery re-validates the persisted metadata, so
 * a stub body passes admission and can never survive a restart. That distinction cost real time once
 * already - a stub made a fixture bug look like a product defect - so this record is built canonically
 * and binds the exact WAV bytes the store writes.
 */
object ContributionRetryCallerTestSupport {

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun wavBytes(samples: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        WavWriter.writeHeader(out, samples)
        WavWriter.writePcm(out, ShortArray(samples) { i -> i.toShort() }, samples)
        return out.toByteArray()
    }

    fun chunks(samples: Int): List<Pair<ShortArray, Int>> = listOf(ShortArray(samples) { i -> i.toShort() } to samples)

    /** A complete canonical record whose audio block binds [wavBytes] for the same capture. */
    fun meta(captureId: String, samples: Int = 1600): ByteArray {
        val audio = wavBytes(samples)
        val block = AudioBlock(
            sha256 = sha256Hex(audio),
            byteCount = audio.size.toLong(),
            retainedSamples = samples.toLong(),
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

    /** The same record as a parsed node, which is what the transport uploads. */
    fun record(captureId: String, samples: Int = 1600): RecordNode.Obj {
        val parsed = CanonicalJson.parse(meta(captureId, samples).toString(Charsets.UTF_8))
        return parsed as? RecordNode.Obj ?: error("the built record must parse to an object")
    }

    /** Cloud's acceptance receipt body for this capture and audio digest. */
    fun acceptance(captureId: String, audioSha256: String): String = """{"capture_id":"$captureId","sha256":"$audioSha256","status":"accepted","schema_version":1}"""
}
