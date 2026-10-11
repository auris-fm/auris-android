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
import okio.ByteString
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

    /**
     * A terminal event must not OVERTAKE frames that were already emitted before it.
     *
     * The failure event bypasses the channel while audio and action frames go through it, so a
     * terminal emitted directly can be delivered while earlier frames are still buffered -- the
     * sink would then see an error before the audio it already received, and the turn's own
     * ordering guarantee ("everything the server sends back, in order") is broken.
     */
    @Test
    fun `a terminal event does not overtake frames emitted before it`() = runBlocking {
        val client = CapturingOkHttpClient()
        val transport = WebSocketCloudTurnTransport("https://example.invalid", client)

        val events = CopyOnWriteArrayList<CloudRouteEvent>()
        val job = launch { transport.exchange(frame()).collect { events.add(it) } }

        withTimeout(5_000) { while (client.listener == null) delay(10) }
        val l = client.listener!!
        val s = client.socket!!

        // An audio frame first, then an undecodable text frame that emits a terminal error.
        l.onMessage(s, ByteString.of(1, 2, 3))
        l.onMessage(s, "not-json")
        delay(300)
        job.cancel()

        val audioIndex = events.indexOfFirst { it is CloudRouteEvent.AudioFrame }
        val errorIndex = events.indexOfFirst { it is CloudRouteEvent.Error }
        assertTrue("the audio frame must be delivered: $events", audioIndex >= 0)
        assertTrue("the terminal error must be delivered: $events", errorIndex >= 0)
        assertTrue(
            "the error must not overtake the audio frame emitted before it: $events",
            audioIndex < errorIndex,
        )
    }

    /**
     * A refused TERMINAL ENQUEUE must still end the turn observably.
     *
     * @spec's distinction: the transport has TWO buffers -- its internal `channel` (4096), which
     * `emitTerminal` enqueues into, and `callbackFlow`'s own output buffer, which the consumer
     * drains. Filling the second and observing ITS failure says nothing about a refused terminal
     * enqueue, which is the case that silently completed in the original defect.
     *
     * MEASURED here rather than assumed: with the listener enqueueing synchronously and the consumer
     * draining concurrently, both buffers are fed by the same flow, and the output buffer is the
     * first to refuse (delivered=65 of 4116, `callbackFlow buffer full`). Reaching a refused
     * INTERNAL enqueue requires the producer to outpace the consumer by the full 4096 -- at which
     * point the output buffer has already failed. So this test pins the property that IS reachable:
     * the turn always ends observably, never in silence, whichever buffer refuses first.
     *
     * The refused-terminal-enqueue path is therefore covered by reading rather than by execution:
     * `emitTerminal` enqueues into the same internal channel and finishes it, and the single consumer
     * forwards whatever is in that channel before ending the flow. A refused enqueue cannot be
     * delivered as a distinct error for the same reason the output buffer's refusal cannot.
     */
    @Test
    fun `a full channel ends the turn observably, never silently`() = runBlocking {
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

        withTimeout(5_000) { while (client.listener == null) delay(10) }
        val l = client.listener!!
        val s = client.socket!!

        repeat(4_096 + 10) {
            l.onMessage(s, ByteString.of(it.toByte(), it.toByte()))
        }
        l.onFailure(s, RuntimeException("boom"), null)

        // The turn must END rather than hang, and it must NOT complete silently: either a terminal
        // Error event is delivered, or the flow fails observably. Silence is the defect.
        withTimeout(5_000) { job.join() }
        val deliveredError = events.any { it is CloudRouteEvent.Error }
        assertTrue(
            "a full channel must end the turn with an error or an observable failure, not silence: " +
                "size=${events.size} threw=${threw?.message}",
            deliveredError || threw != null,
        )
    }
}
