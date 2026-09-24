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

    /** `{"role":"…","text":"…"}` plus separators — measured, not guessed. */
    private const val JSON_ENTRY_FRAMING_BYTES = 24

    /**
     * Clamps recent conversation to the spec bounds: keep the newest entries
     * (at most four), then drop/truncate so the UTF-8 total is within 8 KiB.
     */
    fun clampConversation(entries: List<CloudRouteConversationEntry>): List<CloudRouteConversationEntry> {
        if (entries.isEmpty()) return emptyList()
        val newest = entries.takeLast(MAX_CONVERSATION_ENTRIES)
        // Walk from newest to oldest, keeping whole entries until the byte
        // budget is exhausted; a single oversized entry is truncated.
        //
        // Accounting covers the whole entry — role, text and the JSON framing
        // the bound is stated against ("8 KiB UTF-8 total") — not just the
        // text, so the serialized conversation cannot exceed the budget.
        val kept = ArrayDeque<CloudRouteConversationEntry>()
        var bytes = 0
        for (entry in newest.asReversed()) {
            val overhead = entryFramingBytes(entry)
            val textBytes = entry.text.toByteArray(Charsets.UTF_8).size
            val remaining = MAX_CONVERSATION_BYTES - bytes
            if (remaining <= overhead) break
            if (textBytes + overhead <= remaining) {
                kept.addFirst(entry)
                bytes += textBytes + overhead
            } else {
                // Truncate the text to what fits once framing is accounted for.
                val truncated = entry.text.truncateToUtf8Bytes(remaining - overhead)
                if (truncated.isNotEmpty()) {
                    val trimmed = entry.copy(text = truncated)
                    kept.addFirst(trimmed)
                    bytes += truncated.toByteArray(Charsets.UTF_8).size + entryFramingBytes(trimmed)
                }
                break
            }
        }
        return kept.toList()
    }

    /** Bytes an entry adds beyond its text: role plus JSON key/quoting overhead. */
    private fun entryFramingBytes(entry: CloudRouteConversationEntry): Int = entry.role.toByteArray(Charsets.UTF_8).size + JSON_ENTRY_FRAMING_BYTES

    private fun String.truncateToUtf8Bytes(maxBytes: Int): String {
        val bytes = toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return this
        var end = maxBytes
        // Do not split a multi-byte character.
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8)
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
