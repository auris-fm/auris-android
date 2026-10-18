package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipOwner
import au.com.shiftyjelly.pocketcasts.repositories.sync.SyncAccountManager
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The boundary's credential-ownership operation: an ATOMIC compare-and-invalidate transaction —
 * the rejected credential is compared, the caller's scoped check runs INSIDE, and the clear
 * commits only when both accept. Implemented by the real auth owner (via
 * [CredentialOwnershipOwner.invalidateIfUnchanged]) in production and by the test store in
 * controls.
 */
interface VoiceSharingCredentialOwnership {
    fun invalidateIfCurrent(rejected: CredentialIncarnation, scopedCheck: () -> Boolean): Boolean
}

/**
 * A credential INCARNATION: an opaque, reference-identity handle for one published bearer value
 * carrying the NON-SECRET auth-owner generation it was read at. The bearer string is only
 * readable inside the account boundary; nothing else is derived from it and it is never logged
 * or serialized.
 */
class CredentialIncarnation internal constructor(
    internal val bearer: String?,
    internal val authGeneration: Long = Long.MIN_VALUE,
)

/**
 * Control-scope credential store implementing the ownership transaction with reference-identity
 * compares (used by focused controls; production uses the REAL auth owner via
 * [VoiceSharingAccountAuthBinding]). ALL mutations are serialized under ONE transaction lock,
 * so a rotation can never interleave an invalidation transaction.
 */
class VoiceSharingCredentialStore : VoiceSharingCredentialOwnership {

    private val transactionLock = Any()
    private val current = AtomicReference<CredentialIncarnation?>(null)

    /**
     * Publishes a new incarnation for `bearer` (null clears). The SAME bearer at the SAME
     * auth generation keeps the SAME incarnation (a no-op refresh); a new generation is a NEW
     * incarnation — generation is part of the identity the 401 CAS compares.
     */
    fun publish(bearer: String?, authGeneration: Long = Long.MIN_VALUE): CredentialIncarnation? = synchronized(transactionLock) {
        val previous = current.get()
        val next = when {
            bearer == null -> null
            previous?.bearer == bearer && previous.authGeneration == authGeneration -> previous
            else -> CredentialIncarnation(bearer, authGeneration)
        }
        current.set(next)
        next
    }

    fun current(): CredentialIncarnation? = current.get()

    override fun invalidateIfCurrent(rejected: CredentialIncarnation, scopedCheck: () -> Boolean): Boolean = synchronized(transactionLock) {
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
 * credential store -> state fence, the same order as the 401 transaction, so no deadlock).
 */
class VoiceSharingAccountSessionCoordinator(
    val state: VoiceSharingAccountState,
    val credentials: VoiceSharingCredentialStore,
) {
    private val lifecycleLock = Any()

    /** Auth login event: ONE transaction — publish the credential write, issue the session. */
    fun login(bearer: String?, authGeneration: Long = Long.MIN_VALUE): SessionToken = synchronized(lifecycleLock) {
        credentials.publish(bearer, authGeneration)
        state.sessionStarted()
    }

    /** Auth token-refresh event: NOT a lifecycle boundary; republishes the credential. */
    fun credentialRefreshed(bearer: String?, authGeneration: Long = Long.MIN_VALUE): Unit = synchronized(lifecycleLock) {
        credentials.publish(bearer, authGeneration)
    }

    /** Auth logout event: ONE transaction — clear the credential AND end the session. */
    fun loggedOut(): Unit = synchronized(lifecycleLock) {
        credentials.publish(null)
        state.sessionEnded()
    }
}

/**
 * THE production wiring for the account boundary: binds the EXISTING auth owner
 * ([SyncAccountManager] + its [CredentialOwnershipOwner] implementation) to the coordinated
 * session/credential owner.
 *
 * - Login/logout come from the auth owner's SYNCHRONOUS credential generation (not an async
 *   identity flow): every freeze reads a GENERATION-STAMPED snapshot
 *   ([CredentialOwnershipOwner.accessTokenSnapshot]), so an external rotation between reads is
 *   never missed.
 * - The 401 compare-and-invalidate IS the real owner's atomic transaction
 *   ([CredentialOwnershipOwner.invalidateIfUnchanged]): a refresh that landed first (fresh real
 *   credential) makes the stale rejection a no-op, and the committed transaction invalidates the
 *   credential and the scoped session together.
 * - Started by [VoiceSharingAccountStartup] — the actual production consumer.
 */
class VoiceSharingAccountAuthBinding(
    private val auth: SyncAccountManager,
    private val ownership: CredentialOwnershipOwner,
    val coordinator: VoiceSharingAccountSessionCoordinator,
) : VoiceSharingCredentialOwnership {

    /** Subscribes to the auth owner's account updates; call once from the app scope. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        auth.emailFlow().collect { email ->
            if (email == null) {
                coordinator.loggedOut()
            } else {
                loginFromAuth()
            }
        }
    }

    /** Login through the REAL credential owner's synchronous snapshot. */
    suspend fun loginFromAuth(): SessionToken {
        val snapshot = ownership.accessTokenSnapshot()
        return coordinator.login(
            snapshot?.token?.let { "Bearer " + it.value },
            snapshot?.generation ?: Long.MIN_VALUE,
        )
    }

    /** Credential freeze source: ALWAYS a fresh generation-stamped read of the real owner. */
    suspend fun syncCredential(): CredentialIncarnation? {
        val snapshot = ownership.accessTokenSnapshot()
        return coordinator.credentials.publish(
            snapshot?.token?.let { "Bearer " + it.value },
            snapshot?.generation ?: Long.MIN_VALUE,
        )
    }

    /**
     * The 401 transaction IS the real owner's atomic compare-and-invalidate: the auth-owner
     * generation captured at freeze is compared under the ownership lock, the scoped state
     * check runs inside, and only a full match clears the REAL credential. The store mirror is
     * updated after commit (bookkeeping, not ownership).
     */
    override fun invalidateIfCurrent(rejected: CredentialIncarnation, scopedCheck: () -> Boolean): Boolean {
        val committed = ownership.invalidateIfUnchanged(rejected.authGeneration) { scopedCheck() }
        if (committed) coordinator.credentials.publish(null)
        return committed
    }
}
