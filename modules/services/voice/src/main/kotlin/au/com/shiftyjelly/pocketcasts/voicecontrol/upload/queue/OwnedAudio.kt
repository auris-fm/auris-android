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
            terminal = true
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (activeBorrows.get() > 0) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return false
                settledCondition.await(remaining, TimeUnit.NANOSECONDS)
            }
            closeDescriptorLocked()
            return true
        } finally {
            state.unlock()
        }
    }

    /**
     * Observes settlement without initiating it: true only once no borrow is outstanding. Used to report
     * eventual resolution after [settle] timed out.
     */
    fun awaitSettlement(timeoutMs: Long): Boolean {
        state.lock()
        try {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (activeBorrows.get() > 0) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return false
                settledCondition.await(remaining, TimeUnit.NANOSECONDS)
            }
            closeDescriptorLocked()
            return true
        } finally {
            state.unlock()
        }
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
        }
    }

    fun isClosed(): Boolean = closed

    private fun closeDescriptorLocked() {
        if (closed) return
        closed = true
        runCatching { source.close() }
    }
}
