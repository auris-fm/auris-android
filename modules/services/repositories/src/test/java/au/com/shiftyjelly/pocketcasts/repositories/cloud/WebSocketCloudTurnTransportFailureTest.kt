package au.com.shiftyjelly.pocketcasts.repositories.cloud

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Drives the REAL [WebSocketCloudTurnTransport] through a socket failure.
 *
 * The sibling overflow test simulates the transport's channel mechanics by hand
 * (`Channel(4096)` + `trySend` + `close`), so it exercises a re-statement of the
 * logic rather than the transport. This test instead gives the transport an
 * [OkHttpClient] whose `newWebSocket` captures the listener the transport itself
 * installs, and then invokes that listener's callbacks. Every assertion is
 * therefore about the transport's own callbacks.
 *
 * The property under test is the error-delivery SHAPE, because the sink's
 * cleanup reaches a socket failure through the flow's COMPLETION path rather
 * than through a thrown exception: a transport that failed the flow instead of
 * emitting a terminal event would still clean up, but for a different reason.
 */
class WebSocketCloudTurnTransportFailureTest {

    private class CapturingOkHttpClient : OkHttpClient() {
        var listener: WebSocketListener? = null
            private set
        var socket: WebSocket? = null
            private set

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            this.listener = listener
            return mock<WebSocket>().also { this.socket = it }
        }
    }

    private fun frame() = CloudTurnFrame.Authenticate(
        accessToken = "token",
        requestId = "req-1",
        request = "play something",
        context = mock(),
        capabilities = emptyList(),
        codecs = listOf("opus@48k"),
        routeHint = null,
    )

    /**
     * A socket failure must arrive as a terminal EVENT that completes the flow
     * normally. `collect` cancels the callbackFlow job when its action returns,
     * so the collector completing without an exception is the observable form of
     * "the flow completed" — and a thrown failure would instead propagate here.
     */
    @Test
    fun `socket failure emits a terminal Error event and completes the flow normally`() = runBlocking {
        val client = CapturingOkHttpClient()
        val transport = WebSocketCloudTurnTransport("https://example.invalid", client)

        val events = CopyOnWriteArrayList<CloudRouteEvent>()
        var threw: Throwable? = null

        val job = launch {
            try {
                transport.exchange(frame()).collect { events.add(it) }
            } catch (t: Throwable) {
                threw = t
            }
        }

        // Wait for the transport's own listener to be installed by the running flow,
        // then let the collection job reach its receive so the ordering under test is
        // "close() races an ALREADY-DRAINING consumer", not "close() beats a cold one".
        withTimeout(5_000) {
            while (client.listener == null) delay(10)
        }
        delay(200)
        val listener = client.listener
        val socket = client.socket
        assertNotNull("transport must have installed a listener", listener)
        assertNotNull("transport must have created a socket", socket)
        val l = listener!!
        val s = socket!!

        // CONTROL: drive a known-good callback first. If a normal message cannot reach
        // the collector, the harness is wrong and the failure assertion below means nothing.
        l.onMessage(s, "{\"type\":\"connected\",\"codec\":\"opus@48k\"}")
        delay(200)
        assertTrue(
            "CONTROL: a normal message must reach the collector through this harness: $events",
            events.any { it is CloudRouteEvent.Connected },
        )

        // ORDER TEST: the very same emitOrFail mechanism delivered Connected above. If the Error
        // is buffered but never delivered, the difference is close() arriving between buffer and drain.
        l.onFailure(s, RuntimeException("boom"), null)
        delay(200)

        assertEquals("a socket failure must not throw out of the flow", null, threw)
        assertTrue(
            "the failure must surface as a terminal Error event, not silence: $events",
            events.any { it is CloudRouteEvent.Error },
        )
    }
}
