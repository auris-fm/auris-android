package au.com.shiftyjelly.pocketcasts.repositories.cloud

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
 * by their `type` discriminator through the same Moshi adapters the SSE path used.
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

        // The requested codec is a fallback; the server's auth response supplies the
        // negotiated codec that binary frames should carry.
        val requestedCodec = (frame as CloudTurnFrame.Authenticate).codecs.firstOrNull().orEmpty()
        val negotiatedCodec = AtomicReference<String>(requestedCodec)
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + ROUTE_PATH)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (response.code != 101) {
                    Timber.w("Cloud route WebSocket rejected: %d %s", response.code, response.message)
                    emitOrFail(
                        channel,
                        CloudRouteEvent.Error(
                            code = CloudRouteErrorCodes.CONNECTION_LOST,
                            message = "",
                        ),
                    )
                    close()
                    return
                }
                webSocket.send(frame.toJson())
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val codec = negotiatedCodec.get()
                emitOrFail(channel, CloudRouteEvent.AudioFrame(codec = codec, bytes = bytes.toByteArray()))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = CloudRouteEvents.decode(text)
                if (event == null) {
                    Timber.w("Cloud route frame could not be decoded")
                    emitOrFail(channel, CloudRouteEvent.Error(code = CloudRouteErrorCodes.INVALID_RESPONSE, message = ""))
                } else if (event is CloudRouteEvent.Connected) {
                    // The handshake is where the negotiated codec arrives; binary frames carry it.
                    negotiatedCodec.set(event.codec.ifBlank { requestedCodec })
                    emitOrFail(channel, event)
                } else {
                    emitOrFail(channel, event)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Timber.w(t, "Cloud route socket failed")
                emitOrFail(channel, CloudRouteEvent.Error(code = CloudRouteErrorCodes.CONNECTION_LOST, message = ""))
                close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close()
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
