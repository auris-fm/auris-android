package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

/**
 * Compatibility view over [QueueOwnership]: the generation counter is NOT independently locked. Holding a
 * `QueueFence` reference gives no way to read or change the generation outside the shared ownership
 * transaction, because there is no separate lock to hold (reviewer ee26880c finding 1).
 *
 * New code should take part in [QueueOwnership.transaction] directly; this wrapper exists so a store, its
 * controller and the account-loss watcher can all be handed the same boundary object.
 */
class QueueFence(private val ownership: QueueOwnership) {

    fun currentGeneration(): Long = ownership.currentGeneration()

    /**
     * Revocation from an external authority. Runs as its OWN transaction on the shared monitor, so it is
     * atomic with respect to publication/drop/attempt mutations — but callers that must also purge state
     * should use `ContributionStore.revokeAndPurge`, which does both in ONE section.
     */
    // Revocation ALWAYS withdraws in-flight launch authorization in the same ownership section: ONE
    // authoritative transition (reviewer d1484829 - plain revoke used to bypass lease cancellation).
    // pendingLaunchCancellation is installed by the owning store; null only for a bare fence.
    fun revoke(): Long {
        val cancel = pendingLaunchCancellation
        return ownership.transaction(object : (QueueOwnership.OwnershipTransaction) -> Long {
            override fun invoke(tx: QueueOwnership.OwnershipTransaction): Long {
                val generation = tx.revoke()
                cancel?.invoke()
                return generation
            }
        })
    }

    /** Retained name for existing callers; now identical to [revoke]. */
    fun revokeAndCancelLaunches(reason: String): Long = revoke()

    /** Set by [ContributionStore] so a fence-level revocation also cancels its live leases. */
    internal var pendingLaunchCancellation: (() -> Unit)? = null

    fun isValid(captured: Long): Boolean = ownership.transaction(object : (QueueOwnership.OwnershipTransaction) -> Boolean {
        override fun invoke(tx: QueueOwnership.OwnershipTransaction) = tx.isValid(captured)
    })

    /** Guarded execution inside the shared transaction; re-entrant when already inside one. */
    fun <R> guard(captured: Long, block: () -> R): R? = ownership.transaction(object : (QueueOwnership.OwnershipTransaction) -> R? {
        override fun invoke(tx: QueueOwnership.OwnershipTransaction) = if (tx.isValid(captured)) block() else null
    })
}
