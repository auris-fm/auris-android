package au.com.shiftyjelly.pocketcasts.repositories.cloud

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.channels.Channel
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
 * A turn whose stream ends WITHOUT a terminal must report a failure, never silence.
 *
 * The sink assigns its user-facing outcome only inside the `Done` and `Error` branches, so a flow
 * that simply completes leaves it null and the turn returns `VoiceResponse.Silent`: the user asked
 * something, the server accepted it, and then nothing at all is said. A socket FAILURE already
 * reports `connection_lost`; a clean CLOSE mid-turn did not, on either the dialled listener or the
 * adopted connection's replay. Both are the same user-visible defect through different doors.
 *
 * These controls drive the REAL transport: the dialled path through the listener the transport
 * itself installs, the adopted path through the raw event buffer it replays.
 */
class WebSocketCloudTurnTransportNoTerminalTest {

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
        conversationId = "11111111-1111-4111-8111-111111111111",
        conversationMode = CloudConversationMode.Create,
        accessToken = "token",
        requestId = "req-1",
        request = "play something",
        context = mock(),
        capabilities = emptyList(),
        codecs = listOf("opus@48k"),
        routeHint = null,
    )

    private val connectedJson =
        """{"type":"connected","codec":"opus@48k","reservation_id":"r-1"}"""

    /**
     * The defect: a clean server close after the handshake but before any terminal.
     *
     * The turn was accepted — `connected` arrived — so this is not a failed dial. The stream just
     * stops. Ending the flow with no event makes the sink silent.
     */
    @Test
    fun `a clean close before any terminal reports a connection loss`() = runBlocking {
        val client = CapturingOkHttpClient()
        val transport = WebSocketCloudTurnTransport("https://example.invalid", client)
        val events = CopyOnWriteArrayList<CloudRouteEvent>()
        val job = launch { transport.exchange(frame()).collect { events.add(it) } }

        withTimeout(5_000) { while (client.listener == null) delay(10) }
        delay(200)
        val l = client.listener!!
        val s = client.socket!!

        l.onMessage(s, connectedJson)
        delay(200)
        // CONTROL: if a normal frame cannot reach the collector through this harness, the
        // assertion below would pass for the wrong reason.
        assertTrue(
            "CONTROL: the handshake must reach the collector: $events",
            events.any { it is CloudRouteEvent.Connected },
        )

        l.onClosed(s, 1000, "")
        withTimeout(5_000) { job.join() }

        assertEquals(
            "a close with no terminal must end the turn as a connection loss, not silence: $events",
            CloudRouteEvent.Error(CloudRouteErrorCodes.CONNECTION_LOST, ""),
            events.lastOrNull(),
        )
    }

    /**
     * The same door on the ADOPTED path: an early connection's socket closes before the turn it was
     * opened for ever receives a terminal. Its replay loop must report the loss too, or the two
     * paths disagree about what an ended stream means.
     */
    @Test
    fun `an adopted connection closed before any terminal reports a connection loss`() = runBlocking {
        val raw = Channel<RawSocketEvent>(Channel.UNLIMITED)
        val transport = WebSocketCloudTurnTransport("https://example.invalid", OkHttpClient())
        transport.dial = { _: Request, _: WebSocketListener -> throw IllegalStateException("must not dial") }
        raw.trySend(RawSocketEvent.Opened)
        raw.trySend(RawSocketEvent.Text(connectedJson))
        raw.trySend(RawSocketEvent.Closed(code = 1000, reason = ""))
        transport.adopt(SocketCloudTurnConnection("req-1", openedAtMs = 0L, events = raw))

        val events = CopyOnWriteArrayList<CloudRouteEvent>()
        withTimeout(5_000) { transport.exchange(frame()).collect { events.add(it) } }

        assertTrue(
            "CONTROL: the replayed handshake must reach the turn: $events",
            events.any { it is CloudRouteEvent.Connected },
        )
        assertEquals(
            "an adopted socket closed with no terminal must report a connection loss: $events",
            CloudRouteEvent.Error(CloudRouteErrorCodes.CONNECTION_LOST, ""),
            events.lastOrNull(),
        )
    }

    /**
     * The other half of the rule: a close AFTER the server's terminal is teardown, not news.
     *
     * Reporting a loss there would append a second terminal to a turn that already answered, and
     * the sink would speak a failure over a completed answer.
     *
     * SCOPE: this pins the delivered events, not the guard. Removing the terminal guard does not
     * redden it -- the terminal has already finished the channel, so an extra event cannot be
     * delivered either way (measured, not assumed). What the guard changes is observable elsewhere
     * and IS mutation-proven: the misleading enqueue-failure warning after a LOCAL terminal, and
     * the observer's connection-failure report. See those cases below.
     */
    @Test
    fun `a close after the server's terminal adds no error`() = runBlocking {
        val client = CapturingOkHttpClient()
        val transport = WebSocketCloudTurnTransport("https://example.invalid", client)
        val events = CopyOnWriteArrayList<CloudRouteEvent>()
        val job = launch { transport.exchange(frame()).collect { events.add(it) } }

        withTimeout(5_000) { while (client.listener == null) delay(10) }
        val l = client.listener!!
        val s = client.socket!!

        l.onMessage(s, connectedJson)
        l.onMessage(
            s,
            """{"type":"done","outcome":"completed","usage":{"input_tokens":1,"output_tokens":2,"speech":{"amount":0,"unit":"characters"}}}""",
        )
        l.onClosed(s, 1000, "")
        withTimeout(5_000) { job.join() }

        assertTrue("the terminal must be delivered: $events", events.any { it is CloudRouteEvent.Done })
        assertTrue(
            "a close after the terminal is teardown and must add no error: $events",
            events.none { it is CloudRouteEvent.Error },
        )
    }

    /** Same guarantee on the adopted path, so neither door reports a loss for a finished turn. */
    @Test
    fun `an adopted close after the server's terminal adds no error`() = runBlocking {
        val raw = Channel<RawSocketEvent>(Channel.UNLIMITED)
        val transport = WebSocketCloudTurnTransport("https://example.invalid", OkHttpClient())
        transport.dial = { _: Request, _: WebSocketListener -> throw IllegalStateException("must not dial") }
        raw.trySend(RawSocketEvent.Opened)
        raw.trySend(RawSocketEvent.Text(connectedJson))
        raw.trySend(
            RawSocketEvent.Text(
                """{"type":"done","outcome":"completed","usage":{"input_tokens":1,"output_tokens":2,"speech":{"amount":0,"unit":"characters"}}}""",
            ),
        )
        raw.trySend(RawSocketEvent.Closed(code = 1000, reason = ""))
        transport.adopt(SocketCloudTurnConnection("req-1", openedAtMs = 0L, events = raw))

        val events = CopyOnWriteArrayList<CloudRouteEvent>()
        withTimeout(5_000) { transport.exchange(frame()).collect { events.add(it) } }

        assertTrue("the terminal must be delivered: $events", events.any { it is CloudRouteEvent.Done })
        assertTrue(
            "an adopted close after the terminal must add no error: $events",
            events.none { it is CloudRouteEvent.Error },
        )
    }

    /**
     * A close that arrives with frames still buffered must not discard them.
     *
     * The terminal has to be ENQUEUED behind what the server already sent, exactly as a failure
     * terminal is, so the turn reports the loss after the audio it received rather than instead of
     * it. Closing the channel directly would cancel the consumer with those frames undelivered.
     */
    @Test
    fun `the no-terminal error does not overtake frames already received`() = runBlocking {
        val client = CapturingOkHttpClient()
        val transport = WebSocketCloudTurnTransport("https://example.invalid", client)
        val events = CopyOnWriteArrayList<CloudRouteEvent>()
        val job = launch { transport.exchange(frame()).collect { events.add(it) } }

        withTimeout(5_000) { while (client.listener == null) delay(10) }
        val l = client.listener!!
        val s = client.socket!!

        l.onMessage(s, connectedJson)
        l.onMessage(s, okio.ByteString.of(1, 2, 3))
        l.onClosed(s, 1000, "")
        withTimeout(5_000) { job.join() }

        val audioIndex = events.indexOfFirst { it is CloudRouteEvent.AudioFrame }
        val errorIndex = events.indexOfFirst { it is CloudRouteEvent.Error }
        assertTrue("the buffered audio must still be delivered: $events", audioIndex >= 0)
        assertTrue("the loss must be reported: $events", errorIndex >= 0)
        assertTrue(
            "the error must not overtake audio received before the close: $events",
            audioIndex < errorIndex,
        )
    }

    /**
     * A SERVER ERROR terminal followed by the close keeps the server's own code.
     *
     * The turn already has its reason -- the refusal the server sent -- and appending a generic
     * connection loss would replace it, so the user would hear "connection lost" instead of why the
     * request was refused. Driven with a real error frame through the transport's own listener.
     */
    @Test
    fun `a close after a server error keeps the server's code as the last event`() = runBlocking {
        val client = CapturingOkHttpClient()
        val transport = WebSocketCloudTurnTransport("https://example.invalid", client)
        val events = CopyOnWriteArrayList<CloudRouteEvent>()
        val job = launch { transport.exchange(frame()).collect { events.add(it) } }

        withTimeout(5_000) { while (client.listener == null) delay(10) }
        val l = client.listener!!
        val s = client.socket!!

        l.onMessage(s, """{"type":"error","code":"limit_exceeded","message":""}""")
        l.onClosed(s, 1000, "")
        withTimeout(5_000) { job.join() }

        assertEquals(
            "the server's refusal must be the only terminal: $events",
            listOf(CloudRouteEvent.Error("limit_exceeded", "")),
            events.toList(),
        )
    }

    /**
     * A mid-turn close is a CONNECTION FAILURE for the reader of the persisted log, not just an
     * error event on the flow.
     *
     * `onFailure` already reports it; a clean close that ends the turn without an answer is the
     * same outage through a different door, and support reads the trace, not the flow. Without the
     * report the new failure mode is indistinguishable from a server refusal in the artifact that
     * matters. `stillNeeded` must be true: the turn was still waiting.
     */
    @Test
    fun `a mid-turn close reports a connection failure to the observer`() = runBlocking {
        val client = CapturingOkHttpClient()
        val reported = CopyOnWriteArrayList<Pair<String, Boolean>>()
        val observer = object : CloudTurnTransportObserver {
            override fun onAuthResolved(requestId: String, outcome: CloudTurnTransportObserver.AuthOutcome) = Unit
            override fun onSocketOpened(requestId: String) = Unit
            override fun onSendAttempted(requestId: String) = Unit
            override fun onSendResult(requestId: String, outcome: CloudTurnTransportObserver.SendOutcome) = Unit
            override fun onTerminalReceived(requestId: String, origin: CloudTurnTransportObserver.TerminalOrigin, errorCode: String?) = Unit
            override fun onConnectionFailed(requestId: String, stillNeeded: Boolean) {
                reported += requestId to stillNeeded
            }
        }
        val transport = WebSocketCloudTurnTransport("https://example.invalid", client, observer)
        val events = CopyOnWriteArrayList<CloudRouteEvent>()
        val job = launch { transport.exchange(frame()).collect { events.add(it) } }

        withTimeout(5_000) { while (client.listener == null) delay(10) }
        delay(200)
        val l = client.listener!!
        val s = client.socket!!
        l.onMessage(s, connectedJson)
        delay(200)
        l.onClosed(s, 1000, "")
        withTimeout(5_000) { job.join() }

        assertEquals(
            "a mid-turn close must be reported once, as news about a turn still waiting: $reported",
            listOf("req-1" to true),
            reported.toList(),
        )
    }

    /** Same report on the adopted door, so the two paths do not disagree in the persisted log. */
    @Test
    fun `an adopted mid-turn close reports a connection failure to the observer`() = runBlocking {
        val raw = Channel<RawSocketEvent>(Channel.UNLIMITED)
        val reported = CopyOnWriteArrayList<Pair<String, Boolean>>()
        val observer = object : CloudTurnTransportObserver {
            override fun onAuthResolved(requestId: String, outcome: CloudTurnTransportObserver.AuthOutcome) = Unit
            override fun onSocketOpened(requestId: String) = Unit
            override fun onSendAttempted(requestId: String) = Unit
            override fun onSendResult(requestId: String, outcome: CloudTurnTransportObserver.SendOutcome) = Unit
            override fun onTerminalReceived(requestId: String, origin: CloudTurnTransportObserver.TerminalOrigin, errorCode: String?) = Unit
            override fun onConnectionFailed(requestId: String, stillNeeded: Boolean) {
                reported += requestId to stillNeeded
            }
        }
        val transport = WebSocketCloudTurnTransport("https://example.invalid", OkHttpClient(), observer)
        transport.dial = { _: Request, _: WebSocketListener -> throw IllegalStateException("must not dial") }
        raw.trySend(RawSocketEvent.Opened)
        raw.trySend(RawSocketEvent.Text(connectedJson))
        raw.trySend(RawSocketEvent.Closed(code = 1000, reason = ""))
        transport.adopt(SocketCloudTurnConnection("req-1", openedAtMs = 0L, events = raw))

        withTimeout(5_000) { transport.exchange(frame()).collect { } }

        assertEquals(
            "an adopted mid-turn close must be reported once, as news: $reported",
            listOf("req-1" to true),
            reported.toList(),
        )
    }

    /**
     * A close after a terminal the TRANSPORT itself emitted is teardown too.
     *
     * A rejected handshake or an undecodable frame ends the turn locally, and the server's close
     * then arrives at a channel that is already finished. Treating that as a fresh loss makes
     * `emitTerminal` fail its enqueue and log "terminal event could not be enqueued, failing the
     * turn" -- a warning about a full buffer for what is actually ordinary teardown. The turn must
     * end with the rejection as its reason and no such warning.
     */
    @Test
    fun `a close after a locally emitted terminal logs no enqueue failure`() = runBlocking {
        val lines = CopyOnWriteArrayList<String>()
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                lines += message
            }
        }
        timber.log.Timber.plant(tree)
        try {
            val client = CapturingOkHttpClient()
            val transport = WebSocketCloudTurnTransport("https://example.invalid", client)
            val events = CopyOnWriteArrayList<CloudRouteEvent>()
            val job = launch { transport.exchange(frame()).collect { events.add(it) } }

            withTimeout(5_000) { while (client.listener == null) delay(10) }
            val l = client.listener!!
            val s = client.socket!!

            // Binary before negotiation: the transport rejects the turn itself.
            l.onMessage(s, okio.ByteString.of(1, 2))
            l.onClosed(s, 1000, "")
            withTimeout(5_000) { job.join() }

            assertEquals(
                "the rejection must remain the reason: $events",
                CloudRouteEvent.Error(CloudRouteErrorCodes.INVALID_RESPONSE, ""),
                events.lastOrNull(),
            )
            assertTrue(
                "ordinary teardown must not be logged as a failed enqueue: $lines",
                lines.none { it.contains("could not be enqueued") },
            )
        } finally {
            timber.log.Timber.uproot(tree)
        }
    }

    /** A malformed-handshake rejection must stay the reported reason, not become a generic loss. */
    @Test
    fun `a close after a protocol rejection keeps the rejection as the last event`() = runBlocking {
        val client = CapturingOkHttpClient()
        val transport = WebSocketCloudTurnTransport("https://example.invalid", client)
        val events = CopyOnWriteArrayList<CloudRouteEvent>()
        val job = launch { transport.exchange(frame()).collect { events.add(it) } }

        withTimeout(5_000) { while (client.listener == null) delay(10) }
        val l = client.listener!!
        val s = client.socket!!

        // Binary before negotiation is a protocol violation, reported as invalid_response.
        l.onMessage(s, okio.ByteString.of(1, 2))
        l.onClosed(s, 1000, "")
        withTimeout(5_000) { job.join() }

        assertNotNull("the rejection must be delivered: $events", events.lastOrNull())
        assertEquals(
            "a protocol rejection must remain the reason, not be overwritten by a generic loss: $events",
            CloudRouteEvent.Error(CloudRouteErrorCodes.INVALID_RESPONSE, ""),
            events.lastOrNull(),
        )
    }
}
