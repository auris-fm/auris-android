package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record

import java.security.MessageDigest

// Task 4: the immutable schema-version-1 contribution record and its RFC 8785 digest.
// Required identity/provenance fields (spec "HTTPS request and wire identity"):
//   schema_version, capture_id, enrollment_epoch, platform, app_version, build,
//   capture_session_id, audio, models, observations.
// Absent fields are ABSENT (never null placeholders, never empty-string success); unknown
// stage fields/values are refused at construction ("Unknown schema fields are refused rather
// than silently retained"). Feedback is NOT embedded here: it is a separate linked annotation.

/** Closed vocabulary of observation stages; entries appear in this canonical order. */
enum class StageName(val wire: String) {
    Wake("wake"),
    Bleed("bleed"),
    Asr("asr"),
    Translation("translation"),
    Routing("routing"),
    Execution("execution"),
}

/** Closed vocabulary of per-stage states (spec: five values, no others). */
enum class StageState(val wire: String) {
    Completed("completed"),
    NotRun("not_run"),
    Failed("failed"),
    Cancelled("cancelled"),
    Unknown("unknown"),
}

/** Deny-by-default field names per stage: anything outside these sets is REFUSED. */
object StageFieldAllowlist {
    private val fields: Map<StageName, Set<String>> = mapOf(
        StageName.Wake to setOf("decision", "score", "threshold", "speech_offset_samples", "wake_offset_samples", "offset_units", "reason"),
        StageName.Bleed to setOf("decision", "score", "threshold", "reason"),
        StageName.Asr to setOf("text", "source_language", "reason"),
        StageName.Translation to setOf("translated_text", "target_language", "transform", "reason"),
        StageName.Routing to setOf("intent", "validation", "disposition", "failure_reason", "args"),
        StageName.Execution to setOf("disposition", "failure_reason"),
    )

    fun isAllowed(stage: StageName, key: String): Boolean = fields.getValue(stage).contains(key)
}

/** One stage entry: state plus ONLY allowlisted fields for that stage. */
data class StageEntry(val state: StageState, val fields: Map<String, RecordNode> = emptyMap()) {
    init {
        // Construction-time refusal of unknown fields happens in ContributionRecordBuilder.stage(),
        // which knows the stage name; this map itself must still be plain (no null sentinels).
        require(fields.values.none { it === RecordNode.Null }) { "null placeholder in stage fields" }
    }
}

/** Audio block: digests/byte counts bound the WAV body; durations/samples describe retention. */
data class AudioBlock(
    val sha256: String,
    val byteCount: Long,
    val encoding: String = "pcm_s16le",
    val sampleRateHz: Int = 16000,
    val channels: Int = 1,
    val retainedSamples: Long,
    val originalFormat: String,
    val conversionVersion: String,
    val truncated: Boolean,
    val droppedSpans: List<DroppedSpan>,
    val originalDurationMs: Long? = null, // included ONLY when truncation occurred
)

/** Half-open sample-index interval on the original segment timeline. */
data class DroppedSpan(val startSample: Long, val endSample: Long) {
    init {
        require(endSample >= startSample) { "span end before start" }
    }

    fun toNode(): RecordNode = RecordNode.Obj(mapOf("start_sample" to RecordNode.ofInt(startSample), "end_sample" to RecordNode.ofInt(endSample)))
}

/** Model/frontend versions ACTUALLY used; absent means not run/unknown (never fabricated). */
data class ModelVersions(
    val vad: String?,
    val wakeword: String?,
    val asr: String?,
    val translation: String?,
    val router: String?,
)

class ContributionRecordBuilder(private val captureId: String, private val enrollmentEpoch: String, private val captureSessionId: Long) {
    private val stages = LinkedHashMap<StageName, StageEntry>()
    private val modelEntries = LinkedHashMap<String, RecordNode>()

    init {
        require(HEX_UUID.matches(captureId)) { "capture_id must be a UUID string" }
        require(enrollmentEpoch.isNotBlank()) { "enrollment_epoch required (server-issued opaque)" }
    }

    fun stage(stage: StageName, entry: StageEntry): ContributionRecordBuilder {
        require(!stages.containsKey(stage)) { "duplicate stage ${stage.wire}" }
        for (key in entry.fields.keys) {
            require(StageFieldAllowlist.isAllowed(stage, key)) { "unknown field '$key' for stage ${stage.wire}" }
        }
        stages[stage] = entry
        return this
    }

    /** Names the model/frontend actually used for [key]; null means NOT recorded (absent). */
    fun model(key: String, version: String?): ContributionRecordBuilder {
        require(key in MODEL_KEYS) { "unknown model key '$key'" }
        require(!modelEntries.containsKey(key)) { "duplicate model '$key'" }
        if (version != null) modelEntries[key] = RecordNode.Str(version)
        return this
    }

    fun build(platform: String, appVersion: String, build: String, audio: AudioBlock, links: Map<String, RecordNode>? = null): RecordNode.Obj {
        val root = RecordNode.ObjBuilder()
            .put("schema_version", RecordNode.ofInt(SCHEMA_VERSION))
            .put("capture_id", RecordNode.Str(captureId))
            .put("enrollment_epoch", RecordNode.Str(enrollmentEpoch))
            .put("platform", RecordNode.Str(platform))
            .put("app_version", RecordNode.Str(appVersion))
            .put("build", RecordNode.Str(build))
            .put("capture_session_id", RecordNode.ofInt(captureSessionId))
            .put("audio", audio.toNode())
            .put("models", RecordNode.Obj(modelEntries.toMap()))
            .put("observations", observationsNode())
        if (links != null) {
            for ((key, value) in links) {
                require(key in LINK_KEYS) { "unknown link '$key'" }
            }
            root.put("links", RecordNode.Obj(links))
        }
        return root.build()
    }

    private fun observationsNode(): RecordNode {
        val b = RecordNode.ObjBuilder()
        for ((name, entry) in stages) {
            val sb = RecordNode.ObjBuilder().put("state", RecordNode.Str(entry.state.wire))
            for ((key, value) in entry.fields) sb.put(key, value)
            b.put(name.wire, sb.build())
        }
        return b.build()
    }

    companion object {
        const val SCHEMA_VERSION = 1L
        private val HEX_UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val MODEL_KEYS = setOf("vad", "wakeword", "asr", "translation", "router")
        private val LINK_KEYS = setOf("turn_id", "conversation_id", "operation_id")
    }
}

private fun AudioBlock.toNode(): RecordNode {
    require(encoding == "pcm_s16le") { "only pcm_s16le is specced for records" }
    require(sampleRateHz == 16000 && channels == 1) { "record audio is 16 kHz mono" }
    require(byteCount > 0) { "byte_count includes the WAV header" }
    val b = RecordNode.ObjBuilder()
        .put("sha256", RecordNode.Str(lowerHex(sha256)))
        .put("byte_count", RecordNode.ofInt(byteCount))
        .put("encoding", RecordNode.Str(encoding))
        .put("sample_rate_hz", RecordNode.ofInt(sampleRateHz.toLong()))
        .put("channels", RecordNode.ofInt(channels.toLong()))
        .put("retained_samples", RecordNode.ofInt(retainedSamples))
        .put("original_format", RecordNode.Str(originalFormat))
        .put("conversion_version", RecordNode.Str(conversionVersion))
        .put("truncated", RecordNode.Bool(truncated))
        .put("dropped_spans", RecordNode.Arr(droppedSpans.map { it.toNode() }))
    // Original duration is included WHEN truncation occurred; unknown stays explicit-absent,
    // never fabricated.
    if (truncated && originalDurationMs != null) b.put("original_duration_ms", RecordNode.ofInt(originalDurationMs))
    return b.build()
}

private fun lowerHex(digest: String): String {
    require(Regex("[0-9a-fA-F]{64}").matches(digest)) { "sha256 must be 64 hex characters" }
    return digest.lowercase()
}

/** SHA-256 over the UTF-8 bytes of the RFC 8785 canonicalization of [record]. */
fun recordSha256(record: RecordNode): String {
    val bytes = CanonicalJson.canonicalBytes(record)
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Same canonical bytes hashed by [recordSha256]; kept for queue persistence paths. */
fun canonicalRecordBytes(record: RecordNode): ByteArray = CanonicalJson.canonicalBytes(record)
