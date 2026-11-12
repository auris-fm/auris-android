package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import android.accounts.AccountManager
import android.content.Context
import au.com.shiftyjelly.pocketcasts.repositories.sync.SyncAccountManager
import au.com.shiftyjelly.pocketcasts.servers.di.SyncServiceRetrofit
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentController
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The production STARTUP CONSUMER for the voice-sharing account boundary: reads the signed-in
 * account name from the ordinary sync account manager, builds the operations whose issuance,
 * pre-send, apply and 401 transitions are account-scoped, and observes external account changes
 * via AccountManager's accounts listener (async observation limit: detection happens when
 * observed, never claimed instant). Same app-start convention as VoiceSharingPolicyStartup.
 */
@Singleton
class VoiceSharingAccountStartup @Inject constructor(
    private val syncAccountManager: SyncAccountManager,
    @ApplicationContext private val context: Context,
    @SyncServiceRetrofit private val retrofit: Lazy<retrofit2.Retrofit>,
    /**
     * THE AURIS PROVIDER, injected as the AURIS-SCOPED interface rather than the routing
     * `CloudTokenProviding`. The account route verifies Auris tokens only, so this path either has
     * one or is refused; `AurisScopedTokenProviding` carries NO fallback branch by construction.
     */
    private val aurisToken: au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisScopedTokenProviding,
    /** The durable saved choice; every accepted server answer mirrors into it. */
    private val savedSharingChoice: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoiceStore,
) {
    val state = VoiceSharingAccountState()

    /** Post-commit hook target: the collection authority registers itself here when composed. */
    @Volatile
    var enrollmentEvidenceListener: ((VoiceSharingAccountProjection?, String?) -> Unit)? = null

    /** Session-end observer: evidence must die with the session even before a new one starts. */
    @Volatile
    var sessionEndedListener: (() -> Unit)? = null

    /** Wake slot for the uploader: fired when the signed-in account changes or disappears. */
    @Volatile
    var authorityMovedListener: (() -> Unit)? = null

    /** The signed-in account's name, or null. The durable choice is per-account. */
    fun currentAccountName(): String? = syncAccountManager.getEmail()

    /**
     * Voice-side account-change token for the Settings/Enrollment fence. It increments whenever the
     * signed-in account name changes, so a tap issued under one account never applies to another.
     */
    private val sessionGenerationCounter = AtomicLong(0)

    fun ownerSessionGeneration(): Long = sessionGenerationCounter.get()

    /** Last observed account name, so an account change is reported exactly once. */
    @Volatile
    private var lastSeenAccount: String? = null

    init {
        // State fence is the single commit point for every projection change (GET/enrollment/
        // withdrawal/401/session-end): post-commit mirror notification ONLY — no authority is
        // derived here; CollectionAuthority re-reads state + account name at each decision.
        state.onProjectionListener = { projection ->
            enrollmentEvidenceListener?.invoke(projection, currentAccountName())
        }
    }

    val operations: VoiceSharingAccountOperations by lazy {
        VoiceSharingAccountOperations(
            api = VoiceSharingAccountApi.create(retrofit.get()),
            state = state,
            currentAccountName = ::currentAccountName,
            aurisToken = aurisToken, // the bearer SOURCE (Auris-issued only, no fallback branch)
            // A 401 here revokes only the AURIS credential (force a re-mint from the same account).
            // The upstream session is left alone -- clearing it would sign the user out of the
            // account that minted the token, which is a worse failure than the one we fixed.
            onCredentialInvalid = {},
            onAurisCredentialRefused = { aurisToken.refreshToken() },
            savedSharingChoice = savedSharingChoice,
        )
    }

    /**
     * The enrollment UI's seam (plan Task 2 + UI shell). Built here because every piece it drives —
     * the operations, the state fence, the local purge — is owned by this startup.
     *
     * [clearLocalPending] is wired to the QUEUE's own revocation transaction rather than a bare file
     * sweep, so "stop everything now" uses the same ownership-locked path as every other revocation.
     * The caller supplies it because the store belongs to the upload composition; when nothing has
     * composed the queue yet, the no-op is correct — there is no pending work to stop.
     *
     * The withdrawal TAP also writes the durable WITHDRAWN record BEFORE the server answer: the
     * saved WITHDRAWN record IS the local stop, and it must be in place while the request is in
     * flight or if it fails, so the next capture never admits under a stale ENROLLED record.
     */
    fun enrollmentController(clearLocalPending: () -> Unit): EnrollmentController = EnrollmentController(
        enrollOperation = { acceptedDisclosureVersion, applyUnderGuard ->
            operations.enroll(acceptedDisclosureVersion, applyUnderGuard)
        },
        withdrawOperation = { applyUnderGuard ->
            // The epoch is captured from the fresh projection inside the account transaction.
            operations.withdraw(applyUnderGuard)
        },
        deleteOperation = {
            operations.deleteData()
        },
        clearLocalPending = clearLocalPending,
        recordLocalWithdrawal = {
            val account = currentAccountName()
            if (account.isNullOrBlank()) false else savedSharingChoice.recordWithdrawn(account)
        },
        // Results are fenced to the account they were issued under, so an account switch
        // mid-flight cannot apply one account's outcome to another's screen.
        ownerSession = { ownerSessionGeneration() },
    )

    /** Called once from the application's onCreate; idempotent. */
    fun initializeOnAppStart() {
        // INSTALLED-PATH DIAGNOSTIC (staging): one line per projection refresh tells us whether
        // the authenticated membership GET applied or was refused. Notifications only.
        state.onRefreshObserved = { diagnostic ->
            timber.log.Timber.i(
                "voice-sharing projection refresh: kind=%s status=%s bodyBytes=%s requestIds=%s detail=%s",
                diagnostic.kind,
                diagnostic.status,
                diagnostic.bodyBytes,
                diagnostic.requestIds.joinToString(",") { it.ifBlank { "-" } },
                diagnostic.detail,
            )
        }
        // The account may ALREADY be readable when the app starts (normal cold start): announce it
        // once here so any readiness observer gets the same trigger a later transition would give.
        lastSeenAccount = currentAccountName()
        val initial = lastSeenAccount
        if (initial != null) refreshOrdinaryChoiceIfUnsaved(initial)
        AccountManager.get(context).addOnAccountsUpdatedListener({
            val after = currentAccountName()
            if (after != lastSeenAccount) {
                lastSeenAccount = after
                sessionGenerationCounter.incrementAndGet()
                sessionEndedListener?.invoke()
            }
            authorityMovedListener?.invoke()
            // Same acquisition on a REAL account transition to the new identity (guard is per account).
            after?.let { refreshOrdinaryChoiceIfUnsaved(it) }
        }, null, false)
    }

    /** Reconciles external account state and fires session-end loss to subscribers. */
    fun reconcileAndNotifySessionEnd() {
        val after = currentAccountName()
        if (after != lastSeenAccount) {
            lastSeenAccount = after
            sessionGenerationCounter.incrementAndGet()
            sessionEndedListener?.invoke()
        }
        after?.let { refreshOrdinaryChoiceIfUnsaved(it) }
    }

    /**
     * ONE ordinary account refresh per process per account, ONLY while that account has no saved
     * sharing choice/provenance. The refresh is the EXISTING ordinary GET (`refreshProjection`):
     * the server's real answer is applied through the existing account-transaction funnel, which is
     * what mirrors the saved choice. Nothing is fabricated: a refused/failed fetch leaves the saved
     * state absent (fail-closed collection refuses), and the next process launch may try again —
     * once, not on a loop. No poller, no session-readiness or cache-freshness gate; off choices,
     * local stops and account fences are untouched (this only ACQUIRES, never admits).
     */
    private val refreshAttemptedAccounts = java.util.Collections.synchronizedSet(HashSet<String>())
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun refreshOrdinaryChoiceIfUnsaved(account: String) {
        if (savedSharingChoice.read(account) != null) return // a saved known choice never needs a fetch
        if (!refreshAttemptedAccounts.add(account)) return // once per process per account; no loop
        refreshScope.launch {
            val fetched = operations.refreshProjection()
            timber.log.Timber.i("voice-sharing startup acquisition: account-refreshed=%s applied=%s", account.hashCode(), fetched)
        }
    }
}
