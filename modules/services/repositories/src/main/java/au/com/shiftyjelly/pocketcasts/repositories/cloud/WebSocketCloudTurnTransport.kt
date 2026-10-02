package au.com.shiftyjelly.pocketcasts.repositories.cloud

import java.io.IOException
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
        // The negotiated codec arrives in the handshake; the binary frames carry no codec of their
        // own, so the client remembers what it offered and asks for the first it can play.
        val requestedCodec = (frame as CloudTurnFrame.Authenticate).codecs.firstOrNull().orEmpty()
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + ROUTE_PATH)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // The authentication frame is the whole request, so it goes first and alone.
                webSocket.send(frame.toJson())
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                trySend(CloudRouteEvent.AudioFrame(codec = requestedCodec, bytes = bytes.toByteArray()))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = CloudRouteEvents.decode(text)
                if (event == null) {
                    Timber.w("Cloud route frame could not be decoded")
                    trySend(CloudRouteEvent.Error(code = CloudRouteErrorCodes.INVALID_RESPONSE, message = ""))
                } else {
                    trySend(event)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // A socket failure is a transport failure, never a payload one: the client maps it
                // to connection_lost rather than invalid_response.
                Timber.w(t, "Cloud route socket failed")
                trySend(CloudRouteEvent.Error(code = CloudRouteErrorCodes.CONNECTION_LOST, message = ""))
                close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close()
            }
        }

        val socket = okHttpClient.newWebSocket(request, listener)
        awaitClose { socket.cancel() }
    }

    private fun CloudTurnFrame.toJson(): String = when (this) {
        is CloudTurnFrame.Authenticate -> CloudRouteJson.authenticateAdapter.toJson(this)
    }

    private companion object {
        const val ROUTE_PATH = "/api/v1/cloud/route"
    }
}
