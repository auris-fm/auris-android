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
}
