package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.WithdrawalState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LIVE collection authority composed from the ACTUAL production authorities — the owner's
 * account/session snapshot and the CURRENT-SESSION membership projection obtained by the
 * authenticated GET. Consulted synchronously at every retention decision; a START-time decision
 * never authorizes later retention after withdrawal or account change.
 *
 * Enrollment rule: retention requires a projection for THIS owner session (tagged with the
 * session it was fetched under) showing `voiceSharingEnrolled == true` with a non-null
 * server-issued epoch and accepted disclosure version. No separate evidence cache: the tagged
 * projection published by the account state IS the evidence.
 */
@Singleton
class CollectionAuthority @Inject constructor(
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

    /**
     * Freeze the ORIGINAL authority COHERENTLY under the COLLECTION fence in ONE acquisition
     * (reviewers 1d27bcd6 b4 + a38421f6 b4): account/session/credential and the enrollment
     * projection come from a single coherent read through [CollectionAuthorityTransaction]. An
     * account move cannot authorize old work: validation compares every frozen component against
     * live coherent state acquired under the same fence at the handoff.
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
        )
    }

    /** Test/diagnostic alias of the coherent issuer. */
    fun issueAuthority(): IssuedAuthority = issueAuthorityCoherentLockedIndependent()

    /** Handoff check INSIDE the owner transaction against the value frozen at start. */
    fun validateIssuedAuthority(issued: IssuedAuthority): Boolean = accountStartup.validateUnderOwnerTransaction { live -> validateIssuedLocked(live, issued) } &&
        enrollmentMatches(issued)

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
            enrollmentMatches(issued)
    }

    private fun validateIssuedLocked(
        live: au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot,
        issued: IssuedAuthority,
    ): Boolean = live.accountName == issued.accountName &&
        live.session == issued.sessionGeneration &&
        live.credential == issued.credentialIncarnation

    private fun enrollmentMatches(issued: IssuedAuthority): Boolean {
        val projection = accountStartup.state.projection.value ?: return false
        return projection.enrollmentEpoch == issued.enrollmentEpoch &&
            projection.acceptedDisclosureVersion == issued.acceptedDisclosureVersion &&
            projection.voiceSharingEnrolled &&
            projection.withdrawalState == WithdrawalState.ACTIVE
    }

    /** Fence-wrapped decision for composition callers that must not hold any monitor yet. */
    fun decideLossSignalWrapped(): AuthorityDecision = collectionTx.transaction { decideLocked() }

    fun decide(): AuthorityDecision = collectionTx.transaction { decideLocked() }

    private fun decideLocked(): AuthorityDecision {
        val ownership = accountStartup.authoritySnapshot()
        val hasSession = ownership.accountName != null && ownership.session > 0L
        val projection = accountStartup.state.projection.value
        // THE LOCAL STOP OVERRIDES THE SERVER'S STALE VIEW. Every other condition here reads
        // server-derived state; this one reads what the user actually asked for, which is why it is
        // checked first and independently. A pending or failed withdrawal leaves the projection
        // saying enrolled, and without this clause collection would silently resume.
        val localIntentPermits = localWithdrawalIntent.collectionPermittedByLocalIntent(projection)
        val enrolledThisSession =
            hasSession &&
                projection != null &&
                projection.voiceSharingEnrolled &&
                projection.enrollmentEpoch != null &&
                projection.acceptedDisclosureVersion != null &&
                projection.withdrawalState == WithdrawalState.ACTIVE &&
                localIntentPermits
        // Full revocation covers: no session, OR enrollment not established for this session
        // (missing composition fails closed — login alone cannot authorize retention).
        val fullRevocation = !hasSession || !enrolledThisSession
        return AuthorityDecision(
            collectionPermitted = hasSession && enrolledThisSession,
            fullRevocation = fullRevocation,
        )
    }
}
