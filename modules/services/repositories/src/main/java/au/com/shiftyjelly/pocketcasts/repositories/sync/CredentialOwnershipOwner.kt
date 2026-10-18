package au.com.shiftyjelly.pocketcasts.repositories.sync

import au.com.shiftyjelly.pocketcasts.preferences.AccessToken

/** A synchronous, non-secret snapshot: the current token PLUS the credential generation at which it was written. */
data class AccessTokenSnapshot(val token: AccessToken, val generation: Long)

/**
 * Synchronous, non-secret credential lifecycle OWNERSHIP for the sync account token
 * (reviewer a75df7ef): the real owner exposes a generation that increments on EVERY credential
 * write (login/refresh/set/invalidate/signOut), a synchronous snapshot read, and an ATOMIC
 * compare-and-invalidate that other owners (voice-sharing account boundary) run their scoped
 * checks INSIDE — so external rotations can neither be missed by a cached read nor be cleared
 * by a stale rejection.
 */
interface CredentialOwnershipOwner {
    /** Non-secret generation: changes on every credential write. No secret material. */
    fun credentialGeneration(): Long

    /** Synchronous read of the CURRENT token + its generation. No network, no refresh. */
    fun accessTokenSnapshot(): AccessTokenSnapshot?

    /**
     * ATOMIC compare-and-invalidate under the credential ownership lock: the token is
     * invalidated ONLY if the generation is still [observedGeneration]; [scopedCheck] runs
     * INSIDE the lock so a caller's state check and this invalidation commit together. A
     * refresh or any other write that landed first makes this a no-op that preserves the
     * fresh credential.
     */
    fun invalidateIfUnchanged(observedGeneration: Long, scopedCheck: () -> Boolean): Boolean
}
