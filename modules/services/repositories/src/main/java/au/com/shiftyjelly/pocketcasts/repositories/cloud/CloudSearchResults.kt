package au.com.shiftyjelly.pocketcasts.repositories.cloud

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Negotiated structured discovery results (`search_results_v1`,
 * cloud-assistant.md). Only clients advertising the capability receive these;
 * other clients get deterministic short text plus `done`.
 */
@JsonClass(generateAdapter = true)
data class CloudSearchResults(
    val kind: String,
    @Json(name = "scope") val scope: String,
    @Json(name = "items") val items: List<CloudSearchEvidenceItem> = emptyList(),
    @Json(name = "next_cursor") val nextCursor: String? = null,
) {
    companion object {
        const val KIND_EPISODE_RESULTS = "episode_results"
        const val SCOPE_LIBRARY = "library"
        const val SCOPE_GLOBAL = "global"
        const val SCOPE_CURRENT_EPISODE = "current_episode"
    }
}

/**
 * One bounded evidence item. `episode_id` null means discovery-only (never
 * playable); `playable` and `seekable` are independent gates. Provider IDs are
 * intentionally absent: they must never reach player commands.
 */
@JsonClass(generateAdapter = true)
data class CloudSearchEvidenceItem(
    @Json(name = "evidence_id") val evidenceId: String,
    @Json(name = "episode_id") val episodeId: String? = null,
    @Json(name = "podcast_id") val podcastId: String? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "text") val text: String? = null,
    @Json(name = "speaker") val speaker: String? = null,
    @Json(name = "source_url") val sourceUrl: String? = null,
    @Json(name = "playable") val playable: Boolean = false,
    @Json(name = "seekable") val seekable: Boolean = false,
)
