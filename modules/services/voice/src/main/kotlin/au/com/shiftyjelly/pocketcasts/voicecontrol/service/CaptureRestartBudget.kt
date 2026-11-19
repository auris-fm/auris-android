package au.com.shiftyjelly.pocketcasts.voicecontrol.service

/**
 * The restart rule of `voice-control-core.md` > Automatic capture restarts: capture that ends on
 * its own while listening is still allowed gets ONE automatic restart through the same start path.
 * If that restart fails to start, or the restarted capture ends again within
 * [healthyCaptureMs], automatic restarts are withheld until a real trigger re-arms the budget
 * (the set of available devices changes, a blocker clears, or the user turns voice control on
 * again — see [rearm]). Capture that ran [healthyCaptureMs] or longer is working, so its next
 * self-end gets a fresh restart. A user-driven start always clears the rule ([reset]).
 *
 * Gates automatic restarts only: an acquisition attempt driven by a trigger consults an
 * already-re-armed budget. Consulted at the shared acquisition and start decisions, not only
 * where a restart is scheduled: releasing routing fires selection callbacks that re-enter those
 * paths on their own.
 */
internal class CaptureRestartBudget(
    private val healthyCaptureMs: Long = 60_000L,
) {
    private var restartAvailable = true

    /** True when the restart granted for the last self-ended capture has not been spent yet. */
    private var restartInFlight = false

    /** True when an automatic capture (re)start may begin. */
    fun allows(): Boolean = restartAvailable

    /** Record that the granted automatic restart is beginning (it is spent even if it fails). */
    fun onAutomaticRestart() {
        restartAvailable = false
        restartInFlight = true
    }

    /** Record an unexpected end of capture that had been running for [ranForMs]. */
    fun onTerminated(ranForMs: Long) {
        if (ranForMs >= healthyCaptureMs) {
            // Healthy capture: its next self-end gets a fresh automatic restart.
            restartAvailable = true
            restartInFlight = false
        } else if (restartInFlight) {
            // The restart itself died fast: withhold until a real trigger re-arms.
            restartAvailable = false
            restartInFlight = false
        }
        // A fast death of a NORMAL capture leaves the credit intact: it still gets its restart.
    }

    /** A real trigger — device change, blocker clearing, user re-enable — re-arms restarts. */
    fun rearm() {
        restartAvailable = true
        restartInFlight = false
    }

    /** Clear the rule for a user-driven start. */
    fun reset() {
        restartAvailable = true
        restartInFlight = false
    }
}
