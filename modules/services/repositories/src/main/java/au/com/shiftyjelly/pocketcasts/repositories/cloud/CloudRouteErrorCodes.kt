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

    /**
     * A resume named a conversation the worker no longer holds (it keeps one for a limited time after
     * its last turn). The refused request is never resubmitted; the client says so and starts afresh.
     */
    const val CONVERSATION_EXPIRED = "conversation_expired"

    /** A turn that produced nothing: adopted from the service, which ends a blank turn this way. */
    const val NO_ANSWER = "no_answer"

    /**
     * The service could not synthesise speech for an otherwise successful turn. Recognised so the
     * log names the cause: collapsing it to `unrecognized` cost a diagnosis that needed the
     * server's own logs to recover.
     */
    const val SPEECH_UNAVAILABLE = "speech_unavailable"

    /**
     * The turn's application-level audio queue exceeded its bound: the service produced audio faster
     * than it could be written. Recognised so the trace and the log name the cause rather than
     * collapsing a promised failure to `unrecognized`.
     */
    const val QUEUE_OVERFLOW = "queue_overflow"

    /**
     * Another turn in this conversation is still active. The request is never queued and never
     * resubmitted; the user retries when the current answer finishes or is interrupted.
     */
    const val TURN_CONFLICT = "turn_conflict"

    /**
     * The stored record is unreadable, so prior admissions cannot be established. This turn neither
     * executes nor consumes allowance, and a fresh conversation is required.
     */
    const val CORRUPT_RECORD = "corrupt_record"

    /**
     * The named conversation belongs to another user. Nothing is created, and the refused request is
     * never resubmitted: a new user request carries a fresh identifier.
     */
    const val CONVERSATION_FORBIDDEN = "conversation_forbidden"

    /**
     * The conversation cannot retain another admitted request. This turn neither executes nor
     * consumes allowance, and waiting does not free capacity — a new conversation is required.
     */
    const val RETENTION_EXHAUSTED = "retention_exhausted"

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
        CONVERSATION_EXPIRED,
        SPEECH_UNAVAILABLE,
        QUEUE_OVERFLOW,
        TURN_CONFLICT,
        CORRUPT_RECORD,
        CONVERSATION_FORBIDDEN,
        RETENTION_EXHAUSTED,
    )

    /**
     * Whether a server-supplied code may be persisted verbatim in the lifecycle trace. The SAME
     * vocabulary [normalizeForLog] uses, so a code recognised for one log cannot be silently
     * bounded to "unknown" in the other — which is how a speech outage came to be unnamed in the
     * artifact support reads while the Timber line named it.
     *
     * A function rather than the set itself: [KNOWN] stays internal to this module, and widening
     * its visibility to share a mutable collection would invite exactly the drift this closes.
     */
    fun isRecognised(code: String): Boolean = code in KNOWN

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
        // for log normalization but not minted today: `invalid_request` (declared
        // `mintedByClient` for future use), and
        // `http_<status>`, which carries the status of an unparseable error body — exactly the
        // diagnostic worth having when a gateway answers with HTML.
        code in KNOWN || HTTP_STATUS_CODE.matches(code) -> code

        else -> "unrecognized"
    }
}
