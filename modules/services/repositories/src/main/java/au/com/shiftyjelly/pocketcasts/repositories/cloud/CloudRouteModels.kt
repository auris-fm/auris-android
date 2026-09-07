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
)

@JsonClass(generateAdapter = true)
internal data class CloudRouteRequestBody(
    val request: String,
    val context: CloudRouteContext,
)

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
