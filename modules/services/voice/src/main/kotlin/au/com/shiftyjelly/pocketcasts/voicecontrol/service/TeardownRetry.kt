package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import kotlinx.coroutines.Job

/**
 * The one owner of a held teardown, and the decisions a retry needs.
 *
 * A teardown that could not prove the engine settled deliberately does NOT release its resources.
 * Retrying it is not an age-based timeout (spec d885d207): the same owner rechecks the CURRENT held
 * state, keeps observing the unfinished jobs, and releases only after verified quiescence. It also
 * remembers which releases already ran, so a retry can never repeat destructive cleanup.
 *
 * Held is exposed until the owner confirms completion. It is never silently converted to Released,
 * and startup is never reopened on the strength of a held teardown.
 */
internal object TeardownRetry {
    /**
     * What a retry is allowed to do next.
     *
     * Every case is produced by exactly one condition, so a control can execute each of them.
     */
    sealed interface Decision {
        /** Re-checked and quiet: the remaining releases may run. */
        data object Quiescent : Decision

        /** Work is still unfinished: keep observing, releasing nothing. */
        data class StillHeld(val unfinished: Int) : Decision

        /** The recheck could not prove anything: stay held rather than claim success. */
        data class Unproven(val error: Throwable) : Decision
    }

    /**
     * Decide whether a held teardown may now release.
     *
     * [observe] is re-run against the CURRENT state — never a value captured when the hold began,
     * which would just replay the reason for the hold. [remaining] is the finished/released split so
     * a retry skips cleanup that already happened.
     */
    fun decide(
        recheck: () -> List<Job>,
        observe: Boolean = true,
    ): Decision = if (!observe) {
        Decision.Unproven(IllegalStateException("retry requires continued observation"))
    } else {
        val unfinished = runCatching { recheck().count { !it.isCompleted } }.getOrElse {
            return Decision.Unproven(it)
        }
        if (unfinished == 0) Decision.Quiescent else Decision.StillHeld(unfinished)
    }

    /**
     * The releases that have not yet completed.
     *
     * [completed] is the durable record of what already ran; a retry must SKIP those, because a
     * repeated release of a media session or renderer is destructive rather than merely redundant.
     */
    fun pendingReleases(all: List<String>, completed: Set<String>): List<String> = all.filterNot { it in completed }

    /** True only when every release in [all] is recorded as completed. */
    fun fullyReleased(all: List<String>, completed: Set<String>): Boolean = pendingReleases(all, completed).isEmpty()
}
