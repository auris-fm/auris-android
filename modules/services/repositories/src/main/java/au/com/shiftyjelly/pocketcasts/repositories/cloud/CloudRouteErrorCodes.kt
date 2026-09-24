package au.com.shiftyjelly.pocketcasts.repositories.cloud

/** Server error codes the client branches on, in one place (cloud-assistant.md). */
object CloudRouteErrorCodes {
    const val RETRIEVAL_UNAVAILABLE = "retrieval_unavailable"
    const val INVALID_RESPONSE = "invalid_response"
    const val CONNECTION_LOST = "connection_lost"
    const val UNAUTHORIZED = "unauthorized"
    const val INTERNAL_ERROR = "internal_error"
    const val BUDGET_EXCEEDED = "budget_exceeded"
    const val DUPLICATE_REQUEST = "duplicate_request"
    const val PROVIDER_ERROR = "provider_error"

    private val KNOWN = setOf(
        RETRIEVAL_UNAVAILABLE,
        INVALID_RESPONSE,
        CONNECTION_LOST,
        UNAUTHORIZED,
        INTERNAL_ERROR,
        BUDGET_EXCEEDED,
        DUPLICATE_REQUEST,
        PROVIDER_ERROR,
    )

    /**
     * Log-safe form of a server-supplied code: a known code is logged as
     * itself, anything else collapses to a constant. The raw value comes from
     * a response body and must never be written verbatim to device logs.
     */
    fun normalizeForLog(code: String?): String = when {
        code == null -> "none"
        code in KNOWN -> code
        else -> "unrecognized"
    }
}
