package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import android.os.SystemClock
import au.com.shiftyjelly.pocketcasts.repositories.sync.SyncAccountManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.WithdrawalState
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
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
 *   VoiceSharingPolicyProvider.publishedPolicy collector
 *       -> revoked("policy-disable") on enabled=false
 *   exact-deadline timer -> sleep until activationStamp + lease, then revoked("lease-deadline")
 */
@Singleton
class CollectionLossWatcher @Inject constructor(
    private val observer: SegmentObserver,
    private val authority: CollectionAuthority,
    private val policyProvider: VoiceSharingPolicyProvider,
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
        // Loss fan-out WITHOUT replacing the evidence hooks: chain after whatever the startup
        // composed (authority mark/clear), so one commit updates evidence AND purges in order.
        val priorSessionEnd = accountStartup.sessionEndedListener
        accountStartup.sessionEndedListener = {
            priorSessionEnd?.invoke()
            authority.clearEnrollmentEvidence()
            observer.revoked("account-change")
        }
        val priorEvidence = accountStartup.enrollmentEvidenceListener
        accountStartup.enrollmentEvidenceListener = { projection, ownerSession ->
            priorEvidence?.invoke(projection, ownerSession)
            val permitted = enrollPermitted(
                projection.voiceSharingEnrolled,
                projection.enrollmentEpoch,
                projection.acceptedDisclosureVersion,
                projection.withdrawalState,
            )
            if (permitted) {
                authority.markEnrollmentEstablished(projection, ownerSession)
            } else {
                authority.clearEnrollmentEvidence()
                observer.revoked("enrollment-change")
            }
        }

        // Policy disable observed at the collection boundary.
        scope.launch {
            policyProvider.publishedPolicy.collect { snap ->
                if (!snap.enabled) observer.revoked("policy-disable")
            }
        }

        // EXACT DEADLINE PURGE: computed from the published activation stamp — no lease/4
        // polling that could lag past the expiry instant. Re-armed per new activation.
        scope.launch {
            var scheduledFor = -1L
            while (true) {
                val snap = policyProvider.publishedPolicy.value
                if (!snap.enabled || snap.activatedAtMonotonicMs <= 0) {
                    scheduledFor = -1L
                    delay(REARM_CHECK_MS) // waiting for an activation to schedule against
                    continue
                }
                if (snap.activatedAtMonotonicMs != scheduledFor) {
                    scheduledFor = snap.activatedAtMonotonicMs
                    val remaining = (
                        snap.activatedAtMonotonicMs + VoiceSharingPolicyProvider.DEFAULT_LEASE_MS -
                            SystemClock.elapsedRealtime()
                        ).coerceAtLeast(0L) + 1L
                    delay(remaining)
                    observer.revoked("lease-deadline")
                    scheduledFor = -1L
                } else {
                    delay(REARM_CHECK_MS)
                }
            }
        }
    }

    companion object {
        /** Re-check interval ONLY for waiting on activation changes; never delays a deadline. */
        const val REARM_CHECK_MS = 1_000L

        fun enrollPermitted(enrolled: Boolean, epoch: String?, disclosure: String?, withdrawal: WithdrawalState): Boolean = enrolled && epoch != null && disclosure != null && withdrawal == WithdrawalState.ACTIVE
    }
}
