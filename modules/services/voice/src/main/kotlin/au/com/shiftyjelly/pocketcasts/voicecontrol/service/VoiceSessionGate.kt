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
        /** The caller owns the session and must [VoiceSessionGate.release] it when done. */
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

    // Guarded by [mutex]. `holder` is the session entitled to the resources; `releasing` records
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
        val admitted = withTimeoutOrNull(timeoutMs) {
            // The lock serializes the CHECK, not the holding: taking it and assigning would let
            // every caller in turn overwrite the holder. Wait until nobody holds the resources,
            // then claim them under the same lock so two waiters cannot both see it free.
            while (true) {
                val claimed = synchronized(stateLock) {
                    if (holder == null) {
                        Session(++nextId).also { holder = it }
                    } else {
                        null
                    }
                }
                if (claimed != null) return@withTimeoutOrNull claimed
                delay(POLL_MS)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
        return if (admitted == null) Admission.TimedOut else Admission.Acquired(admitted)
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
     * Give up a session that was acquired but never began a release -- a start that was refused
     * further along, for example. Without this the gate would stay held by a session nobody owns.
     */
    fun release(session: Session) {
        synchronized(stateLock) {
            if (holder?.id == session.id && releasing?.id != session.id) holder = null
        }
    }
}