package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

/**
 * ONE shared validation+mutation ownership transaction for the upload queue (reviewer ee26880c finding 1).
 *
 * The previous shape — "take the store lock, then peek at a generation" — was not a transaction: it read the
 * generation and released before mutating, so an external revocation could complete in between and the
 * mutation would land on state that no longer belongs to this owner. Renaming the order does not fix that.
 *
 * Instead there is exactly one monitor ([lock]) that every queue authority shares, and every readiness or
 * lifecycle change runs inside [transaction] while holding it for the whole validate-then-mutate section:
 *
 * ```
 * store.transaction { /* revalidate generation + ownership, then mutate */ }
 * ```
 *
 * Rules enforced by construction here:
 *  - [QueueFence] reads/writes the generation under THIS SAME [lock]; it has no independent lock at all, so a
 *    caller cannot consult the fence without entering the transaction.
 *  - External authorities (the controller, the account-loss watcher) hold the same object; they call
 *    [ContributionStore.transaction] / [ContributionStore.revokeAndPurge] rather than touching a raw fence,
 *    which is what constrains every entry point instead of relying on convention.
 *  - No nested acquisition of a second ordering lock exists on these paths, so the old inversion (one side
 *    fence→store, the other store→fence) cannot be expressed.
 */
class QueueOwnership {
    /** The single monitor shared by the store index, reservations, staging registry and the generation. */
    val lock: Any = Any()

    private var generation = 0L

    fun currentGeneration(): Long = synchronized(lock) { generation }

    /** Bump the generation. Must run inside [transaction] (or as the whole transaction) so it is atomic with the ownership change it invalidates. */
    internal fun revokeLocked(): Long {
        check(Thread.holdsLock(lock)) { "revoke must run inside the shared ownership transaction" }
        ++generation
        return generation
    }

    /** Revocation for callers already inside the shared monitor (the store's revokeAndPurge transaction). */
    internal fun revokeUnderLock(): Long {
        check(Thread.holdsLock(lock)) { "revoke must run inside the shared ownership transaction" }
        ++generation
        return generation
    }

    internal fun isValidUnderLock(captured: Long): Boolean {
        check(Thread.holdsLock(lock)) { "generation checks must run inside the shared ownership transaction" }
        return captured == generation
    }

    /** Test seam: the public external revoke path. */
    fun revokeExternalForTest(): Long = transaction { revoke() }

    /** Test seam proving the guard exists: calls [isValidUnderLock] WITHOUT acquiring [lock]. */
    internal fun validateOutsideLockForTest(captured: Long): Boolean = isValidUnderLock(captured)

    internal fun isValidLocked(captured: Long): Boolean {
        check(Thread.holdsLock(lock)) { "generation checks must run inside the shared ownership transaction" }
        return captured == generation
    }

    /**
     * The store's preparation transaction: validate the captured generation AND the handle's state, transfer
     * HELD -> WRITING before any byte is written, run [write], then either publish (WRITING -> ADOPTED) or
     * settle (-> RELEASED) — all inside one acquisition of the shared monitor for the decision points.
     * Reviewer ee26880c findings 2 and 3: an untransferred, discarded, adopted, foreign or undersized handle
     * cannot reach the write path, and no early return can escape settlement.
     */
    fun <R> prepareTransaction(
        capturedGeneration: Long,
        handle: ReservationHandle,
        requiredSamples: Long,
        requiredMetadataBytes: Int,
        claimStaging: () -> Unit,
        releaseStaging: () -> Unit,
        write: () -> R,
        publish: (R) -> Boolean,
    ): R? = synchronized(lock) {
        if (capturedGeneration != generation) return null
        if (!handle.transferToWriting(requiredSamples, requiredMetadataBytes)) return null
        claimStaging()
        val result = try {
            write()
        } catch (_: Throwable) {
            handle.release()
            releaseStaging()
            return null
        }
        if (result == null || !publish(result)) {
            handle.release()
            releaseStaging()
            return null
        }
        handle.adopt()
        releaseStaging()
        result
    }

    /** Run [block] as one ownership transaction: validation and mutation share the monitor for their entirety. */
    fun <R> transaction(block: OwnershipTransaction.() -> R): R = synchronized(lock) { OwnershipTransaction().block() }

    /** Receiver bound to the live transaction; only obtainable inside [transaction]. */
    inner class OwnershipTransaction internal constructor() {
        val generation: Long get() = this@QueueOwnership.generation

        /** True while [captured] still names the current owner/session. Validated under the held monitor. */
        fun isValid(captured: Long): Boolean = isValidLocked(captured)

        /** Full revocation: bump the generation so every older captured value becomes stale. */
        fun revoke(): Long = revokeLocked()
    }
}

/**
 * The reservation side of a preparation attempt. Implemented by `ContributionStore.Admission`; exposed here so
 * the ownership transaction can validate and settle it without reaching back into the store's internals.
 */
interface ReservationHandle {
    /** Unique attempt identity used to name staging; also the reservation key. */
    val attemptId: String

    /** Bytes this reservation claimed when it was created. */
    val projectedBytes: Long

    /** Validated sample count this reservation was sized for. */
    val reservedSamples: Long

    /** True while HELD; transitions to WRITING only when size requirements match this attempt exactly. */
    fun transferToWriting(requiredSamples: Long, requiredMetadataBytes: Int): Boolean

    /** Give the budget back (idempotent). */
    fun release()

    /** Hand the budget to the published entry (idempotent). */
    fun adopt()
}
