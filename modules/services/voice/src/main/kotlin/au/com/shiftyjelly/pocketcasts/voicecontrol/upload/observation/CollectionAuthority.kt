package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipOwner
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LIVE collection authority composed from the ACTUAL production authorities — the owner's
 * account/session snapshot and the policy provider's lease-evaluated snapshot. Consulted
 * synchronously at every retention decision; a START-time decision never authorizes later
 * retention after withdrawal, expiry, disable or account change.
 */
@Singleton
class CollectionAuthority @Inject constructor(
    private val policyProvider: VoiceSharingPolicyProvider,
    private val accountStartup: VoiceSharingAccountStartup,
) {

    /**
     * Full revocation = policy disabled/lease-expired/unknown, OR no owned account session,
     * OR enrollment withdrawn/not enrolled (the projection is advisory until the controller
     * composes it; the account-session + policy gates are authoritative for retention).
     */
    fun decide(): AuthorityDecision {
        val permitted = policyProvider.snapshot().enabled
        val ownership = accountStartup.authoritySnapshot()
        val fullRevocation = !permitted || ownership.accountName == null || ownership.session <= 0L
        return AuthorityDecision(collectionPermitted = permitted && !fullRevocation, fullRevocation = fullRevocation)
    }
}

/** The single credential-ownership implementation used to read live authority. */
typealias LiveOwnership = CredentialOwnershipOwner
