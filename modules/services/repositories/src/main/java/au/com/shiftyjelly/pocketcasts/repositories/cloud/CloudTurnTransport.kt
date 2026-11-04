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
    /**
     * One turn's socket: its events and the sender that reaches THAT socket, produced together.
     *
     * There is deliberately no way to ask for a sender separately from the exchange. A sender obtained
     * on its own is a sender for a socket that does not exist yet, and the natural implementation --
     * build a transport here, build another one in [exchange] -- silently answers on a connection no
     * turn is reading. [CloudTurnRoute.openTurn] is the only constructor of this type.
     */
    class TurnSession(
        /** Everything the server sends back for this turn, in order. */
        val events: Flow<CloudRouteEvent>,
        /** Reaches the same socket; false means the frame was not accepted. */
        val sender: ResultSender,
        /**
         * This turn's ownership contract: when it retires, and what still counts as admitted work.
         *
         * It is part of the session on purpose. A caller that had to guess its own deadline would be
         * running a second timer beside the transport's, and the two would disagree about the moment
         * the connection stops belonging to the turn. The default owns nothing and admits nothing,
         * which is the honest contract for a transport that supports no result sending.
         */
        val ownership: TurnOwnership = TurnOwnership.NONE,
    )

    /**
     * Who may still report on a turn's connection, and until when.
     *
     * The transport derives [retirementDeadlineMs] ONCE when retirement starts and never moves it.
     * Retirement forbids NEW admissions immediately; a lease granted before it only lets work that was
     * already admitted finish and report. A caller that keeps computing past the deadline is refused
     * a lease, its outcome stays unknown, and nothing is retried here.
     */
    interface TurnOwnership {
        /**
         * Admits a unit of work that may later report on this turn's socket, or returns null when the
         * turn can no longer admit it.
         *
         * Taken at ADMISSION, while the turn is live, and held across the effect observation and the
         * result send: a lease taken later -- only around the send -- would let cleanup observe no
         * outstanding work and retire the connection while the work was still running.
         */
        fun admit(): TurnLease?

        /**
         * ESTABLISH retirement synchronously at the turn boundary and return the deadline.
         *
         * Idempotent, and callable from any party that reaches the boundary first -- the sink when its
         * collection ends, the transport when its exchange ends. A caller MUST establish before it
         * waits: a deadline read before establishment is zero, and treating zero as "nothing to wait
         * for" would skip the wait entirely. The deadline is capped by [earlierRetainedDeadlineMs], so a
         * turn never owns its connection past a deadline it already carried.
         */
        fun establishRetirement(): Long

        /** Epoch millis when the turn's connection stops being the turn's; 0 before retirement. */
        fun retirementDeadlineMs(): Long

        /**
         * The deadline this turn already carried when it was built, or [NO_EARLIER_DEADLINE].
         *
         * PRODUCTION PROPAGATION IS UNRESOLVED: no caller supplies a retained turn or operation
         * deadline yet, so every production session reports the absent sentinel and retirement is
         * capped by nothing. The seam exists so that when a caller does have one, it caps retirement
         * instead of being ignored -- it is not evidence that deadline propagation works.
         */
        fun earlierRetainedDeadlineMs(): Long

        companion object {
            /** A turn that admits nothing and never retires. */
            val NONE: TurnOwnership = object : TurnOwnership {
                override fun admit(): TurnLease? = null

                override fun establishRetirement(): Long = 0L

                override fun retirementDeadlineMs(): Long = 0L

                override fun earlierRetainedDeadlineMs(): Long = NO_EARLIER_DEADLINE
            }
        }
    }

    /** A granted right to finish admitted work and report it, released exactly once. */
    fun interface TurnLease {
        /** Idempotent: releasing twice must not credit the turn twice. */
        fun release()
    }

    /** Send [frame] and emit everything the server sends back, in order. */
    fun exchange(frame: CloudTurnFrame): Flow<CloudRouteEvent>

    /**
     * The sender for THIS turn, bound to the one socket its exchange opens.
     *
     * Held by the caller before the exchange starts, and NOT looked up from a shared registry: a map
     * keyed by request id lets a later turn replace an entry under the same id, and lets an older
     * collector's cleanup remove its successor's socket. Carrying the socket instance means a result
     * can only reach the connection its operation arrived on.
     *
     * Default: no socket to reach. A transport without result sending says so rather than pretending.
     */
    fun sessionFor(requestId: String): ResultSender = NO_RESULTS

    /** This transport's events and their sender, for the same socket. Default: a sender that reaches nothing. */
    fun openTurn(frame: CloudTurnFrame): TurnSession = TurnSession(events = exchange(frame), sender = sessionFor((frame as CloudTurnFrame.Authenticate).requestId))

    companion object {
        /** A sender that reaches nothing: the honest answer for a transport without result sending. */
        val NO_RESULTS: ResultSender = ResultSender { false }

        /**
         * "This turn carried no earlier deadline."
         *
         * An explicit absence rather than a zero instant: capping against zero would retire the turn
         * in the past, and deriving a lifetime to avoid the sentinel would invent a bound no caller
         * supplied.
         */
        const val NO_EARLIER_DEADLINE = 0L
    }

    /** Emits into the socket its turn opened; false when that socket is gone or the send failed. */
    fun interface ResultSender {
        /** False means the frame was NOT accepted. It is not proof of receipt either. */
        fun sendResult(result: CloudTurnFrame.AppOperationResult): Boolean
    }
}
