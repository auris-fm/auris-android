package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.AudioBlock
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.CanonicalJson
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ContributionRecordBuilder
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageEntry
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageState
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * THE RECORDER PRODUCER (spec 07ae8661 boundary): the one component that binds recorder output to the
 * immutable record at capture time, so `original_format` and `conversion_version` describe the audio the
 * record claims - set HERE, once, from what the capture hardware actually delivered, and never rewritten
 * later by a settings change or an upload retry.
 *
 * WHY A PRODUCER AND NOT A CALL-SITE: every consumer of a capture (upload, parity probes, cleanup) must be
 * able to trust that the record beside the WAV was written by exactly this path. A call-site that assembles
 * a record ad hoc has no such guarantee and no tamper surface to reason about.
 *
 * The audio bytes are ALWAYS produced by [WavWriter] from the same PCM chunks the store persists, so the
 * record's `audio.sha256` matches the file on disk by construction - the store's own durableCapture
 * authority re-verifies this on every read.
 */
class ContributionCaptureProducer(
    private val store: ContributionStore,
    /** Where the audio came from BEFORE conversion: the recorder's actual source format. */
    private val originalFormat: String = "pcm_s16le",
    /** Which converter produced the WAV the store persists. Bump when the pipeline changes. */
    private val conversionVersion: String = "v1",
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** One capture session's provenance: what ran, at what versions, with what outcomes. */
    data class Provenance(
        val enrollmentEpoch: String,
        val models: List<Pair<String, ModelRef>>,
        val stages: List<Triple<StageName, StageState, Map<String, RecordNode.Str>>>,
    )

    /**
     * Bind and publish one capture. Returns the entry on success; null means the store refused
     * (budget, epoch, or validation) and the PCM is discarded WITHOUT a record - a refused capture is
     * not a malformed record and must never be published as one.
     */
    fun publish(
        captureId: String,
        enrollmentEpoch: String,
        sampleRateHz: Int,
        pcmChunks: List<Pair<ShortArray, Int>>,
        models: List<Pair<String, ModelRef>>,
        stages: List<Pair<StageName, StageEntry>>,
    ): ContributionStore.Entry? {
        if (!captureId.matches(UUID_RE)) return null
        val samples = pcmChunks.sumOf { it.second.toLong() }
        // The record's audio block describes EXACTLY the bytes WavWriter will produce.
        val expected = ByteArrayOutputStream()
        WavWriter.writeHeader(expected, Math.toIntExact(samples))
        for ((chunk, valid) in pcmChunks) WavWriter.writePcm(expected, chunk, valid)
        val audioBytes = expected.toByteArray()
        val block = AudioBlock(
            sha256 = sha256Hex(audioBytes),
            byteCount = audioBytes.size.toLong(),
            retainedSamples = samples,
            originalFormat = originalFormat,
            conversionVersion = conversionVersion,
            truncated = false,
            droppedSpans = emptyList(),
        )
        val builder = ContributionRecordBuilder(
            captureId,
            enrollmentEpoch,
            // The session incarnation is the capture id scoped to this producer: opaque on the wire.
            "session-$captureId",
        )
        for ((name, ref) in models) builder.model(name, ref)
        for ((stage, entry) in stages) builder.stage(stage, entry)
        val record = builder.build(
            platform = "android",
            appVersion = APP_VERSION,
            build = APP_BUILD,
            audio = block,
        )
        val metadata = CanonicalJson.canonicalBytes(record)
        val admission = store.admit(samples, metadata.size) ?: return null
        return store.prepare(admission, captureId, enrollmentEpoch, pcmChunks, metadata)
    }

    private fun sha256Hex(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        // Compiled provenance, not settings: the record describes the binary that produced the audio.
        const val APP_VERSION = "8.20"
        const val APP_BUILD = "12345"
    }
}
