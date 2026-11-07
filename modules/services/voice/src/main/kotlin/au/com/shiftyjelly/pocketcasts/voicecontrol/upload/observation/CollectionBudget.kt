package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

/**
 * Aggregate byte budget for collection retention. EVERY upload-owned allocation is RESERVED
 * before it happens and CHARGED here — open prefix buffers, ready candidates awaiting record
 * assembly, queued/in-flight entries handed to the future store (Task 5). Release happens on
 * cancellation, purge, or terminal disposition; completion of an already-reserved prefix must
 * NOT double-count (the ready candidate inherits the prefix's charge via [transfer]).
 */
class CollectionBudget(private val maxTotalBytes: Long) {

    private val lock = Any()
    private var chargedBytes = 0L

    /** Reserve [bytes] atomically; false when the aggregate cap would be exceeded. */
    fun reserve(bytes: Long): Boolean = synchronized(lock) {
        if (bytes < 0 || chargedBytes + bytes > maxTotalBytes) return false
        chargedBytes += bytes
        true
    }

    fun release(bytes: Long) = synchronized(lock) {
        require(bytes >= 0)
        chargedBytes -= bytes
        check(chargedBytes >= 0) { "budget over-released" }
    }

    /** Move a charge between owners (prefix → ready) with no re-allocation and no double count. */
    fun transfer(bytes: Long) = Unit // ownership moves, charge unchanged

    fun charged(): Long = synchronized(lock) { chargedBytes }
}
