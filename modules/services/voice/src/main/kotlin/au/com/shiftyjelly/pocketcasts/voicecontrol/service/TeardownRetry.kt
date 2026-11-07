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
        unresolvedHold: Any? = null,
        // A JOB THAT IS GONE MAY HAVE FAILED ON ITS WAY OUT (reviewer 3ecf1db0). The initial
        // teardown classifies every owned job's finish; the retry only counted unfinished jobs, so a
        // processing job that completed ABNORMALLY after a residual hold looked indistinguishable
        // from one that settled cleanly, and the retry released. This callback is the SAME production
        // predicate the initial teardown uses, applied to the jobs that were owned when the hold
        // began. Returning non-null means "this job did not fail"; null means clean.
        abnormalFinish: (Job) -> Throwable? = { null },
        // The jobs that existed when the hold was taken. Needed because a job removed from the live
        // list cannot be re-examined from it.
        heldJobs: () -> List<Job> = { emptyList() },
    ): Decision = when {
        !observe -> Decision.Unproven(IllegalStateException("retry requires continued observation"))

        // A RECORDED HOLD BLOCKS THE RETRY EVEN WHEN THE LIST LOOKS EMPTY (reviewer 9f873b94).
        // Deciding on list emptiness alone let a retry release resources after a FAILED STOP or an
        // abnormal engine finish: the jobs were gone from the list, but the reason the teardown held
        // had never been resolved. Only a hold that is about quiescence can be cleared by observing
        // quiescence; anything else has to be re-established by the caller.
        else -> {
            val unfinished = runCatching { recheck().count { !it.isCompleted } }.getOrElse {
                return Decision.Unproven(it)
            }
            when {
                unfinished > 0 -> Decision.StillHeld(unfinished)

                unresolvedHold != null -> Decision.Unproven(
                    IllegalStateException("retry cannot clear an unresolved hold: $unresolvedHold"),
                )

                // AN EMPTY LIST IS NOT PROOF OF A CLEAN FINISH. Now that nothing is unfinished, ask
                // the production predicate what actually happened to the jobs the hold covered. A
                // late failure must not be mistaken for quiescence.
                else -> {
                    val failed = runCatching { heldJobs().firstNotNullOfOrNull(abnormalFinish) }
                        .getOrElse { return Decision.Unproven(it) }
                    if (failed != null) {
                        Decision.Unproven(failed)
                    } else {
                        Decision.Quiescent
                    }
                }
            }
        }
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
