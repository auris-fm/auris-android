package au.com.shiftyjelly.pocketcasts.repositories.cloud

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import timber.log.Timber

/*
 * Runs a turn over a WebSocket: connect, send the authentication frame as the first message, then
 * emit the server's frames as [CloudRouteEvent]s.
 *
 * Binary frames are audio in the negotiated codec. Text frames carry the event vocabulary, decoded
 * by their `type` discriminator through the shared [CloudRouteJson] adapters.
 *
 * The turn's retirement deadline below is shared with everything admitted on that turn.
 */
/**
 * THE single retirement grace for this slice.
 *
 * One constant, one deadline: retirement derives it ONCE when it starts, and both the sink's admitted
 * work and this transport's cleanup read that same instant. It is never a second timer, never reset
 * when another lease is acquired, and never extended by a later arrival.
 */
private const val TURN_RETIREMENT_GRACE_MS = 2_000L

/**
 * The states one turn's connection passes through.
 *
 * Declared at file scope because Kotlin forbids an `enum class` inside an inner class; the session
 * owns the transitions, this only names them.
 */
private enum class TurnState { Opening, Live, Retiring, Retired }

/** How often the cancellable lease wait re-checks; a suspension interval, never a deadline. */
private const val LEASE_POLL_MS = 5L

/** See [CloudTurnTransport.NO_EARLIER_DEADLINE]; one sentinel, not a second definition. */
private const val NO_EARLIER_DEADLINE = CloudTurnTransport.NO_EARLIER_DEADLINE

class WebSocketCloudTurnTransport(
    private val baseUrl: String,
    private val okHttpClient: OkHttpClient,
    private val observer: CloudTurnTransportObserver? = null,
    /**
     * A retained deadline this transport was HANDED, or [CloudTurnTransport.NO_EARLIER_DEADLINE].
     *
     * A configuration seam, defaulting to absent: production has no caller that propagates a turn or
     * operation deadline yet, so the default is the honest sentinel and NOT a derived lifetime. When a
     * caller does supply one, retirement is capped by it.
     */
    private val retainedDeadlineMs: Long = CloudTurnTransport.NO_EARLIER_DEADLINE,
) : CloudTurnTransport {

    /**
     * Sockets this exchange opened, keyed by the identity of the turn's OWN session object.
     *
     * Not keyed by request id: a map keyed by request id lets a later turn replace an entry under the
     * same id, and lets an older exchange's cleanup remove its successor's socket. The key is the
     * session instance that [sessionFor] handed out for this exchange, and the value is the socket
     * THIS exchange opened — everything is bound to the exact instance.
     */
    private val sockets = java.util.concurrent.ConcurrentHashMap<CloudTurnTransport.ResultSender, WebSocket>()

    /**
     * One turn's connection, from the socket it owns to the moment it is retired.
     *
     * The states are the whole contract:
     * - `Opening` — a socket has been requested; nothing may be written yet.
     * - `Live`    — the server opened it; admitted operations may be answered on it.
     * - `Retiring`— the turn is over. NO new operation may start, but work already admitted may finish
     *               and report on this same socket until the deadline derived at retirement.
     * - `Retired` — the registration is gone and the socket is cancelled. Holding this instance is
     *               then only useful for reporting a refusal.
     *
     * A late `onOpen` must never move a session forward: `Opening -> Live` is the ONLY transition into
     * Live, and it fails once `Retiring` has been published.
     */
    private inner class Session(private val requestId: String) :
        CloudTurnTransport.ResultSender,
        CloudTurnTransport.TurnOwnership {
        private val state = AtomicReference(TurnState.Opening)

        /** The deadline fixed when retirement started; 0 until then. Read by leases, never reset. */
        private val retirementDeadlineMs = java.util.concurrent.atomic.AtomicLong(0L)

        private val leases = java.util.concurrent.atomic.AtomicInteger(0)

        /** The socket this session adopted, published before the registry entry (see [bind]). */
        private val ownedSocket = AtomicReference<WebSocket?>(null)

        /**
         * The single monitor both admission and retirement decide under.
         */
        private val decisionLock = Any()

        /**
         * The deadline this turn already carried, or [NO_EARLIER_DEADLINE] when there is none.
         *
         * ABSENT IS ABSENT. Nothing here derives a lifetime to stand in for a retained deadline: a
         * fabricated bound would cap retirement against a value no caller ever supplied, and would
         * look like conformance to a contract that has not been satisfied. Production turn- and
         * operation-deadline propagation is UNRESOLVED -- no caller passes one yet -- so this returns
         * the explicit sentinel, and [beginRetirement] then derives the grace without capping.
         */
        override fun earlierRetainedDeadlineMs(): Long = retainedDeadlineMs

        fun currentState(): TurnState = state.get()

        /**
         * Record the socket this session owns. Returns false when the session is already retiring, so
         * a caller that lost the race knows not to adopt it.
         *
         * THE ORDER IS THE RACE: the state is published FIRST and the registration second, so a
         * retirement that starts in between finds nothing to remove and `retire()` still owns the
         * cancellation through [ownedSocket]. Publishing the registration first would leave it behind.
         */
        fun bind(socket: WebSocket): Boolean {
            if (state.get() != TurnState.Opening && state.get() != TurnState.Live) return false
            ownedSocket.compareAndSet(null, socket)
            if (state.get() != TurnState.Opening && state.get() != TurnState.Live) {
                // Retirement won the race: cancel what this call was about to adopt and claim nothing.
                sockets.putIfAbsent(this, socket)?.let { /* another socket is already registered */ }
                sockets.remove(this, socket)
                socket.cancel()
                return false
            }
            sockets[this] = socket
            return true
        }

        /** `Opening -> Live`, and nothing else. A late open on a retiring session is refused. */
        fun markLive(): Boolean = state.compareAndSet(TurnState.Opening, TurnState.Live)

        /**
         * ESTABLISH retirement: fix THE deadline and stop admitting, atomically and once.
         *
         * Called SYNCHRONOUSLY at the turn boundary (the flow ending, a cancel, a failure, a terminal)
         * by whichever party reaches it first -- the sink or the transport cleanup -- never inside a
         * coroutine body. Idempotent: the first caller fixes the instant, every later caller observes
         * it, and no caller can see a zero deadline and then proceed as if the turn never retired.
         *
         * An earlier retained deadline CAPS the derived one: the turn never owns its connection past a
         * deadline it already had.
         */
        override fun establishRetirement(): Long = beginRetirement(earlierRetainedDeadlineMs())

        private fun beginRetirement(earlierDeadlineMs: Long): Long {
            synchronized(decisionLock) {
                if (retirementDeadlineMs.compareAndSet(0L, 0L)) {
                    val now = System.currentTimeMillis()
                    val derived = now + TURN_RETIREMENT_GRACE_MS
                    // CAP ONLY AGAINST A MISSING-OR-EARLIER SUPPLIED DEADLINE. An absent deadline
                    // caps nothing: the grace stands alone, and no invented value takes its place.
                    val capped = if (earlierDeadlineMs != NO_EARLIER_DEADLINE && earlierDeadlineMs < derived) {
                        earlierDeadlineMs
                    } else {
                        derived
                    }
                    retirementDeadlineMs.set(capped)
                }
                // Retiring forbids new work from this instant, whether or not a socket ever opened.
                val current = state.get()
                if (current == TurnState.Opening || current == TurnState.Live) state.set(TurnState.Retiring)
                return retirementDeadlineMs.get()
            }
        }

        fun retirementDeadline(): Long = retirementDeadlineMs.get()

        /**
         * ADMIT a unit of work that may later report on this socket.
         *
         * Allowed ONLY while the turn is live: once retirement starts, no new caller may acquire a
         * lease, so nothing new can begin behind a connection that is on its way out. The lease is
         * taken here -- at admission -- and held across the effect observation and the result send; a
         * lease taken only around the send would let cleanup observe no outstanding work while the
         * work was still running.
         */
        override fun admit(): CloudTurnTransport.TurnLease? {
            // ADMISSION AND RETIREMENT ARE ONE DECISION, taken under one monitor.
            //
            // A check-then-count sequence can always be outrun: retirement starts between the check
            // and the increment, and the lease is granted into a turn that has already ended. Both
            // sides therefore mutate the same state while holding [decisionLock] -- admission decides
            // "still live, count it", retirement decides "no more admissions, from this instant" --
            // so exactly one of them wins and no new lease can be granted once retirement has started.
            synchronized(decisionLock) {
                if (state.get() != TurnState.Live) return null
                leases.incrementAndGet()
            }
            val released = java.util.concurrent.atomic.AtomicBoolean(false)
            return CloudTurnTransport.TurnLease {
                if (released.compareAndSet(false, true)) releaseLease()
            }
        }

        /** The immutable deadline this session fixed, exposed through the session contract. */
        override fun retirementDeadlineMs(): Long = retirementDeadlineMs.get()

        fun releaseLease() {
            // Decrement last: the waiter must not see "no leases" while the count is still held.
            leases.decrementAndGet()
        }

        /** Retire the registration and cancel the socket this session owns. Idempotent. */
        fun retire(): Boolean {
            val previous = state.getAndSet(TurnState.Retired)
            // Both sources: the registry entry AND the socket this session adopted, so a bind that
            // lost the race can never leave a live, unowned connection behind.
            sockets.remove(this)
            val socket = ownedSocket.get()
            // `cancel`, not `close`: a socket whose handshake never completed has no connection to
            // close, and only `cancel` stops the handshake that would otherwise arrive later as an
            // `onOpen` for a session that no longer exists.
            socket?.cancel()
            return previous != TurnState.Retired
        }

        /**
         * Stop granting new leases WITHOUT establishing a retirement deadline.
         *
         * Used by the peer-close path: the turn has ended, but the deadline that bounds the drain is
         * established at the boundary by [establishRetirement], and this must not pre-empt it with a
         * second instant. Under the same decision lock as admission, so a lease either started before
         * the peer closed or is refused -- never both.
         */
        fun stopAdmission() {
            synchronized(decisionLock) {
                val current = state.get()
                if (current == TurnState.Opening || current == TurnState.Live) state.set(TurnState.Retiring)
            }
        }

        /** True when no lease is outstanding; used to end retirement early. */
        fun leasesIdle(): Boolean = leases.get() == 0

        /**
         * Suspend until every lease is released, or until the caller's bound expires.
         *
         * A CANCELLABLE SUSPENSION, not a blocking wait: `Object.wait` in a loop ignores coroutine
         * timeout, so a lease that never releases would outlive the deadline that was supposed to stop
         * it. `yield` keeps the loop cancellable and lets the timeout actually fire.
         */
        suspend fun awaitLeasesReleased() {
            while (!leasesIdle()) {
                delay(LEASE_POLL_MS)
            }
        }

        fun isForwarding(): Boolean = forwarding.get()

        /**
         * Stop forwarding frames once the turn ends, without waiting on a channel nobody drains.
         *
         * Set at retirement START, before any wait: frames that arrive afterwards belong to a turn
         * that is already over, and holding them would only keep the exchange job alive.
         */
        fun stopForwarding() {
            forwarding.set(false)
        }

        private val forwarding = java.util.concurrent.atomic.AtomicBoolean(true)

        override fun sendResult(result: CloudTurnFrame.AppOperationResult): Boolean {
            // A send is permitted while the turn owns the connection: live, or retiring inside the
            // deadline. It does NOT grant admission -- that happened at admission time -- so it takes a
            // SEND lease only, leaving the admission decision alone. Read the state BEFORE counting: a
            // send that becomes legal only because a later state change would otherwise slip in.
            while (true) {
                val current = state.get()
                if (current == TurnState.Retired) return false
                if (current == TurnState.Retiring && System.currentTimeMillis() >= retirementDeadlineMs.get()) return false
                val observed = leases.get()
                if (leases.compareAndSet(observed, observed + 1)) break
            }
            try {
                return sendWithinLease(result)
            } finally {
                releaseLease()
            }
        }

        private fun sendWithinLease(result: CloudTurnFrame.AppOperationResult): Boolean {
            val socket = sockets[this] ?: return false
            val encoded = try {
                result.toWireJson()
            } catch (_: Exception) {
                observer?.onSendResult(requestId, CloudTurnTransportObserver.SendOutcome.ENCODE_FAILED)
                return false
            }
            return try {
                observer?.onSendAttempted(requestId)
                val sent = socket.send(encoded)
                observer?.onSendResult(
                    requestId,
                    if (sent) CloudTurnTransportObserver.SendOutcome.ACCEPTED else CloudTurnTransportObserver.SendOutcome.REJECTED,
                )
                sent
            } catch (_: Exception) {
                observer?.onSendResult(requestId, CloudTurnTransportObserver.SendOutcome.SEND_THREW)
                false
            }
        }
    }

    override fun sessionFor(requestId: String): CloudTurnTransport.ResultSender = Session(requestId)

    override fun exchange(frame: CloudTurnFrame): Flow<CloudRouteEvent> = exchangeOver(frame, Session((frame as CloudTurnFrame.Authenticate).requestId))

    override fun openTurn(frame: CloudTurnFrame): CloudTurnTransport.TurnSession {
        // ONE session instance for both halves, over ONE exchange: the sender and the events cannot
        // end up on different sockets, and a sender can never be handed out for a socket that was
        // never opened.
        val session = Session((frame as CloudTurnFrame.Authenticate).requestId)
        return CloudTurnTransport.TurnSession(
            events = exchangeOver(frame, session),
            sender = session,
            // THE SAME OBJECT: the caller's deadline and leases are this session's, not a copy.
            ownership = session,
        )
    }

    /**
     * Scope for the bounded release of a turn's connection.
     *
     * Releasing runs after the turn's own scope has ended, so it cannot use that scope. A supervisor
     * keeps one turn's release failure from cancelling another's.
     */
    private val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun exchangeOver(frame: CloudTurnFrame, session: Session): Flow<CloudRouteEvent> = callbackFlow {
        // Use a large bounded channel (4096 events) so the WebSocket listener can
        // buffer frames without silently dropping them. With an unbounded channel,
        // events would accumulate indefinitely if the collector is gone; with a
        // bounded channel, we either deliver or fail the turn explicitly.
        val channel = Channel<CloudRouteEvent>(4096)

        val offeredCodecs = (frame as CloudTurnFrame.Authenticate).codecs
        val negotiatedCodec = AtomicReference<String?>(null)
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + ROUTE_PATH)
            .build()

        // The socket OkHttp handed us. Held here, NOT only in the registry: the registry entry is
        // removed when the turn session ends, and the listener still needs the socket to close it.
        val owned = AtomicReference<WebSocket?>(null)
        // Whether the connection was established. The turn can end before the handshake completes too,
        // so closing is only meaningful on a socket the server actually opened.
        val opened = java.util.concurrent.atomic.AtomicBoolean(false)

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val requestId = frame.requestId
                if (response.code != 101) {
                    Timber.w("Cloud route WebSocket rejected: %d %s", response.code, response.message)
                    // Handshake rejected — socket opened but upgrade refused. Not a send rejection
                    // because send was never attempted.
                    observer?.onConnectionFailed(requestId)
                    emitTerminal(
                        channel,
                        CloudRouteEvent.Error(
                            code = CloudRouteErrorCodes.CONNECTION_LOST,
                            message = "",
                        ),
                    )
                    return
                }
                // A LATE OPEN CONFERS NO AUTHORITY. If this session already started retiring, the
                // turn is over: adopting the socket would resurrect a session whose registration is
                // gone or owned by a successor, so the connection is cancelled and nothing is sent.
                if (!session.markLive()) {
                    Timber.w("Cloud route: socket opened after the turn retired; cancelling")
                    webSocket.cancel()
                    return
                }
                opened.set(true)
                observer?.onSocketOpened(requestId)
                val json: String
                try {
                    json = frame.toJson()
                } catch (encodeFailure: Exception) {
                    // Encode failure — send was never attempted. Record bounded outcome and propagate.
                    Timber.w(encodeFailure, "Cloud route auth frame encode failed")
                    observer?.onSendResult(requestId, CloudTurnTransportObserver.SendOutcome.ENCODE_FAILED)
                    emitTerminal(
                        channel,
                        CloudRouteEvent.Error(code = CloudRouteErrorCodes.CONNECTION_LOST, message = ""),
                    )
                    return
                }
                val sent: Boolean
                try {
                    observer?.onSendAttempted(requestId)
                    sent = webSocket.send(json)
                } catch (sendThrow: Exception) {
                    // Send threw — distinct from encode failure and false-return rejection.
                    Timber.w(sendThrow, "Cloud route auth frame send threw")
                    observer?.onSendResult(requestId, CloudTurnTransportObserver.SendOutcome.SEND_THREW)
                    emitTerminal(
                        channel,
                        CloudRouteEvent.Error(code = CloudRouteErrorCodes.CONNECTION_LOST, message = ""),
                    )
                    return
                }
                observer?.onSendResult(
                    requestId,
                    if (sent) CloudTurnTransportObserver.SendOutcome.ACCEPTED else CloudTurnTransportObserver.SendOutcome.REJECTED,
                )
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val codec = negotiatedCodec.get()
                if (codec == null) {
                    emitTerminal(channel, CloudRouteEvent.Error(CloudRouteErrorCodes.INVALID_RESPONSE, ""))
                    return
                }
                emitOrFail(channel, CloudRouteEvent.AudioFrame(codec = codec, bytes = bytes.toByteArray()))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = CloudRouteEvents.decode(text)
                if (event == null) {
                    Timber.w("Cloud route frame could not be decoded")
                    emitTerminal(
                        channel,
                        CloudRouteEvent.Error(code = CloudRouteErrorCodes.INVALID_RESPONSE, message = ""),
                    )
                } else if (event is CloudRouteEvent.Connected) {
                    // The handshake is where the negotiated codec arrives; binary frames carry it.
                    if (event.codec !in offeredCodecs || !negotiatedCodec.compareAndSet(null, event.codec)) {
                        emitTerminal(channel, CloudRouteEvent.Error(CloudRouteErrorCodes.INVALID_RESPONSE, ""))
                    } else {
                        emitOrFail(channel, event)
                    }
                } else if (negotiatedCodec.get() == null && event !is CloudRouteEvent.Error) {
                    emitTerminal(channel, CloudRouteEvent.Error(CloudRouteErrorCodes.INVALID_RESPONSE, ""))
                } else {
                    // Server-originated terminal: record at actual decode point.
                    if (event is CloudRouteEvent.Done) {
                        val outcome = if (event.outcome == CloudRouteEvent.Outcome.Completed) "done" else "interrupted"
                        observer?.onTerminalReceived(
                            frame.requestId,
                            CloudTurnTransportObserver.TerminalOrigin.SERVER_DONE,
                            outcome,
                        )
                    } else if (event is CloudRouteEvent.Error) {
                        observer?.onTerminalReceived(
                            frame.requestId,
                            CloudTurnTransportObserver.TerminalOrigin.SERVER_ERROR,
                            event.code,
                        )
                    }
                    // ONLY a server terminal ends the turn: Done/Error enqueue then finish the
                    // channel so the consumer drains in order and the flow completes. emitOrFail
                    // alone would leave the channel open and the collector suspended forever (the
                    // sink hangs after selection); closing on a NONterminal frame would truncate a
                    // healthy streamed answer mid-flight, so those keep emitOrFail.
                    if (event is CloudRouteEvent.Done || event is CloudRouteEvent.Error) {
                        emitTerminal(channel, event)
                    } else {
                        emitOrFail(channel, event)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val requestId = frame.requestId
                // The connection is gone, so no result can be written: retire THIS session before the
                // socket reference is dropped, so a send after this point fails on the state machine
                // rather than racing a registration that no longer means anything.
                session.retire()
                Timber.w(t, "Cloud route socket failed")
                observer?.onConnectionFailed(requestId)
                emitTerminal(
                    channel,
                    CloudRouteEvent.Error(code = CloudRouteErrorCodes.CONNECTION_LOST, message = ""),
                )
            }

            /**
             * THE PEER INITIATED THE CLOSE, AND WE MUST ANSWER IT.
             *
             * Verified against the installed okhttp 5.5.0 bytecode, not assumed: a close frame reaches
             * `WebSocketReader` -> `FrameCallback.onReadClose`, and `RealWebSocket.onReadClose` only
             * records the code, calls `listener.onClosing`, and returns -- it writes nothing. A close
             * frame is written only from `close(code, reason, timeout)`, and `onClosed` fires only once
             * that has happened. So an un-acknowledged peer close never completes the handshake, and
             * the event channel would never be finished.
             *
             * A peer that is already closing is NOT a live result-delivery channel. Admission stops
             * here, before the acknowledgement, so nothing can claim a lease on a turn that is ending;
             * whatever is already in flight stays UNKNOWN in the durable record and is never retried
             * or reported as succeeded because of this close. Retirement proper (registration removal
             * and socket cancellation) is still the job of [onClosed]/[onFailure], which stay the
             * idempotent original-session path -- this callback only ends admission and the channel.
             */
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                session.stopAdmission()
                // Answer the peer so the close handshake can finish; without this `onClosed` never
                // arrives for a peer-initiated close.
                webSocket.close(code, reason)
                // The turn is over for the consumer: enqueue behind everything already sent and finish
                // the channel through the SAME ordered terminal path the other endings use, so the
                // sink drains in order and the flow completes instead of hanging.
                emitTerminal(
                    channel,
                    CloudRouteEvent.Error(code = CloudRouteErrorCodes.CONNECTION_LOST, message = ""),
                )
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // A clean server close ends the turn with no error event: finish the channel so the
                // consumer drains what it has and then ends the flow. The session is retired here,
                // where the socket really is gone, so a send after this point fails on the state
                // machine instead of writing to a closed connection.
                session.retire()
                channel.close()
            }
        }

        val socket = okHttpClient.newWebSocket(request, listener)
        owned.set(socket)
        // Bound to the exact Socket instance AND the exact session: a later turn cannot replace this
        // entry under the same request id, and this cleanup cannot remove a successor's socket.
        // `bind` refuses a session that is already retiring, so this exchange cannot adopt a socket for
        // a turn that has ended; the socket is cancelled instead of being left unowned.
        if (!session.bind(socket)) {
            socket.cancel()
            return@callbackFlow
        }

        // The launched job forwards channel events to the flow, and is cancelled when the flow is
        // cancelled (which also cancels the socket via the awaitClose cleanup).
        val collectionJob = launch {
            try {
                channel.consumeEach { event ->
                    if (!session.isForwarding()) return@consumeEach
                    val result = trySend(event)
                    if (result.isFailure) {
                        // Propagate failure to the callbackFlow so the consumer
                        // receives an observable terminal failure rather than
                        // silently losing the event. The flow will close with
                        // this exception.
                        close(
                            result.exceptionOrNull() ?: RuntimeException(
                                "Cloud route: callbackFlow buffer full, dropping event ${event::class.simpleName}",
                            ),
                        )
                    }
                }
                // The channel is finished (a terminal event ended the turn, or the socket closed):
                // every buffered frame has now been forwarded, so the flow can end. Closing here
                // rather than from the listener is what keeps the terminal event from being
                // cancelled before the consumer forwards it.
                close()
            } catch (_: CancellationException) {
                // Normal cancellation — the flow was cancelled.
            } catch (e: Exception) {
                Timber.w(e, "Cloud route collection failed")
                close(e)
            }
        }

        awaitClose {
            // THE TURN SESSION OWNS THE CONNECTION, AND ITS END CLOSES IT.
            //
            // The sink stops collecting at the terminal frame; that must NOT end the connection,
            // because an admitted operation's answer is still written into it after the sink is gone.
            // So the connection ends when the EXCHANGE ends (server close, failure, or the producer
            // finishing), never merely because the collector went away. A result that cannot be
            // written before that boundary is not lost either: the settlement is already durable in
            // the journal, and a redelivery of the same operation replays it.
            //
            // ESTABLISH BEFORE LAUNCHING. Establishment is what stops admission, so it must be complete
            // by the time this callback returns: deferring it into the launched body leaves a window in
            // which a cancelled turn still admits new work. It is idempotent, so a deadline the sink
            // already fixed is observed here, not replaced.
            val deadline = session.establishRetirement()
            // `awaitClose` cannot suspend, so the bounded waits run in their own scope -- armed with
            // the instant fixed above, never with one they derive themselves.
            shutdownScope.launch { releaseTurn(session, collectionJob, deadline) }
        }
    }

    /**
     * Release one turn's connection: wait for the exchange and admitted work, then retire.
     *
     * Every wait is a CANCELLABLE SUSPENSION bounded by the single established deadline. A lease that
     * is never released must not be able to hold the connection past that instant, so the wait stops on
     * the deadline even when work is still outstanding -- that work's outcome stays unknown in the
     * durable record and is never retried here.
     */
    private suspend fun releaseTurn(
        session: Session,
        collectionJob: Job,
        deadline: Long,
    ) {
        try {
            // The exchange forwards frames only while the session is live; a cancelled collector must
            // not leave a job waiting on a channel nobody drains.
            session.stopForwarding()
            withTimeoutOrNull(remainingMs(deadline)) { collectionJob.join() }
            // Then wait -- cancellably -- for admitted work to release its leases on the SAME deadline.
            withTimeoutOrNull(remainingMs(deadline)) {
                session.awaitLeasesReleased()
            }
        } finally {
            // On expiry the original registration is retired and ITS OWNED SOCKET CANCELLED. Anything
            // still unresolved stays unknown in the durable record and is never blindly retried: a
            // replay comes from the journal, not from a second send here.
            session.retire()
        }
    }

    private fun remainingMs(deadline: Long): Long = maxOf(0L, deadline - System.currentTimeMillis())

    /**
     * Emit an event or fail the turn if the channel is full.
     *
     * With a bounded channel (4096 events), a full channel means the collector
     * is genuinely overwhelmed — not just slow. In that case, we send an error
     * to terminate the turn rather than silently losing the frame.
     *
     * If even the error can't be delivered, we close the channel to signal
     * termination to any pending receivers.
     */
    // End the turn: enqueue [event] behind everything already sent, then let the SINGLE consumer
    // forward it in order and close the flow once the channel drains.
    //
    // The terminal event must not be sent straight to the flow: it would jump ahead of frames still
    // sitting in the channel, and the sink would see an error before the audio that preceded it.
    // It also must not rely on close() alone: close() cancels the consumer, so an event merely
    // buffered before it can be cancelled undelivered and the turn completes with NO error in it --
    // which the sink renders as silence. Enqueuing it and finishing the channel gives both ordering
    // and delivery, because the consumer, not the caller, decides when the flow ends.
    private fun ProducerScope<CloudRouteEvent>.emitTerminal(
        channel: Channel<CloudRouteEvent>,
        event: CloudRouteEvent,
    ) {
        val result = channel.trySend(event)
        if (result.isFailure) {
            // The terminal event could not be enqueued, so closing normally would end the turn with
            // the reason for ending it lost -- the sink turns a missing error into silence. Close
            // with the CAUSE instead: the consumer surfaces it as a thrown failure, which the sink
            // can act on, and correctness no longer depends on which buffer filled first.
            Timber.w(
                "Cloud route: terminal event could not be enqueued, failing the turn — %s",
                event::class.simpleName,
            )
            channel.close(
                IllegalStateException(
                    "Cloud route: channel full, could not deliver terminal " +
                        "${event::class.simpleName}",
                ),
            )
            return
        }
        channel.close()
    }

    private fun emitOrFail(channel: Channel<CloudRouteEvent>, event: CloudRouteEvent) {
        val result = channel.trySend(event)
        if (result.isFailure) {
            Timber.w("Cloud route: channel full, terminating turn — dropped event %s", event::class.simpleName)
            // Try to send a CONNECTION_LOST error to terminate the turn.
            // If this also fails, close the channel to signal termination.
            val errorResult = channel.trySend(
                CloudRouteEvent.Error(code = CloudRouteErrorCodes.CONNECTION_LOST, message = ""),
            )
            if (errorResult.isFailure) {
                channel.close()
            }
        }
    }

    private fun CloudTurnFrame.AppOperationResult.toWireJson(): String = CloudRouteJson.appOperationResultAdapter.toJson(this)

    private fun CloudTurnFrame.toJson(): String = when (this) {
        is CloudTurnFrame.Authenticate -> CloudRouteJson.authenticateAdapter.toJson(this)
        is CloudTurnFrame.AppOperationResult -> CloudRouteJson.appOperationResultAdapter.toJson(this)
    }

    private companion object {
        const val ROUTE_PATH = "/api/v1/cloud/route"
    }
}
