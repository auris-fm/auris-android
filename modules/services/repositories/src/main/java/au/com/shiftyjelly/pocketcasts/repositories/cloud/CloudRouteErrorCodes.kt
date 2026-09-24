package au.com.shiftyjelly.pocketcasts.repositories.cloud

/** Server error codes the client branches on, in one place (cloud-assistant.md). */
object CloudRouteErrorCodes {
    const val RETRIEVAL_UNAVAILABLE = "retrieval_unavailable"
    const val INVALID_RESPONSE = "invalid_response"
    const val CONNECTION_LOST = "connection_lost"
    const val UNAUTHORIZED = "unauthorized"
    const val INTERNAL_ERROR = "internal_error"
}
