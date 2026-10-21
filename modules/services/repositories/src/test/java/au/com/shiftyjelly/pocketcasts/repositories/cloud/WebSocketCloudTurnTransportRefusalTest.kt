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

    /** The REAL limit refusal: server sends an error frame BEFORE any Connected handshake. */
    @Test
    fun `server limit_exceeded before Connected records terminal at decode with no socket-open lie`() = runBlocking {
        val client = CapturingOkHttpClient()
        val observer = RecordingObserver()
        val transport = WebSocketCloudTurnTransport("https://example.invalid", client, observer)

        val events = CopyOnWriteArrayList<CloudRouteEvent>()
        val job = launch { transport.exchange(frame()).collect { events.add(it) } }

        withTimeout(5_000) { while (client.listener == null) delay(10) }
        delay(100)
        val l = client.listener!!
        val s = client.socket!!

        // The refusal: server accepts the upgrade and immediately answers the auth frame
        // with limit_exceeded — the owner's observed staging behavior (FREE_DAILY_LIMIT=0).
        l.onMessage(s, """{"type":"error","code":"limit_exceeded","message":""}""")
        delay(200)
        job.cancel()

        assertTrue(
            "terminal must be recorded at the transport's decode point: ${observer.events}",
            observer.events.contains("terminal:SERVER_ERROR:limit_exceeded"),
        )
        assertTrue(
            "the flow must surface the server error event: $events",
            events.any { it is CloudRouteEvent.Error && it.code == "limit_exceeded" },
        )
        assertEquals(
            "no Connected ever arrived, so no socket lie: ${observer.events}",
            false,
            observer.events.contains("socket_opened") && !events.any { it is CloudRouteEvent.Connected },
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
}
