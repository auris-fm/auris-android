package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import android.os.SystemClock

/**
 * Rate limiter for automatically restarting capture after it ended on its own.
 *
 * More than [maxConsecutiveRestarts] endings inside [windowMs] withhold restarts until a full
 * window passes without another one. There is no scheduled retry: the next restart happens on the
 * next legitimate trigger (a gate or route emission), which keeps a persistently disconnecting
 * stream from looping while still letting a genuinely new reason to listen start capture.
 *
 * Consulted at the shared acquisition and start decisions, not only where a restart is scheduled:
 * releasing routing fires selection callbacks that re-enter those paths on their own.
 */
internal class CaptureRestartBudget(
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val windowMs: Long = 5_000L,
    private val maxConsecutiveRestarts: Int = 3,
) {
    private var consecutiveRestarts = 0
    private var lastTerminationMs = 0L

    /** Record an unexpected end of capture. */
    fun onTerminated() {
        val now = nowMs()
        consecutiveRestarts =
            if (now - lastTerminationMs < windowMs) consecutiveRestarts + 1 else 1
        lastTerminationMs = now
    }

    /** True when an automatic capture (re)start may begin now. */
    fun allows(): Boolean {
        val now = nowMs()
        if (consecutiveRestarts <= maxConsecutiveRestarts) return true
        // The limiter lifts once a full window passes without another ending.
        if (now - lastTerminationMs >= windowMs) {
            consecutiveRestarts = 0
            return true
        }
        return false
    }

    /** Clear the budget for a user-driven start. */
    fun reset() {
        consecutiveRestarts = 0
        lastTerminationMs = 0L
    }
}
