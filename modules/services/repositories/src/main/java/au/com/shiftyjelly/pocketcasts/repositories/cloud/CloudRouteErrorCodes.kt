package au.com.shiftyjelly.pocketcasts.repositories.cloud

/**
 * The cloud error-code vocabulary in one place (cloud-assistant.md): the codes
 * this client adopts from the server, the codes it mints itself from the
 * response status, and the codes/shapes it only recognises for log
 * normalization — the allowlist [normalizeForLog] uses to keep all of them out
 * of device logs verbatim.
 */
object CloudRouteErrorCodes {
    const val RETRIEVAL_UNAVAILABLE = "retrieval_unavailable"
    const val INVALID_RESPONSE = "invalid_response"
    const val CONNECTION_LOST = "connection_lost"
    const val UNAUTHORIZED = "unauthorized"
    const val INTERNAL_ERROR = "internal_error"
    const val BUDGET_EXCEEDED = "budget_exceeded"
    const val LIMIT_EXCEEDED = "limit_exceeded"
    const val DUPLICATE_REQUEST = "duplicate_request"
    const val PROVIDER_ERROR = "provider_error"
    const val INVALID_REQUEST = "invalid_request"

    /** A turn that produced nothing: adopted from the service, which ends a blank turn this way. */
    const val NO_ANSWER = "no_answer"

    /**
     * Every code this client recognises — those adopted from the server, those it mints itself,
     * and those recognised for log normalization only. Internal rather than private so the
     * contract check can re-derive it from code instead of restating it: a code added here
     * without updating the checked-in contract declaration is exactly the drift that check
     * exists to catch.
     */
    internal val KNOWN = setOf(
        RETRIEVAL_UNAVAILABLE,
        INVALID_RESPONSE,
        CONNECTION_LOST,
        UNAUTHORIZED,
        INTERNAL_ERROR,
        BUDGET_EXCEEDED,
        LIMIT_EXCEEDED,
        DUPLICATE_REQUEST,
        PROVIDER_ERROR,
        INVALID_REQUEST,
        NO_ANSWER,
    )

    /** `http_<3-digit status>`, recognised for log normalization (not minted by this client). */
    // Internal rather than private so the contract check can assert the declaration against the
    // shapes this object recognises, instead of against the declaration's own copy of it.
    internal val HTTP_STATUS_CODE = Regex("http_\\d{3}")

    /**
     * Log-safe form of a server-supplied code: a known code is logged as
     * itself, anything else collapses to a constant. The raw value comes from
     * a response body and must never be written verbatim to device logs.
     */
    fun normalizeForLog(code: String?): String = when {
        code == null -> "none"

        // Known codes (server-supplied and client-minted), plus the bounded shapes recognised
        // for log normalization but never minted by this client: `invalid_request`, and
        // `http_<status>`, which carries the status of an unparseable error body — exactly the
        // diagnostic worth having when a gateway answers with HTML.
        code in KNOWN || HTTP_STATUS_CODE.matches(code) -> code

        else -> "unrecognized"
    }
}
