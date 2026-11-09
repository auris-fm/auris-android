package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record2

import org.json.JSONArray
import org.json.JSONObject

/**
 * The schema-version-1 upload record, as plain JSON.
 *
 * The server parses and validates the fields, so this encoder only has to produce JSON of the
 * right *shape*. Nothing depends on its exact bytes: no canonical ordering, no number formatting,
 * no digests.
 *
 * Field names follow the server's validator exactly. A name this encoder invents is a `400`, which
 * the uploader answers by deleting the recording, so the schema here is not stylistic.
 */
class PlainRecord(
    private val captureId: String,
    private val enrollmentEpoch: String,
    private val sessionId: String,
    private val platform: String,
    private val appVersion: String,
    private val build: String,
    /**
     * The account this recording belongs to, stamped at publish.
     *
     * The server does not read it; it exists so a queue that outlives a sign-in can tell that a
     * recording is no longer ours to send. Without it, a recording captured under one account
     * would be uploaded to whoever is signed in when the queue finally drains.
     */
    private val account: String,
    /**
     * The client's real grace state when the segment started: true when the wake word is required.
     * Required by the server, and required to be a boolean — it cannot be inferred later.
     */
    private val wakeRequired: Boolean,
    private val audio: AudioDescription,
    private val models: Map<String, ModelRef> = emptyMap(),
    /** One entry per stage, keyed by stage name. See [StageObservation]. */
    private val observations: Map<String, StageObservation> = emptyMap(),
    private val links: Map<String, String> = emptyMap(),
) {
    init {
        require(captureId.isNotBlank()) { "capture_id is required" }
        require(enrollmentEpoch.isNotBlank()) { "enrollment_epoch is required" }
        require(sessionId.isNotBlank()) { "capture_session_id is required" }
        require(appVersion.isNotBlank()) { "app_version is required" }
        require(build.isNotBlank()) { "build is required" }
        require(account.isNotBlank()) { "account is required" }
        require(platform == "android" || platform == "ios") { "platform must be android or ios" }
    }

    /**
     * Encode to a JSON object.
     *
     * `observations` is an OBJECT keyed by stage, not an array: the server rejects an array
     * outright, and every stage it names must be present so that "did not run" is stated rather
     * than left to be guessed.
     */
    fun toJson(): JSONObject = JSONObject().apply {
        put("schema_version", SCHEMA_VERSION)
        put("capture_id", captureId)
        put("enrollment_epoch", enrollmentEpoch)
        put("platform", platform)
        put("app_version", appVersion)
        put("build", build)
        put("capture_session_id", sessionId)
        put("account", account)
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
        put(
            "observations",
            JSONObject().apply {
                for ((stage, entry) in observations) put(stage, entry.toJson())
            },
        )
        if (links.isNotEmpty()) {
            put(
                "links",
                JSONObject().apply { for ((k, v) in links) put(k, v) },
            )
        }
    }

    /** The bytes to put in the `metadata` form field. Measured once by the caller. */
    fun encodedBytes(): ByteArray = toJson().toString().toByteArray(Charsets.UTF_8)

    companion object {
        const val SCHEMA_VERSION = 1

        /** The server's metadata bound, in UTF-8 bytes. */
        const val MAX_METADATA_BYTES = 65_536

        /** The server's audio bound: 60 seconds of 16 kHz mono PCM16 plus the WAV header. */
        const val MAX_AUDIO_BYTES = 1_920_044

        /** Every stage the record states, in the order the spec lists them. */
        val STAGES = listOf("bleed", "wake", "asr", "translation", "routing", "execution")
    }
}

/**
 * Describes the WAV that travels with this record.
 *
 * `byte_count` is the only field the server requires; the rest are checked when present, so an
 * accurate value here is how the server can tell a truncated upload from a complete one.
 */
data class AudioDescription(
    val byteCount: Long,
    val sampleRateHz: Int = 16_000,
    val channels: Int = 1,
    val encoding: String = "pcm_s16le",
    val retainedSamples: Long = 0,
    val originalFormat: String = "pcm_s16le",
    val conversionVersion: Int = 1,
    val truncated: Boolean = false,
    val droppedSpans: List<DroppedSpan> = emptyList(),
    val originalDurationMs: Long = 0,
) {
    init {
        require(byteCount > 0) { "audio.byte_count must be positive" }
        require(byteCount <= PlainRecord.MAX_AUDIO_BYTES) { "audio exceeds the server's bound" }
    }

    // Names are the spec's, not invented: a field the server does not expect is ignored, but a
    // required one under the wrong name is a refusal.
    fun toJson(): JSONObject = JSONObject().apply {
        put("byte_count", byteCount)
        put("encoding", encoding)
        put("sample_rate_hz", sampleRateHz)
        put("channels", channels)
        put("retained_samples", retainedSamples)
        put("original_format", originalFormat)
        put("conversion_version", conversionVersion)
        put("truncated", truncated)
        put("original_duration_ms", originalDurationMs)
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
 * One stage's outcome.
 *
 * A stage that did not run states a reason, never an invented result; a completed stage carries no
 * reason. `fields` hold that stage's own measured values — for example `decision`, `score` and
 * `threshold` for wake, `source_language` and `text` for asr.
 */
data class StageObservation(
    val state: String,
    val reason: String? = null,
    val fields: Map<String, Any> = emptyMap(),
) {
    init {
        require(state in STATES) { "unknown stage state: $state" }
        require(reason == null || reason.isNotBlank()) { "a stage reason must not be blank" }
        require(!(state == "completed" && reason != null)) {
            "a completed stage carries no reason (the server refuses it)"
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("state", state)
        reason?.let { put("reason", it) }
        for ((k, v) in fields) put(k, v)
    }

    companion object {
        val STATES = setOf("completed", "not_run", "failed", "cancelled", "unknown")

        fun notRun(reason: String) = StageObservation("not_run", reason)
        fun completed(vararg fields: Pair<String, Any>) = StageObservation("completed", null, fields.toMap())
    }
}
