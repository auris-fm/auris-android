package au.com.shiftyjelly.pocketcasts.repositories.sync

import au.com.shiftyjelly.pocketcasts.preferences.AccessToken

/** A synchronous, non-secret snapshot: the current token PLUS the credential generation at which it was written. */
data class AccessTokenSnapshot(val token: AccessToken, val generation: Long)

/**
 * Synchronous, non-secret credential AND session lifecycle OWNERSHIP for the sync account
 * (reviewers a75df7ef / 2008ea39): the real owner exposes a credential generation that
 * increments on EVERY credential write, a DISTINCT session generation that increments ONLY on
 * login/logout/account-switch, a synchronous snapshot read, and an ATOMIC compare-and-
 * invalidate that other boundaries run their scoped checks INSIDE.
 */
interface CredentialOwnershipOwner {
    /** Non-secret generation: changes on every credential write (login/refresh/set/invalidate/signOut). */
    fun credentialGeneration(): Long

    /** DISTINCT session incarnation: changes ONLY on login/logout/account-switch — never on refresh. */
    fun sessionGeneration(): Long

    /** Synchronous read of the CURRENT token + its generation. No network, no refresh. */
    fun accessTokenSnapshot(): AccessTokenSnapshot?

    /**
     * ATOMIC compare-and-invalidate under the credential ownership lock: the token is
     * invalidated ONLY if BOTH the generation and the token VALUE still match [observed]
     * (async platform/authenticator rotation that bypasses the owner therefore refuses too);
     * [scopedCheck] runs INSIDE the lock so the caller's state check and this invalidation
     * commit together.
     */
    fun invalidateIfUnchanged(observed: AccessTokenSnapshot, scopedCheck: () -> Boolean): Boolean
}
