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

    /** Bounded send outcome. */
    enum class SendOutcome { ACCEPTED, REJECTED }

    /** Bounded terminal origin. */
    enum class TerminalOrigin { SERVER_DONE, SERVER_ERROR, LOCAL_CONNECTION_FAILED, LOCAL_PARSE_ERROR }

    /** Auth token was resolved (or failed) before socket connect. */
    fun onAuthResolved(requestId: String, outcome: AuthOutcome)

    /** WebSocket onOpen fired; auth frame send attempted. */
    fun onSocketOpened(requestId: String)

    /** Auth frame send result. */
    fun onSendResult(requestId: String, outcome: SendOutcome)

    /** Server terminal received (Done or Error from server). Carries the bounded error code if any. */
    fun onTerminalReceived(requestId: String, origin: TerminalOrigin, errorCode: String?)

    /** Connection failed before any server response. */
    fun onConnectionFailed(requestId: String)
}
