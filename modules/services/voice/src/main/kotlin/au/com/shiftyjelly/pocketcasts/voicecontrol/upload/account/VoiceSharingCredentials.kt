package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot
import au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipBinding
import au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipOwner
import java.util.concurrent.atomic.AtomicReference

/**
 * Control-scope credential MIRROR: a reference-identity record of the credential the boundary
 * believes is published. It is NOT authority — the real auth owner
 * ([CredentialOwnershipOwner]) is; this store is cleared by the binding's lifecycle callbacks
 * and by committed 401 transactions, and exists so focused controls can assert what the
 * boundary observed. ALL mutations are serialized under ONE transaction lock.
 */
class VoiceSharingCredentialStore {

    private val transactionLock = Any()
    private val current = AtomicReference<CredentialIncarnation?>(null)

    /** Records the mirror image of `bearer` (null clears). */
    fun publish(bearer: String?): CredentialIncarnation? = synchronized(transactionLock) {
        val next = bearer?.let { CredentialIncarnation(it) }
        current.set(next)
        next
    }

    fun current(): CredentialIncarnation? = current.get()

    /** Control-scope transactional clear (the production 401 transaction lives in the real owner). */
    fun invalidateIfCurrent(rejected: CredentialIncarnation, scopedCheck: () -> Boolean): Boolean = synchronized(transactionLock) {
        if (current.get() !== rejected) return false
        if (!scopedCheck()) return false
        current.set(null)
        true
    }
}

/** Mirror record of one published bearer value (opaque; never logged or serialized). */
class CredentialIncarnation internal constructor(
    internal val bearer: String,
)

/**
 * Session+credential coordination for the boundary: lifecycle transitions keep the mirror and
 * the state fence consistent under [lifecycleLock] (voice-internal lock; the OWNER→voice order
 * is established by the caller — the binding callbacks run inside owner transactions).
 */
class VoiceSharingAccountSessionCoordinator(
    val state: VoiceSharingAccountState,
    val credentials: VoiceSharingCredentialStore,
) {
    private val lifecycleLock = Any()

    /** Session armed (binding callback from the owner's login/install transaction). */
    fun login(bearer: String?, authGeneration: Long = Long.MIN_VALUE, tokenValue: String? = null, sessionGeneration: Long = Long.MIN_VALUE): SessionToken = synchronized(lifecycleLock) {
        if (bearer != null) credentials.publish(bearer) else credentials.publish(null)
        state.sessionStarted(sessionGeneration)
    }

    /** Session ended (binding callback from the owner's logout/removal transaction). */
    fun loggedOut(): Unit = synchronized(lifecycleLock) {
        credentials.publish(null)
        state.sessionEnded()
    }
}

/**
 * THE production wiring for the account boundary: implements the owner's NARROW synchronous
 * binding ([CredentialOwnershipBinding]). The owner invokes [onSessionStarted]/[onSessionEnded]
 * INSIDE its lifecycle transactions (owner→voice, the single lock order), so the raw projection
 * StateFlow is revoked in-transaction — no async email rearming path, no second authority
 * writer, no observable stale interval. [install] performs the fail-closed bootstrap + seed
 * under the SAME owner lock. Started by [VoiceSharingAccountStartup].
 */
class VoiceSharingAccountAuthBinding(
    private val ownership: CredentialOwnershipOwner,
    val coordinator: VoiceSharingAccountSessionCoordinator,
) : CredentialOwnershipBinding {

    /**
     * Installs this binding with the REAL owner and seeds the snapshot under the same owner
     * lock. Returns the adopted snapshot, or null when no live account/token exists.
     */
    fun install(): AuthoritySnapshot? = ownership.installOwnershipBinding(this)

    /** Explicit null seed: revokes the previous binding (reviewer b7ad5a9a). */
    fun install(binding: CredentialOwnershipBinding?): AuthoritySnapshot? = if (binding == null) ownership.installOwnershipBinding(null) else binding.let { ownership.installOwnershipBinding(it) }

    /** Owner login/install transaction tail (INSIDE the owner lock): arm session + mirror. */
    override fun onSessionStarted(session: Long) {
        val snapshot = ownership.authoritySnapshot() // reentrant owner read
        coordinator.login(
            snapshot.token?.let { "Bearer " + it.value },
            snapshot.credential,
            snapshot.token?.value,
            session,
        )
    }

    /** Owner logout/removal transaction tail (INSIDE the owner lock): clear mirror + end session. */
    override fun onSessionEnded(session: Long) {
        coordinator.loggedOut()
    }
}
