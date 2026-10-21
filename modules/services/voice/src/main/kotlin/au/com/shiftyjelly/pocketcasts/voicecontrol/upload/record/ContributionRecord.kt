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

/**
 * Deny-by-default field names per stage: anything outside these sets is REFUSED.
 *
 * These names are the WIRE contract pinned by the spec (voice-recording-uploads.md,
 * "Contribution record") and enforced by the Cloud Worker's record_schema.ts. They are
 * deliberately NOT a locally invented shape: `state` and `reason` are common to every
 * stage, and the additional fields below are the only members each stage may carry.
 * Millisecond wake offsets are used on the wire; sample-offset aliases and
 * `offset_units` are refused (the producer converts before encoding).
 */
object StageFieldAllowlist {
    val COMMON: Set<String> = setOf("state", "reason")

    private val fields: Map<StageName, Set<String>> = mapOf(
        StageName.Wake to setOf("decision", "score", "threshold", "speech_offset_ms", "wake_offset_ms"),
        StageName.Bleed to setOf("decision", "overlap_ratio"),
        StageName.Asr to setOf("text", "source_language", "router_input"),
        StageName.Translation to setOf("translated_text", "target_language"),
        StageName.Routing to setOf("predicted_intent", "predicted_tool", "validation", "execution_disposition", "failure_reason", "arguments"),
        StageName.Execution to setOf("outcome"),
    )

    /** True when [key] may appear on [stage] (state/reason plus that stage's own fields). */
    fun isAllowed(stage: StageName, key: String): Boolean = key in COMMON || fields.getValue(stage).contains(key)

    /** The stage-specific (non-common) field names, for refusal messages and tests. */
    fun fieldsFor(stage: StageName): Set<String> = fields.getValue(stage)
}

/** Enumerated per-stage reasons: an arbitrary sentence is refused (spec: enumerated reason). */
object StageReasons {
    private val reasons: Map<StageName, Set<String>> = mapOf(
        StageName.Wake to setOf("no_wake_detected", "below_threshold", "detector_disabled", "outcome_wait_expired"),
        StageName.Bleed to setOf("no_playback_reference", "reference_unavailable", "bleed_detector_disabled", "outcome_wait_expired"),
        StageName.Asr to setOf("local_only_action", "rejected_by_wake", "provider_error", "outcome_wait_expired", "cancelled_by_user"),
        StageName.Translation to setOf("source_language_target_language", "no_text", "provider_error", "outcome_wait_expired"),
        StageName.Routing to setOf("no_candidates", "low_confidence", "validation_failed", "outcome_wait_expired"),
        StageName.Execution to setOf("client_refused", "precondition_failed", "action_failed", "cancelled_by_user", "outcome_wait_expired"),
    )

    fun isAllowed(stage: StageName, reason: String): Boolean = reasons.getValue(stage).contains(reason)

    fun forStage(stage: StageName): Set<String> = reasons.getValue(stage)
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

/**
 * Wire-format bounds pinned by the spec. These are RESOURCE bounds on the record envelope, not
 * collection budgets: exceeding them means a malformed upload, never a policy decision.
 * Measured in UTF-8 BYTES (the unit the transport and `max_metadata_bytes` use), not UTF-16
 * code units, which would understate multibyte content by up to 3x.
 */
object WireBounds {
    const val TEXT_FIELD_BYTES = 64 * 1024
    const val SHORT_FIELD_BYTES = 512

    /** One allowlisted action argument value (`MAX_ARGUMENT_VALUE_BYTES` in the Worker). */
    const val ARGUMENT_VALUE_BYTES = 128

    fun utf8Length(value: String): Int = value.toByteArray(Charsets.UTF_8).size

    fun requireShort(value: String, what: String) {
        require(utf8Length(value) <= SHORT_FIELD_BYTES) { "$what exceeds $SHORT_FIELD_BYTES UTF-8 bytes" }
    }

    fun requireText(value: String, what: String) {
        require(utf8Length(value) <= TEXT_FIELD_BYTES) { "$what exceeds $TEXT_FIELD_BYTES UTF-8 bytes" }
    }
}

/**
 * A model declaration on the wire: either the concrete `{id, version}` actually used, or an
 * explicit `null` meaning "not run, or its identity is unknown". A bare version string is NOT
 * representable, because the spec requires the id alongside it.
 */
sealed interface ModelRef {
    data class Named(val id: String, val version: String) : ModelRef
    object None : ModelRef
}

/** Model/frontend versions ACTUALLY used; absent means not run/unknown (never fabricated). */
data class ModelVersions(
    val vad: String?,
    val wakeword: String?,
    val asr: String?,
    val translation: String?,
    val router: String?,
)

class ContributionRecordBuilder(
    private val captureId: String,
    private val enrollmentEpoch: String,
    /**
     * Capture-session incarnation as an OPAQUE WIRE STRING. The spec pins this explicitly:
     * a local numeric counter must be encoded as a string before finalization, never a JSON
     * number. Callers with a numeric counter pass `counter.toString()`.
     */
    private val captureSessionId: String,
) {
    private val stages = LinkedHashMap<StageName, StageEntry>()
    private val modelEntries = LinkedHashMap<String, RecordNode>()

    init {
        require(HEX_UUID.matches(captureId)) { "capture_id must be a UUID string" }
        require(enrollmentEpoch.isNotBlank()) { "enrollment_epoch required (server-issued opaque)" }
        require(captureSessionId.isNotEmpty()) { "capture_session_id required (opaque wire string)" }
        require(WireBounds.utf8Length(captureSessionId) <= WireBounds.SHORT_FIELD_BYTES) {
            "capture_session_id exceeds ${WireBounds.SHORT_FIELD_BYTES} UTF-8 bytes"
        }
    }

    /**
     * Replace a stage already declared. Production callers declare each stage once (a duplicate is
     * a programming error); refiner flows legitimately supersede an earlier provisional entry, and
     * this keeps that explicit rather than forcing a fresh builder.
     */
    fun replaceStage(stage: StageName, entry: StageEntry): ContributionRecordBuilder {
        stages.remove(stage)
        return stage(stage, entry)
    }

    fun stage(stage: StageName, entry: StageEntry): ContributionRecordBuilder {
        require(!stages.containsKey(stage)) { "duplicate stage ${stage.wire}" }
        for (key in entry.fields.keys) {
            require(StageFieldAllowlist.isAllowed(stage, key)) { "unknown field '$key' for stage ${stage.wire}" }
        }
        // A non-completed stage MUST carry an enumerated reason; a completed stage does not
        // carry one (an arbitrary sentence is exactly what the reason enum exists to exclude).
        if (entry.state != StageState.Completed) {
            val reason = (entry.fields["reason"] as? RecordNode.Str)?.value
            require(reason != null) { "stage ${stage.wire} is ${entry.state.wire}: an enumerated reason is required" }
            require(StageReasons.isAllowed(stage, reason)) { "reason '$reason' is not enumerated for ${stage.wire}" }
        } else {
            require(!entry.fields.containsKey("reason")) { "stage ${stage.wire} is completed: reason must be absent" }
        }
        // routing.arguments is AUTHORIZED at build time too. A record is immutable once finalized,
        // so a payload the Worker would refuse must be refused HERE — afterwards the device holds a
        // hashed record it can never upload and cannot repair.
        if (stage == StageName.Routing) {
            entry.fields["arguments"]?.let { args ->
                val intent = (entry.fields["predicted_intent"] as? RecordNode.Str)?.value ?: ""
                val refusal = RecordValidator.argumentRefusal(args, intent)
                require(refusal == null) { refusal ?: "" }
            }
        }
        stages[stage] = entry
        return this
    }

    /**
     * Names the model/frontend actually used for [key] as `{id, version}`, or declares it
     * NOT RUN/UNKNOWN with [ModelRef.None] (wire `null`). A bare version string is invalid on
     * the wire, and leaving a key out is invalid too: `build()` requires all five keys.
     */
    fun model(key: String, ref: ModelRef): ContributionRecordBuilder {
        require(key in MODEL_KEYS) { "unknown model key '$key'" }
        require(!modelEntries.containsKey(key)) { "duplicate model '$key'" }
        modelEntries[key] = when (ref) {
            is ModelRef.None -> RecordNode.Null

            is ModelRef.Named -> {
                require(ref.id.isNotEmpty() && ref.version.isNotEmpty()) { "model id/version must be non-empty" }
                require(WireBounds.utf8Length(ref.id) <= WireBounds.SHORT_FIELD_BYTES) { "model id too long" }
                require(WireBounds.utf8Length(ref.version) <= WireBounds.SHORT_FIELD_BYTES) { "model version too long" }
                RecordNode.Obj(mapOf("id" to RecordNode.Str(ref.id), "version" to RecordNode.Str(ref.version)))
            }
        }
        return this
    }

    fun build(platform: String, appVersion: String, build: String, audio: AudioBlock, links: Map<String, RecordNode>? = null): RecordNode.Obj {
        require(platform == "ios" || platform == "android") { "platform must be ios|android" }
        // Absence is a CLAIM: every model key and every stage must be present, declaring
        // not-run/unknown explicitly rather than being omitted.
        for (key in MODEL_KEYS) require(modelEntries.containsKey(key)) { "models.$key must be present (use ModelRef.None when not run)" }
        for (stage in StageName.entries) require(stages.containsKey(stage)) { "observations.${stage.wire} must be present" }
        val root = RecordNode.ObjBuilder()
            .put("schema_version", RecordNode.ofInt(SCHEMA_VERSION))
            .put("capture_id", RecordNode.Str(captureId))
            .put("enrollment_epoch", RecordNode.Str(enrollmentEpoch))
            .put("platform", RecordNode.Str(platform))
            .put("app_version", RecordNode.Str(appVersion))
            .put("build", RecordNode.Str(build))
            .put("capture_session_id", RecordNode.Str(captureSessionId))
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

        // Wire vocabulary includes request_id (spec "links allows only conversation_id,
        // request_id, operation_id and turn_id").
        private val LINK_KEYS = setOf("conversation_id", "request_id", "operation_id", "turn_id")
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
