package au.com.shiftyjelly.pocketcasts.repositories.cloud

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Token contracts for `POST /api/v1/auth/token` and `/api/v1/auth/refresh`
 * (final contract: task #33). The exchanged credential is the account session
 * the app already holds at login — the client never invents a subject.
 */
@JsonClass(generateAdapter = true)
internal data class AurisTokenRequest(
    val credential: String,
    val device: AurisDeviceInfo? = null,
)

@JsonClass(generateAdapter = true)
data class AurisDeviceInfo(
    val platform: String,
    @Json(name = "app_version") val appVersion: String? = null,
)

@JsonClass(generateAdapter = true)
internal data class AurisRefreshRequest(
    @Json(name = "refresh_token") val refreshToken: String,
)

/** Token pair as returned by both auth endpoints (rotation on refresh). */
@JsonClass(generateAdapter = true)
data class AurisTokens(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "expires_in") val expiresIn: Long,
    @Json(name = "token_type") val tokenType: String = "Bearer",
    @Json(name = "refresh_token") val refreshToken: String? = null,
    @Json(name = "refresh_expires_in") val refreshExpiresIn: Long? = null,
    val issuer: String? = null,
    val subject: String? = null,
)

@JsonClass(generateAdapter = true)
internal data class AurisAuthErrorPayload(
    val code: String? = null,
    val message: String? = null,
)

/** Outcome of an auth call; failures are classified, never thrown. */
sealed interface AurisAuthResult {
    data class Success(val tokens: AurisTokens) : AurisAuthResult

    /**
     * 401: the credential is invalid/expired, or an already-rotated refresh
     * token was replayed (`refresh_reused`) — which revokes the chain, so the
     * caller must re-acquire from the account credential rather than retry.
     */
    data class Unauthorized(val code: String?) : AurisAuthResult

    /** Transport failure or unexpected status: inconclusive, fail closed. */
    data object Unavailable : AurisAuthResult
}
