package au.com.shiftyjelly.pocketcasts.repositories.cloud

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CloudRouteContext(
    /**
     * The episode the answer concerns, or NULL when there is none -- discovery with nothing playing.
     *
     * Null is OMITTED on the wire, which is the honest representation of "no current episode". It is
     * deliberately not an empty string and not a substitute: a fabricated identifier would claim a
     * subject the turn does not have, and the server cannot tell that apart from a real episode whose
     * id happens to be blank.
     */
    @Json(name = "episode_id") val episodeId: String? = null,
    @Json(name = "podcast_id") val podcastId: String? = null,
    @Json(name = "reference_position_ms") val referencePositionMs: Long? = null,
    /**
     * The client's own play position, or null when no episode is playing.
     *
     * Absent rather than zero: zero is a real position (the very start), so reporting it for "nothing
     * is playing" would place the turn at an instant that does not exist.
     */
    @Json(name = "client_position_ms") val clientPositionMs: Long? = null,
    @Json(name = "recent_reference_positions") val recentReferencePositions: List<Long> = emptyList(),
    @Json(name = "previous_reference_position_ms") val previousReferencePositionMs: Long? = null,
)

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

/**
 * How a turn relates to the conversation it belongs to.
 *
 * The first turn of a conversation CREATES it; a later turn in the same live conversation RESUMES it.
 * A retry of the same logical turn keeps its identity rather than creating a second conversation, and
 * a turn whose origin has been superseded is discarded rather than admitted against a handle that now
 * belongs to a successor.
 */
enum class CloudConversationMode(val wireValue: String) {
    Create("create"),
    Resume("resume"),
}

/** One logical turn: the utterance plus its turn-control fields. */
data class CloudRouteTurn(
    val request: String,
    val context: CloudRouteContext,
    val requestId: String,
    val capabilities: List<String> = emptyList(),
    val routeHint: CloudRouteHint? = null,
    /** Captured conversation identity; retries keep the same handle and mode. */
    val conversationId: String,
    val conversationMode: CloudConversationMode,
    /**
     * The window this turn was CAPTURED under, or null when it was not captured.
     *
     * Carried so a RETRY is validated against the window the turn belongs to rather than against
     * whichever window is current when it is finally retried -- otherwise a retry of a superseded
     * request would be admitted under its successor.
     */
    val capturedOrigin: Long? = null,
    /** Optional ASR source language; absence is unknown, not the UI locale. */
    val sourceLanguage: String? = null,
)

/** Capability names negotiated with the server (cloud-assistant.md). */
object CloudRouteCapabilities {
    const val SEARCH_RESULTS_V1 = "search_results_v1"
}

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

/**
 * The `connected` frame's payload: the codec the server negotiated for the binary audio path.
 * This is where that value arrives — the protocol has no separate `auth` frame.
 */
@JsonClass(generateAdapter = true)
internal data class CloudRouteConnectedPayload(
    val codec: String,
    @Json(name = "reservation_id") val reservationId: String,
)
