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
