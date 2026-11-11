package au.com.shiftyjelly.pocketcasts.repositories.cloud

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The raw-listener bridge of the early connection: what the listener the production dial installs
 * does with a BINARY frame. The adoption controls feed [RawSocketEvent]s into the channel
 * directly, which cannot catch the listener writing the WRONG event — the production gap this
 * pins: a binary frame received AFTER adoption was treated as a pre-adoption protocol violation,
 * killing the connection and dropping the server's audio.
 *
 * The fake client hands the listener through and lets the test deliver frames exactly as OkHttp
 * would, through the real listener, at controlled points of the dial.
 */
class EarlyConnectionBinaryFrameControlTest {

    /**
     * Captures the listener the dial installed. [holdDial] makes newWebSocket block until
     * [releaseDial], so a frame can be delivered inside the pre-adoption window deterministically
     * (newWebSocket returning is what lets dial's connectionRef.set run).
     */
    private class ListenerCapturingClient(private val holdDial: Boolean = false) : OkHttpClient() {
        val installed = java.util.concurrent.LinkedBlockingQueue<WebSocketListener>()
        private val gate = CountDownLatch(1)

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            installed.add(listener)
            if (holdDial) gate.await()
            return FakeWebSocket
        }

        fun releaseDial() = gate.countDown()
    }

    private object FakeWebSocket : WebSocket {
        override fun request(): Request = Request.Builder().url("https://example.invalid").build()
        override fun send(text: String): Boolean = false
        override fun send(bytes: ByteString): Boolean = false
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() {}
        override fun queueSize(): Long = 0L
    }

    private fun early(client: OkHttpClient) = SocketOpeningEarlyConnections(
        baseUrl = { "wss://example.invalid" },
        now = { 0L },
        okHttpClient = client,
        dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
    )

    @Test
    fun `a binary frame after adoption is forwarded as audio, not treated as a violation`() {
        val client = ListenerCapturingClient()
        val instance = early(client)
        val connection = runBlocking { withTimeout(5_000) { instance.opener.openFor("utt-1") } }
        val listener = client.installed.poll(5, java.util.concurrent.TimeUnit.SECONDS)!!
        runBlocking { withTimeout(5_000) { instance.opener.shutdown() } }
        val events = requireNotNull(connection.events)

        // Adoption has happened the moment the connection exists (the turn sends its auth over
        // it): a binary frame now is the server's audio.
        listener.onMessage(FakeWebSocket, byteArrayOf(1, 2, 3).toByteString())

        val event = runBlocking { withTimeout(5_000) { events.receive() } }
        assertTrue(
            "post-adoption binary must be forwarded as audio, got $event",
            event is RawSocketEvent.Binary,
        )
        assertEquals(byteArrayOf(1, 2, 3).toList(), (event as RawSocketEvent.Binary).bytes.toList())
    }

    @Test
    fun `a binary frame in the pre-adoption window is still a protocol violation`() = runBlocking {
        val client = ListenerCapturingClient(holdDial = true)
        val instance = early(client)
        val openerJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            instance.opener.openFor("utt-2")
        }
        val listener = client.installed.poll(5, java.util.concurrent.TimeUnit.SECONDS)!!
        // The dial is blocked INSIDE newWebSocket: connectionRef is still null. This is the real
        // pre-adoption window.
        listener.onMessage(FakeWebSocket, byteArrayOf(9).toByteString())
        client.releaseDial()
        withTimeout(5_000) { openerJob.join() }

        // The frame must surface as a failure, not as audio, and must not kill the connection
        // before it exists (markDead on a null ref is a no-op by design).
        val connection = requireNotNull(instance.opener.takeFor("utt-2"))
        withTimeout(5_000) { instance.opener.shutdown() }
        val event = requireNotNull(connection.events).receive()
        assertTrue(
            "pre-adoption binary must surface as a failure, got $event",
            event is RawSocketEvent.Failed,
        )
        assertEquals(0, (event as RawSocketEvent.Failed).code)
        assertTrue(
            "the violation must not mark the fresh connection dead",
            !connection.isDead,
        )
    }
}
