package au.com.shiftyjelly.pocketcasts.repositories.cloud

import java.util.concurrent.atomic.AtomicBoolean

/**
 * A connection opened before the turn that will use it.
 *
 * The tag is the identity of the utterance that opened this connection, and it lives here rather
 * than in a shared slot: two connections can be alive at once while one replaces the other, and a
 * checker must be able to ask each one which utterance it belongs to.
 */
class EarlyConnection(val ownerTag: String) {

    private val cancelled = AtomicBoolean(false)

    val isCancelled: Boolean get() = cancelled.get()

    /** Mark this connection closed. Idempotent; a connection is closed at most once. */
    fun cancel() {
        cancelled.set(true)
    }
}

/**
 * Holds at most one early connection, and hands it only to the utterance that opened it.
 *
 * The rules are the spec's, and each exists because the obvious alternative was wrong:
 *
 * - Ownership is checked against the connection's own tag, never against "the current tag", so a
 *   late caller cannot inherit an identity that is not its own.
 * - Taking transfers: a connection is used at most once, so a retry cannot race the first use.
 * - Opening a replacement cancels the held connection. Replacement is the *only* early close. The
 *   spec deliberately removed closing on every routing exit, because that closed connections a
 *   turn was still about to use.
 * - A connection its utterance never used is left to the server's limit. The client does not go
 *   looking for connections to close.
 *
 * Access is serialized on this object. The socket itself is driven from OkHttp's threads, so a
 * caller must not assume the holder's lock is a thread-affine one.
 */
class EarlyConnectionHolder {

    private var held: EarlyConnection? = null

    /** Hold [connection], cancelling whatever was held before. */
    @Synchronized
    fun hold(connection: EarlyConnection) {
        held?.cancel()
        held = connection
    }

    /**
     * Take the held connection if it belongs to [tag], or null.
     *
     * A successful take clears the slot: the connection now belongs to the turn that took it, and
     * a second take for the same utterance must not hand out the same socket again.
     */
    @Synchronized
    fun takeFor(tag: String): EarlyConnection? {
        val current = held ?: return null
        if (current.ownerTag != tag) return null
        held = null
        return current
    }

    /**
     * Let go of [connection] without closing it, if it is still the held one.
     *
     * Used when an utterance's turn ends without having used the connection it opened. It stays at
     * the server's disposal rather than being torn down here, and a connection that has already
     * been replaced is left alone so cleanup for an old utterance cannot reach the new one.
     */
    @Synchronized
    fun releaseIfUnused(connection: EarlyConnection) {
        if (held?.ownerTag == connection.ownerTag) {
            held = null
        }
    }
}
