package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record

/**
 * Decode-side authority for the immutable schema-version-1 contribution record.
 *
 * [ContributionRecordBuilder] is the ENCODE-side authority: it refuses unknown fields at
 * construction. That guarantees nothing about bytes read back from disk, which may be tampered,
 * truncated, or written by an older build. This validator applies the same schema to a parsed
 * [RecordNode] and returns a typed view, or refuses with a [Rejected] reason. It is the ONLY
 * consumer-side parser for persisted records: callers must not hand-inspect fields themselves
 * (an ad-hoc reader is a second, weaker authority that silently accepts what the builder refuses).
 *
 * Everything is exact: numbers must be finite integers for integer fields (a JSON `44.9` is
 * refused, never truncated to 44), strings must be non-empty where the builder requires content,
 * and unknown keys at every level are refused rather than ignored.
 */
object RecordValidator {

    const val SCHEMA_VERSION = ContributionRecordBuilder.SCHEMA_VERSION

    /** Typed projection of a validated record. */
    data class Valid(
        val captureId: String,
        val enrollmentEpoch: String,
        val platform: String,
        val appVersion: String,
        val build: String,
        val captureSessionId: Long,
        val audio: AudioBlock,
        val models: Map<String, String>,
        val observations: Map<StageName, StageEntry>,
    )

    sealed interface Result {
        data class Ok(val record: Valid) : Result

        /** Refusal with a content-free reason (safe to log/count: never carries record content). */
        data class Rejected(val reason: String) : Result
    }

    private val HEX_UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val HEX_SHA256 = Regex("[0-9a-fA-F]{64}")

    private val ROOT_REQUIRED = listOf(
        "schema_version",
        "capture_id",
        "enrollment_epoch",
        "platform",
        "app_version",
        "build",
        "capture_session_id",
        "audio",
        "models",
        "observations",
    )
    private val ROOT_OPTIONAL = listOf("links")
    private val AUDIO_REQUIRED = listOf(
        "sha256",
        "byte_count",
        "encoding",
        "sample_rate_hz",
        "channels",
        "retained_samples",
        "original_format",
        "conversion_version",
        "truncated",
        "dropped_spans",
    )
    private val AUDIO_OPTIONAL = listOf("original_duration_ms")
    private val LINK_KEYS = setOf("turn_id", "conversation_id", "operation_id")
    private val MODEL_KEYS = setOf("vad", "wakeword", "asr", "translation", "router")

    fun validate(node: RecordNode): Result {
        val root = node as? RecordNode.Obj ?: return reject("record is not an object")
        val fields = root.fields
        // Unknown/absent keys at the root are BOTH refusals: an extra key means a producer we do
        // not understand wrote this file; a missing key means it cannot be reconstructed.
        for (key in fields.keys) {
            if (key !in ROOT_REQUIRED && key !in ROOT_OPTIONAL) return reject("unknown root field")
        }
        for (key in ROOT_REQUIRED) {
            if (key !in fields) return reject("missing required root field")
        }
        val schemaVersion = intOf(fields["schema_version"]) ?: return reject("schema_version not an integer")
        if (schemaVersion != SCHEMA_VERSION) return reject("unsupported schema_version")

        val captureId = nonEmptyStr(fields["capture_id"]) ?: return reject("capture_id missing")
        if (!HEX_UUID.matches(captureId)) return reject("capture_id not a uuid")
        val epoch = nonEmptyStr(fields["enrollment_epoch"]) ?: return reject("enrollment_epoch empty or missing")
        val platform = nonEmptyStr(fields["platform"]) ?: return reject("platform missing")
        val appVersion = nonEmptyStr(fields["app_version"]) ?: return reject("app_version missing")
        val build = nonEmptyStr(fields["build"]) ?: return reject("build missing")
        val sessionId = intOf(fields["capture_session_id"]) ?: return reject("capture_session_id not an integer")

        val audioNode = fields["audio"] as? RecordNode.Obj ?: return reject("audio missing or not an object")
        val audio = validateAudio(audioNode) ?: return reject("audio block invalid")

        val modelsNode = fields["models"] as? RecordNode.Obj ?: return reject("models missing or not an object")
        val models = LinkedHashMap<String, String>()
        for ((key, value) in modelsNode.fields) {
            if (key !in MODEL_KEYS) return reject("unknown model key")
            val version = nonEmptyStr(value) ?: return reject("model version missing")
            models[key] = version
        }

        val observationsNode = fields["observations"] as? RecordNode.Obj
            ?: return reject("observations missing or not an object")
        val observations = LinkedHashMap<StageName, StageEntry>()
        for ((key, value) in observationsNode.fields) {
            val stage = StageName.entries.firstOrNull { it.wire == key } ?: return reject("unknown observation stage")
            val entryNode = value as? RecordNode.Obj ?: return reject("observation stage not an object")
            val entry = validateStage(stage, entryNode) ?: return reject("observation stage invalid")
            observations[stage] = entry
        }

        // links: optional, closed vocabulary, string values only.
        fields["links"]?.let { linksNode ->
            val links = linksNode as? RecordNode.Obj ?: return reject("links not an object")
            for ((key, value) in links.fields) {
                if (key !in LINK_KEYS) return reject("unknown link key")
                if (value !is RecordNode.Str) return reject("link value not a string")
            }
        }

        return Result.Ok(
            Valid(
                captureId = captureId,
                enrollmentEpoch = epoch,
                platform = platform,
                appVersion = appVersion,
                build = build,
                captureSessionId = sessionId,
                audio = audio,
                models = models,
                observations = observations,
            ),
        )
    }

    private fun validateAudio(node: RecordNode.Obj): AudioBlock? {
        val f = node.fields
        for (key in f.keys) {
            if (key !in AUDIO_REQUIRED && key !in AUDIO_OPTIONAL) return null
        }
        for (key in AUDIO_REQUIRED) {
            if (key !in f) return null
        }
        val sha = nonEmptyStr(f["sha256"]) ?: return null
        if (!HEX_SHA256.matches(sha)) return null
        val byteCount = intOf(f["byte_count"]) ?: return null
        if (byteCount <= 0) return null
        val encoding = nonEmptyStr(f["encoding"]) ?: return null
        val rate = intOf(f["sample_rate_hz"]) ?: return null
        val channels = intOf(f["channels"]) ?: return null
        val retained = intOf(f["retained_samples"]) ?: return null
        if (retained < 0) return null
        val originalFormat = nonEmptyStr(f["original_format"]) ?: return null
        val conversionVersion = nonEmptyStr(f["conversion_version"]) ?: return null
        val truncated = (f["truncated"] as? RecordNode.Bool)?.value ?: return null
        val spansNode = f["dropped_spans"] as? RecordNode.Arr ?: return null
        val spans = spansNode.items.map { spanNode ->
            val span = spanNode as? RecordNode.Obj ?: return null
            val keys = span.fields.keys
            if (keys.any { it != "start_sample" && it != "end_sample" }) return null
            if ("start_sample" !in keys || "end_sample" !in keys) return null
            val start = intOf(span.fields["start_sample"]) ?: return null
            val end = intOf(span.fields["end_sample"]) ?: return null
            if (end < start) return null
            DroppedSpan(start, end)
        }
        val originalDuration: Long? = if ("original_duration_ms" in f) {
            val v = intOf(f["original_duration_ms"]) ?: return null
            if (!truncated) return null // present ONLY with truncation
            v
        } else {
            null
        }
        if (truncated && originalDuration == null) return null
        return runCatching {
            AudioBlock(
                sha256 = sha,
                byteCount = byteCount,
                encoding = encoding,
                sampleRateHz = rate.toInt(),
                channels = channels.toInt(),
                retainedSamples = retained,
                originalFormat = originalFormat,
                conversionVersion = conversionVersion,
                truncated = truncated,
                droppedSpans = spans,
                originalDurationMs = originalDuration,
            )
        }.getOrNull()
    }

    private fun validateStage(stage: StageName, node: RecordNode.Obj): StageEntry? {
        val f = node.fields
        val stateStr = nonEmptyStr(f["state"]) ?: return null
        val state = StageState.entries.firstOrNull { it.wire == stateStr } ?: return null
        val payload = LinkedHashMap<String, RecordNode>()
        for ((key, value) in f) {
            if (key == "state") continue
            if (!StageFieldAllowlist.isAllowed(stage, key)) return null
            if (value === RecordNode.Null) return null
            payload[key] = value
        }
        return StageEntry(state, payload)
    }

    /** Exact integer accessor: a fractional or non-finite number is REFUSED, never truncated. */
    private fun intOf(node: RecordNode?): Long? {
        val num = node as? RecordNode.Num ?: return null
        val v = num.value
        if (!v.isFinite()) return null
        if (v != Math.floor(v)) return null
        if (v < Long.MIN_VALUE.toDouble() || v > Long.MAX_VALUE.toDouble()) return null
        return v.toLong()
    }

    private fun nonEmptyStr(node: RecordNode?): String? = (node as? RecordNode.Str)?.value?.takeIf { it.isNotEmpty() }

    private fun reject(reason: String): Result = Result.Rejected(reason)
}
