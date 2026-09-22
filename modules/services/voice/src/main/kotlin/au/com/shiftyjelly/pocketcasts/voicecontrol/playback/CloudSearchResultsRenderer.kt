package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudSearchResults

/**
 * Renders negotiated structured discovery results. Present only when the
 * client can render all three states — that presence is what makes the client
 * advertise `search_results_v1`.
 *
 * Results never auto-play: selection goes through the catalog resolution flow,
 * and an item without an episode id stays discovery-only.
 */
interface CloudSearchResultsRenderer {
    fun renderResults(results: CloudSearchResults)

    /** Successful no-match result (distinct from unavailable). */
    fun renderEmpty(scope: String)

    /** Evidence genuinely unavailable for this turn. */
    fun renderUnavailable()
}
