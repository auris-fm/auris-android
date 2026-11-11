package au.com.shiftyjelly.pocketcasts.repositories.cloud

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adopting a connection opened before the turn existed.
 *
 * The whole point of an early connection is that the socket is already open when the turn is ready,
 * so `exchange` must consume it INSTEAD of dialling. These controls enter through `exchange` and
 * watch the dial seam, because a control that pokes the adoption slot directly would pass while
 * `exchange` ignored it entirely.
 */
class EarlyConnectionAdoptionControlTest {

    private val turn = CloudTurnFrame.Authenticate(
        accessToken = "tok",
        requestId = "utt-1",
        request = "next episode",
        context = CloudRouteContext(episodeId = "e-1", clientPositionMs = 0L),
        capabilities = emptyList(),
        codecs = listOf("pcm_s16le@24k"),
        conversationId = "11111111-1111-4111-8111-111111111111",
        conversationMode = CloudConversationMode.Create,
    )

    /** A held connection whose buffered events the test supplies. */
    private fun connection(tag: String, events: Channel<RawSocketEvent>) = SocketCloudTurnConnection(tag, openedAtMs = 0L, events = events)

    @Test
    fun `an adopted connection spares the dial`() = runTest {
        var dialled = 0
        val transport = WebSocketCloudTurnTransport(
            baseUrl = "https://example.invalid",
            okHttpClient = OkHttpClient(),
        )
        transport.dial = { _: Request, _: WebSocketListener ->
            dialled += 1
            throw IllegalStateException("adoption must not dial")
        }
        transport.adopt(connection("utt-1", Channel(Channel.UNLIMITED)))

        withTimeoutOrNull(2_000) { transport.exchange(turn).first() }

        assertEquals("an adopted connection must not be dialled again", 0, dialled)
    }

    @Test
    fun `an adopted connection's buffered frames reach the turn`() = runTest {
        val events = Channel<RawSocketEvent>(Channel.UNLIMITED)
        val transport = WebSocketCloudTurnTransport(
            baseUrl = "https://example.invalid",
            okHttpClient = OkHttpClient(),
        )
        transport.dial = { _: Request, _: WebSocketListener -> throw IllegalStateException("must not dial") }
        // Written BEFORE the exchange collects: the socket can answer in the window between the
        // early open and the turn adopting it, and those frames are exactly the ones a naive
        // implementation would drop.
        events.trySend(
            RawSocketEvent.Text(
                """{"type":"connected","codec":"pcm_s16le@24k","reservation_id":"r-1"}""",
            ),
        )
        transport.adopt(connection("utt-1", events))

        val firstEvent = withTimeoutOrNull(2_000) { transport.exchange(turn).first() }

        assertTrue(
            "a frame buffered before adoption must still reach the turn, got $firstEvent",
            firstEvent is CloudRouteEvent.Connected,
        )
    }

    @Test
    fun `a buffered failure ends the turn as a connection loss`() = runTest {
        val events = Channel<RawSocketEvent>(Channel.UNLIMITED)
        val transport = WebSocketCloudTurnTransport(
            baseUrl = "https://example.invalid",
            okHttpClient = OkHttpClient(),
        )
        transport.dial = { _: Request, _: WebSocketListener -> throw IllegalStateException("must not dial") }
        events.trySend(RawSocketEvent.Failed(code = 503, message = "unavailable"))
        transport.adopt(connection("utt-1", events))

        val firstEvent = withTimeoutOrNull(2_000) { transport.exchange(turn).first() }

        // A connection that failed before the turn adopted it is still a failed turn: the failure
        // must surface as an event, not be swallowed into a silent hang.
        assertTrue("expected a failure event, got $firstEvent", firstEvent is CloudRouteEvent.Error)
    }

    @Test
    fun `adoption is consumed once so one socket cannot serve two turns`() = runTest {
        val transport = WebSocketCloudTurnTransport(
            baseUrl = "https://example.invalid",
            okHttpClient = OkHttpClient(),
        )
        var dialled = 0
        transport.dial = { _: Request, _: WebSocketListener ->
            dialled += 1
            // The second turn has no early connection, so it dials; the stub must hand back a
            // socket rather than fail, or the failure would mask what is being measured.
            org.mockito.kotlin.mock<WebSocket>()
        }
        transport.adopt(connection("utt-1", Channel(Channel.UNLIMITED)))

        withTimeoutOrNull(2_000) { transport.exchange(turn).first() }
        // The second turn has no early connection of its own, so it must dial for itself.
        withTimeoutOrNull(2_000) { transport.exchange(turn).first() }

        assertEquals("the second exchange must dial", 1, dialled)
    }

    /** Records every send and cancellation on the socket an early connection owns. */
    class FakeWebSocket : WebSocket {
        val sends = mutableListOf<String>()
        var cancelled = false
        var closeRequested = false

        override fun request(): okhttp3.Request = Request.Builder().url("https://example.invalid").build()
        override fun send(text: String): Boolean {
            sends += text
            return true
        }
        override fun send(bytes: okio.ByteString): Boolean = false
        override fun close(code: Int, reason: String?): Boolean {
            closeRequested = true
            return true
        }
        override fun cancel() {
            cancelled = true
        }
        override fun queueSize(): Long = 0L
    }

    @Test
    fun `the adopted exchange sends the authentication frame itself`() = runTest {
        val fakeSocket = FakeWebSocket()
        val events = Channel<RawSocketEvent>(Channel.UNLIMITED)
        // Opened is the only pre-buffered event: the server sends nothing before the auth frame,
        // so the send must come from the exchange, the moment it adopts.
        events.trySend(RawSocketEvent.Opened)
        val transport = WebSocketCloudTurnTransport(
            baseUrl = "https://example.invalid",
            okHttpClient = OkHttpClient(),
        )
        transport.dial = { _: Request, _: WebSocketListener -> throw IllegalStateException("must not dial") }
        transport.adopt(
            SocketCloudTurnConnection("utt-1", openedAtMs = 0L, events = events, socket = fakeSocket),
        )

        withTimeoutOrNull(2_000) { transport.exchange(turn).first() }

        assertTrue(
            "the adopted exchange must send the auth frame on the adopted socket, sent=${fakeSocket.sends}",
            fakeSocket.sends.any { it.contains("\"type\":\"authenticate\"") && it.contains("utt-1") },
        )
    }

    @Test
    fun `binary audio after adoption reaches the turn`() = runTest {
        val events = Channel<RawSocketEvent>(Channel.UNLIMITED)
        val transport = WebSocketCloudTurnTransport(
            baseUrl = "https://example.invalid",
            okHttpClient = OkHttpClient(),
        )
        transport.dial = { _: Request, _: WebSocketListener -> throw IllegalStateException("must not dial") }
        events.trySend(RawSocketEvent.Opened)
        events.trySend(
            RawSocketEvent.Text(
                """{"type":"connected","codec":"pcm_s16le@24k","reservation_id":"r-1"}""",
            ),
        )
        val audio = byteArrayOf(1, 2, 3, 4)
        events.trySend(RawSocketEvent.Binary(audio))
        transport.adopt(connection("utt-1", events))

        val events2 = withTimeoutOrNull(2_000) { transport.exchange(turn).take(2).toList() }

        assertTrue("expected the handshake, got $events2", events2?.firstOrNull() is CloudRouteEvent.Connected)
        val frame = events2?.getOrNull(1)
        assertTrue("post-adoption binary must arrive as audio, got $frame", frame is CloudRouteEvent.AudioFrame)
        assertTrue("audio bytes must be intact", (frame as CloudRouteEvent.AudioFrame).bytes.contentEquals(audio))
    }

    @Test
    fun `the exchange closes the adopted socket when the turn ends`() = runTest {
        val fakeSocket = FakeWebSocket()
        var connectionClosed = false
        val events = Channel<RawSocketEvent>(Channel.UNLIMITED)
        events.trySend(RawSocketEvent.Opened)
        events.trySend(RawSocketEvent.Closed(1000, "done"))
        val transport = WebSocketCloudTurnTransport(
            baseUrl = "https://example.invalid",
            okHttpClient = OkHttpClient(),
        )
        transport.dial = { _: Request, _: WebSocketListener -> throw IllegalStateException("must not dial") }
        transport.adopt(
            SocketCloudTurnConnection(
                "utt-1",
                openedAtMs = 0L,
                onClose = {
                    connectionClosed = true
                    fakeSocket.cancelled = true
                },
                events = events,
                socket = fakeSocket,
            ),
        )

        withTimeoutOrNull(2_000) { transport.exchange(turn).toList() }
        testScheduler.advanceUntilIdle()

        assertTrue(
            "a finished turn must leave no socket behind: the exchange owns the adopted socket",
            connectionClosed && fakeSocket.cancelled,
        )
    }
}
