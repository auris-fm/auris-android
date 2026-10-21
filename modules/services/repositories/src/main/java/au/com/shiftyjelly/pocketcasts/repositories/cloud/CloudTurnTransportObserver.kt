package au.com.shiftyjelly.pocketcasts.repositories.cloud

/**
 * Observes actual transport callbacks during a cloud turn.
 *
 * Status and reason codes only — no tokens, headers, or raw payloads.
 * The voice module implements this to persist diagnostics; the repositories
 * module calls it at real WebSocket/auth boundaries.
 */
interface CloudTurnTransportObserver {
    /** Auth token was resolved (or failed) before socket connect. */
    fun onAuthResolved(requestId: String, success: Boolean)

    /** WebSocket onOpen fired; auth frame send attempted. */
    fun onSocketOpened(requestId: String)

    /** Auth frame send completed (accepted by WebSocket layer). */
    fun onSendAccepted(requestId: String)

    /** Auth frame send rejected by WebSocket layer. */
    fun onSendRejected(requestId: String, reason: String)

    /** Server terminal received (Done or Error from server). Carries the error code if any. */
    fun onTerminalReceived(requestId: String, errorCode: String?)

    /** Connection failed before any server response. */
    fun onConnectionFailed(requestId: String, reason: String)
}
