package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

// Revocation fence for queue ownership (spec @05dc7937): a late network/receipt callback must
// never recreate a purged entry or act under a successor account. Every mutation that a response
// can trigger (drop, retry-count, re-prepare) is checked against an EPOCH GENERATION captured
// when the request went out; any full revocation (account change, withdrawal, remote disable,
// lease loss) bumps the generation and invalidates outstanding callbacks.
class QueueFence {
    private val lock = Any()
    private var generation = 0L

    fun currentGeneration(): Long = synchronized(lock) { generation }

    /** Called by the FULL-REVOCATION path before/after purging the store: fences all in-flight. */
    fun revoke(): Long = synchronized(lock) {
        ++generation
        generation
    }

    /** True while [captured] still belongs to the CURRENT owner/session (no revocation since). */
    fun isValid(captured: Long): Boolean = synchronized(lock) { captured == generation }

    /** Runs [block] only if the captured generation is still current; returns null when fenced. */
    fun <R> guard(captured: Long, block: () -> R): R? = synchronized(lock) {
        if (captured != generation) null else block()
    }
}
