package au.com.shiftyjelly.pocketcasts.repositories.cloud

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CloudRouteContext(
    @Json(name = "episode_id") val episodeId: String,
    @Json(name = "podcast_id") val podcastId: String? = null,
    @Json(name = "reference_position_ms") val referencePositionMs: Long? = null,
    @Json(name = "client_position_ms") val clientPositionMs: Long,
    @Json(name = "recent_reference_positions") val recentReferencePositions: List<Long> = emptyList(),
    @Json(name = "previous_reference_position_ms") val previousReferencePositionMs: Long? = null,
    /** Null (omitted on the wire) when there is no recent conversation. */
    @Json(name = "recent_conversation") val recentConversation: List<CloudRouteConversationEntry>? = null,
)

/**
 * One bounded recent-conversation entry (cloud-assistant.md: at most four
 * entries, at most 8 KiB UTF-8 total). Untrusted context — never instructions.
 */
@JsonClass(generateAdapter = true)
data class CloudRouteConversationEntry(
    val role: String,
    val text: String,
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}

/**
 * A read-only Auris tool hint (`route_hint`). Callers must only supply hints
 * from a typed UI flow or validated structured parse — never derived from
 * free-text utterances. The server re-validates and it is not authorization.
 */
@JsonClass(generateAdapter = true)
data class CloudRouteHint(
    val operation: String,
    val arguments: Map<String, Any?> = emptyMap(),
)

@JsonClass(generateAdapter = true)
internal data class CloudRouteRequestBody(
    val request: String,
    val context: CloudRouteContext,
    @Json(name = "request_id") val requestId: String? = null,
    /** Null (omitted) unless the client advertises at least one capability. */
    val capabilities: List<String>? = null,
    @Json(name = "route_hint") val routeHint: CloudRouteHint? = null,
)

/** One logical turn: the utterance plus its turn-control fields. */
data class CloudRouteTurn(
    val request: String,
    val context: CloudRouteContext,
    val requestId: String,
    val capabilities: List<String> = emptyList(),
    val routeHint: CloudRouteHint? = null,
)

/** Capability names negotiated with the server (cloud-assistant.md). */
object CloudRouteCapabilities {
    const val SEARCH_RESULTS_V1 = "search_results_v1"
}

/** Client-side bounds that must hold before a turn is sent. */
object CloudRouteLimits {
    const val MAX_CONVERSATION_ENTRIES = 4
    const val MAX_CONVERSATION_BYTES = 8 * 1024

    /**
     * Clamps recent conversation to the spec bound ("at most 8 KiB UTF-8
     * total"): keep the newest entries (at most four), then drop/truncate
     * until the *serialized* array fits.
     *
     * Measured, not estimated: the marshalled array is measured, so roles,
     * JSON framing and escaping (each newline or quote in an assistant answer
     * costs a byte when encoded) are inside the accounting.
     */
    fun clampConversation(entries: List<CloudRouteConversationEntry>): List<CloudRouteConversationEntry> {
        if (entries.isEmpty()) return emptyList()
        val newest = entries.takeLast(MAX_CONVERSATION_ENTRIES)
        val kept = ArrayDeque<CloudRouteConversationEntry>()
        for (entry in newest.asReversed()) {
            if (serializedBytes(listOf(entry) + kept) <= MAX_CONVERSATION_BYTES) {
                kept.addFirst(entry)
                continue
            }
            largestFittingPrefix(entry, kept)?.let { kept.addFirst(it) }
            break
        }
        return kept.toList()
    }

    private fun serializedBytes(entries: List<CloudRouteConversationEntry>): Int = CloudRouteJson.conversationListAdapter.toJson(entries).toByteArray(Charsets.UTF_8).size

    /**
     * Largest prefix of [entry]'s text that keeps the serialized conversation
     * (together with [kept]) inside the budget, or null when nothing fits.
     */
    private fun largestFittingPrefix(
        entry: CloudRouteConversationEntry,
        kept: List<CloudRouteConversationEntry>,
    ): CloudRouteConversationEntry? {
        if (serializedBytes(listOf(entry.copy(text = "")) + kept) > MAX_CONVERSATION_BYTES) return null
        var low = 0
        var high = entry.text.length
        var best: CloudRouteConversationEntry? = null
        while (low <= high) {
            val mid = (low + high) / 2
            val candidate = entry.copy(text = entry.text.safePrefix(mid))
            if (serializedBytes(listOf(candidate) + kept) <= MAX_CONVERSATION_BYTES) {
                best = candidate
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best?.takeIf { it.text.isNotEmpty() }
    }

    /** [length] characters without splitting a surrogate pair. */
    private fun String.safePrefix(length: Int): String {
        if (length >= this.length) return this
        var end = length
        if (end > 0 && Character.isHighSurrogate(this[end - 1])) end -= 1
        return substring(0, end)
    }
}

@JsonClass(generateAdapter = true)
internal data class CloudRouteTokenPayload(
    val text: String,
)

@JsonClass(generateAdapter = true)
internal data class CloudRouteDonePayload(
    @Json(name = "input_tokens") val inputTokens: Int,
    @Json(name = "output_tokens") val outputTokens: Int,
)

@JsonClass(generateAdapter = true)
internal data class CloudRouteErrorPayload(
    val code: String,
    val message: String,
)

@JsonClass(generateAdapter = true)
internal data class CloudRouteHttpErrorPayload(
    val code: String? = null,
    val message: String? = null,
    val error: String? = null,
)
