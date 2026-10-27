package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import java.io.FileInputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * The validated audio bytes for one in-flight upload, held as an OPEN DESCRIPTOR.
 *
 * WHY A DESCRIPTOR AND NOT A PATH (reviewer ac2d270c, spec ee31a3cc): a `File` is a pathname. Validating a
 * pathname and opening it later leaves a window in which a new owner can publish at the same capture id and
 * replace the path, so the bytes sent are not the bytes validated. Opening FIRST and hashing THAT
 * descriptor removes the window: the hash and the transmitted bytes are the same file description, and no
 * later replacement of the pathname can reach it.
 *
 * OWNERSHIP (reviewer e2bae48b): one attempt/call owns this, and it is closed EXACTLY ONCE after the call
 * reaches terminal settlement and every active body writer has exited. A body invocation only BORROWS it.
 * Closing per-body would leave the next replay with a closed descriptor and would leak whenever the call
 * failed before the body ever ran.
 *
 * The class ENFORCES that lifetime rather than offering helpers a caller can race or omit (reviewer
 * 9436742d). Borrows are exclusive, because all borrows share ONE file position: admitting a second reader
 * would rewind the descriptor under the first and corrupt both bodies. Close is a terminal transition that
 * refuses new borrows and then drains the one in flight; it is never a bare stream close that could pull the
 * descriptor out from under a live reader.
 *
 * The descriptor is immutable: a published WAV is never rewritten in place (the writer streams to
 * `<staging>.wav.part` and renames, and publication refuses to overwrite an existing final name), so the
 * inode this descriptor refers to cannot change under us. Sequential replay is therefore legal.
 */
class OwnedAudio internal constructor(
    internal val source: FileInputStream,
    /** Exactly the number of bytes this descriptor holds; the cap that bounds every replay. */
    val byteCount: Long,
    /** SHA-256 of the bytes this descriptor reads, established by hashing THIS descriptor. */
    val audioSha256: String,
    /**
     * The directory's source lease, held for exactly as long as this descriptor is open.
     *
     * Reviewer 7c2f30e3 finding 2: the operation lease ends when the capture is handed down, so a close
     * could release the directory while an upload still owned bytes. The source lease keeps the directory
     * counted until THIS descriptor closes - which is the real lifetime of the retention.
     */
    private val sourceLease: AutoCloseable? = null,
) : AutoCloseable {
    /** Guards EVERY state transition: admission, cancellation, close, and borrow exit. */
    private val state = ReentrantLock()
    private val settledCondition = state.newCondition()

    /** Borrows outstanding. At most one, enforced below: all borrows share one file position. */
    private val activeBorrows = AtomicInteger(0)

    @Volatile
    private var cancelled = false

    @Volatile
    private var terminal = false

    @Volatile
    private var closed = false

    /**
     * An ACTIVE body writer. Closing the attempt waits for this to exit, so a descriptor is never closed
     * underneath a read that is still running.
     */
    inner class Borrow internal constructor() : AutoCloseable {
        private val released = java.util.concurrent.atomic.AtomicBoolean(false)
        internal val stream: FileInputStream get() = source

        /** Reads are legal only until this borrow is dropped. */
        fun isOpen(): Boolean = !released.get() && !closed

        override fun close() {
            if (!released.compareAndSet(false, true)) return
            state.lock()
            try {
                activeBorrows.decrementAndGet()
                // A terminal transition that could not close because this writer was active completes
                // HERE. Without this the deferral in close() would leak the descriptor forever.
                if (terminal && activeBorrows.get() == 0) closeDescriptorLocked()
                settledCondition.signalAll()
            } finally {
                state.unlock()
                runSettleCallbacks()
            }
        }
    }

    /**
     * Begins the body read, positioned at byte 0.
     *
     * Returns null when the attempt is terminal, cancelled, or a body writer is ALREADY inside one: a
     * second borrow would rewind the single shared file position and corrupt the running body, so it is
     * refused rather than serialized (there is only ever one body write per call attempt).
     */
    fun borrowForBody(): Borrow? {
        state.lock()
        try {
            if (terminal || cancelled) return null
            // EXCLUSIVE: a concurrent borrow would seek the shared descriptor away from the active reader.
            if (activeBorrows.get() > 0) return null
            try {
                source.channel.position(0)
            } catch (failure: java.io.IOException) {
                return null
            }
            activeBorrows.incrementAndGet()
            return Borrow()
        } finally {
            state.unlock()
        }
    }

    /** True while a body writer is still reading. */
    fun hasActiveBorrows(): Boolean = activeBorrows.get() > 0

    /**
     * Refuses any FUTURE replay. The current borrow, if any, continues: cancellation must reach the socket
     * through the call itself, never by yanking the buffer away from a running writer.
     *
     * NO PRODUCTION CALLER, ON PURPOSE (reviewer 7c2f30e3 finding 3). Calling it from the attempt was tried
     * and removed: an attempt always ends in [settle], which already makes this source terminal, so a later
     * [borrowForBody] is refused whether or not this flag was set - a mutant that dropped the call survived
     * even with a borrow granted mid-body. Wiring it would add a line no test can defend. It stays as the
     * named expression of "no further replay" that [settle] enforces, and is NOT evidence that cancellation
     * reaches a source.
     */
    fun cancelFurtherBorrows() {
        state.lock()
        try {
            cancelled = true
        } finally {
            state.unlock()
        }
    }

    /**
     * The single terminal transition: refuse new borrows, then wait for the body writer to exit, then close
     * exactly once.
     *
     * Returns true when the descriptor is closed and nothing is outstanding. Returns FALSE when a body
     * writer is still running at the deadline - ownership is then UNRESOLVED, and the caller must report it
     * as such rather than claiming cleanup completed (spec c12c569f). A later [awaitSettlement] can still
     * observe the resolution.
     */
    fun settle(timeoutMs: Long): Boolean {
        state.lock()
        try {
            return drainAndCloseLocked(timeoutMs)
        } finally {
            state.unlock()
            // Callbacks run AFTER the monitor is released; see runSettleCallbacks().
            runSettleCallbacks()
        }
    }

    /**
     * Observes settlement without initiating it: true only once no borrow is outstanding. Used to report
     * eventual resolution after [settle] timed out.
     */
    fun awaitSettlement(timeoutMs: Long): Boolean {
        state.lock()
        try {
            return drainAndCloseLocked(timeoutMs)
        } finally {
            state.unlock()
            runSettleCallbacks()
        }
    }

    /** Waits for borrows (bounded) then closes the descriptor. Caller holds the monitor. */
    private fun drainAndCloseLocked(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (activeBorrows.get() > 0) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return false
            settledCondition.await(remaining, TimeUnit.NANOSECONDS)
        }
        closeDescriptorLocked()
        return true
    }

    /**
     * Idempotent. Never closes the descriptor underneath a running body writer: it enters the same terminal
     * transition as [settle] and leaves the close to whoever observes the drain, so a `use {}` block cannot
     * yank the stream from a live reader.
     */
    override fun close() {
        state.lock()
        try {
            terminal = true
            if (activeBorrows.get() == 0) closeDescriptorLocked()
        } finally {
            state.unlock()
            runSettleCallbacks()
        }
    }

    fun isClosed(): Boolean = closed

    /**
     * Runs [action] once when the descriptor actually closes, or immediately if it already has. Used by the
     * store to drop this source's pinned-byte charge at exactly the moment the bytes stop being retained.
     */
    fun onSettled(action: () -> Unit) {
        state.lock()
        try {
            if (closed) {
                return runCatching(action).let { }
            }
            settleCallbacks.add(action)
        } finally {
            state.unlock()
        }
    }

    private val settleCallbacks = mutableListOf<() -> Unit>()

    /**
     * Closes the descriptor and runs settle callbacks with NO lock held.
     *
     * SETTLE CALLBACKS MUTATE STORE STATE and take the store's lock. Running them while holding this monitor
     * would nest store-lock-inside-source-lock - the reverse of the order every other path uses (store lock
     * -> source) - and would deadlock against it. The descriptor is closed under the monitor so it happens
     * exactly once; the callbacks run after the monitor is released.
     */
    private fun closeDescriptorLocked() {
        if (closed) return
        closed = true
        runCatching { source.close() }
        // The bytes are only really gone once the descriptor is closed, so the directory may be released
        // only now. Idempotent, and safe to run under the monitor: it takes no store lock.
        runCatching { sourceLease?.close() }
        notifyPending = settleCallbacks.toList()
        settleCallbacks.clear()
    }

    /**
     * Runs this source's settle callbacks with NO lock held.
     *
     * They mutate store state and take the STORE's lock; invoking them while holding this monitor would
     * nest store-lock-inside-source-lock, the reverse of the order every other path uses (store lock ->
     * source), and could deadlock against it. Every terminal path calls this AFTER releasing the monitor.
     */
    private fun runSettleCallbacks() {
        val pending = notifyPending
        if (pending.isNullOrEmpty()) return
        notifyPending = emptyList()
        pending.forEach { runCatching(it) }
    }

    /** Callbacks queued by [closeDescriptorLocked] (which runs with the monitor held) and run afterwards. */
    private var notifyPending: List<() -> Unit>? = null
}
