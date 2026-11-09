package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record2

import org.json.JSONArray
import org.json.JSONObject

/**
 * The schema-version-1 upload record, as plain JSON.
 *
 * Nothing here depends on the exact bytes. The server parses and validates the fields, so this
 * encoder just has to produce JSON of the right *shape*: no canonical ordering, no number
 * formatting rules, no digests. That is deliberate - the previous record layer spent most of its
 * code on byte-exactness the server never needed.
 *
 * Required fields are the spec's: schema_version, capture_id, enrollment_epoch, platform,
 * app_version, build, capture_session_id, wake_required, audio, models, observations, and the
 * optional links.
 */
class PlainRecord(
    private val captureId: String,
    private val enrollmentEpoch: String,
    private val sessionId: String,
    private val platform: String,
    private val appVersion: String,
    private val build: String,
    /**
     * The client's real grace state when the segment started: true when the wake word is required.
     * Recorded from the actual state, never inferred from the model or from what the segment
     * happened to contain.
     */
    private val wakeRequired: Boolean,
    private val audio: AudioDescription,
    private val models: List<Pair<String, ModelRef>> = emptyList(),
    private val observations: List<StageObservation> = emptyList(),
    private val links: Map<String, String> = emptyMap(),
) {
    init {
        require(captureId.isNotBlank()) { "capture_id is required" }
        require(enrollmentEpoch.isNotBlank()) { "enrollment_epoch is required" }
        require(sessionId.isNotBlank()) { "capture_session_id is required" }
        require(platform.isNotBlank()) { "platform is required" }
        require(appVersion.isNotBlank()) { "app_version is required" }
        require(build.isNotBlank()) { "build is required" }
    }

    /**
     * Encode to a JSON object. Field order is whatever the encoder produces: the server reads by
     * name, and there is no canonical form to match.
     */
    fun toJson(): JSONObject = JSONObject().apply {
        put("schema_version", SCHEMA_VERSION)
        put("capture_id", captureId)
        put("enrollment_epoch", enrollmentEpoch)
        put("platform", platform)
        put("app_version", appVersion)
        put("build", build)
        put("capture_session_id", sessionId)
        put("wake_required", wakeRequired)
        put("audio", audio.toJson())
        put(
            "models",
            JSONObject().apply {
                for ((name, ref) in models) {
                    put(
                        name,
                        JSONObject().apply {
                            put("id", ref.id)
                            put("version", ref.version)
                        },
                    )
                }
            },
        )
        put("observations", JSONArray().apply { observations.forEach { put(it.toJson()) } })
        if (links.isNotEmpty()) {
            put(
                "links",
                JSONObject().apply { for ((k, v) in links) put(k, v) },
            )
        }
    }

    /**
     * The bytes to put in the multipart `metadata` part.
     *
     * Not canonical and not digested. A caller that needs the size uses [encodedBytes] once and
     * sends exactly what it measured.
     */
    fun encodedBytes(): ByteArray = toJson().toString().toByteArray(Charsets.UTF_8)

    companion object {
        const val SCHEMA_VERSION = 1
        const val MIME = "application/json"

        /** The spec's metadata bound; a record over this is not kept. */
        const val MAX_METADATA_BYTES = 65_536
    }
}

/** Describes the WAV that travels with this record. Byte count is the number of bytes sent. */
data class AudioDescription(
    val byteCount: Long,
    val encoding: String = "pcm_s16le",
    val sampleRateHz: Int = 16_000,
    val channels: Int = 1,
    val retainedSamples: Long,
    val originalFormat: String,
    val conversionVersion: Int,
    val truncated: Boolean = false,
    val droppedSpans: List<DroppedSpan> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("byte_count", byteCount)
        put("encoding", encoding)
        put("sample_rate_hz", sampleRateHz)
        put("channels", channels)
        put("retained_samples", retainedSamples)
        put("original_format", originalFormat)
        put("conversion_version", conversionVersion)
        put("truncated", truncated)
        put(
            "dropped_spans",
            JSONArray().apply {
                droppedSpans.forEach {
                    put(
                        JSONObject().apply {
                            put("start_sample", it.startSample)
                            put("end_sample", it.endSample)
                        },
                    )
                }
            },
        )
    }
}

data class DroppedSpan(val startSample: Long, val endSample: Long)

data class ModelRef(val id: String, val version: String)

/**
 * One stage's outcome. Stages report only what actually happened: a stage that did not run states
 * a reason, never an invented result. An unknown outcome omits `outcome` entirely.
 */
data class StageObservation(
    val stage: String,
    val state: String,
    val reason: String? = null,
    val outcome: String? = null,
    /** Stage-specific measured values, e.g. wake `score`/`threshold`, routing `wake_only`. */
    val fields: Map<String, Any> = emptyMap(),
) {
    init {
        require(stage.isNotBlank()) { "stage is required" }
        require(state in STATES) { "unknown stage state: $state" }
        require(reason == null || (reason.isNotBlank() && reason.none { it.isISOControl() })) {
            "a stage reason must be non-empty and free of control characters"
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("stage", stage)
        put("state", state)
        reason?.let { put("reason", it) }
        outcome?.let { put("outcome", it) }
        for ((k, v) in fields) put(k, v)
    }

    companion object {
        val STATES = setOf("completed", "not_run", "failed", "cancelled", "unknown")
    }
}
