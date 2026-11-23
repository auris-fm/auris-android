package au.com.shiftyjelly.pocketcasts.repositories.cloud

/**
 * Observes actual transport callbacks during a cloud turn.
 *
 * Status and reason codes only — no tokens, headers, or raw payloads.
 * All string codes are bounded/allowlisted before persistence.
 */
interface CloudTurnTransportObserver {
    /** Bounded auth outcome. */
    enum class AuthOutcome { RESOLVED, MISSING_TOKEN }

    /** Bounded send outcome. Distinguishes encode failure from send rejection from throw. */
    enum class SendOutcome { ACCEPTED, REJECTED, ENCODE_FAILED, SEND_THREW }

    /** Bounded terminal origin. */
    enum class TerminalOrigin { SERVER_DONE, SERVER_ERROR, LOCAL_CONNECTION_FAILED, LOCAL_PARSE_ERROR }

    /** Auth token was resolved (or failed) before socket connect. */
    fun onAuthResolved(requestId: String, outcome: AuthOutcome)

    /** WebSocket onOpen fired; auth frame send attempted. */
    fun onSocketOpened(requestId: String)

    /** Send entry: the call to WebSocket.send is about to be made. Encode has passed. */
    fun onSendAttempted(requestId: String)

    /** Auth frame send result. */
    fun onSendResult(requestId: String, outcome: SendOutcome)

    /** Server terminal received (Done or Error from server). Carries the bounded error code if any. */
    fun onTerminalReceived(requestId: String, origin: TerminalOrigin, errorCode: String?)

    /**
     * The socket ended without giving the turn its answer: WebSocket onFailure (connection lost or
     * upgrade refused), or a clean onClosed that arrives mid-turn with no terminal. May occur after
     * a partial response. A normal close is NOT exempt -- an ended stream is the same outage as a
     * failed one, so implementers must not assume this only fires on failure callbacks.
     *
     * [stillNeeded] says whether the failure was news about a turn that was still waiting for it.
     * A socket that dies after the turn's terminal arrived, or after the exchange was cancelled,
     * is being torn down because the turn no longer needs it: the close is the expected
     * consequence, not a connection failure, and a consumer that reports it as one tells the
     * reader a finished turn failed to connect.
     */
    fun onConnectionFailed(requestId: String, stillNeeded: Boolean)
}
