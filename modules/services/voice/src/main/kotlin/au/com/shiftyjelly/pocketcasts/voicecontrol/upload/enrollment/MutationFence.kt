package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes membership mutations and fences every result against the attempt that produced it
 * (reviewer 9156a8ea point 2).
 *
 * THE TWO DEFECTS THIS CLOSES:
 *
 * 1. **Concurrent mutations.** Two taps could run an enroll and a withdraw simultaneously; whichever
 *    returned last won the displayed state, regardless of which the user meant most recently.
 * 2. **Stale results applied.** A slow enrollment returning after the user already turned sharing
 *    off could set the UI back to "sharing on", and an account switch mid-flight could apply one
 *    account's result to another's screen.
 *
 * THE RULE: every operation is tagged with a monotonically increasing generation and the owner
 * session it was issued under. A result is applied ONLY if both still match at apply time. Anything
 * else is dropped — silently, because a superseded result is not an error, it is simply no longer
 * the truth.
 *
 * CRITICAL ASYMMETRY, and the reason this is not a plain mutex: **an Off/withdrawal must never wait
 * behind an in-flight enrollment.** A user asking to stop is not a queued request; the local stop is
 * recorded immediately by the caller, and the pending enrollment is SUPERSEDED (its generation is
 * invalidated) rather than being allowed to finish and win. Serialization here orders the SERVER
 * calls; it does not order the user's right to stop behind them.
 */
class MutationFence {

    private val mutex = Mutex()

    /** Bumped on every supersession: session change, or an off action while work is in flight. */
    private var generation: Long = 0L

    /** The owner session all in-flight work is bound to. */
    private var boundSession: Long? = null

    /**
     * Issues an attempt under the current generation and session.
     *
     * NOTE the binding is NOT updated here. An earlier version re-bound on every issue, which made
     * the session check pass for a result issued under the OLD account once anything was issued under
     * the new one — the cross-account leak this fence exists to prevent. The binding is established
     * for a generation and only moves when the generation is superseded.
     */
    fun issue(ownerSession: Long): Attempt {
        val generationAtIssue = synchronized(this) {
            if (boundSession == null || boundSession != ownerSession) {
                // First use of a session, or a session change: this generation belongs to it. A result
                // issued under a superseded generation still carries the OLD session and is dropped.
                generation += 1
                boundSession = ownerSession
            }
            generation
        }
        return Attempt(generationAtIssue, ownerSession)
    }

    /**
     * Drops everything in flight. Called on account/session change and — crucially — when the user
     * turns sharing off, so a slow enrollment cannot later restore the UI to "on".
     */
    fun supersede() = synchronized(this) {
        generation += 1
    }

    /**
     * Atomically invalidates every EARLIER attempt and issues a fresh one for [ownerSession].
     *
     * This exists because the two steps cannot be done separately: superseding after issuing kills
     * the new attempt too, and issuing after superseding leaves a window in which an older in-flight
     * result is still "current". Returns the attempt that is authoritative from this moment.
     */
    fun supersedeAndIssue(ownerSession: Long): Attempt = synchronized(this) {
        generation += 1
        boundSession = ownerSession
        Attempt(generation, ownerSession)
    }

    /**
     * True when [attempt] is still the current attempt for the current owner session.
     *
     * A session change is detected HERE, at apply time, not only when a new attempt is issued: the
     * account can switch with nothing else happening on this screen, and in that case no later
     * [issue] ever runs to notice. Detecting it on check keeps the fence correct whether or not
     * another mutation follows.
     */
    fun isCurrent(attempt: Attempt, liveSession: () -> Long): Boolean = synchronized(this) {
        val live = liveSession()
        // The account changed while this was in flight: the result belongs to somebody else's screen.
        if (attempt.ownerSession != live) {
            generation += 1
            boundSession = live
            return false
        }
        attempt.generation == generation && boundSession == live
    }

    /** Runs SERVER work one at a time; the caller decides what to do with a superseded result. */
    suspend fun <T> serialize(block: suspend () -> T): T = mutex.withLock { block() }

    /** Identifies one issued attempt: its generation plus the owner session it belongs to. */
    data class Attempt(val generation: Long, val ownerSession: Long)
}
