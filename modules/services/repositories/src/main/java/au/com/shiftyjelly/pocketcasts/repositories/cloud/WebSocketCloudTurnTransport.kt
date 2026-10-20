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
) : CloudTurnTransport {

    override fun exchange(frame: CloudTurnFrame): Flow<CloudRouteEvent> = callbackFlow {
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

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (response.code != 101) {
                    Timber.w("Cloud route WebSocket rejected: %d %s", response.code, response.message)
                    emitTerminal(
                        channel,
                        CloudRouteEvent.Error(
                            code = CloudRouteErrorCodes.CONNECTION_LOST,
                            message = "",
                        ),
                    )
                    return
                }
                webSocket.send(frame.toJson())
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
                    emitOrFail(channel, event)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Timber.w(t, "Cloud route socket failed")
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

        val socket = okHttpClient.newWebSocket(request, listener)

        // Launch collection BEFORE awaitClose so it runs concurrently with the
        // socket being open. awaitClose suspends until the callbackFlow is
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
            socket.cancel()
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

    private companion object {
        const val ROUTE_PATH = "/api/v1/cloud/route"
    }
}
