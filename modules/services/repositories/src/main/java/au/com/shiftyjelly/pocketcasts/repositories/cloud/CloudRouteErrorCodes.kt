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
    const val INVALID_REQUEST = "invalid_request"

    private val KNOWN = setOf(
        RETRIEVAL_UNAVAILABLE,
        INVALID_RESPONSE,
        CONNECTION_LOST,
        UNAUTHORIZED,
        INTERNAL_ERROR,
        BUDGET_EXCEEDED,
        DUPLICATE_REQUEST,
        PROVIDER_ERROR,
        INVALID_REQUEST,
    )

    /** `http_<3-digit status>` is minted locally from the response code. */
    private val HTTP_STATUS_CODE = Regex("http_\\d{3}")

    /**
     * Log-safe form of a server-supplied code: a known code is logged as
     * itself, anything else collapses to a constant. The raw value comes from
     * a response body and must never be written verbatim to device logs.
     */
    fun normalizeForLog(code: String?): String = when {
        code == null -> "none"

        // Known server codes, plus the bounded shapes this client mints
        // itself: `invalid_request`, and `http_<status>`, which carries the
        // status of an unparseable error body — exactly the diagnostic worth
        // having when a gateway answers with HTML.
        code in KNOWN || HTTP_STATUS_CODE.matches(code) -> code

        else -> "unrecognized"
    }
}
