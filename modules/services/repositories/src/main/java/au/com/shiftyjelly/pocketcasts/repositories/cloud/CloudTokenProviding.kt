package au.com.shiftyjelly.pocketcasts.repositories.cloud

import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.CloudIdentity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies the bearer token for every cloud call.
 *
 * **Design seam only.** Today's behaviour is preserved exactly by
 * [CloudStaticIdentityTokenProvider] (trust-on-first-use `user_<uuid>`).
 * The verified-issuer shape — acquisition, short lifetime, refresh, key
 * rotation — is owned by the cloud-identity work and is deliberately not
 * guessed here: swapping this implementation is the whole integration point.
 *
 * Contract: returns null when no usable token exists (missing identity or a
 * token known to be expired/revoked). Callers must fail closed — never dial
 * without a token.
 */
interface CloudTokenProviding {
    suspend fun currentToken(): String?

    /**
     * Ensures the next [currentToken] returns a replacement, ignoring a still-fresh cache.
     *
     * Called after a `401` from the cloud route, where the cached token is known to be bad rather
     * than merely old. It returns nothing on purpose: callers read [currentToken] again, so returning
     * the value here would invite a caller to use it and bypass any provider-specific caching.
     *
     * A provider that cannot mint a replacement leaves this doing nothing, which is honest — the
     * caller then sends one identical request and gives up rather than pretending it recovered.
     */
    suspend fun refreshToken() {
        currentToken()
    }
}

/**
 * Preserves the current client identity as the bearer token, byte-for-byte
 * (`Authorization: Bearer user_<uuid>`), until the trusted issuer lands.
 */
@Singleton
class CloudStaticIdentityTokenProvider @Inject constructor(
    private val cloudIdentity: CloudIdentity,
) : CloudTokenProviding {
    override suspend fun currentToken(): String? = cloudIdentity.userId().takeIf { it.isNotBlank() }
}

/**
 * A fixed identity token, for callers handed a user id rather than the identity seam.
 *
 * Its caller is `CloudPrefetchClient`, which posts playback-start context hints and does not retry a
 * 401 at all — so the fact that this provider cannot mint a replacement costs it nothing: the
 * interface's default `refreshToken` re-reads the same value, and nobody is waiting on a different
 * one. It is here rather than nested in a client so the two callers that want it do not have to
 * reach through one of them.
 */
internal class CloudFixedTokenProvider(private val token: String) : CloudTokenProviding {
    override suspend fun currentToken(): String? = token.takeIf { it.isNotBlank() }
}
