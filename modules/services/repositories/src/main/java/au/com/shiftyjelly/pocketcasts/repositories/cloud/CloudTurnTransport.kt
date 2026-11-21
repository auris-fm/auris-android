package au.com.shiftyjelly.pocketcasts.repositories.cloud

import kotlinx.coroutines.flow.Flow

/**
 * The connection a turn runs over, as a seam rather than a socket.
 *
 * OkHttp's MockWebServer has no WebSocket-upgrade support, so the socket mechanics cannot be driven
 * from the unit suite. This interface keeps the frame *contents* — the authentication frame, the
 * binary audio frames, the event vocabulary — under test, and leaves the upgrade handshake to the
 * implementation.
 */
interface CloudTurnTransport {
    /** Send [frame] and emit everything the server sends back, in order. */
    fun exchange(frame: CloudTurnFrame): Flow<CloudRouteEvent>

    /**
     * The two-way form: [onOpen] is invoked with a sender for CLIENT-originated result frames once
     * the connection can carry them (and never after it cannot); a send returns false when the
     * connection cannot deliver. Null means the caller has nothing to answer with. The default
     * keeps one-way implementations compiling unchanged.
     */
    fun exchange(
        frame: CloudTurnFrame,
        onOpen: ((sendResult: (CloudTurnFrame) -> Boolean) -> Unit)?,
    ): Flow<CloudRouteEvent> = exchange(frame)
}
