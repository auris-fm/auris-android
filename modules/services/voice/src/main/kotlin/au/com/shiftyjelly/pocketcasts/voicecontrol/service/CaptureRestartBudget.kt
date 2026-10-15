package au.com.shiftyjelly.pocketcasts.voicecontrol.service

/**
 * Limits automatically restarting capture after it ended on its own.
 *
 * At most [maxAutomaticRestarts] automatic restarts are allowed per listening session; after that
 * automatic restarts are refused until a user-driven start clears the count. A total cap rather
 * than a rolling rate on purpose: a rolling count still permits an unbounded number of restarts
 * over a long session, which is the loop this guards against.
 *
 * Consulted at the shared acquisition and start decisions, not only where a restart is scheduled:
 * releasing routing fires selection callbacks that re-enter those paths on their own.
 */
internal class CaptureRestartBudget(
    private val maxAutomaticRestarts: Int = 3,
) {
    private var restarts = 0

    /** Record an unexpected end of capture. */
    fun onTerminated() {
        restarts += 1
    }

    /** True when an automatic capture (re)start may begin. */
    fun allows(): Boolean = restarts <= maxAutomaticRestarts

    /** Clear the count for a user-driven start. */
    fun reset() {
        restarts = 0
    }
}
