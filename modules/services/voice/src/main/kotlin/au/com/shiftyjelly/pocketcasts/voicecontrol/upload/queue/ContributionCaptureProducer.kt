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
    /**
     * The REAL binary provenance: the app version and build that produced the audio, resolved when the
     * record is built (a null versionName at construction must not be frozen into an empty wire field).
     */
    private val appVersion: () -> String,
    private val appBuild: () -> String,
    /**
     * The capture session's REAL identity, supplied by the session that owns the recording - never
     * derived from the capture id, which is a capture-scoped value, not a session-scoped one.
     */
    private val captureSessionId: () -> String,
    /**
     * The ADMITTED ownership of the capture (reviewer 0081c95f): account and session FROZEN by the
     * owning capture session at capture start - never inferred at upload time. Every published
     * record gets a full owner sidecar; without them the store refuses to serve the capture.
     */
    private val enrollmentAccount: () -> String,
    private val enrollmentSession: () -> Long,
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
        /** Frozen allowance metadata bound for THIS capture (null = no allowance constraint). */
        metadataCapBytes: Int? = null,
    ): ContributionStore.Entry? {
        if (!captureId.matches(UUID_RE)) return null
        val samples = pcmChunks.sumOf { it.second.toLong() }
        // BOUNDED VALIDATION BEFORE ALLOCATION (reviewer 064d5a00): refuse over-budget input with the
        // same arithmetic the store will apply, so no unbounded buffer is ever constructed.
        // ALL input validation happens here, BEFORE any allocation, and REFUSES (null) instead of
        // throwing through the recorder path (reviewer d2f1bfc8): invalid rates, empty or
        // out-of-range chunk lengths, and over-budget input never reach WavWriter.
        if (sampleRateHz != SAMPLE_RATE.toInt()) return null
        if (samples <= 0 || store.projectedBytesFor(samples, 0) > store.limits.maxClipBytes) return null
        if (samples * 1000L / SAMPLE_RATE > store.limits.maxClipMs) return null
        if (pcmChunks.any { (chunk, valid) -> valid <= 0 || valid > chunk.size }) return null
        val sessionId = captureSessionId()
        if (sessionId.isEmpty()) return null
        // REQUIRED IDENTITY BEFORE ANY RESERVATION (spec fc035523): the admitted account/session are
        // read and validated BEFORE store.admit, so an invalid identity can neither leak a
        // reservation nor reduce queue capacity.
        val account = enrollmentAccount()
        val session = enrollmentSession()
        if (account.isEmpty() || session <= 0) return null
        // The record's audio block describes EXACTLY the bytes WavWriter will produce.
        val expected = ByteArrayOutputStream()
        WavWriter.writeHeader(expected, Math.toIntExact(samples))
        for ((chunk, valid) in pcmChunks) WavWriter.writePcm(expected, chunk, valid)
        val audioBytes = expected.toByteArray()
        val block = AudioBlock(
            sha256 = sha256Hex(audioBytes),
            byteCount = audioBytes.size.toLong(),
            sampleRateHz = sampleRateHz,
            retainedSamples = samples,
            originalFormat = originalFormat,
            conversionVersion = conversionVersion,
            truncated = false,
            droppedSpans = emptyList(),
        )
        val builder = ContributionRecordBuilder(
            captureId,
            enrollmentEpoch,
            sessionId,
        )
        for ((name, ref) in models) builder.model(name, ref)
        for ((stage, entry) in stages) builder.stage(stage, entry)
        // A blank identity is a WIRING defect, not a bad capture: the Cloud intake refuses an empty
        // app_version/build, so returning null here would drop the capture silently after its audio
        // was retained and queued, with nothing reported anywhere. Fail loudly instead, and name the
        // field, so the defect surfaces where it is introduced.
        val version = appVersion()
        val buildNumber = appBuild()
        check(version.isNotBlank() && buildNumber.isNotBlank()) {
            "capture identity is incomplete (app_version='$version', build='$buildNumber'): the " +
                "producer was constructed without real binary provenance, and the Cloud intake would " +
                "refuse this record"
        }
        val record = builder.build(
            platform = "android",
            appVersion = version,
            build = buildNumber,
            audio = block,
        )
        val metadata = CanonicalJson.canonicalBytes(record)
        // THE PREPAID METADATA BOUND, enforced at the persistence boundary (spec 0987c463):
        // count the FULL canonical record/digest envelope - the representation the transport
        // actually sends - and refuse BEFORE any store reservation or write.
        if (metadataCapBytes != null && UploadMetadataEnvelope.bytes(record).size > metadataCapBytes) return null
        val admission = store.admit(samples, metadata.size) ?: return null
        return store.prepare(admission, captureId, enrollmentEpoch, pcmChunks, metadata, account, session)
    }

    private fun sha256Hex(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private const val SAMPLE_RATE = 16_000L
    }
}
