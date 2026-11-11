package au.com.shiftyjelly.pocketcasts.repositories.cloud

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
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
}
