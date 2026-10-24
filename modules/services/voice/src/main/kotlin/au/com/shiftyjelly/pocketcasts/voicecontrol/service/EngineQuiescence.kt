package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import kotlinx.coroutines.Job

/**
 * Decides whether the engine has actually settled before its resources may be released.
 *
 * This is deliberately a pure value type rather than inline logic in [VoiceControlService]: the
 * teardown's safety property — "do not release what may still be in use" — has to be *executable*,
 * not just readable in the service's source text (reviewer 9294defb).
 */
internal object EngineQuiescence {
    /**
     * The cause of an abnormal finish, or null for a clean one.
     *
     * `Job.join()` returns normally even when the job FAILED (verified by a one-off probe: 1.11.0), so completion alone is not proof of a healthy finish. The caller supplies the cause
     * because `getCompletionExceptionOrNull` is not public API in this coroutines version; a
     * cancelled job is not abnormal, since the teardown itself cancels these jobs.
     */
    private fun abnormalCause(job: Job, completionCause: (Job) -> Throwable?): Throwable? = if (!job.isCompleted) null else completionCause(job)

    /** Bounded re-sampling rounds; exhaustion is a failure, never a licence to release. */
    const val MAX_ROUNDS = 8

    sealed interface Verdict {
        /** Every owned job finished; the resources may be released. */
        data object Settled : Verdict

        /** The final stop threw, so the owned set is unknown: release nothing. */
        data class StopFailed(val error: Throwable) : Verdict

        /** Waiting for owned work threw: it has not been proven finished, so release nothing. */
        data class JoinFailed(val error: Throwable) : Verdict

        /**
         * A job finished ABNORMALLY. `Job.join()` returns normally even when the job threw, so
         * completion alone is not proof of a healthy finish (verified by a one-off probe: coroutines 1.11.0).
         * The caller supplies the cause, because Job exposes no public failure-cause accessor in this
         * coroutines version. Release nothing.
         */
        data class AbnormallyFinished(val error: Throwable) : Verdict

        /** Work kept appearing past the round bound: release nothing. */
        data class Residual(val remaining: Int) : Verdict
    }

    /**
     * Joins [pending] for up to [MAX_ROUNDS] rounds, re-sampling after each round because joining is
     * not atomic with a snapshot. Returns a verdict; only [Verdict.Settled] permits release.
     *
     * [stop] is invoked once, first; its failure yields [Verdict.StopFailed]. [unfinished] is the
     * current owned-job set and [join] waits for one job.
     */
    suspend fun settle(
        stop: () -> Unit,
        unfinished: () -> List<Job>,
        join: suspend (Job) -> Unit,
        maxRounds: Int = MAX_ROUNDS,
        completionCause: (Job) -> Throwable? = { null },
    ): Verdict {
        val stopResult = runCatching { stop() }
        stopResult.exceptionOrNull()?.let { return Verdict.StopFailed(it) }

        val joined = mutableSetOf<Job>()
        var round = 0
        while (round < maxRounds) {
            // Re-sample each round: joining is not atomic with the snapshot, and the engine may
            // register work faster than we drain it.
            unfinished().filterNot { it in joined }.forEach { job ->
                // A THROWING JOIN IS NOT PROOF OF COMPLETION (reviewer bdb914a6): it must become a
                // verdict, not an exception escaping into whatever handles the caller. Only a
                // returned join proves the job finished.
                val wait = runCatching { join(job) }
                wait.exceptionOrNull()?.let { return Verdict.JoinFailed(it) }
                joined.add(job)
            }
            // Quiescence is "nothing UNFINISHED remains", not "the list is empty": the engine keeps
            // completed jobs listed until their completion callback prunes them.
            if (unfinished().none { !it.isCompleted }) {
                // COMPLETION IS NOT SUCCESS: a failed job joins cleanly. Inspect the cause before
                // calling the engine settled.
                val abnormal = unfinished()
                    .firstNotNullOfOrNull { job -> abnormalCause(job, completionCause) }
                return if (abnormal == null) Verdict.Settled else Verdict.AbnormallyFinished(abnormal)
            }
            round += 1
        }
        // Rounds exhausted and work is STILL unfinished: this is the residual verdict, and it is
        // the only place it can be produced. (An earlier version had an unreachable tail that a
        // mutant walked straight through.)
        val residual = unfinished().count { !it.isCompleted }
        return Verdict.Residual(residual)
    }
}

/**
 * Why a teardown declined to release the engine's dependent resources.
 *
 * Held resources are not a clean teardown: the audio stack may still be owned, and a caller decides
 * whether to retry. Exposed so completion can be truthful about which happened.
 */
internal data class TeardownFailure(val verdict: EngineQuiescence.Verdict) {
    /** Human-readable reason, suitable for logs and status text. */
    val reason: String get() = verdict.toString()
}
