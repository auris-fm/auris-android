package au.com.shiftyjelly.pocketcasts.repositories.cloud

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Drives the REAL [WebSocketCloudTurnTransport] — the transport's own listener, its own send
 * path, its own decode point — against a recording observer, for the pre-Connected refusal
 * cases the silent-refusal diagnosis turns on.
 *
 * Unlike hand-driven sink tests, no observer callback here is invoked by the test: every
 * recorded event is emitted by the transport's own onOpen/onMessage/onFailure/send wiring.
 * Removing a real callback wiring therefore fails these tests.
 */
class WebSocketCloudTurnTransportRefusalTest {

    /** Records what the transport ACTUALLY reports, in order. */
    private class RecordingObserver : CloudTurnTransportObserver {
        val events = CopyOnWriteArrayList<String>()
        override fun onAuthResolved(requestId: String, outcome: CloudTurnTransportObserver.AuthOutcome) {
            events += "auth:${outcome.name}"
        }
        override fun onSocketOpened(requestId: String) {
            events += "socket_opened"
        }
        override fun onSendAttempted(requestId: String) {
            events += "send_attempted"
        }
        override fun onSendResult(requestId: String, outcome: CloudTurnTransportObserver.SendOutcome) {
            events += "send:${outcome.name}"
        }
        override fun onTerminalReceived(requestId: String, origin: CloudTurnTransportObserver.TerminalOrigin, errorCode: String?) {
            events += "terminal:${origin.name}:$errorCode"
        }
        override fun onConnectionFailed(requestId: String) {
            events += "connection_failed"
        }
    }

    /** Captures the listener the real transport installs, and records real send calls. */
    private class CapturingOkHttpClient : OkHttpClient() {
        var listener: WebSocketListener? = null
            private set
        var socket: WebSocket? = null
            private set
        var sendReturn = true
        val sendCalls = CopyOnWriteArrayList<String>()

        /** Lets a test substitute its own throwing socket before the transport installs its own. */
        fun overrideSocket(socket: WebSocket) {
            this.socket = socket
        }

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            this.listener = listener
            return mock<WebSocket>().also { s ->
                this.socket = s
                org.mockito.kotlin.whenever(s.send(org.mockito.kotlin.any<String>())).thenAnswer { inv ->
                    sendCalls += inv.getArgument<String>(0)
                    sendReturn
                }
            }
        }
    }

    private fun frame(requestId: String = "req-refusal") = CloudTurnFrame.Authenticate(
        conversationId = "11111111-1111-4111-8111-111111111111",
        conversationMode = CloudConversationMode.Create,
        accessToken = "token",
        requestId = requestId,
        request = "play something",
        context = mock(),
        capabilities = emptyList(),
        codecs = listOf("opus@48k"),
        routeHint = null,
    )

    /** The REAL limit refusal: 101 handshake, real send, then an error frame BEFORE protocol Connected. */
    @Test
    fun `server limit_exceeded after real open-send records terminal at decode before protocol Connected`() = runBlocking {
        val client = CapturingOkHttpClient()
        val observer = RecordingObserver()
        val transport = WebSocketCloudTurnTransport("https://example.invalid", client, observer)

        val events = CopyOnWriteArrayList<CloudRouteEvent>()
        val job = launch { transport.exchange(frame()).collect { events.add(it) } }

        withTimeout(5_000) { while (client.listener == null) delay(10) }
        // The real transport requires the 101 handshake; onOpen drives the actual auth send.
        val upgrading = mock<Response>().also { org.mockito.kotlin.whenever(it.code).thenReturn(101) }
        client.listener!!.onOpen(client.socket!!, upgrading)
        withTimeout(5_000) { while (client.sendCalls.isEmpty()) delay(10) }
        val l = client.listener!!
        val s = client.socket!!

        // The refusal: the server answers the auth frame with limit_exceeded BEFORE any
        // protocol Connected handshake — the owner's observed staging behavior.
        l.onMessage(s, """{"type":"error","code":"limit_exceeded","message":""}""")
        delay(200)
        job.cancel()

        assertTrue(
            "socket_opened must be recorded — the TCP open truthfully preceded the refusal: ${observer.events}",
            observer.events.contains("socket_opened"),
        )
        assertTrue(
            "send attempt must precede the refusal: ${observer.events}",
            observer.events.contains("send_attempted") && observer.events.contains("send:ACCEPTED"),
        )
        assertTrue(
            "terminal must be recorded at the transport's decode point: ${observer.events}",
            observer.events.contains("terminal:SERVER_ERROR:limit_exceeded"),
        )
        assertTrue(
            "the flow must surface the server error event: $events",
            events.any { it is CloudRouteEvent.Error && it.code == "limit_exceeded" },
        )
        assertEquals(
            "no protocol Connected may have been delivered: $events",
            false,
            events.any { it is CloudRouteEvent.Connected },
        )
    }

    /** An unencodable auth frame is ENCODE_FAILED — send was never attempted. */
    @Test
    fun `unencodable frame records ENCODE_FAILED without a send attempt`() = runBlocking {
        val client = CapturingOkHttpClient()
        val observer = RecordingObserver()
        val transport = WebSocketCloudTurnTransport("https://example.invalid", client, observer)

        val badFrame = frame().copy(conversationId = "not-a-uuid")
        val events = CopyOnWriteArrayList<CloudRouteEvent>()
        val job = launch { transport.exchange(badFrame).collect { events.add(it) } }

        withTimeout(5_000) { while (client.listener == null) delay(10) }
        val upgrading = mock<Response>().also { org.mockito.kotlin.whenever(it.code).thenReturn(101) }
        client.listener!!.onOpen(client.socket!!, upgrading)
        delay(300)
        job.cancel()

        assertEquals(
            "encode failure is its own bounded outcome: ${observer.events}",
            true,
            observer.events.contains("send:ENCODE_FAILED"),
        )
        assertEquals(
            "no send attempt may be claimed when encode failed: ${observer.events}",
            false,
            observer.events.contains("send_attempted"),
        )
        assertTrue("nothing reached the socket", client.sendCalls.isEmpty())
        assertTrue(
            "the turn must end with the local terminal: $events",
            events.any { it is CloudRouteEvent.Error && it.code == CloudRouteErrorCodes.CONNECTION_LOST },
        )
    }

    /** A throwing send is SEND_THREW — acceptance unknown, not a clean rejection. */
    @Test
    fun `throwing send records SEND_THREW and ends the turn locally`() = runBlocking {
        val client = CapturingOkHttpClient()
        val observer = RecordingObserver()
        val transport = WebSocketCloudTurnTransport("https://example.invalid", client, observer)
        // Simulate a socket whose send throws (channel closing underneath us).
        val throwingSocket = mock<WebSocket>().also { s ->
            org.mockito.kotlin.whenever(s.send(org.mockito.kotlin.any<String>())).thenThrow(RuntimeException("socket closed"))
        }
        client.overrideSocket(throwingSocket)

        val events = CopyOnWriteArrayList<CloudRouteEvent>()
        val job = launch { transport.exchange(frame()).collect { events.add(it) } }

        withTimeout(5_000) { while (client.listener == null) delay(10) }
        val upgrading = mock<Response>().also { org.mockito.kotlin.whenever(it.code).thenReturn(101) }
        client.listener!!.onOpen(throwingSocket, upgrading)
        delay(300)
        job.cancel()

        assertTrue(
            "send attempt must be recorded before the throw: ${observer.events}",
            observer.events.contains("send_attempted"),
        )
        assertTrue(
            "a throwing send must be SEND_THREW: ${observer.events}",
            observer.events.contains("send:SEND_THREW"),
        )
        assertEquals(
            "acceptance is unknown — never claim ACCEPTED or REJECTED: ${observer.events}",
            false,
            observer.events.contains("send:ACCEPTED") || observer.events.contains("send:REJECTED"),
        )
        assertTrue(
            "the turn must end with the local terminal: $events",
            events.any { it is CloudRouteEvent.Error && it.code == CloudRouteErrorCodes.CONNECTION_LOST },
        )
    }

    /** Auth-then-send boundary: the real send path records attempt then result. */
    @Test
    fun `real send path records attempt before accepted result`() = runBlocking {
        val client = CapturingOkHttpClient()
        val observer = RecordingObserver()
        val transport = WebSocketCloudTurnTransport("https://example.invalid", client, observer)

        val job = launch { transport.exchange(frame()).collect { } }

        withTimeout(5_000) { while (client.listener == null) delay(10) }
        // The real transport sends the auth frame from its own onOpen handler.
        // The real transport requires a 101 handshake before it will send.
        val upgrading = mock<Response>().also { org.mockito.kotlin.whenever(it.code).thenReturn(101) }
        client.listener!!.onOpen(client.socket!!, upgrading)
        withTimeout(5_000) { while (client.sendCalls.isEmpty()) delay(10) }
        delay(100)
        job.cancel()

        val attemptIdx = observer.events.indexOf("send_attempted")
        val acceptedIdx = observer.events.indexOf("send:ACCEPTED")
        assertTrue("send attempt must be recorded: ${observer.events}", attemptIdx >= 0)
        assertTrue("send result must be recorded: ${observer.events}", acceptedIdx >= 0)
        assertTrue(
            "attempt must precede result: ${observer.events}",
            attemptIdx < acceptedIdx,
        )
        assertTrue("the auth frame was really handed to the socket", client.sendCalls.isNotEmpty())
    }

    /** A rejected send (webSocket.send returns false) must be a distinct bounded outcome. */
    @Test
    fun `send returning false records REJECTED, not ACCEPTED`() = runBlocking {
        val client = CapturingOkHttpClient().apply { sendReturn = false }
        val observer = RecordingObserver()
        val transport = WebSocketCloudTurnTransport("https://example.invalid", client, observer)

        val job = launch { transport.exchange(frame()).collect { } }

        withTimeout(5_000) { while (client.listener == null) delay(10) }
        val upgrading = mock<Response>().also { org.mockito.kotlin.whenever(it.code).thenReturn(101) }
        client.listener!!.onOpen(client.socket!!, upgrading)
        withTimeout(5_000) { while (client.sendCalls.isEmpty()) delay(10) }
        delay(100)
        job.cancel()

        assertTrue(
            "false return must be REJECTED: ${observer.events}",
            observer.events.contains("send:REJECTED"),
        )
        assertEquals("must not claim ACCEPTED: ${observer.events}", false, observer.events.contains("send:ACCEPTED"))
    }

    /** Socket failure before any server message: connection failure, no server terminal. */
    @Test
    fun `onFailure before Connected records connection failure without a server terminal`() = runBlocking {
        val client = CapturingOkHttpClient()
        val observer = RecordingObserver()
        val transport = WebSocketCloudTurnTransport("https://example.invalid", client, observer)

        val events = CopyOnWriteArrayList<CloudRouteEvent>()
        val job = launch { transport.exchange(frame()).collect { events.add(it) } }

        withTimeout(5_000) { while (client.listener == null) delay(10) }
        delay(100)
        client.listener!!.onFailure(client.socket!!, RuntimeException("handshake refused"), null)
        delay(200)
        job.cancel()

        assertTrue(
            "connection failure must be recorded by the real onFailure wiring: ${observer.events}",
            observer.events.contains("connection_failed"),
        )
        assertEquals(
            "no server terminal may be claimed for a local failure: ${observer.events}",
            false,
            observer.events.any { it.startsWith("terminal:") },
        )
        assertTrue(
            "the flow must end with the local connection_lost terminal: $events",
            events.any { it is CloudRouteEvent.Error && it.code == CloudRouteErrorCodes.CONNECTION_LOST },
        )
    }

    @Test
    fun `flow completes after terminal event`() = runBlocking {
        val client = CapturingOkHttpClient()
        val transport = WebSocketCloudTurnTransport("https://example.invalid", client, RecordingObserver())
        var completed = false
        val job = launch {
            transport.exchange(frame()).collect { }
            completed = true
        }
        withTimeout(5_000) { while (client.listener == null) delay(10) }
        val upgrading = mock<Response>().also { org.mockito.kotlin.whenever(it.code).thenReturn(101) }
        client.listener!!.onOpen(client.socket!!, upgrading)
        withTimeout(5_000) { while (client.sendCalls.isEmpty()) delay(10) }
        client.listener!!.onMessage(client.socket!!, """{"type":"error","code":"limit_exceeded","message":""}""")
        withTimeout(5_000) {
            while (!completed) delay(10)
        }
        job.cancel()
    }
}
