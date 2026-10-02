package au.com.shiftyjelly.pocketcasts.repositories.cloud

/**
 * A frame the client sends on the turn connection. Only the authentication frame exists so far;
 * the server answers with [CloudRouteEvent]s.
 */
sealed interface CloudTurnFrame {
    /**
     * The first frame on a turn, carrying the whole request. The access token travels here rather
     * than in a header, which is why the transport cannot reuse the SSE request shape.
     */
    data class Authenticate(
        val accessToken: String,
        val requestId: String,
        val request: String,
        val context: CloudRouteContext,
        val capabilities: List<String>,
        val codecs: List<String>,
    ) : CloudTurnFrame
}
