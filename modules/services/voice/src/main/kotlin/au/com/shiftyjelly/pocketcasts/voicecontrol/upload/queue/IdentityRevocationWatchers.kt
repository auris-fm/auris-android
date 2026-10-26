package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountAuthBinding
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The PRODUCTION account-transition connector (reviewer f4d85031): binds invalidation to the
 * AUTHORITATIVE owner transition tail - [VoiceSharingAccountAuthBinding] fires
 * onSessionStarted/onSessionEnded exactly once per real login/logout/removal INSIDE the owner
 * transaction - and cancels from a COHERENT snapshot read (account+session read under the owner
 * lock, epoch from the state projection) at each event. No polling, so no window in which a move
 * away and back can be missed. Selection compares the LEASES' own admitted identities inside
 * [ContributionStore.cancelLaunchesIdentityMismatch]; retired-identity captures are RETAINED.
 */
object IdentityRevocationWatchers {

    fun production(
        binding: VoiceSharingAccountAuthBinding,
        accountState: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountState,
        snapshot: () -> AuthoritySnapshot?,
        epoch: () -> String?,
        store: ContributionStore,
        /**
         * THE POLICY-DISABLE TAIL: the published policy is a THIRD revocation source, alongside the
         * authority-session and projection tails. A disable changes neither the account nor the
         * projection epoch, so before this subscription existed a disable closed the upload gate
         * but left already-queued audio and metadata on disk indefinitely - captured under a policy
         * that then said no. Observed on the real flow (not sampled), purging through the store's
         * own [ContributionStore.revokeAndPurge] so cancellation, index clear and file deletion stay
         * ONE ownership transaction. Supply it only where a real policy exists.
         */
        policyProvider: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyProvider? = null,
    ): suspend kotlinx.coroutines.CoroutineScope.() -> Unit = coroutineScope@{
        val cancel: () -> Unit = {
            val snap = snapshot()
            store.cancelLaunchesIdentityMismatch(snap?.accountName, snap?.session, epoch(), "account-transition")
        }
        // AUTHORITY-SESSION tail: login/logout/removal inside the owner transaction.
        binding.authorityMovedListener = cancel
        // PROJECTION tail (reviewer dde0905a): same-session enrollment/withdrawal/GET/401 epoch
        // changes apply through the state fence and fire here - an epoch-1 call cannot survive an
        // epoch retirement just because no login happened.
        accountState.projectionInvalidatedListener = { _, _ -> cancel() }
        // POLICY tail: a published disable purges the durable queue. `revokeAndPurge` is the store's
        // own full-revocation transaction, so this reuses the existing revocation connection instead
        // of introducing a second, weaker purge path.
        policyProvider?.let { provider ->
            this@coroutineScope.launch {
                // LEVEL, not just an edge (spec voice-recording-uploads.md:75, ruling 51ebeb5f):
                // "Disabling, withdrawal or policy-lease expiry cancels collection-owned work and
                // deletes pending local contribution audio/metadata, including partial buffers."
                // There is NO same-process-edge exemption and NO compiled-off exemption, so the
                // FIRST disabled observation must purge too — a build that starts disabled may still
                // find a queue left by an earlier enabled run, and leaving it would retain audio the
                // current policy says must not exist.
                provider.publishedPolicy.collect { snap ->
                    if (!snap.enabled) store.revokeAndPurge("policy-disable")
                }
            }
        }
        awaitCancellation()
    }
}
