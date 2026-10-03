package au.com.shiftyjelly.pocketcasts.repositories.cloud

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
        // callbackFlow creates an unbounded channel (Int.MAX_VALUE) by default,
        // so trySend never rejects from buffer pressure. We prefer trySend over
        // send because the WebSocket callbacks are non-suspending and cannot
        // call suspend functions. Dropped events only occur when the channel
        // is already closed (collector gone), in which case the turn is unwinding
        // and there is no consumer anyway.
        //
        // We log drops for observability; the unbounded channel means drops are
        // rare and only happen during turn cleanup.

        // The requested codec is a fallback; the server's auth response supplies the
        // negotiated codec that binary frames should carry.
        val requestedCodec = (frame as CloudTurnFrame.Authenticate).codecs.firstOrNull().orEmpty()
        val negotiatedCodec = AtomicReference<String>(requestedCodec)
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + ROUTE_PATH)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // A non-101 response means the server rejected the upgrade; send an error
                // and close so the caller knows the transport failed.
                if (response.code != 101) {
                    Timber.w("Cloud route WebSocket rejected: %d %s", response.code, response.message)
                    emitOrLog(CloudRouteEvent.Error(
                        code = CloudRouteErrorCodes.CONNECTION_LOST,
                        message = "",
                    ))
                    close()
                    return
                }
                // The authentication frame is the whole request, so it goes first and alone.
                webSocket.send(frame.toJson())
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val codec = negotiatedCodec.get()
                emitOrLog(CloudRouteEvent.AudioFrame(codec = codec, bytes = bytes.toByteArray()))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = CloudRouteEvents.decode(text)
                if (event == null) {
                    Timber.w("Cloud route frame could not be decoded")
                    emitOrLog(CloudRouteEvent.Error(code = CloudRouteErrorCodes.INVALID_RESPONSE, message = ""))
                } else if (event is CloudRouteEvent.AuthResponse) {
                    // Capture the server's negotiated codec for binary audio frames.
                    negotiatedCodec.set(event.codec.ifBlank { requestedCodec })
                    emitOrLog(event)
                } else {
                    emitOrLog(event)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // A socket failure is a transport failure, never a payload one: the client maps it
                // to connection_lost rather than invalid_response.
                Timber.w(t, "Cloud route socket failed")
                emitOrLog(CloudRouteEvent.Error(code = CloudRouteErrorCodes.CONNECTION_LOST, message = ""))
                close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close()
            }
        }

        val socket = okHttpClient.newWebSocket(request, listener)
        awaitClose { socket.cancel() }
    }

    /**
     * Emit an event or log a drop if the channel is closed.
     *
     * The callbackFlow's channel is unbounded, so buffer-pressure drops don't occur.
     * A drop here means the collector is already gone (the turn is unwinding).
     */
    private fun emitOrLog(event: CloudRouteEvent) {
        val result = trySend(event)
        if (result.isFailure) {
            Timber.w("Cloud route: dropped event %s (channel closed)", event::class.simpleName)
        }
    }

    private fun CloudTurnFrame.toJson(): String = when (this) {
        is CloudTurnFrame.Authenticate -> CloudRouteJson.authenticateAdapter.toJson(this)
    }

    private companion object {
        const val ROUTE_PATH = "/api/v1/cloud/route"
    }
}
