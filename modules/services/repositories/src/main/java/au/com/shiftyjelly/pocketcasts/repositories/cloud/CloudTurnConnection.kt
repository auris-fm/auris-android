package au.com.shiftyjelly.pocketcasts.repositories.cloud

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel

/**
 * A route connection whose lifetime is explicit, so it can be opened before the turn that uses it.
 *
 * An early connection is opened at the end of addressed speech, while the turn is still being
 * recognised. The turn then adopts the connection it opened instead of dialling again, which saves
 * a round trip on the critical path.
 *
 * The seam exists because the old transport dialled inside the exchange: a socket that must outlive
 * the call which opened it needs a handle both callers can name.
 */
interface CloudTurnConnection {

    /** The utterance this connection belongs to. Ownership is checked against this, never a shared slot. */
    val ownerTag: String

    /** Client-clock instant of the upgrade request, used for the staleness rule. */
    val openedAtMs: Long

    /** True once this connection has been handed to a turn or closed. */
    val isSpent: Boolean

    /**
     * Mark this connection adopted, or refuse if it has already been used.
     *
     * Deliberately does NOT re-check the tag: ownership is the holder's job, and checking it in
     * both places made the holder's check untestable, because either copy alone kept the property
     * true. One check, in the one place that knows what is held.
     */
    fun adopt(): Boolean

    /** True once the connection has been closed. Observable so a caller can tell a closed socket
     * from one that is merely unheld. */
    val isClosed: Boolean

    /**
     * The raw socket events this connection's listener has already received, or null for a handle
     * that owns no socket.
     *
     * Buffered so an early close or failure is still observable after the fact: the listener runs
     * before any turn adopts, and the turn must decode whatever arrived rather than miss it because
     * nobody was reading yet. The events are undecoded -- the frame, and therefore the codecs, are
     * not known when an early connection opens, so decoding happens at adoption.
     */
    val events: kotlinx.coroutines.channels.Channel<RawSocketEvent>? get() = null

    /** Close the connection if it is still open. Idempotent. */
    fun close()

    /**
     * Whether this connection is too old to be worth using, measured from the upgrade request.
     *
     * A stale connection is replaced silently: it is not an error, and the user must never see it.
     * The threshold is the server's own limit, because past it the server may have closed the
     * socket already and the only honest move is to dial again.
     */
    fun isStale(nowMs: Long, maxAgeMs: Long = MAX_AGE_MS): Boolean = nowMs - openedAtMs >= maxAgeMs

    companion object {
        /** The server closes an idle route connection at ten seconds; stay inside that. */
        const val MAX_AGE_MS = 9_000L
    }
}

/**
 * The default [CloudTurnConnection]: a handle over a real socket.
 *
 * All mutual exclusion is on this object. The socket is driven from OkHttp's threads and the turn
 * from a coroutine, so a caller must not assume it is already on the right thread -- the caller is
 * responsible for the hop, which keeps the thread policy visible in one place instead of hidden in
 * every accessor.
 */
class SocketCloudTurnConnection(
    override val ownerTag: String,
    override val openedAtMs: Long,
    private val onClose: () -> Unit = {},
    /**
     * The socket events this connection's listener has received, or null for a handle that owns no
     * socket. Buffered so a close or failure that arrives before a turn adopts is still observable.
     */
    override val events: Channel<RawSocketEvent>? = null,
) : CloudTurnConnection {

    private val spent = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    override val isSpent: Boolean get() = spent.get()

    override fun adopt(): Boolean = spent.compareAndSet(false, true)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            onClose()
        }
    }

    override val isClosed: Boolean get() = closed.get()
}

/**
 * One undecoded event from an early connection's socket.
 *
 * The listener writes these before any turn exists, so it cannot encode them into route events:
 * encoding needs the negotiated codec, which needs the frame, which needs the turn. Keeping the
 * raw text lets the adopting exchange decode exactly once, with the same code it would have used
 * had it dialled itself.
 */
sealed interface RawSocketEvent {
    data object Opened : RawSocketEvent
    data class Text(val text: String) : RawSocketEvent
    data class Failed(val code: Int, val message: String) : RawSocketEvent
    data class Closed(val code: Int, val reason: String) : RawSocketEvent
}
