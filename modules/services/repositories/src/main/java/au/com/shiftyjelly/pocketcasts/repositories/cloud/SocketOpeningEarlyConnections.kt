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
    /**
     * The dial seam, as [WebSocketCloudTurnTransport] has. OkHttpClient is final, so a control that
     * needs a socket dying at a chosen moment substitutes this instead, firing a terminal callback
     * inside the dial window where the pre-connection flag is the only record of it.
     */
    internal var dialSocket: (Request, WebSocketListener) -> WebSocket = { request, listener ->
        okHttpClient.newWebSocket(request, listener)
    }

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
        // Terminal socket state (close/failure/violation) can land while dial is still inside
        // newWebSocket, BEFORE connectionRef exists to mark dead. The connection created later
        // must not present itself alive over a socket that already died: remember the terminal
        // state here and apply it to the connection the moment it exists.
        val socketDiedBeforeConnection = java.util.concurrent.atomic.AtomicBoolean(false)
        // Serialises "publish the reference / read the flag" against the listener's "set the flag /
        // read the reference", which is what makes the install check exhaustive.
        val disseminationLock = Any()
        fun rememberTerminalState() = synchronized(disseminationLock) {
            socketDiedBeforeConnection.set(true)
            connectionRef.get()?.markDead()
        }
        val request = Request.Builder()
            .url(baseUrl().trimEnd('/') + WebSocketCloudTurnTransport.ROUTE_PATH)
            .build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (response.code != 101) {
                    rememberTerminalState()
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
                    // forever.
                    events.trySendBlocking(RawSocketEvent.Failed(code = 0, message = "binary frame before adoption"))
                } else {
                    // After adoption the binary frames are the server's audio; the adopting turn
                    // decodes them with the same code a dialled connection would have used.
                    events.trySendBlocking(RawSocketEvent.Binary(bytes.toByteArray()))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                rememberTerminalState()
                events.trySendBlocking(RawSocketEvent.Failed(code = response?.code ?: 0, message = t.message ?: "failed"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                rememberTerminalState()
                events.trySendBlocking(RawSocketEvent.Closed(code, reason))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        }
        val socket = dialSocket(request, listener)
        val connection = SocketCloudTurnConnection(
            ownerTag = tag,
            openedAtMs = openedAtMs,
            onClose = { socket.cancel() },
            events = events,
            socket = socket,
        )
        // Publishing the reference and reading the flag happen under the SAME lock the listener
        // takes, so a terminal event cannot fall between them: it runs either wholly before this
        // block, leaving its flag visible here, or wholly after it, seeing the published reference
        // and marking the connection itself. Ordering the two writes without the lock leaves a gap
        // in both directions, because an event that reads a null reference marks nothing.
        synchronized(disseminationLock) {
            connectionRef.set(connection)
            if (socketDiedBeforeConnection.get()) connection.markDead()
        }
        return connection
    }
}
