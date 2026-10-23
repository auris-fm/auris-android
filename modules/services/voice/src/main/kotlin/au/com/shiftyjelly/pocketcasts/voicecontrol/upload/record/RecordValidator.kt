package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record

/**
 * Decode-side authority for the immutable schema-version-1 contribution record.
 *
 * This deliberately mirrors the CONTRACT, not the local writer: the wire shape is pinned by the
 * spec ("Contribution record") and enforced independently by the Cloud Worker's
 * `worker/src/contributions/record_schema.ts`. [ContributionRecordBuilder] is the encode side;
 * a validator that only agreed with the builder would prove nothing about server acceptance, so
 * every rule below corresponds to a Worker rule. When the two ever disagree, the Worker wins and
 * this file is wrong.
 *
 * Rules of note, each a refusal rather than a preference:
 * - Unknown keys are refused at EVERY depth (root, audio, each stage, each model entry, links).
 * - Integers must be EXACT finite integers: 44.9 is refused, never truncated to 44.
 * - Absence is a claim: all five model keys and all six stages must be present.
 * - A non-completed stage must carry an enumerated reason; a completed stage must not.
 * - Content-bearing names are refused even under an otherwise allowed parent.
 * - Strings are bounded in UTF-8 bytes (512 for short fields, 64 KiB for text).
 *
 * Refusal reasons are content-free labels: they never interpolate record values, so they are safe
 * to log or count.
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
        val captureSessionId: String,
        val audio: AudioBlock,
        val models: Map<String, ModelRef>,
        val observations: Map<StageName, StageEntry>,
    )

    sealed interface Result {
        data class Ok(val record: Valid) : Result
        data class Rejected(val reason: String) : Result
    }

    private val HEX_UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val LOWERCASE_HEX64 = Regex("[0-9a-f]{64}")

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
    private val LINK_KEYS = setOf("conversation_id", "request_id", "operation_id", "turn_id")
    private val MODEL_KEY_LIST = listOf("vad", "wakeword", "asr", "translation", "router")

    /** Content-bearing names refused even where a parent is allowed (Worker FORBIDDEN_NAMES). */
    private val FORBIDDEN_NAMES = setOf(
        "access_token",
        "refresh_token",
        "token",
        "cookie",
        "cookies",
        "signed_url",
        "presigned_url",
        "conversation_context",
        "exception",
        "stack",
        "raw_tool_payload",
        "payload",
    )

    private val WAKE_DECISIONS = setOf("detected", "rejected")
    private val BLEED_DECISIONS = setOf("overlap", "no_overlap", "uncertain")
    private val ROUTING_VALIDATION = setOf("valid", "invalid", "not_run")
    private val EXECUTION_DISPOSITIONS = setOf("executed", "refused", "failed", "not_run", "pending")
    private val EXECUTION_OUTCOMES = setOf("success", "failure", "refused", "cancelled")

    fun validate(node: RecordNode): Result {
        val root = node as? RecordNode.Obj ?: return reject("record is not an object")
        val fields = root.fields
        for (key in fields.keys) {
            if (isForbidden(key)) return reject("forbidden field")
            if (key !in ROOT_REQUIRED && key !in ROOT_OPTIONAL) return reject("unknown root field")
        }
        for (key in ROOT_REQUIRED) {
            if (key !in fields) return reject("missing required root field")
        }
        if (intOf(fields["schema_version"]) != SCHEMA_VERSION) return reject("unsupported schema_version")

        val captureId = boundedStr(fields["capture_id"], WireBounds.SHORT_FIELD_BYTES) ?: return reject("capture_id invalid")
        if (!HEX_UUID.matches(captureId)) return reject("capture_id not a uuid")
        val epoch = boundedStr(fields["enrollment_epoch"], WireBounds.SHORT_FIELD_BYTES) ?: return reject("enrollment_epoch invalid")
        if (epoch.isBlank()) return reject("enrollment_epoch blank")
        val platform = (fields["platform"] as? RecordNode.Str)?.value ?: return reject("platform missing")
        if (platform != "ios" && platform != "android") return reject("platform not ios|android")
        val appVersion = boundedStr(fields["app_version"], WireBounds.SHORT_FIELD_BYTES) ?: return reject("app_version invalid")
        val build = boundedStr(fields["build"], WireBounds.SHORT_FIELD_BYTES) ?: return reject("build invalid")
        // The session id is an OPAQUE STRING on the wire: a JSON number here is a durable
        // incompatibility, not a lenient parse.
        val sessionId = boundedStr(fields["capture_session_id"], WireBounds.SHORT_FIELD_BYTES)
            ?: return reject("capture_session_id must be a non-empty string")

        val audioNode = fields["audio"] as? RecordNode.Obj ?: return reject("audio missing or not an object")
        val audio = validateAudio(audioNode) ?: return reject("audio block invalid")
        val models = validateModels(fields["models"]) ?: return reject("models invalid")
        val observations = validateObservations(fields["observations"]) ?: return reject("observations invalid")
        fields["links"]?.let { if (validateLinks(it) == null) return reject("links invalid") }

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
            if (isForbidden(key)) return null
            if (key !in AUDIO_REQUIRED && key !in AUDIO_OPTIONAL) return null
        }
        for (key in AUDIO_REQUIRED) if (key !in f) return null
        // Digests are LOWERCASE hex on the wire: uppercase is a different string, so it cannot be
        // normalised away without changing the identity the digest names.
        val sha = boundedStr(f["sha256"], WireBounds.SHORT_FIELD_BYTES) ?: return null
        if (!LOWERCASE_HEX64.matches(sha)) return null
        val byteCount = intOf(f["byte_count"])?.takeIf { it > 0 } ?: return null
        val encoding = (f["encoding"] as? RecordNode.Str)?.value ?: return null
        if (encoding != "pcm_s16le") return null
        if (intOf(f["sample_rate_hz"]) != 16_000L) return null
        if (intOf(f["channels"]) != 1L) return null
        val retained = intOf(f["retained_samples"])?.takeIf { it >= 0 } ?: return null
        val originalFormat = boundedStr(f["original_format"], WireBounds.SHORT_FIELD_BYTES) ?: return null
        val conversionVersion = boundedStr(f["conversion_version"], WireBounds.SHORT_FIELD_BYTES) ?: return null
        val truncated = (f["truncated"] as? RecordNode.Bool)?.value ?: return null
        val spansNode = f["dropped_spans"] as? RecordNode.Arr ?: return null
        val spans = spansNode.items.map { spanNode ->
            val span = spanNode as? RecordNode.Obj ?: return null
            if (span.fields.keys.any { it != "start_sample" && it != "end_sample" }) return null
            if ("start_sample" !in span.fields || "end_sample" !in span.fields) return null
            val start = intOf(span.fields["start_sample"]) ?: return null
            val end = intOf(span.fields["end_sample"]) ?: return null
            if (start < 0 || end < start) return null
            DroppedSpan(start, end)
        }
        val originalDuration = if ("original_duration_ms" in f) {
            intOf(f["original_duration_ms"])?.takeIf { it >= 0 } ?: return null
        } else {
            null
        }
        return runCatching {
            AudioBlock(
                sha256 = sha,
                byteCount = byteCount,
                encoding = encoding,
                sampleRateHz = 16_000,
                channels = 1,
                retainedSamples = retained,
                originalFormat = originalFormat,
                conversionVersion = conversionVersion,
                truncated = truncated,
                droppedSpans = spans,
                originalDurationMs = originalDuration,
            )
        }.getOrNull()
    }

    /** All five keys required; each `{id,version}` of exactly two bounded strings, or null. */
    private fun validateModels(node: RecordNode?): Map<String, ModelRef>? {
        val obj = node as? RecordNode.Obj ?: return null
        for (key in obj.fields.keys) {
            if (isForbidden(key)) return null
            if (key !in MODEL_KEY_LIST) return null
        }
        val out = LinkedHashMap<String, ModelRef>()
        for (key in MODEL_KEY_LIST) {
            if (key !in obj.fields) return null
            when (val value = obj.fields.getValue(key)) {
                is RecordNode.Null -> out[key] = ModelRef.None

                is RecordNode.Obj -> {
                    for (inner in value.fields.keys) {
                        if (isForbidden(inner)) return null
                        if (inner != "id" && inner != "version") return null
                    }
                    val id = boundedStr(value.fields["id"], WireBounds.SHORT_FIELD_BYTES) ?: return null
                    val version = boundedStr(value.fields["version"], WireBounds.SHORT_FIELD_BYTES) ?: return null
                    out[key] = ModelRef.Named(id, version)
                }

                else -> return null
            }
        }
        return out
    }

    /** All six stages required, each with a state and only its allowlisted fields. */
    private fun validateObservations(node: RecordNode?): Map<StageName, StageEntry>? {
        val obj = node as? RecordNode.Obj ?: return null
        for (key in obj.fields.keys) {
            if (isForbidden(key)) return null
            if (StageName.entries.none { it.wire == key }) return null
        }
        val out = LinkedHashMap<StageName, StageEntry>()
        for (stage in StageName.entries) {
            val entryNode = obj.fields[stage.wire] as? RecordNode.Obj ?: return null
            val entry = validateStage(stage, entryNode) ?: return null
            out[stage] = entry
        }
        return out
    }

    private fun validateStage(stage: StageName, node: RecordNode.Obj): StageEntry? {
        val f = node.fields
        for (key in f.keys) {
            if (isForbidden(key)) return null
            if (!StageFieldAllowlist.isAllowed(stage, key)) return null
        }
        val stateStr = (f["state"] as? RecordNode.Str)?.value ?: return null
        val state = StageState.entries.firstOrNull { it.wire == stateStr } ?: return null
        // Reason rules: present-and-enumerated when not completed, absent when completed.
        val reasonNode = f["reason"]
        if (state == StageState.Completed) {
            if (reasonNode != null) return null
        } else {
            val reason = (reasonNode as? RecordNode.Str)?.value ?: return null
            if (!StageReasons.isAllowed(stage, reason)) return null
            // delta736d3180 pairing rule, enforced on the RECOVERY path too (reviewer 3180d15d):
            // `observation_unavailable` means "normal execution supplied no trustworthy observation",
            // so it is only meaningful with state unknown. Because the reason is in every stage's
            // general set, checking membership alone would accept it beside not_run/failed/cancelled
            // — pairs both the builder and the Cloud intake refuse. A persisted record must not be
            // trusted locally in a shape it could never be uploaded in.
            if (reason == "observation_unavailable" && state != StageState.Unknown) return null
            // spec 15:46Z: a detector that THREW is a known failure, not an unavailable observation.
            if (reason == "detector_error" && state != StageState.Failed) return null
        }
        // Per-stage value rules that the enum/scalar typing cannot express on its own.
        when (stage) {
            StageName.Wake -> {
                enumIfPresent(f["decision"], WAKE_DECISIONS) ?: return null
                for (key in listOf("score", "threshold", "speech_offset_ms", "wake_offset_ms")) {
                    f[key]?.let { if (numOf(it) == null) return null }
                }
                // No fabricated confidence: a score needs the threshold it was judged against.
                if ("score" in f && "threshold" !in f) return null
            }

            StageName.Bleed -> {
                enumIfPresent(f["decision"], BLEED_DECISIONS) ?: return null
                f["overlap_ratio"]?.let { if (numOf(it) == null) return null }
            }

            StageName.Asr -> {
                textIfPresent(f["text"]) ?: return null
                // Language tags are short fields on the wire (512 bytes), not text (64 KiB).
                textIfPresent(f["source_language"], WireBounds.SHORT_FIELD_BYTES) ?: return null
                textIfPresent(f["router_input"]) ?: return null
                // Missing text stays ABSENT with a state/reason: an empty transcript claiming
                // success is the exact defect the spec names, and text present on a
                // non-completed stage contradicts the state.
                val textPresent = "text" in f
                if (state == StageState.Completed && !textPresent) return null
                if (state != StageState.Completed && textPresent) return null
                if ((f["text"] as? RecordNode.Str)?.value?.isBlank() == true) return null
                if ((f["router_input"] as? RecordNode.Str)?.value?.isBlank() == true) return null
            }

            StageName.Translation -> {
                textIfPresent(f["translated_text"]) ?: return null
                textIfPresent(f["target_language"], WireBounds.SHORT_FIELD_BYTES) ?: return null
            }

            StageName.Routing -> {
                textIfPresent(f["predicted_intent"]) ?: return null
                textIfPresent(f["predicted_tool"]) ?: return null
                enumIfPresent(f["validation"], ROUTING_VALIDATION) ?: return null
                enumIfPresent(f["execution_disposition"], EXECUTION_DISPOSITIONS) ?: return null
                // failure_reason is the field a client would fill with an exception string.
                when (val failure = f["failure_reason"]) {
                    null, is RecordNode.Null -> Unit

                    is RecordNode.Str -> {
                        if (!ROUTING_FAILURE_REASONS.contains(failure.value)) return null
                    }

                    else -> return null
                }
                if ("arguments" in f) {
                    val intent = (f["predicted_intent"] as? RecordNode.Str)?.value ?: ""
                    validateArguments(f["arguments"], intent) ?: return null
                }
            }

            StageName.Execution -> enumIfPresent(f["outcome"], EXECUTION_OUTCOMES) ?: return null
        }
        val payload = LinkedHashMap<String, RecordNode>()
        for ((key, value) in f) {
            if (key == "state") continue
            if (value === RecordNode.Null) {
                // `failure_reason: null` is the declared "no failure"; nothing else may be null.
                if (key != "failure_reason") return null
            }
            payload[key] = value
        }
        return StageEntry(state, payload)
    }

    private val ROUTING_FAILURE_REASONS = setOf(
        "no_matching_intent",
        "validation_failed",
        "client_refused",
        "precondition_failed",
        "action_failed",
        "cancelled_by_user",
    )

    /**
     * `routing.arguments` authorization and content boundary, mirroring the Worker.
     *
     * A missing argument authorization is NOT a licence to retain the payload locally: a record that
     * the server refuses would be kept and trusted on the device and could never be uploaded. The
     * remedy the Worker names is for the client to omit `arguments` entirely BEFORE finalizing.
     *
     * An intent with no approved list refuses ANY arguments; an approved intent refuses unknown
     * members, and every value must be a bounded scalar — a structured value could carry content the
     * forbidden-name list at the key level cannot see.
     */
    private fun validateArguments(node: RecordNode?, intent: String): Unit? {
        val obj = node as? RecordNode.Obj ?: return null
        val allowed = INTENT_ARGUMENT_ALLOWLIST[intent] ?: return null
        for ((key, value) in obj.fields) {
            if (isForbidden(key)) return null
            if (!allowed.contains(key)) return null
            when (value) {
                is RecordNode.Str -> {
                    if (value.value.isEmpty()) return null
                    if (WireBounds.utf8Length(value.value) > WireBounds.ARGUMENT_VALUE_BYTES) return null
                }

                is RecordNode.Num -> if (!value.value.isFinite()) return null

                is RecordNode.Bool, RecordNode.Null -> Unit

                // Arrays and nested objects are refused without naming them.
                else -> return null
            }
        }
        return Unit
    }

    // Approved argument members per intent. A Map, not a plain object lookup, so an intent named
    // `constructor` or `__proto__` resolves to "no approved list" rather than to inherited members.

    /**
     * The same authorization/content boundary the decoder applies, callable by the ENCODE side.
     *
     * The builder previously accepted any `routing.arguments` payload: it checked field NAMES only,
     * so an unauthorized intent with a private payload could be finalized into an immutable record
     * that the Worker would refuse and the device would have already hashed and could not repair.
     * Returning a failure reason rather than throwing keeps this usable from a builder `require`.
     */
    fun argumentRefusal(arguments: RecordNode?, intent: String): String? {
        if (validateArguments(arguments, intent) == null) return "arguments are not authorized for this intent"
        return null
    }

    private val INTENT_ARGUMENT_ALLOWLIST: Map<String, Set<String>> = mapOf(
        "playlist.play" to setOf("playlist_hint"),
        "track.play" to setOf("track_hint", "artist_hint"),
        "track.pause" to emptySet(),
        "sleep_timer.set" to setOf("duration_minutes"),
    )

    private fun validateLinks(node: RecordNode): RecordNode.Obj? {
        val obj = node as? RecordNode.Obj ?: return null
        for ((key, value) in obj.fields) {
            if (isForbidden(key)) return null
            if (key !in LINK_KEYS) return null
            if (boundedStr(value, WireBounds.SHORT_FIELD_BYTES) == null) return null
        }
        return obj
    }

    /**
     * Optional enumerated string: absent is fine, present must be in [allowed].
     *
     * Returns a NON-NULL marker when valid and `null` when invalid, so `?: return null` is correct.
     * The previous version returned `Boolean?` with `false` for an invalid value; call sites used
     * `?: return null`, which rejects only null, so every invalid enum was ACCEPTED. A three-state
     * result cannot be checked correctly by an elvis operator, so the result now has two states.
     */
    private fun enumIfPresent(node: RecordNode?, allowed: Set<String>): Unit? = when (node) {
        null -> Unit
        is RecordNode.Str -> if (allowed.contains(node.value)) Unit else null
        else -> null
    }

    /** Optional text field: absent is fine, present must be a non-empty bounded string. */
    private fun textIfPresent(node: RecordNode?, maxBytes: Int = WireBounds.TEXT_FIELD_BYTES): Unit? = when (node) {
        null -> Unit
        is RecordNode.Str -> if (node.value.isNotEmpty() && WireBounds.utf8Length(node.value) <= maxBytes) Unit else null
        else -> null
    }

    private fun numOf(node: RecordNode): Double? = (node as? RecordNode.Num)?.value?.takeIf { it.isFinite() }

    private fun isForbidden(key: String): Boolean = FORBIDDEN_NAMES.contains(key.lowercase())

    /** Exact integer accessor: a fractional or non-finite number is REFUSED, never truncated. */
    private fun intOf(node: RecordNode?): Long? {
        val v = (node as? RecordNode.Num)?.value ?: return null
        if (!v.isFinite()) return null
        if (v != Math.floor(v)) return null
        if (v < Long.MIN_VALUE.toDouble() || v > Long.MAX_VALUE.toDouble()) return null
        return v.toLong()
    }

    private fun boundedStr(node: RecordNode?, maxBytes: Int): String? {
        val s = (node as? RecordNode.Str)?.value ?: return null
        if (s.isEmpty()) return null
        if (WireBounds.utf8Length(s) > maxBytes) return null
        return s
    }

    private fun reject(reason: String): Result = Result.Rejected(reason)
}
