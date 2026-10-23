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
    private val collectionTx: CollectionAuthorityTransaction,
    /**
     * THE LOCAL STOP (reviewer 9156a8ea). Durable, not derived: a user who turns sharing off has
     * decided, and that decision must hold while the withdrawal request is in flight and after it
     * fails. Without this the authority kept reading the server projection, which still says
     * enrolled, so the next capture admitted itself under the new queue generation.
     */
    private val localWithdrawalIntent: LocalWithdrawalIntentStore,
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

    /**
     * Freeze the ORIGINAL authority COHERENTLY under the COLLECTION fence in ONE acquisition
     * (reviewers 1d27bcd6 b4 + a38421f6 b4): account/session/credential, the enrollment
     * projection and the policy activation incarnation come from a single coherent read through
     * [CollectionAuthorityTransaction]. A later enable or a new policy generation cannot
     * authorize old work: validation compares every frozen component against live coherent state
     * acquired under the same fence at the handoff.
     */
    fun issueAuthorityCoherentLockedIndependent(): IssuedAuthority = collectionTx.transaction {
        val coherent = collectionTx.coherentAuthority()
        val projection = accountStartup.state.projection.value
        IssuedAuthority(
            accountName = coherent.accountName,
            sessionGeneration = coherent.session,
            credentialIncarnation = coherent.credential,
            enrollmentEpoch = projection?.enrollmentEpoch,
            acceptedDisclosureVersion = projection?.acceptedDisclosureVersion,
            policyActivationGeneration = coherent.policyActivationGeneration,
            policyActivatedAtMonotonicMs = coherent.policyActivatedAtMonotonicMs,
            policyEnabledAtIssue = coherent.policyEnabled,
        )
    }

    /** Test/diagnostic alias of the coherent issuer. */
    fun issueAuthority(): IssuedAuthority = issueAuthorityCoherentLockedIndependent()

    /** Handoff check INSIDE the owner transaction against the value frozen at start. */
    fun validateIssuedAuthority(issued: IssuedAuthority): Boolean = accountStartup.validateUnderOwnerTransaction { live -> validateIssuedLocked(live, issued) } &&
        enrollmentEvidenceMatches(issued)

    /**
     * Handoff validation via ONE coherent acquisition of the SHARED primitive (reviewer
     * 8273382e): identity + policy snapshot come from [CollectionAuthorityTransaction.coherentAuthority]
     * — the same outermost lock every account/policy publication uses. The coordinator may call
     * this with NO inner lock held; reentry under the shared primitive is order-safe because it
     * IS the outermost tier.
     */
    fun validateIssuedAuthorityLocked(issued: IssuedAuthority): Boolean {
        val c = collectionTx.coherentAuthority()
        return c.accountName == issued.accountName &&
            c.session == issued.sessionGeneration &&
            c.credential == issued.credentialIncarnation &&
            c.policyEnabled == issued.policyEnabledAtIssue &&
            c.policyActivationGeneration == issued.policyActivationGeneration &&
            c.policyActivatedAtMonotonicMs == issued.policyActivatedAtMonotonicMs &&
            enrollmentEvidenceMatches(issued)
    }

    private fun validateIssuedLocked(
        live: au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot,
        issued: IssuedAuthority,
    ): Boolean = live.accountName == issued.accountName &&
        live.session == issued.sessionGeneration &&
        live.credential == issued.credentialIncarnation &&
        // Policy incarnation frozen at issue must still be the CURRENT activation: a newer
        // generation/stamp means the old lease died — old work cannot be reauthorized.
        policyProvider.publishedPolicy.value.let { p ->
            p.enabled == issued.policyEnabledAtIssue &&
                p.activationGeneration == issued.policyActivationGeneration &&
                p.activatedAtMonotonicMs == issued.policyActivatedAtMonotonicMs
        }

    private fun enrollmentEvidenceMatches(issued: IssuedAuthority): Boolean {
        val projection = accountStartup.state.projection.value ?: return false
        return establishedSession == accountStartup.authoritySnapshot().session &&
            projection.enrollmentEpoch == issued.enrollmentEpoch &&
            projection.acceptedDisclosureVersion == issued.acceptedDisclosureVersion &&
            projection.voiceSharingEnrolled &&
            projection.withdrawalState == WithdrawalState.ACTIVE
    }

    /**
     * Loss-signal decision from the RAW published policy (no lease re-evaluation): used by
     * revoked()/fan-out where "policy currently disabled" is the trigger, and the exact-deadline
     * sweep separately guarantees purge at the lease instant even while the flag stays true.
     */
    fun decideLossSignal(): Boolean {
        val p = policyProvider.publishedPolicy.value
        return !p.enabled
    }

    /** Fence-wrapped decision for composition callers that must not hold any monitor yet. */
    fun decideLossSignalWrapped(): AuthorityDecision = collectionTx.transaction { decideLocked() }

    fun decide(): AuthorityDecision = collectionTx.transaction { decideLocked() }

    private fun decideLocked(): AuthorityDecision {
        val permitted = policyProvider.snapshot().enabled
        val ownership = accountStartup.authoritySnapshot()
        val hasSession = ownership.accountName != null && ownership.session > 0L
        val projection = accountStartup.state.projection.value
        // THE LOCAL STOP OVERRIDES THE SERVER'S STALE VIEW. Every other condition here reads
        // server-derived state; this one reads what the user actually asked for, which is why it is
        // checked first and independently. A pending or failed withdrawal leaves the projection
        // saying enrolled, and without this clause collection would silently resume.
        val localIntentPermits = localWithdrawalIntent.collectionPermittedByLocalIntent(projection)
        val enrolledThisSession =
            hasSession && establishedSession == ownership.session &&
                projection != null &&
                projection.voiceSharingEnrolled &&
                projection.enrollmentEpoch != null &&
                projection.acceptedDisclosureVersion != null &&
                projection.withdrawalState == WithdrawalState.ACTIVE &&
                localIntentPermits
        // Full revocation covers: no policy, no session, OR enrollment not established for this
        // session (missing composition fails closed — flag+login alone cannot authorize retention).
        val fullRevocation = !permitted || !hasSession || !enrolledThisSession
        return AuthorityDecision(
            collectionPermitted = permitted && hasSession && enrolledThisSession,
            fullRevocation = fullRevocation,
        )
    }
}
