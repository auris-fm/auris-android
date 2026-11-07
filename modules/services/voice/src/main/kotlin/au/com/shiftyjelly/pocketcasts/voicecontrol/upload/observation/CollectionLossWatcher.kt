package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import au.com.shiftyjelly.pocketcasts.repositories.sync.SyncAccountManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.WithdrawalState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * PRODUCTION loss subscriptions (reviewer 01877984 blocker 1): every path that can revoke
 * collection authority invokes [SegmentObserver.revoked] WITHOUT needing an engine event or a
 * read. Composed by VoiceObservationModule and started from the app surface; this is the caller
 * graph, not intended design:
 *
 *   SyncAccountManager.onAccountsChanged -> reconcileExternalState -> sessionEnded fan-out
 *       -> revoked("account-change")                         [startup listener, real wiring]
 *   VoiceSharingAccountStartup.sessionEndedListener  (set in start(), fired on switch/remove)
 *   VoiceSharingAccountState.onProjectionApplied hook -> enrollment evidence update
 *       -> revoked("enrollment-change") when the applied projection is NOT enroll-permitted
 */
@Singleton
class CollectionLossWatcher @Inject constructor(
    private val observer: SegmentObserver,
    private val authority: CollectionAuthority,
    private val accountStartup: VoiceSharingAccountStartup,
    private val syncAccountManager: SyncAccountManager,
    @au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
    private val scope: CoroutineScope,
) {
    private var started = false

    /** Idempotent: installs the listeners/collectors exactly once. */
    fun start() {
        if (started) return
        started = true

        // Account removal/change: production emailFlow is the repo's own accounts-update
        // collector; subscribing it here means a REAL AccountsUpdate reaches reconcile WITHOUT
        // any engine event. (emailFlow emits immediately + on every onAccountsUpdated.)
        scope.launch {
            syncAccountManager.emailFlow().collect { accountStartup.reconcileAndNotifySessionEnd() }
        }
        // Loss fan-out: chain after whatever the startup composed.
        val priorSessionEnd = accountStartup.sessionEndedListener
        accountStartup.sessionEndedListener = {
            priorSessionEnd?.invoke()
            observer.revoked("account-change")
        }
        val priorEvidence = accountStartup.enrollmentEvidenceListener
        accountStartup.enrollmentEvidenceListener = { projection, ownerSession ->
            priorEvidence?.invoke(projection, ownerSession)
            val permitted = projection != null && enrollPermitted(
                projection.voiceSharingEnrolled,
                projection.enrollmentEpoch,
                projection.acceptedDisclosureVersion,
                projection.withdrawalState,
            )
            if (!permitted) observer.revoked("enrollment-change")
        }
    }

    companion object {
        fun enrollPermitted(enrolled: Boolean, epoch: String?, disclosure: String?, withdrawal: WithdrawalState): Boolean = enrolled && epoch != null && disclosure != null && withdrawal == WithdrawalState.ACTIVE
    }
}
