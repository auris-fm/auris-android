package au.com.shiftyjelly.pocketcasts.repositories.cloud

import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * The production [EarlyConnectionOpener] for the cloud route: one that actually dials.
 *
 * The opener is policy and was built dial-agnostic; this class supplies the piece it needs to be
 * real — an `open` that upgrades the route WebSocket before the turn exists, with a listener
 * writing undecoded [RawSocketEvent]s into a channel the connection owns. The adopting
 * `exchange` decodes them with the same code it would have used had it dialled itself, so the
 * early and dialled paths cannot drift.
 *
 * Under the single-frame protocol the server sends nothing until the client's first frame, so the
 * channel normally stays empty until adoption; it is buffered anyway, so a close or failure that
 * lands in the window between the early open and the adoption is still observable.
 */
class SocketOpeningEarlyConnections(
    /** Resolved at open time, not construction, so a settings change is picked up on the next open. */
    private val baseUrl: () -> String,
    private val now: () -> Long,
    private val okHttpClient: OkHttpClient = CloudTurnRoute.defaultOkHttpClient(),
    /** One owning dispatcher for open, take and replace, as the opener requires. */
    private val dispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "early-conn-owner")
    }.asCoroutineDispatcher(),
    private val eventBufferSize: Int = 64,
) {
    val opener: EarlyConnectionOpener =
        EarlyConnectionOpener(
            dispatcher = dispatcher,
            open = { tag, openedAtMs -> dial(tag, openedAtMs) },
            now = now,
        )

    /**
     * Upgrade the route socket and wrap it in a connection owning it.
     *
     * No frame is sent here: the auth frame belongs to the turn that adopts the socket, which is
     * why the listener carries only raw events. The request is the same one the transport dials,
     * so the server sees an identical upgrade on both paths.
     */
    private fun dial(tag: String, openedAtMs: Long): CloudTurnConnection {
        val events = Channel<RawSocketEvent>(eventBufferSize)
        // Set the moment the connection object exists; the listener reads it to mark the
        // connection dead on any failure or close. Null only in the window before that, in which
        // case there is nothing to mark.
        val connectionRef = java.util.concurrent.atomic.AtomicReference<SocketCloudTurnConnection?>(null)
        val request = Request.Builder()
            .url(baseUrl().trimEnd('/') + WebSocketCloudTurnTransport.ROUTE_PATH)
            .build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (response.code != 101) {
                    connectionRef.get()?.markDead()
                    events.trySendBlocking(RawSocketEvent.Failed(code = response.code, message = response.message))
                } else {
                    // 101 says nothing the adopting turn does not already know: it opened. Recorded
                    // as an event anyway so a consumer can tell a completed upgrade from a silent one.
                    events.trySendBlocking(RawSocketEvent.Opened)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                events.trySendBlocking(RawSocketEvent.Text(text))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (connectionRef.get() == null) {
                    // Binary before adoption is a protocol violation: the server sends nothing
                    // until the client's auth frame, which has not been sent. Surface it as a
                    // failure rather than dropping a frame the adopting turn would wait for
                    // forever. (Null ref: nothing to mark dead yet.)
                    events.trySendBlocking(RawSocketEvent.Failed(code = 0, message = "binary frame before adoption"))
                } else {
                    // After adoption the binary frames are the server's audio; the adopting turn
                    // decodes them with the same code a dialled connection would have used.
                    events.trySendBlocking(RawSocketEvent.Binary(bytes.toByteArray()))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connectionRef.get()?.markDead()
                events.trySendBlocking(RawSocketEvent.Failed(code = response?.code ?: 0, message = t.message ?: "failed"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connectionRef.get()?.markDead()
                events.trySendBlocking(RawSocketEvent.Closed(code, reason))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        }
        val socket = okHttpClient.newWebSocket(request, listener)
        val connection = SocketCloudTurnConnection(
            ownerTag = tag,
            openedAtMs = openedAtMs,
            onClose = { socket.cancel() },
            events = events,
            socket = socket,
        )
        connectionRef.set(connection)
        return connection
    }
}
