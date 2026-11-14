package au.com.shiftyjelly.pocketcasts.repositories.cloud

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import timber.log.Timber

/**
 * Runs a turn over a WebSocket: connect, send the authentication frame as the first message, then
 * emit the server's frames as [CloudRouteEvent]s.
 *
 * Binary frames are audio in the negotiated codec. Text frames carry the event vocabulary, decoded
 * by their `type` discriminator through the shared [CloudRouteJson] adapters.
 */
class WebSocketCloudTurnTransport(
    private val baseUrl: String,
    private val okHttpClient: OkHttpClient,
    private val observer: CloudTurnTransportObserver? = null,
) : CloudTurnTransport {

    /**
     * Dial the route socket.
     *
     * The default is a plain dial. A turn adopting an early connection supplies one instead, which
     * is what lets the socket outlive the collection that will consume it.
     */
    internal var dial: (Request, WebSocketListener) -> WebSocket = { request, listener ->
        okHttpClient.newWebSocket(request, listener)
    }

    /**
     * A connection opened before this turn existed, to consume instead of dialling.
     *
     * Consume-once: reading clears it, so the same socket can never be handed to two turns.
     */
    private val adopted = java.util.concurrent.atomic.AtomicReference<CloudTurnConnection?>(null)

    /** Offer [connection] to the next exchange. */
    internal fun adopt(connection: CloudTurnConnection) {
        adopted.set(connection)
    }

    override fun exchange(frame: CloudTurnFrame): Flow<CloudRouteEvent> = callbackFlow {
        // Use a large bounded channel (4096 events) so the WebSocket listener can
        // buffer frames without silently dropping them. With an unbounded channel,
        // events would accumulate indefinitely if the collector is gone; with a
        // bounded channel, we either deliver or fail the turn explicitly.
        val channel = Channel<CloudRouteEvent>(4096)

        val offeredCodecs = (frame as CloudTurnFrame.Authenticate).codecs
        val negotiatedCodec = AtomicReference<String?>(null)
        // A socket failure is only news about a turn that is still waiting for one. These two
        // record the ways a turn stops waiting: the server sent its terminal, or the collector
        // went away and cancelled the exchange. After either, the socket is being closed because
        // the turn does not need it, and a close coming back through onFailure is teardown.
        val terminalArrived = java.util.concurrent.atomic.AtomicBoolean(false)
        val exchangeCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        // Built only when this exchange dials. An adopted exchange has no use for it: the socket
        // and its upgrade request already exist, and building one here would pretend the early
        // open had not happened.

        // The ONE authentication send both paths use: encode, observe, send, observe the outcome.
        // The dialled socket sends from its listener's onOpen; an adopted connection has no onOpen
        // of its own any more, so its exchange sends through this same helper — the two paths
        // cannot drift.
        fun sendAuthFrame(requestId: String, send: (String) -> Boolean) {
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
                sent = send(json)
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

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val requestId = frame.requestId
                if (response.code != 101) {
                    Timber.w("Cloud route WebSocket rejected: %d %s", response.code, response.message)
                    // Handshake rejected — socket opened but upgrade refused. Not a send rejection
                    // because send was never attempted. This is always news: the turn is waiting
                    // for a handshake it will never get.
                    observer?.onConnectionFailed(requestId, stillNeeded = true)
                    emitTerminal(
                        channel,
                        CloudRouteEvent.Error(
                            code = CloudRouteErrorCodes.CONNECTION_LOST,
                            message = "",
                        ),
                    )
                    return
                }
                observer?.onSocketOpened(requestId)
                sendAuthFrame(requestId) { json -> webSocket.send(json) }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val codec = negotiatedCodec.get()
                if (codec == null) {
                    emitTerminal(channel, CloudRouteEvent.Error(CloudRouteErrorCodes.INVALID_RESPONSE, ""))
                    return
                }
                emitOrFail(channel, CloudRouteEvent.AudioFrame(codec = codec, bytes = bytes.toByteArray()))
            }

            override fun onMessage(webSocket: WebSocket, text: String) = acceptText(text)

            // Shared by the dialled socket and the adopted replay, so the two paths cannot drift:
            // decoding and handshake validation happen in exactly one place. `webSocket` is unused
            // because every branch works through `channel`.
            fun acceptText(text: String) {
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
                    if (event is CloudRouteEvent.Done || event is CloudRouteEvent.Error) {
                        terminalArrived.set(true)
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
                Timber.w(t, "Cloud route socket failed")
                // A close after the terminal or after cancellation is the teardown this exchange
                // asked for, not a connection the turn lost.
                observer?.onConnectionFailed(
                    requestId,
                    stillNeeded = !terminalArrived.get() && !exchangeCancelled.get(),
                )
                emitTerminal(
                    channel,
                    CloudRouteEvent.Error(code = CloudRouteErrorCodes.CONNECTION_LOST, message = ""),
                )
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // A clean server close ends the turn with no error event: finish the channel so the
                // consumer drains what it has and then ends the flow.
                channel.close()
            }
        }

        // A connection opened for this utterance BEFORE the turn existed is consumed instead of
        // dialled. It was opened early so the handshake could overlap the endpoint wait; dialling
        // again here would hand back the latency the optimisation exists to save.
        //
        // Its listener has been writing undecoded events since before this turn existed, so they
        // are replayed into THIS exchange's channel: decoding stays in one place, and the adopted
        // path cannot drift from the dialled one. They are buffered because the socket can open,
        // fail or close in the window between the early open and this turn adopting it.
        val early = adopted.getAndSet(null)
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + ROUTE_PATH)
            .build()
        val socket: WebSocket? = if (early != null) null else dial(request, listener)
        val replayJob = early?.let { connection ->
            // An early connection's exchange owns the socket the moment it adopts it. The first
            // frame is this exchange's job now: sent immediately when the open is already buffered,
            // otherwise the moment the open arrives. After adoption every event is forwarded —
            // binary included, because audio after the handshake is the turn's payload — and only
            // PRE-adoption failures or closes end the turn, replayed exactly as the dialled
            // listener would have reported them.
            connection.events?.let { events ->
                launch {
                    var authSent = false
                    fun ensureAuthSent() {
                        if (authSent) return
                        authSent = true
                        sendAuthFrame(frame.requestId) { json -> connection.send(json) }
                    }
                    for (raw in events) {
                        when (raw) {
                            // Through the same handler the dialled socket uses, so the adopted
                            // path gets identical handshake validation instead of its own copy.
                            is RawSocketEvent.Text -> listener.acceptText(raw.text)

                            RawSocketEvent.Opened -> ensureAuthSent()

                            // Post-adoption binary is audio, not a protocol violation: same
                            // negotiated-codec guard the dialled listener applies.
                            is RawSocketEvent.Binary -> {
                                val codec = negotiatedCodec.get()
                                if (codec == null) {
                                    emitTerminal(channel, CloudRouteEvent.Error(CloudRouteErrorCodes.INVALID_RESPONSE, ""))
                                } else {
                                    emitOrFail(channel, CloudRouteEvent.AudioFrame(codec = codec, bytes = raw.bytes))
                                }
                            }

                            is RawSocketEvent.Failed -> {
                                observer?.onConnectionFailed(
                                    frame.requestId,
                                    stillNeeded = !terminalArrived.get() && !exchangeCancelled.get(),
                                )
                                channel.trySend(CloudRouteEvent.Error(CloudRouteErrorCodes.CONNECTION_LOST, ""))
                                channel.close()
                                return@launch
                            }

                            is RawSocketEvent.Closed -> {
                                channel.close()
                                return@launch
                            }
                        }
                    }
                }
            } ?: run {
                // No replayable events (a handle that owns no socket buffer): the open has
                // already happened by construction, so the exchange sends now.
                launch { sendAuthFrame(frame.requestId) { json -> connection.send(json) } }
            }
        }

        // Launch collection BEFORE awaitClose so it runs concurrently with the
        // cancelled, so any code after it would not execute while the socket
        // is active. The launched job forwards channel events to the flow,
        // and is cancelled when the flow is cancelled (which also cancels the
        // socket via the awaitClose cleanup).
        val collectionJob = launch {
            try {
                channel.consumeEach { event ->
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
            // From here the turn has stopped collecting, so any close the socket reports is this
            // teardown coming back through the listener rather than a connection it lost.
            exchangeCancelled.set(true)
            // An adopted socket belongs to ITS EXCHANGE once adopted: completion, error and
            // cancellation all close it here, so a finished turn can never leave a socket open.
            // Before adoption it belongs to its connection, whose own bookkeeping closes it.
            socket?.cancel()
            early?.close()
            replayJob?.cancel()
            collectionJob.cancel()
        }
    }

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

    private fun CloudTurnFrame.toJson(): String = when (this) {
        is CloudTurnFrame.Authenticate -> CloudRouteJson.authenticateAdapter.toJson(this)
    }

    internal companion object {
        const val ROUTE_PATH = "/api/v1/cloud/route"
    }
}
