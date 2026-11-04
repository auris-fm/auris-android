package au.com.shiftyjelly.pocketcasts.repositories.cloud

/**
 * A frame the client sends on the turn connection.
 *
 * [Authenticate] opens the turn. [AppOperationResult] answers one app operation on that SAME
 * authenticated socket, and only after its settlement is durable.
 */
sealed interface CloudTurnFrame {
    /**
     * The first frame on a turn, carrying the whole request. The access token travels here rather
     * than in a header, because the request is part of the socket's first message.
     */
    data class Authenticate(
        val accessToken: String,
        val requestId: String,
        val request: String,
        val context: CloudRouteContext,
        val capabilities: List<String>,
        val codecs: List<String>,
        /**
         * A read-only tool hint from a typed UI flow, when one supplied it. Optional and omitted
         * when absent.
         */
        val routeHint: CloudRouteHint? = null,
        /** The conversation this turn creates or resumes. */
        val conversationId: String,
        val conversationMode: CloudConversationMode,
        /** Optional ASR source-language tag, omitted when unknown. */
        val sourceLanguage: String? = null,
    ) : CloudTurnFrame

    /**
     * The answer to one app operation (core `cloud-assistant.md`, "App-operation protocol v1").
     *
     * Sent on the socket the operation arrived on, identified by the same [requestId] and
     * [operationId]. A send accepted by the socket is not proof the server received it, so these are
     * never retried by this client: the durable journal, not the socket, is what makes a redelivery
     * answerable.
     */
    data class AppOperationResult(
        val requestId: String,
        val operationId: String,
        val payloadJson: String,
    ) : CloudTurnFrame
}
