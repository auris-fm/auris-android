package au.com.shiftyjelly.pocketcasts.voicecontrol.playback.appops

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Client codec for the app-operation protocol v1 (core `cloud-assistant.md`, "App-operation
 * protocol v1", committed `bf5fb219`). Parse/serialize and bound enforcement ONLY — this file
 * executes nothing and advertises nothing. The authenticate-frame advertisement
 * (`app_operations_v1` / `app_operation_support`) is deliberately NOT wired anywhere yet.
 *
 * The legacy playback stream and the deterministic answer-hold controls are separate
 * compatibility paths; a malformed app operation must never fall back to either.
 */
object AppOperationV1 {
    const val SCHEMA_VERSION = "app-ops/1"
    const val CAPABILITY = "app_operations_v1"
    const val SUPPORT_FIELD = "app_operation_support"
    const val FRAME_TYPE_ACTION = "action"
    const val RESULT_TYPE = "app_operation_result"

    /** Complete request or result envelopes are at most 16 KiB of UTF-8, enforced before execution. */
    const val MAX_ENVELOPE_BYTES = 16 * 1024

    /** The encoded `state` object is at most 8 KiB. */
    const val MAX_STATE_BYTES = 8 * 1024

    /** `detail` is at most 512 UTF-8 bytes, truncated by the sender on a code-point boundary. */
    const val MAX_DETAIL_BYTES = 512

    /** Collections contain at most 100 items, at every nesting level. */
    const val MAX_COLLECTION_ITEMS = 100

    /**
     * Episode identity is required for every action whose TARGET matters for the episode: it either
     * addresses a position or chapter inside the current episode, or it changes which episode plays
     * (and so must be checked against the episode the proposal was formed for). Names come from the
     * Voice Intents action tables (`voice-intents.md`, committed `eb2ccbcd`); each tool's complete
     * action list is transcribed here so the mapping is reviewable in one place.
     *
     * - `playback`: pause, resume, seek_relative, seek_to, next_episode
     * - `chapter`: next, previous, by_index, by_title, open_link, query_list, query_current,
     *   query_count, query_next
     * - `queue`: add_top, add_bottom, remove, move_to_top, move_to_bottom, clear, remove_by_podcast,
     *   sort, query_contents, query_next, query_length, query_is_queued
     */
    val EPISODE_RELATIVE_ACTIONS = setOf(
        // Position and chapter navigation address a position inside one episode.
        "playback.seek_relative",
        "playback.seek_to",
        "chapter.next",
        "chapter.previous",
        "chapter.by_index",
        "chapter.by_title",
        "chapter.open_link",
        // Chapter reads report on an episode and are proposals about that episode.
        "chapter.query_list",
        "chapter.query_current",
        "chapter.query_count",
        "chapter.query_next",
        // next_episode changes which episode plays: the target episode matters.
        "playback.next_episode",
    )

    /**
     * Queue mutations change a client-owned queue and require the `queue_revision` token. Queue
     * reads report current contents and are not mutations.
     */
    val QUEUE_MUTATIONS = setOf(
        "queue.add_top",
        "queue.add_bottom",
        "queue.remove",
        "queue.move_to_top",
        "queue.move_to_bottom",
        "queue.remove_by_podcast",
        "queue.sort",
        "queue.clear",
    )

    /** Reason tokens: machine-readable ASCII on every non-success outcome. */
    private val REASON_REGEX = Regex("[a-z][a-z0-9_]{0,127}")

    private val OPERATION_ID_REGEX = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    const val OUTCOME_SUCCEEDED = "succeeded"
    const val OUTCOME_REFUSED = "refused"
    const val OUTCOME_FAILED = "failed"
    const val OUTCOME_CANCELLED = "cancelled"
    const val OUTCOME_UNKNOWN = "unknown"

    /** A parsed request, retaining the ORIGINAL serialized bytes for replay comparison. */
    class Request(
        val operationId: String,
        val capabilityVersion: String,
        val tool: String,
        val action: String,
        val params: JSONObject,
        val precondition: JSONObject?,
        val rawBytes: ByteArray,
    )

    sealed class Parsed {
        class Valid(val request: Request) : Parsed()

        /**
         * Malformed or oversize. When a valid bounded operation identity is available the client
         * answers `refused`/`invalid_request`; with no recoverable identity the message is simply
         * terminated — never guessed into a legacy or effect path.
         */
        class Invalid(val operationId: String?, val reasonToken: String?) : Parsed()
    }

    /**
     * Parses an `action` frame as an app operation. Oversize is rejected BEFORE any JSON work; an
     * operation requires BOTH `operation_id` and `capability_version` — an incomplete pair is
     * malformed, not legacy playback (legacy frames never reach this parser). Malformed input is
     * never repaired into a [Valid].
     */
    fun parse(frameJson: String): Parsed {
        val bytes = frameJson.toByteArray(Charsets.UTF_8)
        // Oversize envelopes terminate unidentified: no prefix scan, no guessing an identity out of a
        // partially received frame. The server retains the operation unresolved and it expires to
        // unknown; the client asserts nothing about it.
        if (bytes.size > MAX_ENVELOPE_BYTES) return Parsed.Invalid(null, null)
        val obj = try {
            JSONObject(JSONTokener(frameJson))
        } catch (_: Exception) {
            return Parsed.Invalid(recoverOperationId(frameJson), null)
        }
        val operationId = obj.strictString("operation_id")?.takeIf { OPERATION_ID_REGEX.matches(it) }
        val version = obj.strictString("capability_version")
        if (operationId == null) {
            // No usable bounded identity: terminate rather than guess an effect path.
            return Parsed.Invalid(null, null)
        }
        if (version == null || version != SCHEMA_VERSION) return Parsed.Invalid(operationId, "invalid_request")
        if (obj.strictString("type") != FRAME_TYPE_ACTION) return Parsed.Invalid(operationId, "invalid_request")
        val tool = obj.strictString("tool") ?: return Parsed.Invalid(operationId, "invalid_request")
        val action = obj.strictString("action") ?: return Parsed.Invalid(operationId, "invalid_request")
        // `params` must be present and an object; it is never repaired to an empty object.
        val params = obj.optJSONObject("params") ?: return Parsed.Invalid(operationId, "invalid_request")
        val preconditionRaw = obj.opt("precondition")
        if (preconditionRaw != null && preconditionRaw !is JSONObject) {
            return Parsed.Invalid(operationId, "invalid_request")
        }
        val precondition: JSONObject? = preconditionRaw
        val key = "$tool.$action"
        if (key in EPISODE_RELATIVE_ACTIONS && precondition?.strictString("episode_id") == null) {
            return Parsed.Invalid(operationId, "invalid_request")
        }
        if (key in QUEUE_MUTATIONS && precondition?.strictString("queue_revision") == null) {
            return Parsed.Invalid(operationId, "invalid_request")
        }
        if (exceedsCollectionBounds(params) || (precondition != null && exceedsCollectionBounds(precondition))) {
            return Parsed.Invalid(operationId, "invalid_request")
        }
        return Parsed.Valid(Request(operationId, version, tool, action, params, precondition, bytes))
    }

    private fun recoverOperationId(text: String): String? = try {
        val obj = JSONObject(JSONTokener(text))
        obj.strictString("operation_id")?.takeIf { OPERATION_ID_REGEX.matches(it) }
    } catch (_: Exception) {
        null
    }

    /** Nested bound check: an oversized collection anywhere in the tree fails the whole request. */
    private fun exceedsCollectionBounds(obj: JSONObject): Boolean {
        for (key in obj.keys()) {
            if (exceedsCollectionBounds(obj.opt(key))) return true
        }
        return false
    }

    private fun exceedsCollectionBounds(array: JSONArray): Boolean {
        if (array.length() > MAX_COLLECTION_ITEMS) return true
        for (i in 0 until array.length()) {
            if (exceedsCollectionBounds(array.opt(i))) return true
        }
        return false
    }

    private fun exceedsCollectionBounds(value: Any?): Boolean = when (value) {
        is JSONObject -> exceedsCollectionBounds(value)
        is JSONArray -> exceedsCollectionBounds(value)
        else -> false
    }

    private fun JSONObject.strictString(key: String): String? {
        val v = opt(key) ?: return null
        return if (v is String && v.isNotBlank()) v else null
    }

    fun isOutcome(value: String): Boolean = value == OUTCOME_SUCCEEDED || value == OUTCOME_REFUSED ||
        value == OUTCOME_FAILED || value == OUTCOME_CANCELLED || value == OUTCOME_UNKNOWN

    /**
     * Whether an outcome/reason/state triple satisfies the result contract. This is the ONE validator
     * used both by result serialization and by the durable journal before it records a settlement, so
     * the two can never disagree about what a publishable result is.
     */
    fun isValidResultShape(outcome: String, reason: String?, state: JSONObject?): Boolean {
        if (!isOutcome(outcome)) return false
        if (outcome == OUTCOME_SUCCEEDED) {
            if (reason != null) return false
            if (state == null) return false // success REQUIRES observed state; an earcon is not evidence
        } else {
            if (state != null) return false // state is absent on non-success
            if (reason == null || !REASON_REGEX.matches(reason)) return false
        }
        if (state != null) {
            if (state.toString().toByteArray(Charsets.UTF_8).size > MAX_STATE_BYTES) return false
            if (exceedsCollectionBounds(state)) return false
        }
        return true
    }

    /**
     * Serializes a result control frame, enforcing every field rule. Returns null when the result
     * as given is unpublishable (bad/empty id, bad outcome/reason, oversize or over-wide state) — an
     * unpublishable result never settles success and is never sent in a repaired form.
     */
    fun result(
        operationId: String,
        outcome: String,
        reason: String? = null,
        state: JSONObject? = null,
        detail: String? = null,
    ): String? {
        if (!OPERATION_ID_REGEX.matches(operationId)) return null
        if (!isValidResultShape(outcome, reason, state)) return null
        val obj = JSONObject()
        obj.put("type", RESULT_TYPE)
        obj.put("operation_id", operationId)
        obj.put("outcome", outcome)
        if (reason != null) obj.put("reason", reason)
        if (state != null) obj.put("state", JSONObject(JSONTokener(state.toString())))
        if (detail != null) obj.put("detail", truncateUtf8(detail, MAX_DETAIL_BYTES))
        val text = obj.toString()
        if (text.toByteArray(Charsets.UTF_8).size > MAX_ENVELOPE_BYTES) return null
        return text
    }

    /** UTF-8 byte bound with code-point-safe truncation: never splits a multi-byte character. */
    fun truncateUtf8(text: String, maxBytes: Int): String {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        var end = text.length
        while (end > 0 && text.substring(0, end).toByteArray(Charsets.UTF_8).size > maxBytes) {
            end--
        }
        // Step back over a split surrogate pair, if truncation landed mid-pair.
        while (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end)
    }
}
