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
 * [ContributionStore.cancelLaunchesIdentityMismatch] (account+session; the enrollment epoch is
 * record provenance and never a cancellation criterion); retired-identity captures are RETAINED.
 */
object IdentityRevocationWatchers {

    fun production(
        binding: VoiceSharingAccountAuthBinding,
        accountState: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountState,
        snapshot: () -> AuthoritySnapshot?,
        store: ContributionStore,
    ): suspend kotlinx.coroutines.CoroutineScope.() -> Unit = coroutineScope@{
        val cancel: () -> Unit = {
            val snap = snapshot()
            store.cancelLaunchesIdentityMismatch(snap?.accountName, snap?.session, null, "account-transition")
        }
        // AUTHORITY-SESSION tail: login/logout/removal inside the owner transaction.
        binding.authorityMovedListener = cancel
        // PROJECTION tail (reviewer dde0905a): same-session enrollment/withdrawal/GET/401 epoch
        // changes apply through the state fence and fire here - an epoch-1 call cannot survive an
        // epoch retirement just because no login happened.
        accountState.projectionInvalidatedListener = { _, _ -> cancel() }
        awaitCancellation()
    }
}
