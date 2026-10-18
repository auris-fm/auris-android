package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipOwner
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyProvider
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import kotlin.concurrent.withLock

/** Narrow read seam for the policy incarnation inside the collection fence. */
interface PolicyIncarnationReader {
    fun enabled(): Boolean
    fun activationGeneration(): Long
    fun activatedAtMonotonicMs(): Long
}

/**
 * THE collection authority transaction (reviewer a38421f6): ONE lock serializes BOTH account
 * transitions and policy transitions for collection purposes, plus every handoff/admission/purge
 * that consumes them. The credential owner's lock alone does NOT cover policy publication, and
 * the observer's lifecycle monitor must never reach upward into either authority — so this
 * dedicated COLLECTION fence is acquired FIRST in every entry path:
 *
 *   LOCK ORDER (enforced by construction): collectionFence → owner → observer → sink.
 *   - Entry points below take collectionFence, then may take owner (reentrant-safe reads) and
 *     invoke observer/sink work through narrow callbacks.
 *   - Nothing inside observer or sink acquires collectionFence or the owner lock.
 *
 * A policy disable/expiry published under the provider fence becomes visible to collection only
 * through [authoritySnapshotLocked]-style reads taken INSIDE this fence at decision points; a
 * handoff either sees the new generation (and refuses old work) or completes its copy before the
 * loss transition acquires the fence — deterministic held-copy-vs-expiry, never a race.
 */

class CollectionAuthorityTransaction @Inject constructor(
    private val ownership: CredentialOwnershipOwner,
    private val policy: PolicyIncarnationReader,
) {

    private val fence = ReentrantLock()

    /** True when THIS thread holds the collection fence (entry-path precondition). */
    fun heldByCurrentThread(): Boolean = fence.isHeldByCurrentThread

    /** Non-blocking probe used by contract tests: true if another party holds the fence. */
    fun tryEnterOnce(): Boolean {
        if (!fence.tryLock()) return false
        fence.unlock()
        return true
    }

    /** Runs [block] as ONE collection-authority transaction over account + policy state. */
    fun <T> transaction(block: () -> T): T = fence.withLock { block() }

    /**
     * Owner identity read WITHOUT the fence held (reviewer c0c45049): the fence and the owner
     * lock are acquired SEQUENTIALLY, never nested, so no entry path can invert order against a
     * concurrent owner transition. Components are individually coherent (AuthoritySnapshot is an
     * atomic one-lock read; the policy triple is read under the fence elsewhere).
     */
    fun ownerIdentityNow(): au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot = ownership.authoritySnapshot()

    /** Coherent authority read: policy incarnation under the fence, owner read sequentially after. */
    fun coherentAuthority(): CoherentAuthority {
        val policy = fence.withLock { Triple(policy.enabled(), policy.activationGeneration(), policy.activatedAtMonotonicMs()) }
        // Fence RELEASED before the owner acquisition: strictly sequential, never nested.
        val live = ownership.authoritySnapshot()
        return CoherentAuthority(
            accountName = live.accountName,
            session = live.session,
            credential = live.credential,
            policyEnabled = policy.first,
            policyActivationGeneration = policy.second,
            policyActivatedAtMonotonicMs = policy.third,
        )
    }

    /** Policy lease deadline read inside the same fence (expiry decisions serialize with handoffs). */
    fun policyLeaseDeadlineMs(): Long = fence.withLock {
        val enabled = policy.enabled()
        val stamp = policy.activatedAtMonotonicMs()
        if (!enabled || stamp <= 0) 0L else stamp + VoiceSharingPolicyProvider.DEFAULT_LEASE_MS
    }
}

/** Everything collection admission/handoff freezes and validates, from ONE fence acquisition. */
data class CoherentAuthority(
    val accountName: String?,
    val session: Long,
    val credential: Long,
    val policyEnabled: Boolean,
    val policyActivationGeneration: Long,
    val policyActivatedAtMonotonicMs: Long,
)
