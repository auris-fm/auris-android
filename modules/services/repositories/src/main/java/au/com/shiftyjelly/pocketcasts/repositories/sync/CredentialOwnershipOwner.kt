package au.com.shiftyjelly.pocketcasts.repositories.sync

import au.com.shiftyjelly.pocketcasts.preferences.AccessToken
import au.com.shiftyjelly.pocketcasts.preferences.AccountConstants
import au.com.shiftyjelly.pocketcasts.preferences.RefreshToken

/**
 * ONE immutable owner snapshot (reviewer 504da9ea / spec d91ddd80): the platform account
 * identity, the session incarnation, the credential incarnation, and the OWNER-PUBLISHED
 * token — all read under the owner lock in one call. The token is NEVER a bare platform-cache
 * read relabeled with the latest counter: it is attributed at publication (or at the explicit
 * fail-closed bootstrap adoption); a later AccountManager cache copy cannot acquire authority.
 */
data class AuthoritySnapshot(
    val accountName: String?,
    val session: Long,
    val credential: Long,
    val token: AccessToken?,
)

/**
 * A refresh admission captured BEFORE the network work: the publication commit compares
 * account, session AND credential against this lease, so concurrent same-session refreshes
 * are fenced (an older completion cannot overwrite a newer credential) and a late refresh for
 * a superseded account/session cannot write into it.
 */
class RefreshLease(
    val accountName: String,
    val session: Long,
    val credential: Long,
    val refreshToken: RefreshToken,
    val signInType: AccountConstants.SignInType,
)

/**
 * The NARROW synchronous binding (owner→voice is THE single lock order): implemented by the
 * voice boundary, invoked by the real owner at the tail of lifecycle transactions, INSIDE the
 * owner lock. It must contain no owner calls other than reentrant snapshot reads and no
 * arbitrary subscriber work — only the voice state fence.
 */
interface CredentialOwnershipBinding {
    fun onSessionStarted(session: Long)
    fun onSessionEnded(session: Long)
}

/**
 * The actual auth owner (SyncAccountManagerImpl) as the SINGLE authority for the sync
 * account. Every authority issuance, pre-send validation, response apply and 401 transition
 * runs as an owner→voice transaction; the voice boundary never takes the owner lock while
 * holding its fence outside these transactions.
 */
interface CredentialOwnershipOwner {
    /**
     * THE shared collection serialization primitive (reviewer 8273382e): one and the same lock
     * object for the COLLECTION → ACCOUNT/POLICY → OBSERVER → SINK order. The collection lane
     * composes its transaction over THIS object; every owner transition acquires it before the
     * credential write lock. Nothing may acquire it while holding an inner lock.
     */
    val collectionSharedLock: Any

    /** ONE-lock atomic read of the authoritative state. */
    fun authoritySnapshot(): AuthoritySnapshot

    /**
     * Install the narrow binding and seed its snapshot UNDER THE SAME OWNER LOCK (fail-closed
     * bootstrap): the existing platform token is adopted ONCE and attributed to the current
     * credential incarnation; the session is armed synchronously through the binding. Returns
     * null when no live account/token exists.
     */
    fun installOwnershipBinding(binding: CredentialOwnershipBinding?): AuthoritySnapshot?

    /**
     * Refresh admission against the caller's OBSERVED snapshot (captured BEFORE the request):
     * validates the original account name AND session AND credential incarnation and
     * ATOMICALLY freezes ALL network inputs (refresh token + sign-in type) under the lock.
     * A refused admission means ZERO network work. Null = refused.
     */
    fun beginRefresh(observed: AuthoritySnapshot): RefreshLease?

    /**
     * Conditional publication of BOTH refreshed tokens: commits only if the account, session
     * AND credential still match the lease. FALSE means refused — the caller MUST NOT return
     * the token to the platform (no cache copy of a rejected refresh).
     */
    fun publishRefresh(lease: RefreshLease, refreshToken: RefreshToken, accessToken: AccessToken): Boolean

    /** Owner→voice scope: [block] runs under the owner lock (reentrant snapshot reads allowed). */
    fun <T> transaction(block: () -> T): T

    /**
     * The 401 owner transaction: compares the observed snapshot atomically, runs
     * [transaction] (the scoped voice revocation) INSIDE the owner lock, and clears the
     * rejected token value + advances the credential incarnation ONLY when [transaction]
     * returned true (the voice scoped check accepted). No second-read guard, no restoration
     * flags: a stale rejection — snapshot mismatch OR scoped refusal — invalidates NOTHING.
     */
    fun invalidateIfCurrent(observed: AuthoritySnapshot, transaction: () -> Boolean): Boolean

    /**
     * External platform state reconciliation (account removal observed asynchronously): under
     * the lock, a vanished account ends the session/credential exactly once and drives
     * subscribed revocation via the binding. New operations reconcile the LAST OBSERVABLE
     * platform state — a removal racing just after reconciliation remains an OS observation
     * limit; no real-time notification is promised.
     */
    fun reconcileExternalState(): AuthoritySnapshot

    /**
     * ATOMIC conditional publication of BOTH credentials after a password change: commits
     * only if the observed account/session/credential (captured BEFORE the request) are still
     * current — a late A password change after a B login refuses without writing. One lock,
     * ONE credential incarnation move.
     */
    fun publishCredentials(observed: AuthoritySnapshot, refreshToken: RefreshToken, accessToken: AccessToken): Boolean

    fun sessionGeneration(): Long
}
