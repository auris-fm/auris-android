package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import java.util.concurrent.atomic.AtomicReference

/**
 * A credential INCARNATION: an opaque, reference-identity handle for one published bearer
 * value. The bearer string is only readable inside the account boundary; nothing else is
 * derived from it and it is never logged or serialized.
 */
class CredentialIncarnation internal constructor(internal val bearer: String?)

/**
 * Production credential owner for the voice-sharing account boundary. ALL credential
 * mutations — publish (login/refresh), clear (logout), and 401 compare-and-invalidate — are
 * serialized under ONE transaction lock, so a rotation can never interleave with an
 * invalidation transaction: compare, scoped state check, and clear run as a single
 * coordinated transaction, not two independent locks or checks.
 */
class VoiceSharingCredentialStore {

    private val transactionLock = Any()
    private val current = AtomicReference<CredentialIncarnation?>(null)

    /**
     * Publishes a new incarnation for `bearer` (null clears). Rotation is transactional:
     * the same bearer keeps the SAME incarnation (a refresh is not a rotation); a different
     * bearer is a NEW incarnation that supersedes the old atomically.
     */
    fun publish(bearer: String?): CredentialIncarnation? = synchronized(transactionLock) {
        val next = when {
            bearer == null -> null
            current.get()?.bearer == bearer -> current.get()
            else -> CredentialIncarnation(bearer)
        }
        current.set(next)
        next
    }

    fun current(): CredentialIncarnation? = current.get()

    /**
     * ATOMIC COORDINATED INVALIDATION (single transaction under the credential lock):
     * 1. the rejected incarnation is compared against the current one (rotation before the
     *    transaction -> no-op, NOTHING is invalidated);
     * 2. the caller's SCOPED STATE CHECK runs INSIDE the transaction (a superseded operation
     *    or session -> abort with the credential untouched);
     * 3. only after both accept is the credential cleared — atomically with the state
     *    invalidation, so no rotation can slip between them and no fresh authority or fresh
     *    incarnation is ever destroyed.
     */
    fun invalidateIfCurrent(rejected: CredentialIncarnation, scopedCheck: () -> Boolean): Boolean = synchronized(transactionLock) {
        if (current.get() !== rejected) return false
        if (!scopedCheck()) return false
        current.set(null)
        true
    }
}

/**
 * Production composition for the voice-sharing account boundary: the credential owner and the
 * session authority are coordinated HERE, and auth events drive both. Login publishes the
 * incarnation AND issues the session; a token refresh republishes (same bearer keeps the same
 * incarnation); logout clears the credential AND ends the session. Operations are always built
 * against this coordinator's store — there is no empty fallback owner anywhere.
 */
class VoiceSharingAccountSessionCoordinator(
    val state: VoiceSharingAccountState,
    val credentials: VoiceSharingCredentialStore,
) {
    /** Auth login event: binds the credential write to the session issuance. */
    fun login(bearer: String?): SessionToken {
        credentials.publish(bearer)
        return state.sessionStarted()
    }

    /** Auth token-refresh event: NOT a lifecycle boundary; republishes the credential. */
    fun credentialRefreshed(bearer: String?) {
        credentials.publish(bearer)
    }

    /** Auth logout event: clears the credential and ends the session together. */
    fun loggedOut() {
        credentials.publish(null)
        state.sessionEnded()
    }
}
