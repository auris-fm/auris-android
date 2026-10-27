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
     * Non-blocking teardown: invalidates on the caller's thread, drains and releases on [scope], and
     * reports whether ownership was actually released. Used from lifecycle callbacks, where waiting for
     * in-flight work could mean waiting on a network write.
     */
    fun closeAsync(
        scope: java.util.concurrent.ExecutorService,
        onUnresolved: (String) -> Unit = {},
    ): java.util.concurrent.Future<Boolean>

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
        // CLOSED IS TERMINAL. A late callback must not be able to take the OS lock again: isHeld() would
        // still report false (closed stays true), so the handle would hold the directory while reporting
        // that it does not - denying every legitimate owner that tries to take over.
        if (closed || closeInProgress) {
            reason = reason ?: "closed"
            return this
        }
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

    private fun isHeldLocked(): Boolean = !closed && lock?.isValid == true

    override fun unavailableReason(): String? = if (isHeld()) null else reason ?: "closed"

    /** Operations that passed the guard and have not finished; see [enter]/[leave]. */
    private val inFlight = java.util.concurrent.atomic.AtomicInteger()

    /** Monitors [inFlight] transitions so [close] can wait for the count to reach zero. */
    private val drained = java.util.concurrent.locks.ReentrantLock()
    private val drainSignal: java.util.concurrent.locks.Condition = drained.newCondition()

    /**
     * Admits ONE operation into the directory. Returns false when this handle may not touch it, which
     * includes the window during and after [close].
     *
     * The count is incremented BEFORE the liveness check and decremented by [leave], so a thread cannot
     * slip between "close saw zero in-flight" and "I registered myself".
     */
    fun enter(): Boolean {
        inFlight.incrementAndGet()
        // PER-THREAD DEPTH, not mere membership: an inner enter/leave pair must not remove the thread while
        // an outer lease is still held, or a later self-close would try to drain against itself again.
        leaseDepth.set((leaseDepth.get() ?: 0) + 1)
        if (isHeld()) return true
        leave()
        return false
    }

    fun leave() {
        val depth = (leaseDepth.get() ?: 1) - 1
        leaseDepth.set(depth)
        if (inFlight.decrementAndGet() == 0) {
            // THE LAST LEASE IS GONE: this is the only place a deferred self-close may actually release,
            // because there is no writer left to resurrect against a successor. The monitor is taken ONLY
            // here, briefly, and never while waiting - that is what keeps this from deadlocking.
            synchronized(this) {
                if (closeDeferred) {
                    closeDeferred = false
                    releaseLockLocked()
                }
            }
            drained.lock()
            try {
                drainSignal.signalAll()
            } finally {
                drained.unlock()
            }
        }
    }

    override fun close() {
        // PHASE 1 - MARK, UNDER THE MONITOR. Invalidate first so a delayed callback observing isHeld()==false
        // refuses before the directory becomes someone else's, and publish that a close is in progress.
        synchronized(this) {
            closed = true
            closeInProgress = true
        }
        // PHASE 2 - DRAIN WITHOUT THE MONITOR (reviewer 7f1bfeb0/487d6967). Holding it while waiting
        // deadlocks any leased operation that needs acquire() or releaseLock(): its lease keeps inFlight
        // above zero, so neither side can proceed. The drain must run outside the lifecycle lock.
        val callerIsLeased = (leaseDepth.get() ?: 0) > 0
        if (!callerIsLeased) awaitIdle()
        // PHASE 3 - FINALIZE UNDER THE MONITOR, as ONE consistent transition.
        synchronized(this) {
            closed = true
            if (callerIsLeased || inFlight.get() > 0) {
                // A self-close, or other leases still running: DEFER the release to the last leave().
                // Releasing now would hand the directory to a successor while a writer is still inside.
                closeDeferred = true
            } else {
                closeDeferred = false
                releaseLockLocked()
            }
        }
    }

    /**
     * INVALIDATE NOW, RELEASE LATER (spec c12c569f). Returns immediately after the state transition that
     * makes every late callback refuse; the drain and the OS release happen on [scope] and are reported
     * through the returned handle.
     *
     * The blocking [close] may not be called from a lifecycle callback: it waits for in-flight operations,
     * and an in-flight operation can be a network write, so waiting there would stall teardown on the
     * network. This variant never waits on the caller's thread.
     */
    override fun closeAsync(scope: java.util.concurrent.ExecutorService, onUnresolved: (String) -> Unit): java.util.concurrent.Future<Boolean> {
        // PHASE 1 on the CALLER's thread: invalidate first, so nothing new starts.
        synchronized(this) {
            closed = true
            closeInProgress = true
        }
        val callerIsLeased = (leaseDepth.get() ?: 0) > 0
        return scope.submit<Boolean> {
            try {
                if (!callerIsLeased) awaitIdle()
                synchronized(this) {
                    closed = true
                    if (callerIsLeased || inFlight.get() > 0) {
                        closeDeferred = true
                        false
                    } else {
                        closeDeferred = false
                        releaseLockLocked()
                        true
                    }
                }
            } catch (failure: Throwable) {
                // A drain that never completed leaves ownership UNRESOLVED. Report it; never claim clean.
                onUnresolved("drain withdrawn: ${failure.javaClass.simpleName}")
                false
            }
        }
    }

    /** Drops the OS lock; caller must hold the lifecycle monitor. */
    private fun releaseLockLocked() {
        runCatching { lock?.release() }
        runCatching { channel?.close() }
        lock = null
        channel = null
    }

    /** Per-thread lease nesting depth, so a self-close can recognise its OWN lease precisely. */
    private val leaseDepth = ThreadLocal.withInitial { 0 }

    /** Set when a self-close could not release because its own writer (or another) was still inside. */
    @Volatile
    private var closeDeferred = false

    /**
     * True from the moment close() begins. Read by [acquire] only, so a late callback cannot take the OS
     * lock once a teardown is under way. It does NOT make a second close a no-op: close() has no such guard,
     * and a second call re-runs the drain and may release again.
     */
    @Volatile
    private var closeInProgress = false

    /**
     * Waits until no operation holds a lease. MUST be called WITHOUT the lifecycle monitor held: a leased
     * operation that needs that monitor would otherwise keep inFlight above zero forever.
     */
    private fun awaitIdle() {
        drained.lock()
        try {
            while (inFlight.get() > 0) {
                // This poll does NOT bound the wait: the enclosing while re-checks and simply waits again.
                // It only lets the loop re-observe inFlight when the signal is missed, so a stuck writer
                // delays the handover indefinitely rather than failing it.
                drainSignal.await(DRAIN_POLL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        } finally {
            drained.unlock()
        }
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

        /** Re-observation interval inside the drain loop, not an overall teardown deadline. */
        private const val DRAIN_POLL_MS = 50L
    }
}

/** Test seam: an ownership that is never held, so refusal paths can be exercised deterministically. */
internal class UnavailableDirectoryOwnership(private val why: String) : DirectoryOwnership {
    override fun isHeld(): Boolean = false

    override fun unavailableReason(): String? = why

    override fun close() = Unit

    /** Nothing is held, so there is nothing to drain: already released. */
    override fun closeAsync(
        scope: java.util.concurrent.ExecutorService,
        onUnresolved: (String) -> Unit,
    ): java.util.concurrent.Future<Boolean> = java.util.concurrent.CompletableFuture.completedFuture(true)
}
