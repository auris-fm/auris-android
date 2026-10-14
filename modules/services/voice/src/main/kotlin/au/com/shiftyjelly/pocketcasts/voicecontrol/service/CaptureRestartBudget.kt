package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import android.os.SystemClock

/**
 * Budget for automatically restarting capture after it ended on its own.
 *
 * A capture that keeps ending must not be reopened forever, and a just-ended capture must not be
 * reopened before the audio system settles. The budget is consulted at every shared acquisition and
 * start decision, not only where a restart is scheduled: releasing routing fires selection callbacks
 * that re-enter those paths on their own, so a schedule-only check is bypassed.
 */
internal class CaptureRestartBudget(
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    /** How long after an unexpected end before a restart may begin, so the audio system settles. */
    val settleDelayMs: Long = 500L,
    private val windowMs: Long = 5_000L,
    private val maxConsecutiveRestarts: Int = 3,
    private val cooldownMs: Long = 30_000L,
) {
    private var consecutiveRestarts = 0
    private var lastTerminationMs = 0L
    private var blockedUntilMs = 0L

    /**
     * Record an unexpected end of capture and return whether a restart is still allowed. More than
     * [maxConsecutiveRestarts] endings inside [windowMs] block restarts for [cooldownMs].
     */
    fun onTerminated(): Boolean {
        val now = nowMs()
        consecutiveRestarts =
            if (now - lastTerminationMs < windowMs) consecutiveRestarts + 1 else 1
        lastTerminationMs = now
        if (consecutiveRestarts > maxConsecutiveRestarts) {
            blockedUntilMs = now + cooldownMs
        }
        return allows()
    }

    /** True when an automatic capture (re)start may begin now. */
    fun allows(): Boolean {
        val now = nowMs()
        if (now < blockedUntilMs) return false
        // No termination yet: a first start is always allowed. Otherwise wait for the settle delay.
        return lastTerminationMs == 0L || now - lastTerminationMs >= settleDelayMs
    }

    /** Clear the budget for a user-driven start. */
    fun reset() {
        consecutiveRestarts = 0
        lastTerminationMs = 0L
        blockedUntilMs = 0L
    }
}
