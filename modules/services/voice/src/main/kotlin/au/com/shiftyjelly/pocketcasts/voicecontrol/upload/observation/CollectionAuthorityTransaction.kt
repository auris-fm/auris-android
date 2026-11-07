package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipOwner
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import kotlin.concurrent.withLock

/**
 * THE collection authority transaction (reviewer a38421f6): ONE lock serializes BOTH account
 * transitions for collection purposes, plus every handoff/admission/purge that consumes them.
 * The observer's lifecycle monitor must never reach upward into the authority — so this
 * dedicated COLLECTION fence is acquired FIRST in every entry path:
 *
 *   LOCK ORDER (enforced by construction): collectionFence → owner → observer → sink.
 *   - Entry points below take collectionFence, then may take owner (reentrant-safe reads) and
 *     invoke observer/sink work through narrow callbacks.
 *   - Nothing inside observer or sink acquires collectionFence or the owner lock.
 */

class CollectionAuthorityTransaction @Inject constructor(
    private val ownership: CredentialOwnershipOwner,
    /** THE SHARED serialization primitive: the SAME object as owner.collectionSharedLock (injected
     * by composition). ALWAYS outermost in the single global order COLLECTION → ACCOUNT →
     * OBSERVER → SINK; nothing acquires it while holding an inner lock, and every authority lane
     * enters it before any inner tier. */
    private val sharedLock: Any,
) {

    private val fence = ReentrantLock() // reentrancy-tracking companion for diagnostics only

    private fun <T> withShared(block: () -> T): T = synchronized(sharedLock) {
        // Nested ONLY for isHeldByCurrentThread bookkeeping (never a second serialization edge).
        fence.lock()
        try {
            block()
        } finally {
            fence.unlock()
        }
    }

    /** True when THIS thread holds the collection fence (entry-path precondition). */
    fun heldByCurrentThread(): Boolean = fence.isHeldByCurrentThread

    /** Non-blocking probe used by contract tests: true if another party holds the fence. */
    fun tryEnterOnce(): Boolean {
        if (!fence.tryLock()) return false
        fence.unlock()
        return true
    }

    /** Runs [block] as ONE collection-authority transaction over account + policy state. */
    fun <T> transaction(block: () -> T): T = withShared { block() }

    /**
     * Owner identity read WITHOUT the fence held (reviewer c0c45049): the fence and the owner
     * lock are acquired SEQUENTIALLY, never nested, so no entry path can invert order against a
     * concurrent owner transition (AuthoritySnapshot is an atomic one-lock read).
     */
    fun ownerIdentityNow(): au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot = ownership.authoritySnapshot()

    /** Coherent authority read under the shared primitive, owner read sequentially after. */
    fun coherentAuthority(): CoherentAuthority = withShared {
        val live = ownership.authoritySnapshot()
        CoherentAuthority(
            accountName = live.accountName,
            session = live.session,
            credential = live.credential,
        )
    }
}

/** Everything collection admission/handoff freezes and validates, from ONE fence acquisition. */
data class CoherentAuthority(
    val accountName: String?,
    val session: Long,
    val credential: Long,
)
