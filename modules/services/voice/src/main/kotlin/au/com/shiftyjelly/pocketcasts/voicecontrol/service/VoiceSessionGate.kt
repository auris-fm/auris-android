package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ONE VOICE SESSION AT A TIME over the process-wide voice resources.
 *
 * The engine, the ASR backends and the intent router are singletons: one of each for the whole
 * process. Every guard against starting during a teardown, by contrast, belongs to a single service
 * instance. A second instance therefore sees clean guards and starts while the first instance's
 * teardown is still releasing the objects it is about to use -- and the backend release frees a
 * native recognizer, so the overlap is a use-after-release rather than a stale read.
 *
 * This gate states the rule once, at process scope, so it holds for every caller rather than only
 * for the next service instance. A session is admitted only when no release is in progress; the
 * holder keeps it until every release has finished, including the asynchronous tail that outlives
 * the call which started it.
 *
 * The wait is BOUNDED. A teardown that cannot finish must not turn a use-after-release into a
 * permanently dead feature, so a start behind a stuck release is refused with [Admission.TimedOut]
 * rather than waiting forever or proceeding over half-released resources.
 */
@Singleton
class VoiceSessionGate @Inject constructor() {
    /** Why a start was or was not admitted. A refusal is explicit, never a silent no-op. */
    sealed interface Admission {
        /**
         * The caller owns the session. A start that goes on to run a pipeline hands it to its
         * teardown, which calls [beginRelease] and then [finishRelease] once every release has
         * finished. A start that goes no further gives it back with [release].
         */
        data class Acquired(val session: Session) : Admission

        /** Another session holds the resources and did not give them up within the bound. */
        data object TimedOut : Admission
    }

    /**
     * One holding of the shared resources. Identity matters: a release only frees the gate for the
     * session that actually held it, so a repeated or late finish cannot admit an extra session.
     */
    class Session internal constructor(val id: Long)

    // A plain lock, not a suspending mutex: `beginRelease` is called by a teardown that must mark
    // the window BEFORE it cancels anything, and a suspending call could be dispatched after that
    // point -- leaving exactly the gap this gate exists to close. Every critical section here is a
    // field read or write, so nothing suspends while holding it.
    private val stateLock = Any()

    // Guarded by [stateLock]. `holder` is the session entitled to the resources; `releasing` records
    // that its teardown has begun but not finished, which is the window a new start must not enter.
    private var holder: Session? = null
    private var releasing: Session? = null
    private var nextId = 0L

    private companion object {
        /** How often a waiting start re-checks. Short enough to be prompt, long enough not to spin. */
        const val POLL_MS = 10L
    }

    /**
     * Admit a session, waiting up to [timeoutMs] for any in-progress release to finish.
     *
     * Returns [Admission.TimedOut] when the bound is reached. The caller must treat that as a failed
     * start and say so; proceeding would use resources whose release never completed.
     */
    suspend fun acquire(timeoutMs: Long): Admission {
        // A CLAIM MUST NOT OUTLIVE ITS WAITER. Claiming happens under the lock inside the timeout,
        // and cancellation can land between the claim and the return: the session would then have
        // an owner that never learns it owns anything, so no release ever arrives and every later
        // start is refused for the life of the process. The claim is therefore recorded here as it
        // is made, and given back below unless this call actually returns it to a live caller.
        var claimed: Session? = null
        try {
            val admitted = withTimeoutOrNull(timeoutMs) {
                // The lock serializes the CHECK, not the holding: taking it and assigning would let
                // every caller in turn overwrite the holder. Wait until nobody holds the resources,
                // then claim them under the same lock so two waiters cannot both see it free.
                while (true) {
                    val taken = synchronized(stateLock) {
                        if (holder == null) {
                            Session(++nextId).also { holder = it }
                        } else {
                            null
                        }
                    }
                    if (taken != null) {
                        claimed = taken
                        return@withTimeoutOrNull taken
                    }
                    delay(POLL_MS)
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
            if (admitted == null) {
                // The bound expired. Give back anything claimed as it expired.
                claimed?.let { release(it) }
                return Admission.TimedOut
            }
            // Handed to the caller: from here the caller owns it.
            claimed = null
            return Admission.Acquired(admitted)
        } finally {
            // Cancellation, including a timeout that fires after the claim. `release` is a no-op once
            // a teardown has begun releasing this session, so this cannot undo a real teardown.
            claimed?.let { release(it) }
        }
    }

    /**
     * Take the session only if it is free RIGHT NOW, without waiting.
     *
     * A stop that owns no session uses this to find out whether it may release the process-wide
     * objects: if nobody holds them they are unowned and releasing them is correct, and if another
     * instance holds them they must be left alone. Waiting is not an option here -- a stop cannot
     * block on whoever is using voice.
     */
    fun tryAcquire(): Admission = synchronized(stateLock) {
        if (holder == null) {
            Admission.Acquired(Session(++nextId).also { holder = it })
        } else {
            Admission.TimedOut
        }
    }

    /**
     * The holder's teardown has begun. The gate stays held: the releases run asynchronously and a
     * start arriving now would meet resources mid-release.
     */
    fun beginRelease(session: Session) {
        synchronized(stateLock) {
            if (holder?.id == session.id) releasing = session
        }
    }

    /**
     * Every release for this session has finished, including the asynchronous tail. Only now may
     * the next session be admitted. A finish for a session that no longer holds the gate is ignored,
     * so a repeated finish cannot hand out a second concurrent session.
     */
    fun finishRelease(session: Session) {
        synchronized(stateLock) {
            if (holder?.id != session.id) return
            releasing = null
            holder = null
        }
    }

    /**
     * Whether this session still owns the shared resources, including while its release is in
     * progress. A retry uses this to decide whether it may still release them: once another session
     * holds the gate, releasing would free objects the new owner is using.
     */
    fun holds(session: Session): Boolean = synchronized(stateLock) { holder?.id == session.id }

    /**
     * Give up a session that was acquired but never began a release -- a start that was refused
     * further along, for example. Without this the gate would stay held by a session nobody owns.
     */
    fun release(session: Session) {
        synchronized(stateLock) {
            if (holder?.id == session.id && releasing?.id != session.id) holder = null
        }
    }
}
