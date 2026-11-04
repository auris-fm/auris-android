package au.com.shiftyjelly.pocketcasts.repositories.cloud

import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.CloudIdentity
import javax.inject.Inject
import javax.inject.Singleton

/*
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
 *
 * See [CloudCredential] for the credential handed to a turn.
 */
/**
 * One credential as a single fact: the token actually sent, and the account it is known to belong to.
 *
 * [accountId] is null whenever the provider cannot establish the association. Callers must then refuse
 * app-operation execution; an operation bound to a guessed account could never be answered for.
 */
data class CloudCredential(
    val token: String?,
    val accountId: String?,
)

interface CloudTokenProviding {
    suspend fun currentToken(): String?

    /**
     * The stable authenticated ACCOUNT the current token belongs to, or null when the provider cannot
     * establish one.
     *
     * Distinct from [currentToken] on purpose: a token is bearer material that rotates and must never
     * be journaled or used as an identity, while an account id is exactly what an app operation is
     * bound to. A provider that cannot answer returns null, and callers must refuse v1 execution
     * rather than substitute the token or a local placeholder.
     *
     * [CloudStaticIdentityTokenProvider] returns null today: the `user_<uuid>` value it sends is
     * documented as a trust-on-first-use placeholder until the trusted issuer lands, so treating it
     * as a stable account identity would persist an identity that is expected to change.
     */
    suspend fun currentAccountId(): String? = null

    /**
     * The bearer token and the account it belongs to, resolved as ONE association.
     *
     * Two separate reads cannot establish this: an account switch between the two awaits yields a
     * token from one account and an identity from another, and the journal would then bind an
     * operation to an account that never made the request. Providers that can associate them must
     * override this; the default reports the token with NO account, which refuses v1 execution rather
     * than guessing a pairing.
     */
    suspend fun currentCredential(): CloudCredential = CloudCredential(
        token = currentToken(),
        // Deliberately null: an overridden [currentAccountId] alone cannot prove the pairing, so a
        // provider that wants v1 execution must say how the two are associated.
        accountId = null,
    )

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
