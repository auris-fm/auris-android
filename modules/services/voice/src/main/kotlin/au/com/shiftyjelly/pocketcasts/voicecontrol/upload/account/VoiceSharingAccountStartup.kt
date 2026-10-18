package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import android.accounts.AccountManager
import android.content.Context
import au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot
import au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipOwner
import au.com.shiftyjelly.pocketcasts.servers.di.SyncServiceRetrofit
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The actual production STARTUP CONSUMER for the voice-sharing account boundary: installs the
 * narrow binding with the REAL auth owner (fail-closed bootstrap + seed UNDER THE OWNER LOCK)
 * and builds the operations whose issuance/pre-send/apply/401 transitions all run as owner→voice
 * transactions. External account removal is observed via AccountManager's accounts listener and
 * reconciled at the owner (async observation limit: detection happens when observed, never
 * claimed instant). Same app-start convention as VoiceSharingPolicyStartup.
 */
@Singleton
class VoiceSharingAccountStartup @Inject constructor(
    private val ownership: CredentialOwnershipOwner,
    @ApplicationContext private val context: Context,
    @SyncServiceRetrofit private val retrofit: Lazy<retrofit2.Retrofit>,
) {
    val state = VoiceSharingAccountState()
    val credentials = VoiceSharingCredentialStore()
    val coordinator = VoiceSharingAccountSessionCoordinator(state, credentials)
    val binding = VoiceSharingAccountAuthBinding(ownership, coordinator)

    /** Post-commit hook target: the collection authority registers itself here when composed. */
    @Volatile
    var enrollmentEvidenceListener: ((VoiceSharingAccountProjection, Long) -> Unit)? = null

    /** Session-end observer: evidence must die with the session even before a new one starts. */
    @Volatile
    var sessionEndedListener: (() -> Unit)? = null

    init {
        // State fence is the single commit point for every projection change (GET/enrollment/
        // withdrawal/401/session-end): post-commit mirror notification ONLY — no authority is
        // derived here; CollectionAuthority re-reads state + owner snapshot at each decision.
        state.onProjectionApplied = { projection, ownerSession ->
            enrollmentEvidenceListener?.invoke(projection, ownerSession)
        }
    }

    val operations: VoiceSharingAccountOperations by lazy {
        VoiceSharingAccountOperations(
            api = VoiceSharingAccountApi.create(retrofit.get()),
            state = state,
            ownership = ownership, // the REAL owner: every transition is an owner transaction
            // Notification hook only: the REAL invalidation already committed inside the
            // owner transaction; telemetry joins here. Mirror bookkeeping, NO authority effects.
            onCredentialInvalid = { credentials.publish(null) },
        )
    }

    /** Called once from the application's onCreate; idempotent. */
    fun initializeOnAppStart() {
        binding.install()
        AccountManager.get(context).addOnAccountsUpdatedListener({
            val before = ownership.authoritySnapshot().session
            val after = ownership.reconcileExternalState()
            // ACCOUNT CHANGE observed: notify loss subscribers WITHOUT any engine event/read.
            if (after.accountName == null || after.session != before) sessionEndedListener?.invoke()
        }, null, false)
    }

    /** THE shared serialization primitive owned by the credential owner (same object identity). */
    fun sharedCollectionLock(): Any = ownership.collectionSharedLock

    /** The REAL owner for one-transaction handoffs (order owner→observer→sink is composed there). */
    fun ownerForHandoff(): CredentialOwnershipOwner = ownership

    /** Reconciles external account state and fires session-end loss to subscribers. */
    fun reconcileAndNotifySessionEnd() {
        val before = ownership.authoritySnapshot().session
        val after = ownership.reconcileExternalState()
        if (after.accountName == null || after.session != before) sessionEndedListener?.invoke()
    }

    /** Last observable authority — exposed for diagnostics; never reconstructs from cache. */
    fun authoritySnapshot(): AuthoritySnapshot = ownership.authoritySnapshot()

    /** Owner-transaction scope: [block] runs under the REAL owner lock (owner→voice order). */
    fun <T> validateUnderOwnerTransaction(block: (AuthoritySnapshot) -> T): T = ownership.transaction { block(ownership.authoritySnapshot()) }
}
