package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    private val closed = AtomicBoolean(false)
    private val activeBorrows = AtomicInteger(0)

    /** Serializes borrow admission with cancellation and rewind (reviewer 3cb8574f). */
    private val borrowLock = Any()

    /** Set before the descriptor is closed; a cancelled attempt must not begin another replay. */
    @Volatile
    private var cancelled = false

    /**
     * An ACTIVE body writer. Closing the attempt waits for this to be recycled, so a descriptor is never
     * closed underneath a read that is still running.
     */
    inner class Borrow internal constructor() : AutoCloseable {
        private val released = AtomicBoolean(false)
        internal val stream: FileInputStream get() = source

        /** Reads are legal only until this borrow is dropped. */
        fun isOpen(): Boolean = !released.get() && !closed.get()

        override fun close() {
            if (!released.compareAndSet(false, true)) return
            activeBorrows.decrementAndGet()
            releaseBorrowWaiter()
        }
    }

    /**
     * Begins one body read, positioned at byte 0. Returns null when the attempt is cancelled or closed:
     * a cancelled attempt must not start another replay (reviewer 3cb8574f, spec c12c569f).
     */
    fun borrowForBody(): Borrow? = synchronized(borrowLock) {
        if (cancelled || closed.get()) return null
        try {
            source.channel.position(0)
        } catch (failure: java.io.IOException) {
            return null
        }
        activeBorrows.incrementAndGet()
        Borrow()
    }

    /** True while any body writer is still reading. */
    fun hasActiveBorrows(): Boolean = activeBorrows.get() > 0

    /**
     * Refuses any FUTURE replay. The current borrow, if any, continues: cancellation must reach the socket
     * through the call itself, never by yanking the buffer away from a running writer.
     */
    fun cancelFurtherBorrows() {
        synchronized(borrowLock) { cancelled = true }
    }

    /** Waits for active body writers to exit. Returns false when any is still running at the deadline. */
    internal fun awaitBorrowsSettled(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (activeBorrows.get() > 0) {
            if (System.nanoTime() >= deadline) return false
            awaitBorrowSignal(remainingMs(deadline))
        }
        return true
    }

    /** Idempotent: the attempt-level finally may run more than once on some paths. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { source.close() }
    }

    fun isClosed(): Boolean = closed.get()

    private fun remainingMs(deadline: Long): Long = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)

    private fun awaitBorrowSignal(millis: Long) {
        borrowLock2.lock()
        try {
            borrowCondition.await(millis, java.util.concurrent.TimeUnit.MILLISECONDS)
        } finally {
            borrowLock2.unlock()
        }
    }

    private fun releaseBorrowWaiter() {
        borrowLock2.lock()
        try {
            borrowCondition.signalAll()
        } finally {
            borrowLock2.unlock()
        }
    }

    private val borrowLock2 = java.util.concurrent.locks.ReentrantLock()
    private val borrowCondition = borrowLock2.newCondition()
}
