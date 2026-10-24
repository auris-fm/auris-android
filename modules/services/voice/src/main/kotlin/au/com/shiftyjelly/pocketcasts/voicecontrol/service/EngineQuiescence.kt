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
     * `Job.join()` returns normally even when the job FAILED (verified by a one-off probe on
     * coroutines 1.11.0), so completion alone is not proof of a healthy finish. The caller supplies
     * the cause because Job exposes no public failure-cause accessor in this version.
     */
    private fun abnormalCause(job: Job, completionCause: (Job) -> Throwable?): Throwable? = if (!job.isCompleted) null else completionCause(job)

    /**
     * The cause when an engine job FAILED, or null when its end was expected.
     *
     * The teardown cancels these jobs deliberately, so a cancellation the ENGINE initiated is
     * expected shutdown and must not be reported as a held failure (reviewer 47a3a267). A job that
     * finished without cancellation is clean. Because Job exposes no public failure-cause accessor
     * in this coroutines version, a cancellation the engine did not initiate stays conservative:
     * it is treated as a failure rather than claimed as success.
     */
    fun abnormalEngineFinish(
        job: Job,
        cancelledByEngine: Set<Job>,
        recordedCause: Throwable? = null,
        expectedCancellation: Set<Job> = emptySet(),
    ): Throwable? {
        // AN EXPECTED CANCELLATION IS NOT A FAILURE — BUT ONLY THE CANCELLATION ITSELF IS EXEMPT
        // (reviewers 51baa020, 9f873b94). This used to exempt the job unconditionally, which hid a
        // genuine failure: a cleanup path that threw a RuntimeException AFTER we cancelled it had
        // its throwable recorded, and the early return discarded it. Exemption is now literal —
        // the recorded cause must actually BE a cancellation, and a cancellation wrapping a real
        // cause is not one. Anything else keeps its verdict.
        if (job in expectedCancellation && recordedCause.isCancellationLike()) return null
        // A RECORDED FAILURE WINS (reviewer 1ff17ee6): a job that had already failed before the stop
        // must keep its own failure, even though the teardown also referenced it.
        recordedCause?.let { cause ->
            // A cancellation WRAPPING a real cause is not an exemption: report the real cause, so a
            // failure thrown during cleanup is visible as itself rather than as "cancelled".
            if (cause is kotlinx.coroutines.CancellationException) return cause.cause
            return cause
        }
        if (!job.isCancelled) return null
        return if (job in cancelledByEngine) null else ABANDONED_CANCELLATION
    }

    /**
     * True only for a cause that is a cancellation and carries nothing else.
     *
     * A `CancellationException` with a non-null cause is a wrapper: the real throwable is inside it,
     * so it is NOT exempt. Keeping the test in one place means the exemption cannot quietly widen.
     */
    private fun Throwable?.isCancellationLike(): Boolean = this is kotlinx.coroutines.CancellationException && cause == null

    /** Cause reported for a cancellation the engine did not initiate. */
    val ABANDONED_CANCELLATION: Throwable = IllegalStateException("engine job finished cancelled by someone else")

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
        data class Residual(val remaining: Int, val error: Throwable? = null) : Verdict
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
                // COMPLETION IS NOT SUCCESS (reviewer 47a3a267): a failed job joins cleanly, so the
                // outcomes are inspected. Judge from the CAPTURED jobs, never by re-reading the live
                // list — pruning means the same shutdown could otherwise be classified Held or
                // Settled purely on prune timing.
                val abnormal = joined.firstNotNullOfOrNull { job -> abnormalCause(job, completionCause) }
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

/**
 * How a teardown ended.
 *
 * Returned rather than thrown: the teardown runs in an unrouted coroutine, so an exception would be
 * uncaught and could take the process down (reviewer 3104ba44). A [Held] result means the engine
 * could not be proven settled and the dependent resources were deliberately NOT released.
 */
internal sealed interface TeardownResult {
    data object Released : TeardownResult

    data class Held(val failure: TeardownFailure) : TeardownResult
}
