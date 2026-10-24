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
            // EVERY issue takes a fresh generation, including within one session. An earlier version
            // reused the generation for successive same-session attempts (reviewer d47d2326), so two
            // queued enrolls shared one generation and the OLDER response still looked current once
            // it returned - the newer intent lost to the older one even though both were serialized.
            //
            // The newest issue is authoritative by definition: it is the latest thing the user asked
            // for. A result from any earlier issue is stale regardless of session.
            generation += 1
            boundSession = ownerSession
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

    /**
     * The currently authoritative attempt WITHOUT issuing or superseding anything.
     *
     * Used only to label a locally-refused outcome with the action that produced it, so Settings can
     * still fence the side effect to that action. Returning null when no attempt has been issued is
     * correct: there is then no action whose side effects could apply.
     */
    fun peek(): Attempt? = synchronized(this) {
        Attempt(generation, boundSession ?: return null)
    }

    /**
     * Runs [effect] only if [attempt] is still authoritative, with the decision and the effect
     * performed under THIS fence's lock (reviewer 60647af3, a65eef2f).
     *
     * Why this lives here and not in the caller: a caller-side lock cannot make check-then-act
     * indivisible against THIS object's own mutations. [issue] and [supersede] do not acquire a
     * caller's lock, so an owner/action change could land in the gap between "is it current?" and
     * "do it" - the check passes, another account's action is issued, and the effect then applies to
     * state the decision no longer described. Serializing only the effect leaves that window open; the
     * decision and the effect have to sit inside the same critical section as the mutations they are
     * racing.
     *
     * Returns true if the effect ran. A refused outcome runs NOTHING, which is what makes this safe
     * for a durable stop: an effect applied for a superseded or foreign action is indistinguishable
     * from a real one.
     */
    fun <T> applyIfCurrent(
        attempt: Attempt,
        liveSession: () -> Long,
        /**
         * Optional OWNER-TRANSACTION scope. When supplied, the session reading, the currency decision
         * and the effect all run INSIDE the owner lock, so an account transition cannot land between
         * them (reviewer e313b32c).
         *
         * Without it, `liveSession()` takes its own snapshot under a different authority's lock: the
         * session could change immediately after the snapshot and the effect would then apply to an
         * account the decision never described. Holding the fence lock alone does not stop that,
         * because the OWNER does not take the fence lock.
         */
        underOwnerTransaction: ((() -> T?) -> T?)? = null,
        effect: () -> T,
    ): T? {
        if (underOwnerTransaction != null) {
            // The owner transaction is the OUTERMOST scope and the fence lock nests inside it, so the
            // two are never acquired in opposite orders.
            return underOwnerTransaction { decideLocked(attempt, liveSession, effect) }
        }
        return decideLocked(attempt, liveSession, effect)
    }

    private fun <T> decideLocked(attempt: Attempt, liveSession: () -> Long, effect: () -> T): T? = synchronized(this) {
        // Inlined rather than delegating to isCurrent: isCurrent is itself synchronized on `this`,
        // and re-entering it here would be correct but obscure the fact that one lock covers both
        // the decision and the effect. The condition is kept textually identical to isCurrent.
        val live = liveSession()
        val current = if (attempt.ownerSession != live) {
            generation += 1
            boundSession = live
            false
        } else {
            attempt.generation == generation && boundSession == live
        }
        if (!current) null else effect()
    }

    /** Runs SERVER work one at a time; the caller decides what to do with a superseded result. */
    suspend fun <T> serialize(block: suspend () -> T): T = mutex.withLock { block() }

    /** Identifies one issued attempt: its generation plus the owner session it belongs to. */
    data class Attempt(val generation: Long, val ownerSession: Long)
}
