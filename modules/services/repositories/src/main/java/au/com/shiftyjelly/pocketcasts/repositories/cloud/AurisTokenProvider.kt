package au.com.shiftyjelly.pocketcasts.repositories.cloud

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Supplies an Auris access token acquired from the account credential the app
 * already holds (task #33 client half).
 *
 * Postures, all pinned by tests:
 * - **Single-flight:** concurrent callers share one refresh. A double refresh
 *   would rotate twice, and the replayed token revokes the whole chain
 *   (`refresh_reused`) — so exactly one call must leave the client.
 * - **Reactive, not proactive:** a token is refreshed when it is needed, once.
 *   No same-turn retry: a request that observed a stale token fails and the
 *   *next* call uses the fresh one.
 * - **Fail closed:** no credential, a 401, or an inconclusive failure yields
 *   null and drops any cached token — never a stale bearer, never an invented
 *   identity.
 */
class AurisTokenProvider(
    /** Null when auth is not configured for this build/environment. */
    private val clientProvider: () -> AurisAuthClient?,
    private val credentialProvider: AurisAccountCredentialProviding,
    private val device: AurisDeviceInfo? = AurisDeviceInfo(platform = PLATFORM_ANDROID),
    private val clock: () -> Long = System::currentTimeMillis,
) : CloudTokenProviding {

    private val mutex = Mutex()
    private var cached: CachedTokens? = null

    override suspend fun currentToken(): String? {
        cached?.let { if (it.isFresh()) return it.tokens.accessToken }
        return mutex.withLock {
            // Re-check under the lock: a concurrent caller may have refreshed.
            cached?.let { if (it.isFresh()) return@withLock it.tokens.accessToken }
            val client = clientProvider() ?: run {
                cached = null
                return@withLock null
            }
            val refreshed = refreshOnce(client) ?: exchangeWithCredential(client)
            refreshed?.let { tokens ->
                cached = CachedTokens(tokens, fetchedAtMs = clock())
                tokens.accessToken
            } ?: run {
                cached = null
                null
            }
        }
    }

    /** Rotate the refresh token; a 401 (invalid/replayed) means re-acquire. */
    private suspend fun refreshOnce(client: AurisAuthClient): AurisTokens? {
        val refreshToken = cached?.tokens?.refreshToken ?: return null
        return when (val result = client.refresh(refreshToken)) {
            is AurisAuthResult.Success -> result.tokens
            is AurisAuthResult.Unauthorized -> null
            AurisAuthResult.Unavailable -> null
        }
    }

    /** Exchange the account credential; null when there is nothing to present. */
    private suspend fun exchangeWithCredential(client: AurisAuthClient): AurisTokens? {
        val credential = credentialProvider.credential() ?: return null
        return when (val result = client.exchange(credential, device)) {
            is AurisAuthResult.Success -> result.tokens
            is AurisAuthResult.Unauthorized -> null
            AurisAuthResult.Unavailable -> null
        }
    }

    private inner class CachedTokens(val tokens: AurisTokens, val fetchedAtMs: Long) {
        fun isFresh(): Boolean {
            val lifetimeMs = tokens.expiresIn * 1000
            return clock() < fetchedAtMs + lifetimeMs - EXPIRY_SKEW_MS
        }
    }

    private companion object {
        const val PLATFORM_ANDROID = "android"

        /** Refresh slightly before expiry so an in-flight request never races it. */
        const val EXPIRY_SKEW_MS = 30_000L
    }
}

/** The account session credential the app already holds at login. */
interface AurisAccountCredentialProviding {
    suspend fun credential(): String?
}
