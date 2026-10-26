package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * THE DIRECTORY-OWNERSHIP EXCLUSION (spec c8f766fb): one live owner per queue directory, enforced by the
 * OS rather than by a monitor.
 *
 * A Kotlin monitor cannot fence two processes, and it cannot even fence two store instances in one process:
 * every `ContributionStore` constructs its own [QueueOwnership], so sharing today is an accident of Hilt
 * `@Singleton` scoping. An exclusive [FileLock] on `<root>/.owner.lock` is the narrowest primitive that
 * actually excludes publishers, recovery and purge from each other, across processes and instances alike.
 *
 * LIFETIME. The lock is acquired once at store construction and held for the owner's lifetime, because the
 * thing being excluded is *ownership of the directory*, not any one mutation. It is NEVER held across network
 * work: the store performs no I/O beyond its own queue, and uploads run outside it.
 *
 * RELEASE. Two paths only:
 *  - [close] — atomically invalidates this handle, refuses every later operation, then releases. A delayed
 *    callback into a closed store refuses rather than mutating a directory someone else may now own.
 *  - process death — the kernel drops the lock, so a fresh process can acquire it and inspect prior-process
 *    entries safely. There is deliberately no stale-lock reclamation: there is nothing to reclaim.
 *
 * NO SILENT FALLBACK. A store that cannot acquire is UNAVAILABLE, never quietly unguarded. The failure is
 * retained for diagnosis ([unavailableReason]) because a queue that silently stopped working is worse than
 * one that says why.
 */
interface DirectoryOwnership {
    /** True once this handle has validly acquired the directory and has not been closed. */
    fun isHeld(): Boolean

    /** Why acquisition failed, for diagnosis; null while held. Never used to decide correctness. */
    fun unavailableReason(): String?

    /**
     * Releases the directory. Idempotent. After this returns the handle is INVALIDATED first (so no new
     * operation can pass) and the OS lock is dropped last.
     */
    fun close()
}

/**
 * The OS-backed implementation: `tryLock()` so a competing owner FAILS FAST instead of blocking a caller
 * for an unbounded time, and so "another owner exists" is a state this process can report.
 *
 * Acquisition is LAZY and happens on the first operation that would actually touch the directory, because
 * a queue object may be constructed only to read pure limits arithmetic ([ContributionStore.projectedBytesFor]).
 * Such an instance must not be able to quietly take the directory away from the owner actually using it.
 */
class FileDirectoryOwnership(root: File) : DirectoryOwnership {
    /** The queue root; the lock file is its SIBLING so it never appears in the queue's own listings. */
    private val lockFile = File(root.parentFile ?: root, "${root.name}$OWNER_LOCK_SUFFIX")

    @Volatile
    private var closed = false

    @Volatile
    private var reason: String? = null

    private var channel: RandomAccessFile? = null
    private var lock: FileLock? = null

    /** Acquires exclusively, or records why it could not. Never throws for a contested directory. */
    @Synchronized
    fun acquire(): DirectoryOwnership {
        if (lock != null || reason != null) return this
        try {
            lockFile.parentFile?.mkdirs()
            val raf = RandomAccessFile(lockFile, "rw")
            val acquired = try {
                raf.channel.tryLock()
            } catch (overlapping: OverlappingFileLockException) {
                // Same JVM, different instance: tryLock reports an overlap rather than returning null.
                null
            } catch (io: IOException) {
                raf.close()
                reason = "io:${io.javaClass.simpleName}"
                return this
            }
            if (acquired == null) {
                raf.close()
                reason = "held-by-another-owner"
                return this
            }
            channel = raf
            lock = acquired
            reason = null
        } catch (io: IOException) {
            reason = "io:${io.javaClass.simpleName}"
        }
        return this
    }

    override fun isHeld(): Boolean = !closed && lock?.isValid == true

    override fun unavailableReason(): String? = if (isHeld()) null else reason ?: "closed"

    override fun close() {
        // INVALIDATE FIRST: a delayed callback observing isHeld()==false must refuse before the directory
        // becomes someone else's. Releasing first would leave a window in which a stale writer passes the
        // guard and mutates a directory a new owner already holds.
        closed = true
        runCatching { lock?.release() }
        runCatching { channel?.close() }
        lock = null
        channel = null
    }

    companion object {
        /**
         * The lock file sits BESIDE the queue directory, not inside it. The queue directory is enumerated
         * by coverage, recovery and the purge sweep, all of which classify unknown names as retained
         * debris - a lock file in there would be charged as an owned capture and, with a small record
         * budget, consume the one slot the queue exists to hand out. It travels with the data because its
         * location is derived from the same root.
         */
        const val OWNER_LOCK_SUFFIX = ".owner.lock"
    }
}

/** Test seam: an ownership that is never held, so refusal paths can be exercised deterministically. */
internal class UnavailableDirectoryOwnership(private val why: String) : DirectoryOwnership {
    override fun isHeld(): Boolean = false

    override fun unavailableReason(): String? = why

    override fun close() = Unit
}
