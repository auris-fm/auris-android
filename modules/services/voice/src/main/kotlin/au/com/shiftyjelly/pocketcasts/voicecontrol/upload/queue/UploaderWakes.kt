package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The uploader's wake source (spec ruling, reviewer simplification S4): the queue is drained by
 * EVENTS, not by polling. One worker runs a pass, then sleeps until either
 *
 * - an event wake arrives — [wake], called from the producers the spec names (a recording was
 *   published, sign-in was restored, the app came to the foreground, the network came back), or
 * - the single retry timer fires — armed to the EARLIEST retry time the loop's backoff maps
 *   hold, so a retryable answer schedules exactly one timer, never a repeating poll.
 *
 * There is deliberately no scheduler next to this loop and no periodic tick: a pass costs one
 * queue read against an empty folder list when nothing happened, and the event wakes cover every
 * instant new work can appear.
 */
class UploaderWakes(
    /** The earliest retry deadline currently scheduled, in the same clock as [nowMs]; null when none. */
    private val earliestRetryAtMs: () -> Long?,
    /** The loop's clock, so the timer is computed against the same time the backoff used. */
    private val nowMs: () -> Long,
    /** One drain pass; may suspend (the loop runs it in the worker coroutine). */
    private val pass: suspend () -> Unit,
) {
    private val wakes = Channel<Unit>(Channel.CONFLATED)

    /** Signal that new work may exist. Conflated: N signals pending = one drain, correctly. */
    fun wake() {
        wakes.trySend(Unit)
    }

    /** Runs the drain loop in [scope]; one instance per process. */
    fun startPass(scope: CoroutineScope): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        while (isActive) {
            runCatching { pass() }
            val dueAtMs = earliestRetryAtMs()
            val delayMs = dueAtMs?.let { it - nowMs() }?.takeIf { it > 0 }
            if (delayMs == null) {
                wakes.receive()
            } else {
                // Exactly one timer, at the earliest retry time; a wake during the window
                // short-circuits it.
                withTimeoutOrNull(delayMs) { wakes.receive() }
            }
        }
    }
}
