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
    /** Bounded re-sampling rounds; exhaustion is a failure, never a licence to release. */
    const val MAX_ROUNDS = 8

    sealed interface Verdict {
        /** Every owned job finished; the resources may be released. */
        data object Settled : Verdict

        /** The final stop threw, so the owned set is unknown: release nothing. */
        data class StopFailed(val error: Throwable) : Verdict

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
    ): Verdict {
        val stopResult = runCatching { stop() }
        stopResult.exceptionOrNull()?.let { return Verdict.StopFailed(it) }

        val joined = mutableSetOf<Job>()
        var round = 0
        while (round < maxRounds) {
            // Re-sample each round: joining is not atomic with the snapshot, and the engine may
            // register work faster than we drain it.
            unfinished().filterNot { it in joined }.forEach { job ->
                join(job)
                joined.add(job)
            }
            // Quiescence is "nothing UNFINISHED remains", not "the list is empty": the engine keeps
            // completed jobs listed until their completion callback prunes them.
            if (unfinished().none { !it.isCompleted }) return Verdict.Settled
            round += 1
        }
        // Rounds exhausted and work is STILL unfinished: this is the residual verdict, and it is
        // the only place it can be produced. (An earlier version had an unreachable tail that a
        // mutant walked straight through.)
        val residual = unfinished().count { !it.isCompleted }
        return Verdict.Residual(residual)
    }
}
