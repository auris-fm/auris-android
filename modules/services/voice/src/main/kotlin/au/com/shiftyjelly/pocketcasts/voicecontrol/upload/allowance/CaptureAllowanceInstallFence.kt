package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

import android.os.SystemClock
import au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipOwner

/**
 * The PRODUCTION implementation of the provisioner's required atomic install transaction
 * (reviewer 452bebc7 / 78acb0b2): every validation and every installation effect execute under
 * ONE owner→voice transaction — the credential store's single lock, never released between
 * steps — so no identity switch, session rotation, newer overlapping attempt, or deadline
 * crossing can slip between check and bind, and the provisioner's state publication happens
 * INSIDE the fence (a slower older attempt can never overwrite a newer installation after its
 * callback returns).
 *
 * FENCE CONTENTS, in order, all under `ownership.transaction`:
 * 1. ATTEMPT SUPERSESSION: this attemptId must still be the latest announced one
 *    ([beginAttempt]); an older in-flight request whose transport outlived a newer attempt is
 *    refused without touching anything.
 * 2. IDENTITY: the credential incarnation AND session captured at request time must both still
 *    be live in the owner's authoritative snapshot; any rotation refuses with zero effects.
 * 3. DEADLINE AT INSTALL: the grant's freshness is re-evaluated against its ORIGINAL
 *    request-start anchor — a grant that expired while in transit is refused, never installed.
 * 4. INSTALL: `lifecycle.bind(owner)` (retire-then-set) then `publish()` — both inside the
 *    transaction.
 */
class CaptureAllowanceInstallFence(
    private val ownership: CredentialOwnershipOwner,
    private val lifecycle: CaptureAllowanceLifecycle,
    /**
     * The LIVE enrollment authority basis from the account state (operations layer), read
     * inside the install transaction under the same serialization as withdrawal. Epoch
     * equality ALONE proves nothing (reviewer ea4ae310): VoiceSharingAccountState accepts
     * withdrawn projections that retain the epoch string, so the basis must show an ACTIVE
     * enrolled membership AND a live policy — a withdrawn projection keeping the old epoch
     * refuses exactly like a changed one.
     */
    private val enrollmentBasis: () -> EnrollmentBasis?,
    private val nowElapsedMs: () -> Long = SystemClock::elapsedRealtime,
) {

    /** The enrollment authority the composition requires at install time. */
    data class EnrollmentBasis(
        val enrollmentEpoch: String,
        /** True only when the projection is enrolled with withdrawalState == ACTIVE. */
        val membershipActive: Boolean,
        /** True only when the policy snapshot is enabled within its evaluated lease. */
        val policyLive: Boolean,
    )

    @Volatile
    private var latestAttemptId: String? = null

    /**
     * Announces a new provision attempt UNDER THE SAME OWNER TRANSACTION the install check
     * runs in. Registration outside the lock would allow an attempt to be superseded after
     * install validated its id but before bind/publication.
     */
    fun beginAttempt(attemptId: String) {
        ownership.transaction { latestAttemptId = attemptId }
    }

    /**
     * The provisioner's `installAuthority` production binding. Returns false (refusal,
     * zero effects) on any failed fence check; on success the bind and the provisioner's
     * publication have both committed inside the owner transaction.
     */
    fun install(
        owner: CaptureAllowanceOwner,
        observedCredentialGeneration: Long,
        observedSession: Long,
        observedEnrollmentEpoch: String,
        attemptId: String,
        grant: CaptureAllowanceProvisioner.Grant,
        publish: () -> Unit,
    ): Boolean = ownership.transaction {
        val live = ownership.authoritySnapshot()
        val basis = enrollmentBasis()
        when {
            latestAttemptId != attemptId -> false

            live.credential != observedCredentialGeneration -> false

            live.session != observedSession -> false

            // ENROLLMENT AUTHORITY: enrolled + ACTIVE membership + matching epoch + live
            // policy, read under the same lock that serializes withdrawal. A withdrawn
            // projection retaining the old epoch string refuses exactly like a changed epoch.
            basis == null ||
                !basis.membershipActive ||
                !basis.policyLive ||
                basis.enrollmentEpoch != observedEnrollmentEpoch -> false

            !grant.isFresh(nowElapsedMs()) -> false

            else -> {
                // The installed authority carries the ALLOWANCE's own deadline (reviewer
                // c226fb6f): install-time freshness alone lets a short batch outlive
                // `valid_for_ms` while the independently live policy lease keeps it installed.
                lifecycle.bind(
                    CaptureAllowanceDeadlineAuthority(owner, grant) { nowElapsedMs() },
                    CaptureAllowanceLifecycle.GrantIdentity(
                        enrollmentEpoch = observedEnrollmentEpoch,
                        ownerSession = live.session,
                    ),
                )
                publish()
                // Publication of the fence's own record happens INSIDE the transaction, so a
                // slower older attempt can never overwrite a newer installation after return.
                lastInstalledGrant = grant
                true
            }
        }
    }

    /** The grant currently installed through this fence; null before the first install. */
    @Volatile
    var lastInstalledGrant: CaptureAllowanceProvisioner.Grant? = null
        private set
}
