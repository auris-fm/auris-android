package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The uploader's wake source: the queue is drained by
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
            // THREE cases, not two: null means no deadline (sleep on events);
            // a delay > 0 arms exactly one timer at the earliest retry time; and a delay <= 0
            // means the deadline is ALREADY exhausted — UploaderLoop schedules from pass start,
            // so a slow failed send can burn the whole backoff before the pass returns — and a
            // due retry must run promptly, not wait for another event to happen along.
            if (dueAtMs != null && dueAtMs - nowMs() <= 0) {
                // Due retry: another pass, immediately.
                continue
            }
            when {
                dueAtMs == null -> wakes.receive()
                else -> withTimeoutOrNull(dueAtMs - nowMs()) { wakes.receive() }
            }
        }
    }
}
