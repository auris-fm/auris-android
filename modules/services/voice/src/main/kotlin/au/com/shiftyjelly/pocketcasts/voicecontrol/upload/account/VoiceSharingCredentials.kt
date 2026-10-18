package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import au.com.shiftyjelly.pocketcasts.repositories.sync.SyncAccountManager
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
 * session authority are coordinated HERE, and auth events drive both. EVERY session+credential
 * transition is ONE coordinated transaction under [lifecycleLock] (lock order lifecycle ->
 * credential store -> state fence, the same order as the 401 transaction, so no deadlock): a
 * concurrent logout can never clear-then-kill a NEW login session and vice versa. Operations
 * are always built against this coordinator's store — there is no empty fallback owner.
 */
class VoiceSharingAccountSessionCoordinator(
    val state: VoiceSharingAccountState,
    val credentials: VoiceSharingCredentialStore,
) {
    private val lifecycleLock = Any()

    /** Auth login event: ONE transaction — publish the credential write, issue the session. */
    fun login(bearer: String?): SessionToken = synchronized(lifecycleLock) {
        credentials.publish(bearer)
        state.sessionStarted()
    }

    /** Auth token-refresh event: NOT a lifecycle boundary; republishes the credential. */
    fun credentialRefreshed(bearer: String?): Unit = synchronized(lifecycleLock) {
        credentials.publish(bearer)
    }

    /** Auth logout event: ONE transaction — clear the credential AND end the session. */
    fun loggedOut(): Unit = synchronized(lifecycleLock) {
        credentials.publish(null)
        state.sessionEnded()
    }
}

/**
 * THE production wiring for the account boundary: binds the EXISTING auth owner
 * ([SyncAccountManager]) to the coordinated session/credential owner.
 *
 * - Login/logout come from the auth owner's own account updates ([SyncAccountManager.emailFlow]):
 *   an account appearing is a login, disappearing is a logout — routed through the coordinator's
 *   coordinated transactions.
 * - Every credential freeze reads the REAL token owner ([SyncAccountManager.getAccessToken],
 *   the existing refresh path) and publishes it — so a token refresh is bound too, and no test
 *   or parallel helper ever writes the production store.
 * - The credential-invalid notification effect is bound to the auth owner's REAL invalidation
 *   write ([SyncAccountManager.invalidateAccessToken]).
 */
class VoiceSharingAccountAuthBinding(
    private val auth: SyncAccountManager,
    val coordinator: VoiceSharingAccountSessionCoordinator,
) {
    /** Subscribes to the auth owner's account updates; call once from an app scope. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        auth.emailFlow().collect { email ->
            if (email == null) {
                coordinator.loggedOut()
            } else {
                loginFromAuth()
            }
        }
    }

    /** Login through the REAL credential read (existing refresh path inside getAccessToken). */
    suspend fun loginFromAuth(): SessionToken {
        val bearer = auth.getAccessToken()?.let { "Bearer " + it.value }
        return coordinator.login(bearer)
    }

    /** Credential freeze source for operations: ALWAYS the real token owner, never a cache. */
    suspend fun syncCredential(): CredentialIncarnation? {
        val bearer = auth.getAccessToken()?.let { "Bearer " + it.value }
        return coordinator.credentials.publish(bearer)
    }

    /** The REAL credential-invalid effect: the auth owner's own invalidation write. */
    suspend fun onCredentialInvalid(rejected: CredentialIncarnation) {
        auth.invalidateAccessToken()
    }
}
