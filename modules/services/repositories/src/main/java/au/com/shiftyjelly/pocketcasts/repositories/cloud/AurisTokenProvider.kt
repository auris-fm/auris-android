package au.com.shiftyjelly.pocketcasts.repositories.cloud

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Supplies an Auris access token acquired from the account credential the app
 * already holds (task #33 client half).
 *
 * Postures, all pinned by tests:
 * - **Single-flight for [currentToken]:** concurrent callers share one refresh,
 *   because a double refresh would rotate twice and the replayed token revokes
 *   the whole chain (`refresh_reused`) — so exactly one call must leave the
 *   client. [refreshToken] is deliberately not single-flight: it is a caller
 *   saying the token it holds is bad, so each caller gets its own exchange.
 * - **Reactive, not proactive:** a token is refreshed when it is needed, once —
 *   [currentToken] never retries by itself. A token the route refused is
 *   re-sent once inside the same turn, but only because the caller asked for a
 *   replacement through [refreshToken] and repeats the turn's `request_id`.
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
    private val clock: () -> Long = System::currentTimeMillis,
) : AurisScopedTokenProviding {

    private val mutex = Mutex()

    /**
     * The cache monitor. The coroutine [mutex] single-flights the NETWORK exchange; this monitor
     * owns every PUBLICATION and every synchronous read of [cached], so a non-suspending reader
     * observes the same coherent sequence of entries the writers published (reviewer 57246e46:
     * two different locks do not synchronize).
     * Entries are immutable, so a snapshot taken under this monitor is stable outside it.
     */
    private val cacheLock = Any()
    private var cached: CachedTokens? = null

    private fun snapshot(): CachedTokens? = synchronized(cacheLock) { cached }

    private fun publish(entry: CachedTokens?) {
        synchronized(cacheLock) { cached = entry }
    }

    /**
     * Acquires a replacement even when the cached token is still fresh, for the case where the route
     * has already told us that token is no good.
     *
     * Forced unlike [currentToken], so two concurrent callers each acquire rather than sharing one
     * exchange — asking for a *new* token means asking again. What it does keep is the previous
     * entry: an inconclusive answer (an auth service 5xx) leaves the still-valid token in place
     * instead of dropping it, which a cache-clear would have done before the fallback could use it.
     */
    override suspend fun refreshToken() {
        token(force = true)
    }

    override suspend fun currentToken(): String? = token(force = false)

    /**
     * The FAIL-CLOSED synchronous read for non-suspending credential sites (the upload
     * transport's per-attempt authorization): serves ONLY a fresh cached token for the
     * CURRENT identity — never fetches, never serves a stale or cross-account token.
     * Null means "acquire first" and callers must refuse, not fall back.
     */
    fun peekCurrentToken(): String? = withCurrentCachedToken { token -> token?.takeIf { it.isNotBlank() } }

    /**
     * Runs [block] on the SAME monitor that owns cache publication, with the access token the
     * cache currently holds for the CURRENT identity (null when unconfigured, no credential, a
     * switched or logged-out account, an unmet origin, or an expired entry). Never fetches.
     */
    override fun <T> withCurrentCachedToken(block: (String?) -> T): T = synchronized(cacheLock) {
        val identity = currentIdentity()
        block(if (identity == null) null else snapshot()?.takeIf { it.matches(identity) && it.isFresh() }?.tokens?.accessToken)
    }

    /**
     * TRUE only when [bearer] is still the token the cache holds for the CURRENT identity --
     * the staleness input for a 401 fence: a bearer from a superseded mint, a switched account,
     * a different origin, or a cleared cache is NOT current, and a rejection answering it must
     * commit nothing. Synchronous, so a non-suspending fence can hold the publication monitor
     * across its whole check-and-commit.
     */
    fun isCurrentCachedBearer(bearer: String): Boolean = synchronized(cacheLock) {
        val identity = currentIdentity() ?: return false
        snapshot()?.takeIf { it.matches(identity) }?.tokens?.accessToken == bearer
    }

    private suspend fun token(force: Boolean): String? {
        // Read the credential first, before any return: a cached token that came
        // from a different credential belongs to a previous account (logout, or
        // a switch) and must not be served.
        val identity = currentIdentity()
        if (identity == null) {
            publish(null)
            return null
        }
        snapshot()?.let { if (!force && it.matches(identity) && it.isFresh()) return it.tokens.accessToken }
        return mutex.withLock {
            // Re-check under the lock: a concurrent caller may have refreshed,
            // or the account may have changed while we waited.
            val lockedIdentity = currentIdentity()
            if (lockedIdentity == null) {
                publish(null)
                return@withLock null
            }
            snapshot()?.let { if (!force && it.matches(lockedIdentity) && it.isFresh()) return@withLock it.tokens.accessToken }
            val client = clientProvider() ?: run {
                publish(null)
                return@withLock null
            }
            // A token past the refresh skew but still inside its real lifetime
            // can keep serving through an inconclusive upstream blip — but only
            // for this same credential.
            val stillValid = snapshot()?.takeIf { it.matches(lockedIdentity) && it.isWithinLifetime() }
            val attempt = acquire(client, lockedIdentity)
            // The identity can change *while* the request is in flight: a logout
            // or a switch during the exchange would otherwise mint a token for
            // the new account and hand it to the old account's turn (and stamp
            // it with the identity we started with). Re-read before returning or
            // caching anything, and start over on the next call if it moved.
            val settledIdentity = currentIdentity()
            when {
                attempt is Acquisition.Tokens && settledIdentity == lockedIdentity -> {
                    publish(CachedTokens(attempt.tokens, fetchedAtMs = clock(), identity = lockedIdentity))
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
                    publish(stillValid)
                    stillValid.tokens.accessToken
                }

                else -> {
                    // Includes the race: the token in hand belongs to an account
                    // that is no longer signed in, so it is not usable and not
                    // worth caching.
                    publish(null)
                    null
                }
            }
        }
    }

    /**
     * What a usable token is bound to: the environment it was issued for, and
     * the account it was issued to. Either changing invalidates the cache — a
     * token minted by one environment must not be replayed to another, and a
     * token minted for one account must not be served to the next.
     *
     * Null when there is no configured environment or no credential, so both
     * failure modes collapse into "fail closed".
     */
    private fun currentIdentity(): String? {
        val origin = clientProvider()?.origin?.takeIf { it.isNotBlank() } ?: return null
        val credential = credentialProvider.credential()?.takeIf { it.isNotBlank() } ?: return null
        return origin + IDENTITY_SEPARATOR + credentialDigest(credential)
    }

    /**
     * Get a token for [identity]: rotate the refresh token we hold for it, and
     * on a definitive 401 fall back to exchanging the account credential
     * (a 401 means the refresh chain is dead, not that the account is).
     */
    private suspend fun acquire(client: AurisAuthClient, identity: String): Acquisition {
        val refreshToken = snapshot()?.takeIf { it.matches(identity) }?.tokens?.refreshToken
        if (refreshToken != null) {
            when (val refreshed = acquire(client.refresh(refreshToken))) {
                is Acquisition.Tokens -> return refreshed

                Acquisition.Definitive -> Unit

                // re-acquire below
                Acquisition.Inconclusive -> return refreshed
            }
        }
        val credential = credentialProvider.credential() ?: return Acquisition.Definitive
        return acquire(client.exchange(credential))
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

        /** Refresh slightly before expiry so an in-flight request never races it. */
        const val EXPIRY_SKEW_MS = 30_000L

        /** Cannot occur in an origin or a hex digest, so the pair is unambiguous. */
        const val IDENTITY_SEPARATOR = "\u0000"
    }
}

/**
 * The Auris-scoped view of [CloudTokenProviding]: adds the SYNCHRONOUS, fail-closed cache fence
 * non-suspending consumers need (upload transport authorization, the 401 staleness commit).
 * A cache read that cannot suspend must never fetch and must never serve a token that is not
 * the current identity's -- these operations are what make that checkable.
 */
interface AurisScopedTokenProviding : CloudTokenProviding {
    /** Runs [block] under the cache-publication monitor with the current-identity access token. */
    fun <T> withCurrentCachedToken(block: (String?) -> T): T
}

/** The account session credential the app already holds at login. */
interface AurisAccountCredentialProviding {
    /**
     * Synchronous by contract: the backing credential is local device state
     * (the account session's refresh token), not a fetch. A synchronous read is
     * what lets the token cache answer identity questions without a coroutine,
     * which is what makes a fail-closed non-suspending cache read possible.
     */
    fun credential(): String?
}

/**
 * Compare credentials by digest so the cache does not retain a second copy of
 * the secret in order to notice that the account changed.
 */
private fun credentialDigest(credential: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(credential.toByteArray())
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
