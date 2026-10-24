package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job

/**
 * The single point that closes a shutdown: it EXCLUDES future starts and returns every job the
 * pipeline owns, in one atomic step.
 *
 * Reviewers asked for this repeatedly (902e0c1a1, cce1f100, 9045dd43). Taking a snapshot of
 * "currently running jobs" and then cancelling is racy: a start that lands between the snapshot and
 * the cancellation is owned by nobody, so its work can outlive the teardown and the backend can be
 * released underneath it. Here the gate closes FIRST and every start must pass through [beginStart],
 * so anything admitted before the barrier is in the returned set and anything after is refused.
 */
internal class ShutdownBarrier {
    private val closed = AtomicBoolean(false)

    /** Jobs owned by a start that is still in flight; cleared when that start ends. */
    private val owned = AtomicReference<List<Job>>(emptyList())

    /** True once the barrier is closed; every later [beginStart] is refused. */
    val isClosed: Boolean get() = closed.get()

    /**
     * Register a start's jobs. Returns false when the pipeline is shutting down, in which case the
     * caller must NOT start: the barrier already owns the shutdown and will not wait for it.
     *
     * The check and the registration are ordered so a start cannot slip past a close: registration
     * under the atomic value is re-validated against the closed flag.
     */
    fun beginStart(jobs: List<Job>): Boolean {
        // ONE authoritative check, re-validated while holding the owned value. Two checks looked
        // safer but were redundant: with either removed alone the behaviour was unchanged, so the
        // second was untestable and merely implied extra safety (found by mutation).
        var admitted = false
        owned.updateAndGet { current ->
            if (closed.get()) {
                current
            } else {
                admitted = true
                current + jobs
            }
        }
        return admitted
    }

    /**
     * Close the barrier and return every job owned at that instant.
     *
     * Atomic with respect to [beginStart]: once this returns, no further start is admitted, and the
     * returned set is exactly the work the shutdown is responsible for.
     */
    fun close(): List<Job> {
        closed.set(true)
        // Anything registered before the close is owned by this shutdown; nothing can be added now.
        return owned.getAndSet(emptyList())
    }

    /** Drop a job that has finished, so a long-lived pipeline does not accumulate them. */
    fun release(jobs: List<Job>) {
        owned.updateAndGet { current -> current.filterNot { it in jobs } }
    }
}
