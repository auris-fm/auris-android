package au.com.shiftyjelly.pocketcasts.repositories.cloud

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
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

    private fun openWith(terminal: (WebSocketListener, WebSocket) -> Unit): CloudTurnConnection = runBlocking {
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
            listener.onFailure(socket, IOException("died during dial"), null)
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
    fun `a terminal event arriving after the install marks the connection dead`() {
        // The POST-INSTALL side: the connection object exists, so the callback must reach it through
        // the reference rather than through the flag. This is not the race window -- an event here
        // was handled correctly before the shared lock too.
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
        val connection = runBlocking { opener.opener.openFor("tag-" + System.nanoTime()) }

        // The socket dies now, after the connection object exists, so the reference is what carries
        // the marking.
        listener.onFailure(socket, IOException("died after install"), null)
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
    fun `a terminal event that runs before the publish is still applied to the connection`() {
        // The PRE-PUBLISH side, made deterministic: the seam fires the event on another thread and
        // waits for it to finish, so it strictly precedes the publish and the flag is the only thing
        // that can record it. Repeated because the delivering thread is real.
        //
        // WHAT THESE CONTROLS DO NOT COVER: the race window itself, where the listener reads a null
        // reference, the install then publishes and reads a still-unset flag, and only then does the
        // listener set the flag. Every event here lands either wholly before the dial returns or
        // wholly after the install, and both of those were already handled correctly. The shared lock
        // is therefore verified against the source, not by a failing test.
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
                val fired = CountDownLatch(1)
                opener.dialSocket = { _, listener ->
                    val racer = Thread {
                        listener.onFailure(socket, IOException("died during dial"), null)
                        fired.countDown()
                    }
                    racer.start()
                    // WAIT for the event to have run: strictly before the connection is published.
                    fired.await()
                    socket
                }
                val connection = runBlocking { opener.opener.openFor("tag-$attempt") }
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
