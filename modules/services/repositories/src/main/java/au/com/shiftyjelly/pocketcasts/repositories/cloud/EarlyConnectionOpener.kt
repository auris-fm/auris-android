package au.com.shiftyjelly.pocketcasts.repositories.cloud

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Opens and replaces early route connections on one dispatcher.
 *
 * Three things are deliberate here.
 *
 * **One owner.** Opening, taking and replacing all run on [dispatcher]. The capture thread and the
 * turn coroutine both want to touch the held connection, and letting each reach it directly is how
 * a socket ends up driven from two threads. Every entry point is a `withContext` onto the owner, so
 * the hop is the API rather than a rule a caller has to remember.
 *
 * **Silent replacement.** A connection older than [maxAgeMs] is closed and replaced without being
 * reported as a failure. It is a normal outcome: the user spoke, the connection aged out, a new one
 * opened. Surfacing it as an error would put a connection detail in front of a user who only
 * wanted to ask for something.
 *
 * **Resumption keeps its connection.** An utterance that resumes speaking keeps the connection it
 * already opened; only a *new* utterance replaces it. The tag is what distinguishes the two.
 */
class EarlyConnectionOpener(
    private val dispatcher: CoroutineDispatcher,
    private val maxAgeMs: Long = CloudTurnConnection.MAX_AGE_MS,
    private val open: suspend (tag: String, nowMs: Long) -> CloudTurnConnection,
    private val now: () -> Long,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Mutex()
    private var held: CloudTurnConnection? = null

    /**
     * Open a connection for [tag], replacing whatever is held.
     *
     * Returns the connection now held for [tag]. An open for an utterance that is already holding a
     * connection is a resumption and leaves that connection in place rather than dialling again.
     */
    suspend fun openFor(tag: String): CloudTurnConnection = withContext(dispatcher) {
        lock.withLock {
            val current = held
            if (current != null && current.ownerTag == tag && !current.isSpent) {
                // Resumption: the same utterance speaking again keeps the connection it opened.
                Timber.i("Early connection resume tag=%s", tag)
                return@withLock current
            }
            val nowMs = now()
            if (current != null && (current.ownerTag != tag || current.isSpent || current.isStale(nowMs, maxAgeMs))) {
                // Replacement is the only early close. A stale handle is discarded silently.
                // This is the one place a held connection dies before its turn can take it, so it
                // says which tag it belonged to and why it went: otherwise the turn's later
                // "does not match" or "none held" is the only trace, and the cause is lost.
                Timber.i(
                    "Early connection dial tag=%s replacing=%s reason=%s",
                    tag,
                    current.ownerTag,
                    when {
                        current.ownerTag != tag -> "other tag"
                        current.isSpent -> "spent"
                        else -> "stale"
                    },
                )
                current.close()
                held = null
            } else if (current == null) {
                Timber.i("Early connection dial tag=%s replacing=none", tag)
            }
            val opened = open(tag, nowMs)
            held = opened
            opened
        }
    }

    /**
     * Hand the connection held for [tag] to its turn, or null.
     *
     * Null means the turn must dial for itself: nothing was held, it belonged to another utterance,
     * or it was too old to use. None of those is an error.
     */
    suspend fun takeFor(tag: String): CloudTurnConnection? = withContext(dispatcher) {
        lock.withLock {
            // Every refusal below has the same visible consequence -- the turn dials for itself --
            // and they are indistinguishable afterwards unless each says why. A tag mismatch in
            // particular looks exactly like "the early connection never opened", so the tag that
            // was held is logged beside the one the turn asked for.
            val current = held ?: run {
                Timber.i("Early connection take tag=%s: none held", tag)
                return@withLock null
            }
            if (current.ownerTag != tag) {
                Timber.i("Early connection take tag=%s: held tag=%s does not match", tag, current.ownerTag)
                return@withLock null
            }
            held = null
            if (current.isStale(now(), maxAgeMs)) {
                Timber.i("Early connection take tag=%s: stale", tag)
                current.close()
                return@withLock null
            }
            if (current.isDead) {
                // A failure or close already arrived on this socket: adopting it would end a turn
                // that had not started. The turn dials fresh.
                Timber.i("Early connection take tag=%s: dead", tag)
                current.close()
                return@withLock null
            }
            if (!current.adopt()) {
                Timber.i("Early connection take tag=%s: adopt refused", tag)
                current.close()
                return@withLock null
            }
            Timber.i("Early connection take tag=%s: adopted age=%dms", tag, now() - current.openedAtMs)
            current
        }
    }

    /**
     * Drop a connection the turn never used, without closing it.
     *
     * The server disposes of it. The client does not go looking for sockets to close on every
     * routing exit, which is the behaviour the spec removed.
     */
    suspend fun releaseIfUnused(tag: String) = withContext(dispatcher) {
        lock.withLock {
            if (held?.ownerTag == tag) held = null
        }
    }

    /** Close everything. Only for shutdown: a turn in flight already adopted its own connection. */
    suspend fun shutdown() = withContext(dispatcher) {
        lock.withLock {
            held?.close()
            held = null
        }
    }

    /**
     * Open for [tag] off the caller's thread.
     *
     * The capture thread calls this and returns immediately: dialling must never block audio.
     */
    fun openForLater(tag: String) {
        scope.launch {
            try {
                openFor(tag)
            } catch (t: Throwable) {
                // An early connection is an optimisation. If it cannot be opened, the turn dials for
                // itself and the user notices nothing.
                Timber.w(t, "Early route connection could not be opened")
            }
        }
    }

    /** For tests and diagnostics: what is held right now, without taking it. */
    internal fun peek(): CloudTurnConnection? = held
}
