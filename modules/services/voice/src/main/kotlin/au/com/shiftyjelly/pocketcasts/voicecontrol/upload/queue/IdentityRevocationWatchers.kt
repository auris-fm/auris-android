package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountAuthBinding
import kotlinx.coroutines.awaitCancellation

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
        snapshot: () -> AuthoritySnapshot?,
        epoch: () -> String?,
        store: ContributionStore,
    ): suspend kotlinx.coroutines.CoroutineScope.() -> Unit = {
        binding.authorityMovedListener = {
            val snap = snapshot()
            store.cancelLaunchesIdentityMismatch(snap?.accountName, snap?.session, epoch(), "account-transition")
        }
        awaitCancellation()
    }
}
