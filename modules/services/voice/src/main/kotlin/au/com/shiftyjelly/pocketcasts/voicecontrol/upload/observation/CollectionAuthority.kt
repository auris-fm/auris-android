package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.WithdrawalState
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LIVE collection authority composed from the ACTUAL production authorities — the lease-evaluated
 * policy snapshot, the owner's account/session snapshot, and the CURRENT-SESSION membership
 * projection obtained by the authenticated GET. Consulted synchronously at every retention
 * decision; a START-time decision never authorizes later retention after withdrawal, expiry,
 * disable or account change.
 *
 * FAIL-CLOSED enrollment rule (reviewer 251afd7d): a permitted policy flag plus a logged-in
 * account is NOT enough to retain PCM. Retention additionally requires a fresh projection for
 * THIS session showing `voiceSharingEnrolled == true` with a non-null server-issued epoch AND a
 * non-null accepted disclosure version. Missing composition (no projection fetched under the
 * current session) keeps everything revoked until [markEnrollmentEstablished] runs — which only
 * the projection-success path of the operations layer may call.
 */
@Singleton
class CollectionAuthority @Inject constructor(
    private val policyProvider: VoiceSharingPolicyProvider,
    private val accountStartup: VoiceSharingAccountStartup,
) {

    // Enrollment evidence bound to ONE owner session; any session change clears it fail-closed.
    @Volatile
    private var establishedSession: Long? = null

    /** Called ONLY from the post-commit projection hook; establishes evidence for ONE session. */
    fun markEnrollmentEstablished(projection: VoiceSharingAccountProjection, sessionGeneration: Long) {
        if (projection.voiceSharingEnrolled &&
            projection.enrollmentEpoch != null &&
            projection.acceptedDisclosureVersion != null &&
            projection.withdrawalState == WithdrawalState.ACTIVE
        ) {
            establishedSession = sessionGeneration
        } else {
            // Not enrolled / withdrawn / missing epoch or disclosure: fail closed.
            establishedSession = null
        }
    }

    /** Session end / account change: enrollment evidence dies with the session, fail-closed. */
    fun clearEnrollmentEvidence() {
        establishedSession = null
    }

    fun decide(): AuthorityDecision {
        val permitted = policyProvider.snapshot().enabled
        val ownership = accountStartup.authoritySnapshot()
        val hasSession = ownership.accountName != null && ownership.session > 0L
        val projection = accountStartup.state.projection.value
        val enrolledThisSession =
            hasSession && establishedSession == ownership.session &&
                projection != null &&
                projection.voiceSharingEnrolled &&
                projection.enrollmentEpoch != null &&
                projection.acceptedDisclosureVersion != null &&
                projection.withdrawalState == WithdrawalState.ACTIVE
        // Full revocation covers: no policy, no session, OR enrollment not established for this
        // session (missing composition fails closed — flag+login alone cannot authorize retention).
        val fullRevocation = !permitted || !hasSession || !enrolledThisSession
        return AuthorityDecision(collectionPermitted = permitted && hasSession && enrolledThisSession, fullRevocation = fullRevocation)
    }
}
