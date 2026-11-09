package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue2

import kotlin.math.min
import kotlin.math.pow

/**
 * One loop, oldest recording first.
 *
 * Backoff is kept in memory only: a restart forgets it and simply tries again, which is correct for
 * a queue whose worst case is one extra request. One recording never blocks the others - a failure
 * schedules that recording's next attempt and the loop moves on.
 */
class UploaderLoop(
    private val queue: FolderQueue,
    private val send: suspend (FolderQueue.Recording) -> Int?,
    private val refreshSignIn: suspend () -> Unit,
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
        for (recording in queue.recordings()) {
            val dueAt = nextAttemptAt[recording.captureId] ?: 0L
            if (t < dueAt) continue

            val status = runCatching { send(recording) }
                .onFailure { log("upload: request failed (${it.javaClass.simpleName})") }
                .getOrNull()

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
