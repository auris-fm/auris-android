package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import kotlin.math.min
import kotlin.math.pow

/**
 * One loop, oldest recording first.
 *
 * Backoff is kept in memory only: a restart forgets it and simply tries again, which is correct for
 * a queue whose worst case is one extra request. One recording never blocks the others - a failure
 * schedules that recording's next attempt and the loop moves on.
 *
 * Nothing is sent until this pass has established that sending it is still meaningful and still
 * permitted. Those checks live here rather than at publish time because the facts they read can
 * change while a recording sits in the queue: the account can change, sharing can be switched off,
 * and a recording can simply get old.
 */
class UploaderLoop(
    private val queue: FolderQueue,
    private val send: suspend (FolderQueue.Recording) -> Int?,
    private val refreshSignIn: suspend () -> Unit,
    /**
     * Establishes whether this recording may be sent right now, and why not when it may not.
     *
     * The answer is read fresh on every pass, never cached: the whole point is to notice that
     * something changed after the recording was queued.
     */
    private val gate: PreSendGate = PreSendGate.OPEN,
    /**
     * Abandons a request that is already on the wire.
     *
     * Deleting the folder is not enough when sharing turns off: the request has already been handed
     * to the socket, so only cancelling the call stops it from being delivered.
     */
    private val cancelInFlightOnce: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val baseBackoffMs: Long = DEFAULT_BASE_BACKOFF_MS,
    private val maxBackoffMs: Long = DEFAULT_MAX_BACKOFF_MS,
    private val log: (String) -> Unit = {},
) {
    /** When each capture may next be attempted. Memory only, by design. */
    private val nextAttemptAt = mutableMapOf<String, Long>()
    private val attemptCount = mutableMapOf<String, Int>()

    /** What one pass did, for tests and for a caller that wants to log it. */
    data class PassResult(
        val accepted: Int = 0,
        val deleted: Int = 0,
        val retried: Int = 0,
        val refreshed: Int = 0,
    )

    /**
     * Run one pass over everything that is due now.
     *
     * A recording whose backoff has not elapsed is skipped, not failed - so a stuck recording costs
     * one skip per pass and never delays the ones behind it.
     */
    suspend fun runOnce(): PassResult {
        var accepted = 0
        var deleted = 0
        var retried = 0
        var refreshed = 0
        val t = now()

        // Age is a property of the queue, not of one recording: settle it once per pass so an
        // expired recording cannot be sent even if it is first in line.
        queue.deleteExpired(t)

        for (recording in queue.recordings()) {
            // The gate is asked BEFORE the backoff skip. Waiting does not make an ineligible
            // recording eligible, so deferring its deletion to its next retry time would keep data
            // the user has already withdrawn sitting in the queue for no reason.
            // Deleted rather than retried: none of these conditions resolve by waiting, and a
            // recording that can never be sent would otherwise occupy a queue slot until it aged
            // out. Sharing being off is the user's current answer, so it is honoured immediately.
            when (val verdict = gate.verdictFor(recording)) {
                is GateVerdict.Refuse -> {
                    // Anything buffered for this recording must stop too, not just its folder.
                    cancelInFlightOnce()
                    log("upload: ${recording.captureId} not sent: ${verdict.reason}")
                    queue.delete(recording, verdict.reason)
                    clearBackoff(recording.captureId)
                    deleted++
                    continue
                }

                GateVerdict.Send -> Unit
            }

            // Only now does the retry schedule matter: this recording may be sent, but not yet.
            val dueAt = nextAttemptAt[recording.captureId] ?: 0L
            if (t < dueAt) {
                log("upload: ${recording.captureId} waiting to retry")
                continue
            }

            val status = runCatching { send(recording) }
                .onFailure { log("upload: request failed (${it.javaClass.simpleName})") }
                .getOrNull()

            // One line per attempt, so a loop that is running and a loop that is stuck can be told
            // apart from the outside. Without this the two are identical in the log.
            log("upload: ${recording.captureId} answered ${status ?: "no answer"}")

            when (AnswerTable.classify(status)) {
                Answer.Accepted -> {
                    queue.delete(recording, "accepted")
                    clearBackoff(recording.captureId)
                    accepted++
                }

                Answer.Delete -> {
                    queue.delete(recording, "the server refused it")
                    clearBackoff(recording.captureId)
                    deleted++
                }

                Answer.RefreshSignIn -> {
                    // A lapsed sign-in is not a refusal: refresh and try the SAME recording again.
                    runCatching { refreshSignIn() }
                        .onFailure { log("upload: sign-in refresh failed (${it.javaClass.simpleName})") }
                    scheduleRetry(recording.captureId, t)
                    refreshed++
                }

                Answer.Retry -> {
                    scheduleRetry(recording.captureId, t)
                    retried++
                }
            }
        }
        return PassResult(accepted, deleted, retried, refreshed)
    }

    /** How long until [captureId] may be attempted, or 0 when it is due. */
    // Abandons a request already on the wire, from outside the loop. While a call is active this
    // loop is suspended inside it and cannot reach its own gate to notice that sharing was turned
    // off, so whoever performs the stop calls this instead.
    fun cancelInFlight() = cancelInFlightOnce()

    fun backoffRemainingMs(captureId: String): Long = ((nextAttemptAt[captureId] ?: 0L) - now()).coerceAtLeast(0L)

    private fun scheduleRetry(captureId: String, t: Long) {
        val attempts = (attemptCount[captureId] ?: 0) + 1
        attemptCount[captureId] = attempts
        val delay = min(maxBackoffMs, (baseBackoffMs * 2.0.pow(attempts - 1)).toLong())
        nextAttemptAt[captureId] = t + delay
    }

    private fun clearBackoff(captureId: String) {
        nextAttemptAt.remove(captureId)
        attemptCount.remove(captureId)
    }

    companion object {
        const val DEFAULT_BASE_BACKOFF_MS = 60_000L
        const val DEFAULT_MAX_BACKOFF_MS = 60L * 60 * 1000
    }
}
