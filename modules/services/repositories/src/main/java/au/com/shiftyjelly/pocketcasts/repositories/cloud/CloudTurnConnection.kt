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
     * Send [text] on this connection's socket, or false when there is no socket or the send was
     * rejected. This is how an adopted exchange sends the authentication frame the dialled path
     * sends from its listener's `onOpen`: an early socket has no turn's frame when it opens, so
     * the first frame is sent at adoption, on the connection.
     */
    fun send(text: String): Boolean = false

    /**
     * True once a failure or close has arrived on this connection's socket before any turn adopted
     * it. A dead connection must never be adopted: the turn it belonged to no longer exists, and
     * its buffered failure would end a turn that had not started.
     */
    val isDead: Boolean get() = false

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
    /** The socket this connection owns, when it is a real dial. Null for a test handle. */
    private val socket: okhttp3.WebSocket? = null,
) : CloudTurnConnection {

    private val spent = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val dead = AtomicBoolean(false)

    override val isSpent: Boolean get() = spent.get()

    override fun adopt(): Boolean = spent.compareAndSet(false, true)

    override fun send(text: String): Boolean = socket?.send(text) ?: false

    /**
     * Mark this connection unusable: a failure or close arrived before any turn adopted it. Set by
     * the listener that drives the socket, which is the only side that observes those events.
     */
    fun markDead() {
        dead.set(true)
    }

    override val isDead: Boolean get() = dead.get()

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

    /** Binary audio. Only meaningful after the handshake carried a codec; before it is a violation. */
    class Binary(val bytes: ByteArray) : RawSocketEvent
    data class Failed(val code: Int, val message: String) : RawSocketEvent
    data class Closed(val code: Int, val reason: String) : RawSocketEvent
}
