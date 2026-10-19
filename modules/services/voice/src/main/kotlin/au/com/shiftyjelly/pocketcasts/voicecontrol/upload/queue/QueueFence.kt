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
    fun revoke(): Long = ownership.transaction { revoke() }

    fun isValid(captured: Long): Boolean = ownership.transaction { isValid(captured) }

    /** Guarded execution inside the shared transaction; re-entrant when already inside one. */
    fun <R> guard(captured: Long, block: () -> R): R? = ownership.transaction { if (isValid(captured)) block() else null }
}
