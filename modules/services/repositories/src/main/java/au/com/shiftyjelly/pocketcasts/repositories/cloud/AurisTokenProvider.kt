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
 * - **Bound to the current account:** the credential is read before *every*
 *   return, and a cached token is only served while it was acquired from that
 *   same credential. After a logout or an account switch the previous user's
 *   token is dropped rather than served until it expires.
 * - **Fail closed:** no credential, a 401, or an inconclusive failure yields
 *   null and drops any cached token — never a stale bearer, never an invented
 *   identity. The one exception is a token that is *still genuinely valid*
 *   after an inconclusive outage; a 401 is definitive and never falls back to
 *   it, because that 401 (e.g. `refresh_reused`) means the chain is revoked.
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
        // Read the credential first, before any return: a cached token that came
        // from a different credential belongs to a previous account (logout, or
        // a switch) and must not be served.
        val identity = currentIdentity()
        if (identity == null) {
            cached = null
            return null
        }
        cached?.let { if (it.matches(identity) && it.isFresh()) return it.tokens.accessToken }
        return mutex.withLock {
            // Re-check under the lock: a concurrent caller may have refreshed,
            // or the account may have changed while we waited.
            val lockedIdentity = currentIdentity()
            if (lockedIdentity == null) {
                cached = null
                return@withLock null
            }
            cached?.let { if (it.matches(lockedIdentity) && it.isFresh()) return@withLock it.tokens.accessToken }
            val client = clientProvider() ?: run {
                cached = null
                return@withLock null
            }
            // A token past the refresh skew but still inside its real lifetime
            // can keep serving through an inconclusive upstream blip — but only
            // for this same credential.
            val stillValid = cached?.takeIf { it.matches(lockedIdentity) && it.isWithinLifetime() }
            val attempt = acquire(client, lockedIdentity)
            // The identity can change *while* the request is in flight: a logout
            // or a switch during the exchange would otherwise mint a token for
            // the new account and hand it to the old account's turn (and stamp
            // it with the identity we started with). Re-read before returning or
            // caching anything, and start over on the next call if it moved.
            val settledIdentity = currentIdentity()
            when {
                attempt is Acquisition.Tokens && settledIdentity == lockedIdentity -> {
                    cached = CachedTokens(attempt.tokens, fetchedAtMs = clock(), identity = lockedIdentity)
                    attempt.tokens.accessToken
                }

                // A 401 on either path is upstream saying this identity is no
                // longer good — and a replayed refresh token revokes the chain.
                // Serving the still-valid bearer here is exactly the wrong move.
                stillValid != null && !attempt.definitive && settledIdentity == lockedIdentity -> {
                    // Inconclusive failure (e.g. 503 on the verification path):
                    // never dial unauthenticated, but do not sign the user out
                    // or discard a token that is still valid. A later retry
                    // re-attempts the exchange.
                    cached = stillValid
                    stillValid.tokens.accessToken
                }

                else -> {
                    // Includes the race: the token in hand belongs to an account
                    // that is no longer signed in, so it is not usable and not
                    // worth caching.
                    cached = null
                    null
                }
            }
        }
    }

    /** Digest of the credential we would present now; null when there is none. */
    private suspend fun currentIdentity(): String? = credentialProvider.credential()?.takeIf { it.isNotBlank() }?.let(::credentialDigest)

    /**
     * Get a token for [identity]: rotate the refresh token we hold for it, and
     * on a definitive 401 fall back to exchanging the account credential
     * (a 401 means the refresh chain is dead, not that the account is).
     */
    private suspend fun acquire(client: AurisAuthClient, identity: String): Acquisition {
        val refreshToken = cached?.takeIf { it.matches(identity) }?.tokens?.refreshToken
        if (refreshToken != null) {
            when (val refreshed = acquire(client.refresh(refreshToken))) {
                is Acquisition.Tokens -> return refreshed

                Acquisition.Definitive -> Unit

                // re-acquire below
                Acquisition.Inconclusive -> return refreshed
            }
        }
        val credential = credentialProvider.credential() ?: return Acquisition.Definitive
        return acquire(client.exchange(credential, device))
    }

    private fun acquire(result: AurisAuthResult): Acquisition = when (result) {
        is AurisAuthResult.Success -> Acquisition.Tokens(result.tokens)
        is AurisAuthResult.Unauthorized -> Acquisition.Definitive
        AurisAuthResult.Unavailable -> Acquisition.Inconclusive
    }

    private sealed interface Acquisition {
        /** True when upstream rejected this identity — no cached bearer, ever. */
        val definitive: Boolean

        data class Tokens(val tokens: AurisTokens) : Acquisition {
            override val definitive = false
        }

        /** Upstream rejected this identity: never serve a cached bearer. */
        data object Definitive : Acquisition {
            override val definitive = true
        }

        /** Could not reach upstream: a still-valid bearer may keep serving. */
        data object Inconclusive : Acquisition {
            override val definitive = false
        }
    }

    private inner class CachedTokens(
        val tokens: AurisTokens,
        val fetchedAtMs: Long,
        private val identity: String,
    ) {
        fun matches(other: String): Boolean = identity == other

        private fun expiresAtMs(): Long = fetchedAtMs + tokens.expiresIn * 1000

        /** Usable without a refresh (with pre-expiry skew). */
        fun isFresh(): Boolean = clock() < expiresAtMs() - EXPIRY_SKEW_MS

        /** Still genuinely unexpired, even if past the skew window. */
        fun isWithinLifetime(): Boolean = clock() < expiresAtMs()
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

/**
 * Compare credentials by digest so the cache does not retain a second copy of
 * the secret in order to notice that the account changed.
 */
private fun credentialDigest(credential: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(credential.toByteArray())
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
