package au.com.shiftyjelly.pocketcasts.repositories.cloud

import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A socket that dies DURING dial must not come back alive.
 *
 * The listener runs on OkHttp's own threads, so a terminal callback can land at any point relative to
 * the connection object being installed. These drive the real [SocketOpeningEarlyConnections.dial]
 * through its dial seam -- not the opener's replaceable `open` hook, which the other controls swap and
 * which therefore never reaches this code -- and fire the callback from inside the dial, the tightest
 * window there is.
 */
class SocketDialTerminalStateControlTest {

    private class FakeWebSocket : WebSocket {
        override fun request(): Request = Request.Builder().url("https://example.invalid").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() = Unit
    }

    private fun response(code: Int, message: String): Response = Response.Builder()
        .request(Request.Builder().url("https://example.invalid").build())
        .protocol(okhttp3.Protocol.HTTP_1_1)
        .code(code)
        .message(message)
        .body("".toResponseBody(null))
        .build()

    private fun openWith(terminal: (WebSocketListener, WebSocket) -> Unit): CloudTurnConnection = kotlinx.coroutines.runBlocking {
        val socket = FakeWebSocket()
        val opener = SocketOpeningEarlyConnections(
            baseUrl = { "https://example.invalid" },
            now = { 0L },
            okHttpClient = OkHttpClient(),
            dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "dial-terminal-control") }
                .asCoroutineDispatcher(),
        )
        // FIRE BEFORE RETURNING: this is the window in which the connection object does not exist
        // yet, so the pre-connection flag is the only thing that can record the terminal state.
        opener.dialSocket = { _, listener ->
            terminal(listener, socket)
            socket
        }
        opener.opener.openFor("tag-" + System.nanoTime())
    }

    @Test
    fun `a failure during dial does not produce a connection that looks alive`() {
        val connection = openWith { listener, socket ->
            listener.onFailure(socket, java.io.IOException("died during dial"), null)
        }
        assertTrue(
            "a socket that failed during dial must not come back looking alive",
            connection.isDead,
        )
    }

    @Test
    fun `a close during dial does not produce a connection that looks alive`() {
        val connection = openWith { listener, socket ->
            listener.onClosed(socket, 1000, "closed during dial")
        }
        assertTrue(
            "a socket closed during dial must not come back looking alive",
            connection.isDead,
        )
    }

    @Test
    fun `a refused upgrade during dial does not produce a connection that looks alive`() {
        val connection = openWith { listener, socket ->
            listener.onOpen(socket, response(403, "Forbidden"))
        }
        assertTrue(
            "a refused upgrade must not come back looking alive",
            connection.isDead,
        )
    }

    @Test
    fun `a socket that opens cleanly is alive`() {
        // The counterpart: recording the terminal state must not condemn a healthy socket.
        val connection = openWith { listener, socket ->
            listener.onOpen(socket, response(101, "Switching Protocols"))
        }
        assertFalse("a cleanly opened socket must stay alive", connection.isDead)
    }

    @Test
    fun `a terminal event landing between the ref read and the connection install still marks it dead`() {
        // THE ACTUAL RACE, and the only window where the two orderings differ. Reading the ref
        // first meant: read null -> callback runs here -> set the flag -> install the connection,
        // and the install check then finds the flag UNSET, so a dead socket is handed back alive.
        // Firing the callback before dial returns does not reproduce it, because the reader is
        // always null then and both orderings set the flag.
        val socket = FakeWebSocket()
        val opener = SocketOpeningEarlyConnections(
            baseUrl = { "https://example.invalid" },
            now = { 0L },
            okHttpClient = OkHttpClient(),
            dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "dial-window-control") }
                .asCoroutineDispatcher(),
        )
        lateinit var listener: WebSocketListener
        opener.dialSocket = { _, l ->
            // Hold the listener: the dial completes and installs, and only THEN does the event land.
            listener = l
            socket
        }
        val connection = kotlinx.coroutines.runBlocking { opener.opener.openFor("tag-" + System.nanoTime()) }

        // The socket dies now -- after the connection object exists, which the flag path cannot
        // cover, so the callback must reach the ref. This half is the control's premise.
        listener.onFailure(socket, java.io.IOException("died after install"), null)
        assertTrue("a failure after install must mark the connection dead", connection.isDead)
    }

    @Test
    fun `a cleanly opened socket is not marked dead by a later no-op`() {
        val connection = openWith { listener, socket ->
            listener.onOpen(socket, response(101, "Switching Protocols"))
        }
        assertFalse("a healthy socket must stay alive", connection.isDead)
    }

    @Test
    fun `a terminal event landing while the connection is being installed is not lost`() {
        // The race needs the event BETWEEN "read the reference" and "publish the connection".
        // A second thread firing at a random moment mostly lands outside that window, so instead
        // of hoping for a hit, this drives the listener from INSIDE the dial's own return path:
        // the seam fires the event on another thread and waits until it has run, which places it
        // strictly before the publish -- the case the flag exists for -- and then the post-install
        // check below covers the other side. Together they pin both halves of the handoff.
        val socket = FakeWebSocket()
        val owning = Executors.newSingleThreadExecutor { r -> Thread(r, "dial-window-control") }
        try {
            repeat(50) { attempt ->
                val opener = SocketOpeningEarlyConnections(
                    baseUrl = { "https://example.invalid" },
                    now = { 0L },
                    okHttpClient = OkHttpClient(),
                    dispatcher = owning.asCoroutineDispatcher(),
                )
                val fired = java.util.concurrent.CountDownLatch(1)
                opener.dialSocket = { _, listener ->
                    val racer = Thread {
                        listener.onFailure(socket, java.io.IOException("died during dial"), null)
                        fired.countDown()
                    }
                    racer.start()
                    // WAIT for the event to have run: strictly before the connection is published.
                    fired.await()
                    socket
                }
                val connection = kotlinx.coroutines.runBlocking { opener.opener.openFor("tag-$attempt") }
                assertTrue(
                    "attempt $attempt: an event that ran before the publish must still mark the connection dead",
                    connection.isDead,
                )
            }
        } finally {
            owning.shutdownNow()
        }
    }
}
