package au.com.shiftyjelly.pocketcasts.repositories.cloud

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Controls over the REAL [WebSocketCloudTurnTransport], not a fake sender.
 *
 * The fake-sender tests can only prove that a result is handed to whatever sender the sink was given.
 * They cannot prove the sender the sink was given is the one attached to the socket the turn actually
 * opened -- which is exactly the defect these controls exist for. These run against MockWebServer's
 * WebSocket upgrade so the socket is real: what the server receives is the evidence.
 */
class WebSocketCloudTurnTransportTest {

    private val turn = CloudRouteTurn(
        conversationId = "11111111-1111-4111-8111-111111111111",
        conversationMode = CloudConversationMode.Create,
        request = "pause playback",
        context = CloudRouteContext(episodeId = "e-1", clientPositionMs = 1000L),
        requestId = "req-socket-1",
        capabilities = listOf(CloudRouteCapabilities.SEARCH_RESULTS_V1),
    )

    /**
     * A dispatcher that accepts the upgrade and records what the client sends, then stays open.
     *
     * MockWebServer has no WebSocket routing of its own, so the upgrade response is written by hand and
     * the listener owns the socket for the rest of the test.
     */
    private class RecordingDispatcher(
        private val delayUpgradeMs: Long = 0,
        /**
         * Whether this fixture completes the turn after the handshake.
         *
         * OPT-IN, because completion is a property of the control, not of the fixture: a dispatcher
         * that always ended the turn would silently change the premises of every control written
         * against a LIVE session. Only the control that is about a completed turn asks for it.
         */
        private val sendDoneOnOpen: Boolean = false,
    ) {
        val sockets = java.util.concurrent.CopyOnWriteArrayList<WebSocket>()
        val received = java.util.concurrent.CopyOnWriteArrayList<String>()

        val socket: WebSocket? get() = sockets.firstOrNull()

        /**
         * What each socket received.
         *
         * A socket that never READS its peer's frames is not a socket: OkHttp's real server closes a
         * connection once unread frames pile up, and a result written to it is rejected rather than
         * delivered. The listener therefore drains every frame and keeps it here.
         */
        val perSocket = java.util.concurrent.ConcurrentHashMap<WebSocket, MutableList<String>>()
        val failures = java.util.concurrent.CopyOnWriteArrayList<String>()
        val closed = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val closedPerSocket = java.util.concurrent.ConcurrentHashMap<WebSocket, Int>()

        /**
         * Sockets the transport ABORTED, per socket instance.
         *
         * Separate from [failures] on purpose: that list also collects peer-close notifications and
         * binary frames, neither of which says anything about the transport terminating a connection.
         * Termination is only ever one of a completed close handshake ([closedPerSocket]) or an abort
         * observed as a failure on THAT socket.
         */
        val failedPerSocket = java.util.concurrent.ConcurrentHashMap<WebSocket, String>()

        fun receivedOn(webSocket: WebSocket): List<String> = perSocket[webSocket].orEmpty()

        fun dispatcher(): okhttp3.mockwebserver.Dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                        sockets += webSocket
                        perSocket[webSocket] = java.util.concurrent.CopyOnWriteArrayList()
                        // The handshake the sink waits on, sent once the socket is registered so a
                        // sender can already reach it.
                        webSocket.send("""{"type":"connected","codec":"pcm_s16le@24k","reservation_id":"r-1"}""")
                        // A FIXTURE THAT NEVER ENDS THE TURN CANNOT TEST A COMPLETED TURN. Without a
                        // server terminal the channel stays open, the collector parked, and the flow
                        // never completes -- so a control about completion was asserting on a turn
                        // that never completed. Opt-in, so every other control keeps its live-session
                        // premise and the peer-close control still owns the close it initiates.
                        if (sendDoneOnOpen) {
                            webSocket.send("""{"type":"done","outcome":"completed"}""")
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        received += text
                        perSocket[webSocket]?.add(text)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        failures += "closing:$code:$reason"
                        webSocket.close(code, reason)
                    }

                    // A COMPLETED close handshake is its own event. Without this, a control cannot
                    // tell "we acknowledged" from "the peer went away", and the two ends of the
                    // handshake collapse into one observation.
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        closed += code
                        closedPerSocket[webSocket] = code
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                        failures += (t::class.simpleName + ":" + t.message)
                        failedPerSocket[webSocket] = (t::class.simpleName + ":" + t.message)
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                        failures += "binary(${bytes.size})"
                    }
                },
            ).apply { if (delayUpgradeMs > 0) setBodyDelay(delayUpgradeMs, TimeUnit.MILLISECONDS) }
        }
    }

    /**
     * A collection that is still RUNNING, owned by the test.
     *
     * The previous helper cancelled its own collector before returning while claiming to leave the turn
     * live, so every control using it asserted against a retiring turn. Ownership is explicit now: the
     * caller keeps this handle alive for the duration it needs and closes it in `finally`.
     */
    private class LiveTurn(private val job: Job) : AutoCloseable {
        fun isActive(): Boolean = job.isActive

        override fun close() {
            job.cancel()
        }
    }

    /**
     * Drives one turn's events until the handshake arrives and RETURNS the live collection.
     *
     * The wait is bounded, and the collector is left running on purpose: a turn in progress is what
     * these controls are about. The caller closes the handle in `finally`, so a failed assertion cannot
     * leave a collector behind.
     */
    private suspend fun openAndHandshake(
        scope: CoroutineScope,
        events: Flow<CloudRouteEvent>,
        dispatcher: RecordingDispatcher,
    ): LiveTurn {
        // THE SOCKET EXISTING SERVERSIDE IS NOT THE TURN BEING REACHABLE. `waitForSocket` only proves
        // the server accepted the upgrade; on the client the `onOpen` callback that registers the
        // socket may not have run, so a send issued now can still find no socket and report failure
        // for a reason that has nothing to do with what the control is asking. The signal that the
        // turn is genuinely live is its OWN first event, observed by this same collector.
        val connected = java.util.concurrent.CountDownLatch(1)
        val collecting = scope.launch(Dispatchers.IO) {
            runCatching {
                events.collect { event ->
                    if (event is CloudRouteEvent.Connected) connected.countDown()
                }
            }
        }
        try {
            waitForSocket(dispatcher)
            assertTrue(
                "the turn never reported Connected, so the socket was never live for sending",
                connected.await(5, TimeUnit.SECONDS),
            )
        } catch (failure: Throwable) {
            // The handle must not outlive the helper's own failure.
            collecting.cancel()
            throw failure
        }
        return LiveTurn(collecting)
    }

    private fun route(server: MockWebServer, token: String? = "tok") = CloudTurnRoute(
        baseUrl = server.url("/").toString().trimEnd('/'),
        tokenProvider = object : CloudTokenProviding {
            override suspend fun currentToken(): String? = token
            override suspend fun refreshToken() = Unit
            override suspend fun currentCredential(): CloudCredential = CloudCredential(token = token, accountId = "user-account-1")
        },
        okHttpClient = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(),
    )

    @Test
    fun `the sender returned with the events writes to the socket the exchange opened`() {
        kotlinx.coroutines.runBlocking {
            MockWebServer().use { server ->
                val dispatcher = RecordingDispatcher()
                server.dispatcher = dispatcher.dispatcher()
                server.start()
                val opened = route(server).openTurn(turn)
                // Collect just the handshake: the socket must be open before a result can be sent, and the
                // opened session must already be the one holding it.
                val live = openAndHandshake(this, opened.session.events, dispatcher)
                try {
                    assertTrue(
                        "the sender handed out with the events did not reach the turn's own socket",
                        opened.session.sender.sendResult(
                            CloudTurnFrame.AppOperationResult(
                                requestId = turn.requestId,
                                operationId = "op-1",
                                payloadJson = "{\"outcome\":\"succeeded\"}",
                            ),
                        ),
                    )
                    waitForMessages(dispatcher, 2)
                    val result = dispatcher.received.last()
                    assertTrue(result, result.contains("\"app_operation_result\""))
                    assertTrue(result, result.contains("op-1"))
                } finally {
                    live.close()
                }
            }
        }
    }

    @Test
    fun `a sender from a second transport instance cannot reach the first turn's socket`() {
        kotlinx.coroutines.runBlocking {
            // The defect this replaces: a sender built from one transport, events from another. Such a
            // sender must not silently appear to succeed.
            MockWebServer().use { server ->
                val dispatcher = RecordingDispatcher()
                server.dispatcher = dispatcher.dispatcher()
                server.start()
                val opened = route(server).openTurn(turn)
                val live = openAndHandshake(this, opened.session.events, dispatcher)
                try {
                    val stranger = WebSocketCloudTurnTransport(server.url("/").toString().trimEnd('/'), OkHttpClient())
                        .sessionFor(turn.requestId)
                    assertFalse(
                        "a sender that never opened a socket must report failure, not pretend to send",
                        stranger.sendResult(
                            CloudTurnFrame.AppOperationResult(turn.requestId, "op-1", "{}"),
                        ),
                    )
                } finally {
                    live.close()
                }
            }
        }
    }

    @Test
    fun `an older turn's sender cannot remove or hijack a newer turn's socket`() {
        kotlinx.coroutines.runBlocking {
            MockWebServer().use { server ->
                val dispatcher = RecordingDispatcher()
                server.dispatcher = dispatcher.dispatcher()
                server.start()
                // `transport.openTurn` takes a frame; the route builds it from the turn, so this control
                // uses the transport directly with the same frame shape.
                val transport = WebSocketCloudTurnTransport(
                    server.url("/").toString().trimEnd('/'),
                    OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(),
                )
                val first = transport.openTurn(authFrame(turn))
                val firstLive = openAndHandshake(this, first.events, dispatcher)
                val second = transport.openTurn(authFrame(turn.copy(requestId = "req-socket-2")))
                val secondLive = openAndHandshake(this, second.events, dispatcher)
                try {
                    waitForSockets(dispatcher, 2)
                    // The first turn's sender must still address the first socket; the second's, the second.
                    // Each sender reaches only its own socket: an id-only map keyed by request id would let
                    // the second turn's close remove the first turn's socket, or vice versa.
                    assertTrue(first.sender.sendResult(CloudTurnFrame.AppOperationResult(turn.requestId, "op-1", "{}")))
                    assertTrue(second.sender.sendResult(CloudTurnFrame.AppOperationResult("req-socket-2", "op-2", "{}")))
                    waitForMessages(dispatcher, 3)
                    assertNotNull(dispatcher.received.firstOrNull { it.contains("op-1") })
                    assertNotNull(dispatcher.received.firstOrNull { it.contains("op-2") })
                } finally {
                    firstLive.close()
                    secondLive.close()
                }
            }
        }
    }

    @Test
    fun `the authenticated account is resolved with the token by the same call`() {
        kotlinx.coroutines.runBlocking {
            MockWebServer().use { server ->
                val dispatcher = RecordingDispatcher()
                server.dispatcher = dispatcher.dispatcher()
                server.start()
                val opened = route(server).openTurn(turn)
                val live = openAndHandshake(this, opened.session.events, dispatcher)
                // The handle is held across the assertion and closed here: the account is read from a LIVE
                // turn, and nothing outlives the control.
                try {
                    assertEquals("user-account-1", opened.accountId)
                } finally {
                    live.close()
                }
            }
        }
    }

    @Test
    fun `no account means no v1 owner even though the token exists`() {
        kotlinx.coroutines.runBlocking {
            MockWebServer().use { server ->
                val dispatcher = RecordingDispatcher()
                server.dispatcher = dispatcher.dispatcher()
                server.start()
                val opened = CloudTurnRoute(
                    baseUrl = server.url("/").toString().trimEnd('/'),
                    tokenProvider = object : CloudTokenProviding {
                        override suspend fun currentToken(): String? = "tok"
                        override suspend fun refreshToken() = Unit
                    },
                    okHttpClient = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(),
                ).openTurn(turn)
                val live = openAndHandshake(this, opened.session.events, dispatcher)
                try {
                    assertNull("an unresolvable account must be null, never derived from the token", opened.accountId)
                } finally {
                    live.close()
                }
            }
        }
    }

    private fun waitForSocket(dispatcher: RecordingDispatcher) {
        val deadline = System.currentTimeMillis() + 5_000
        while (dispatcher.socket == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
        if (dispatcher.socket == null) {
            // A socket that never opens is a real result, and the sender must say so rather than throw.
            assertFalse(
                "no socket opened, yet the sender reported success",
                dispatcher.socket != null,
            )
        }
    }

    /**
     * Wait, bounded, for a frame matching [needle] to arrive on ONE SPECIFIC server socket.
     *
     * Per socket on purpose: a frame that shows up on some other connection is not evidence that this
     * turn's socket received it.
     */
    private fun waitForMessageOn(socket: WebSocket, dispatcher: RecordingDispatcher, needle: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (dispatcher.receivedOn(socket).none { it.contains(needle) } && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue(
            "expected a frame containing $needle on the turn's own socket, saw ${dispatcher.receivedOn(socket)}",
            dispatcher.receivedOn(socket).any { it.contains(needle) },
        )
    }

    private fun waitForMessages(dispatcher: RecordingDispatcher, count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (dispatcher.received.size < count && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("expected $count frames, saw ${dispatcher.received}", dispatcher.received.size >= count)
    }

    // MockWebServer's dispatcher has no per-socket registry; the second socket is asserted through the
    // messages it alone can carry (op-2 arrives after op-1, on a distinct connection).
    private fun waitForSockets(dispatcher: RecordingDispatcher, count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (dispatcher.sockets.size < count && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(count, dispatcher.sockets.size)
    }

    private fun authFrame(turn: CloudRouteTurn) = CloudTurnFrame.Authenticate(
        accessToken = "tok",
        requestId = turn.requestId,
        request = turn.request,
        sourceLanguage = null,
        context = turn.context,
        capabilities = turn.capabilities,
        codecs = CloudRouteCodecs.advertised,
        routeHint = null,
        conversationId = turn.conversationId,
        conversationMode = turn.conversationMode,
    )

    // --- Socket lifecycle: the turn session owns the connection -------------------------------------

    /**
     * Opens a turn whose exchange is ALREADY RUNNING.
     *
     * `callbackFlow` is lazy: without a collector nothing connects, so a test that only inspects the
     * sender would be measuring an exchange that never started. The collector here is the sink's shape
     * -- it ends as soon as the turn does -- and it is what makes the socket real.
     */
    private suspend fun openTurn(
        server: MockWebServer,
        token: String = "tok",
        requestId: String = turn.requestId,
        retainedDeadlineMs: Long = CloudTurnTransport.NO_EARLIER_DEADLINE,
    ) = CloudTurnRoute(
        baseUrl = server.url("/").toString().trimEnd('/'),
        tokenProvider = object : CloudTokenProviding {
            override suspend fun currentToken(): String? = token
            override suspend fun refreshToken() = Unit
            override suspend fun currentCredential(): CloudCredential = CloudCredential(token, "user-account-1")
        },
        okHttpClient = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(),
        // THE DEADLINE MUST REACH THE TRANSPORT. The route builds its own transport when none is
        // injected, and that construction cannot carry a deadline: a parameter accepted here and
        // never forwarded makes the caller's bound invisible while still looking honoured.
        transport = WebSocketCloudTurnTransport(
            baseUrl = server.url("/").toString().trimEnd('/'),
            okHttpClient = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(),
            retainedDeadlineMs = retainedDeadlineMs,
        ),
    ).openTurn(turn.copy(requestId = requestId))

    @Test
    fun `a completed turn closes its socket, and the sender stops reaching it`() {
        kotlinx.coroutines.runBlocking {
            MockWebServer().use { server ->
                // THE ONLY CONTROL THAT WANTS THE TURN TO END ON ITS OWN: the fixture completes it,
                // and everything asserted below follows from that completion.
                val dispatcher = RecordingDispatcher(sendDoneOnOpen = true)
                server.dispatcher = dispatcher.dispatcher()
                server.start()
                val opened = openTurn(server)
                // Run the whole turn: the server closes, the flow completes, and the sink's collection ends
                // at exactly the point an admitted answer would still need the connection.
                val whole = consumeTurn(opened.session.events)
                awaitBounded("the sink's collection did not end after the turn completed", whole::isActive, expectedActive = false)
                // The turn itself is over, and the transport terminates the connection it owned.
                waitForTerminated(dispatcher)
                // The session is over: a late result cannot be written into a closed connection.
                assertFalse(
                    opened.session.sender.sendResult(
                        CloudTurnFrame.AppOperationResult(turn.requestId, "op-late", "{}"),
                    ),
                )
            }
        }
    }

    @Test
    fun `a cancelled turn closes its socket`() {
        kotlinx.coroutines.runBlocking {
            MockWebServer().use { server ->
                val dispatcher = RecordingDispatcher()
                server.dispatcher = dispatcher.dispatcher()
                server.start()
                val opened = openTurn(server)
                // Cancel the collection the way a caller that stops reading does.
                withContext(Dispatchers.IO) { withTimeout(5_000) { opened.session.events.take(1).collect { } } }
                waitForTerminated(dispatcher)
                assertFalse(opened.session.sender.sendResult(CloudTurnFrame.AppOperationResult(turn.requestId, "op-x", "{}")))
            }
        }
    }

    @Test
    fun `a peer-initiated close is acknowledged, ends the consumer, and stops admission`() {
        kotlinx.coroutines.runBlocking {
            MockWebServer().use { server ->
                val dispatcher = RecordingDispatcher()
                server.dispatcher = dispatcher.dispatcher()
                server.start()
                val opened = openTurn(server)
                val live = openAndHandshake(this, opened.session.events, dispatcher)
                try {
                    // THE SERVER INITIATES THE CLOSE. These are MockWebServer-side sockets, so this
                    // statement is what starts the handshake -- not the callback that observes it.
                    waitForSockets(dispatcher, 1)
                    dispatcher.sockets.forEach { it.close(1001, "server going away") }
                    // BOUNDED: a close that ends nothing must fail here with a stall dump, not hang
                    // the whole class for the run's five minutes.
                    awaitBounded("consumer did not end after a peer close", live::isActive, expectedActive = false)
                    // The server side recorded the completed handshake: our answer was received.
                    awaitBounded(
                        "the peer close handshake never completed",
                        { dispatcher.closed.isNotEmpty() },
                        expectedActive = true,
                    )
                    assertFalse(
                        "a peer already closing must not accept a new result",
                        opened.session.sender.sendResult(
                            CloudTurnFrame.AppOperationResult(turn.requestId, "op-y", "{}"),
                        ),
                    )
                } finally {
                    live.close()
                }
            }
        }
    }

    /**
     * Wait, bounded, until [condition] holds; on expiry dump every thread and fail.
     *
     * A marker-based wait, not a join: when it stalls, the dump says WHICH thread is stuck and where,
     * which a bare timeout never does.
     */
    private fun awaitBounded(
        what: String,
        condition: () -> Boolean,
        expectedActive: Boolean,
        timeoutMs: Long = 5_000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (condition() != expectedActive && System.currentTimeMillis() < deadline) Thread.sleep(20)
        if (condition() != expectedActive) {
            val stacks = Thread.getAllStackTraces().entries.joinToString("\n") { (thread, frames) ->
                "\"${thread.name}\" ${thread.state}\n" + frames.joinToString("\n") { "    at $it" }
            }
            fail("$what within ${timeoutMs}ms; thread dump:\n$stacks")
        }
    }

    @Test
    fun `no result reaches another session's socket`() {
        kotlinx.coroutines.runBlocking {
            MockWebServer().use { server ->
                val dispatcher = RecordingDispatcher()
                server.dispatcher = dispatcher.dispatcher()
                server.start()
                val first = openTurn(server)
                val firstLive = openAndHandshake(this, first.session.events, dispatcher)
                val firstSocket = dispatcher.sockets.single()
                // THE FIRST TURN ENDS, TERMINALLY. Cancelling its collector establishes retirement, but
                // establishment is not retirement: the grace deliberately lets already-admitted work
                // finish and report on this connection, so `retirementDeadlineMs() > 0` would prove only
                // that the countdown started. What must be shown here is that the SESSION has retired --
                // it refuses admission, and the transport has terminated the socket it owned.
                firstLive.close()
                // EVERY PROBE IS RELEASED. `admit()` grants a real lease when it succeeds, so a poll that
                // abandoned the lease it obtained would hold the connection open itself and the wait below
                // would be measuring this harness's own outstanding work rather than the transport's.
                awaitBounded(
                    "the first session still admits work after its turn ended",
                    { probeAdmission(first.session.ownership) != null },
                    expectedActive = false,
                )
                assertNull(
                    "a retired session must not admit new work",
                    probeAdmission(first.session.ownership),
                )
                // Termination is the per-socket observation, not the refusal: refusal means admission
                // stopped, which happens at the START of retirement, before the socket is released.
                waitForTerminated(dispatcher, firstSocket)
                // Now the socket really is gone, and a result for the first turn must reach nothing.
                assertFalse(
                    "a retired session's sender must not report success",
                    first.session.sender.sendResult(CloudTurnFrame.AppOperationResult(turn.requestId, "op-from-one", "{}")),
                )
                // THE SUCCESSOR IS OPENED AND LEFT GENUINELY LIVE: a second session must be unaffected by
                // the first one's retirement, so this control asserts against a socket that still works.
                val second = openTurn(server, requestId = "req-socket-2")
                val secondLive = openAndHandshake(this, second.session.events, dispatcher)
                try {
                    assertTrue(
                        "the second turn's own sender could not reach its socket",
                        second.session.sender.sendResult(CloudTurnFrame.AppOperationResult("req-socket-2", "op-from-two", "{}")),
                    )
                    waitForMessages(dispatcher, 3)
                    assertNull(dispatcher.received.firstOrNull { it.contains("op-from-one") })
                    assertNotNull(dispatcher.received.firstOrNull { it.contains("op-from-two") })
                } finally {
                    secondLive.close()
                }
            }
        }
    }

    /**
     * Collects a turn's events in the background, the way the sink does, and ends the collection as
     * soon as the turn completes -- which is exactly the boundary the lifecycle has to survive.
     */
    private fun CoroutineScope.consumeTurn(events: Flow<CloudRouteEvent>): Job = launch(Dispatchers.IO) { runCatching { events.collect { } } }

    // --- Explicit turn-session lifecycle --------------------------------------------------------------

    @Test
    fun `a turn cancelled before the handshake opens never sends auth and never becomes reachable`() {
        kotlinx.coroutines.runBlocking {
            MockWebServer().use { server ->
                val dispatcher = RecordingDispatcher(delayUpgradeMs = 1_000)
                server.dispatcher = dispatcher.dispatcher()
                server.start()
                val opened = openTurn(server)
                // Cancel while the upgrade is still pending: no `onOpen` has happened yet.
                val turnJob = consumeTurn(opened.session.events)
                turnJob.cancel()
                awaitBounded("the turn did not stop", turnJob::isActive, expectedActive = false)
                // The handshake completes late, for a session that is already over. It must not send
                // the authentication frame, and the sender must not be able to reach it.
                Thread.sleep(1_500)
                assertNull(
                    "a session cancelled before its handshake must never write an auth frame",
                    dispatcher.received.firstOrNull { it.contains("authenticate") },
                )
                assertFalse(
                    "a retired session's sender must refuse",
                    opened.session.sender.sendResult(CloudTurnFrame.AppOperationResult(turn.requestId, "op-late-open", "{}")),
                )
            }
        }
    }

    @Test
    fun `a late open on a retired session confers no authority`() {
        kotlinx.coroutines.runBlocking {
            MockWebServer().use { server ->
                val dispatcher = RecordingDispatcher(delayUpgradeMs = 800)
                server.dispatcher = dispatcher.dispatcher()
                server.start()
                val opened = openTurn(server)
                val turnJob = consumeTurn(opened.session.events)
                turnJob.cancel()
                awaitBounded("the turn did not stop", turnJob::isActive, expectedActive = false)
                Thread.sleep(1_200)
                // Whatever the server saw, the session is retired: no result may be written, and a second
                // cancel/close cycle cannot revive it.
                assertFalse(opened.session.sender.sendResult(CloudTurnFrame.AppOperationResult(turn.requestId, "op-revive", "{}")))
                assertNull(dispatcher.received.firstOrNull { it.contains("op-revive") })
            }
        }
    }

    @Test
    fun `an admitted result can still be written after the sink stops at the terminal frame`() {
        kotlinx.coroutines.runBlocking {
            MockWebServer().use { server ->
                val dispatcher = RecordingDispatcher()
                server.dispatcher = dispatcher.dispatcher()
                server.start()
                val opened = openTurn(server)
                val turnJob = consumeTurn(opened.session.events)
                waitForSocket(dispatcher)
                // The sink is done reading, exactly as it is when it has consumed `done`; the socket is
                // still the turn's, and an already-admitted answer must reach it within the grace.
                turnJob.cancel()
                awaitBounded("the turn did not stop", turnJob::isActive, expectedActive = false)
                assertTrue(
                    "an admitted result must reach the turn's original socket after the sink stops reading",
                    opened.session.sender.sendResult(
                        CloudTurnFrame.AppOperationResult(turn.requestId, "op-after-terminal", "{\"outcome\":\"succeeded\"}"),
                    ),
                )
                waitForMessages(dispatcher, 2)
                assertNotNull(dispatcher.received.firstOrNull { it.contains("op-after-terminal") })
                // ...and the connection is released afterwards, so the lease was bounded, not permanent.
                waitForTerminated(dispatcher)
            }
        }
    }

    @Test
    fun `a successor turn's socket is not reachable from, or removed by, the earlier session`() {
        kotlinx.coroutines.runBlocking {
            MockWebServer().use { server ->
                val dispatcher = RecordingDispatcher()
                server.dispatcher = dispatcher.dispatcher()
                server.start()
                val first = openTurn(server)
                val firstLive = openAndHandshake(this, first.session.events, dispatcher)
                val second = openTurn(server, requestId = "req-successor")
                val secondLive = openAndHandshake(this, second.session.events, dispatcher)
                try {
                    waitForSockets(dispatcher, 2)
                    // The first session retires; the successor must keep its own socket and its own results.
                    first.session.sender.sendResult(CloudTurnFrame.AppOperationResult(turn.requestId, "op-first", "{}"))
                    assertTrue(
                        "the successor's socket must survive the earlier session's retirement",
                        second.session.sender.sendResult(CloudTurnFrame.AppOperationResult("req-successor", "op-second", "{}")),
                    )
                    waitForMessages(dispatcher, 3)
                    val socketsThatSawSecond = dispatcher.perSocket.entries.filter { (_, frames) ->
                        frames.any { it.contains("op-second") }
                    }
                    assertEquals("the successor's operation must travel on exactly one socket", 1, socketsThatSawSecond.size)
                    assertNull(
                        "the earlier session must not reach the successor's socket",
                        dispatcher.perSocket[socketsThatSawSecond.single().key]?.firstOrNull { it.contains("op-first") },
                    )
                } finally {
                    firstLive.close()
                    secondLive.close()
                }
            }
        }
    }

    // --- Admission leases, retirement deadline, and the boundary race ------------------------------

    @Test
    fun `a held admission lease keeps the connection alive until the work reports`() {
        kotlinx.coroutines.runBlocking {
            MockWebServer().use { server ->
                val dispatcher = RecordingDispatcher()
                server.dispatcher = dispatcher.dispatcher()
                server.start()
                val opened = openTurn(server)
                val ownership = opened.session.ownership
                val turnJob = consumeTurn(opened.session.events)
                waitForSocket(dispatcher)
                val turnSocket = dispatcher.sockets.single()
                // ADMITTED while live, then the turn ends while the work is still computing: the lease,
                // not the send, is what holds the connection open.
                val lease = ownership.admit()
                assertNotNull("a live turn must admit work", lease)
                try {
                    turnJob.cancel()
                    awaitBounded("the turn did not stop", turnJob::isActive, expectedActive = false)
                    // Cleanup has started and would previously have retired the socket here. The result
                    // still reaches it, because a lease is outstanding.
                    assertTrue(
                        "admitted work must still answer while its lease is held",
                        opened.session.sender.sendResult(
                            CloudTurnFrame.AppOperationResult(turn.requestId, "op-held-lease", "{}"),
                        ),
                    )
                    // THE PREMISE, MEASURED WHILE IT STILL HOLDS. `sendResult` returning true means the
                    // frame was QUEUED -- not flushed, and not received. Releasing the lease and only
                    // then looking for the frame would measure the queue, and a retirement that wins the
                    // race cancels a socket with frames still in it. So the arrival is asserted on the
                    // owned socket BEFORE the release, which is the state the control is named for.
                    // ONCE RELEASED, AN UNFLUSHED REPORT IS NOT LOST SILENTLY: its settlement is already
                    // durable, and a redelivery of the same operation replays it rather than resending.
                    waitForMessageOn(turnSocket, dispatcher, "op-held-lease")
                } finally {
                    lease?.release()
                }
                // ...and only then does retirement run its course and terminate the socket.
                waitForTerminated(dispatcher, turnSocket)
            }
        }
    }

    @Test
    fun `retirement refuses new admissions from the instant it is established`() {
        kotlinx.coroutines.runBlocking {
            MockWebServer().use { server ->
                val dispatcher = RecordingDispatcher()
                server.dispatcher = dispatcher.dispatcher()
                server.start()
                val opened = openTurn(server)
                val ownership = opened.session.ownership
                waitForSocket(dispatcher)
                // Establish retirement on THIS session; the state machine is the evidence, not the socket.
                val deadline = ownership.establishRetirement()
                assertTrue("establishment must fix a deadline", deadline > 0L)
                assertNull(
                    "after retirement starts, no new caller may acquire a lease",
                    probeAdmission(ownership),
                )
                assertEquals(
                    "establishment is idempotent: the instant must not move",
                    deadline,
                    ownership.establishRetirement(),
                )
                assertEquals("and the exposed deadline is the established one", deadline, ownership.retirementDeadlineMs())
            }
        }
    }

    @Test
    fun `admission and retirement cannot both win`() {
        kotlinx.coroutines.runBlocking {
            MockWebServer().use { server ->
                val dispatcher = RecordingDispatcher()
                server.dispatcher = dispatcher.dispatcher()
                server.start()
                val opened = openTurn(server)
                val ownership = opened.session.ownership
                waitForSocket(dispatcher)
                // Race admission against retirement from both directions, repeatedly: every admitted lease
                // must release, and nothing may be admitted after retirement has been established.
                val admittedBefore = java.util.concurrent.atomic.AtomicInteger(0)
                val admittedAfter = java.util.concurrent.atomic.AtomicInteger(0)
                repeat(200) { round ->
                    val established = java.util.concurrent.atomic.AtomicBoolean(false)
                    val racer = Thread {
                        if (ownership.establishRetirement() > 0L) established.set(true)
                    }
                    val leaser = Thread {
                        val lease = ownership.admit()
                        if (lease != null) {
                            if (established.get()) admittedAfter.incrementAndGet() else admittedBefore.incrementAndGet()
                            lease.release()
                        }
                    }
                    racer.start()
                    leaser.start()
                    racer.join(5_000)
                    leaser.join(5_000)
                }
                assertEquals(
                    "no lease may be granted once retirement has been established",
                    0,
                    admittedAfter.get(),
                )
            }
        }
    }

    @Test
    fun `the sink reaching the boundary first still waits on the transport's deadline`() {
        kotlinx.coroutines.runBlocking {
            MockWebServer().use { server ->
                val dispatcher = RecordingDispatcher()
                server.dispatcher = dispatcher.dispatcher()
                server.start()
                val opened = openTurn(server)
                val ownership = opened.session.ownership
                val turnJob = consumeTurn(opened.session.events)
                waitForSocket(dispatcher)
                val lease = ownership.admit()
                // THE SINK'S PATH: its collection ends first, so it establishes retirement before the
                // transport cleanup ever looks at it. The transport must find that same instant, not a
                // zero deadline that would let it skip its wait.
                turnJob.cancel()
                awaitBounded("the turn did not stop", turnJob::isActive, expectedActive = false)
                val sinkDeadline = ownership.establishRetirement()
                assertTrue("the sink's establishment must produce a usable deadline", sinkDeadline > 0L)
                assertEquals(
                    "the transport must observe the deadline the sink established",
                    sinkDeadline,
                    ownership.retirementDeadlineMs(),
                )
                // And the work admitted before the boundary can still report on it.
                assertTrue(
                    opened.session.sender.sendResult(CloudTurnFrame.AppOperationResult(turn.requestId, "op-sink-first", "{}")),
                )
                lease?.release()
                waitForMessages(dispatcher, 2)
            }
        }
    }

    @Test
    fun `a turn with no supplied deadline reports the absent sentinel and is capped by nothing`() {
        kotlinx.coroutines.runBlocking {
            MockWebServer().use { server ->
                val dispatcher = RecordingDispatcher()
                server.dispatcher = dispatcher.dispatcher()
                server.start()
                val opened = openTurn(server)
                val ownership = opened.session.ownership
                waitForSocket(dispatcher)
                // PRODUCTION PROPAGATION IS UNRESOLVED: no caller supplies a retained deadline, and the
                // transport must SAY SO rather than derive a plausible-looking lifetime.
                assertEquals(
                    "an unresolved earlier deadline is absent, never a derived instant",
                    CloudTurnTransport.NO_EARLIER_DEADLINE,
                    ownership.earlierRetainedDeadlineMs(),
                )
                val before = System.currentTimeMillis()
                val established = ownership.establishRetirement()
                val after = System.currentTimeMillis()
                assertTrue(
                    "with no supplied cap, retirement is the grace window from now (established=$established)",
                    established in (before + 1)..(after + 2_000L),
                )
            }
        }
    }

    @Test
    fun `a caller-supplied earlier deadline caps retirement`() {
        kotlinx.coroutines.runBlocking {
            MockWebServer().use { server ->
                val dispatcher = RecordingDispatcher()
                server.dispatcher = dispatcher.dispatcher()
                server.start()
                // THE REAL SESSION, not a stand-in: the deadline is handed to the production transport and
                // the production cap must honour it. A fake ownership would only prove its own arithmetic.
                val supplied = System.currentTimeMillis() + 300L
                val opened = openTurn(server, retainedDeadlineMs = supplied)
                val ownership = opened.session.ownership
                waitForSocket(dispatcher)
                assertEquals(
                    "the session must report the deadline the transport was given",
                    supplied,
                    ownership.earlierRetainedDeadlineMs(),
                )
                val established = ownership.establishRetirement()
                assertTrue(
                    "production retirement must be capped by the supplied deadline (supplied=$supplied established=$established)",
                    established <= supplied,
                )
            }
        }
    }

    @Test
    fun `a lease that is never released cannot hold the connection past the deadline`() {
        kotlinx.coroutines.runBlocking {
            MockWebServer().use { server ->
                val dispatcher = RecordingDispatcher()
                server.dispatcher = dispatcher.dispatcher()
                server.start()
                // A deadline close enough to observe: the connection must close ON the deadline
                // despite an outstanding lease, not wait for a release that never comes.
                val supplied = System.currentTimeMillis() + 400L
                val opened = openTurn(server, retainedDeadlineMs = supplied)
                val ownership = opened.session.ownership
                val turnJob = consumeTurn(opened.session.events)
                waitForSocket(dispatcher)
                val lease = ownership.admit()
                assertNotNull("a live turn must admit work", lease)
                val started = System.currentTimeMillis()
                turnJob.cancel()
                awaitBounded("the turn did not stop", turnJob::isActive, expectedActive = false)
                // NEVER RELEASED. The turn still ends: expiry is a deadline, not a lease count.
                waitForTerminated(dispatcher)
                val elapsed = System.currentTimeMillis() - started
                assertTrue(
                    "the connection must close at the shared deadline despite a held lease (elapsed=$elapsed)",
                    elapsed < 5_000L,
                )
            }
        }
    }

    /**
     * Ask whether the turn still admits work, and give any granted lease straight back.
     *
     * A probe that kept its lease would be outstanding work of the harness's own making, extending the
     * very retirement it is asking about.
     */
    private fun probeAdmission(ownership: CloudTurnTransport.TurnOwnership): CloudTurnTransport.TurnLease? = ownership.admit()?.also { it.release() }

    /**
     * Wait, bounded, for the transport to terminate the socket it owned.
     *
     * WHAT COUNTS AS TERMINATION, and what does not. `Session.retire()` aborts with
     * `WebSocket.cancel()` rather than declining gracefully with `close()`, so on this path the peer
     * does not receive a close frame and the server's completed handshake is NOT expected. But a
     * terminated socket still has to show up as one of exactly two things ON THAT SOCKET: a completed
     * close handshake, or a failure. The dispatcher's general [RecordingDispatcher.failures] list is
     * deliberately NOT accepted -- it also records peer-close notifications and binary frames, and
     * neither of those proves anything ended.
     */
    private fun waitForTerminated(dispatcher: RecordingDispatcher, socket: WebSocket? = null) {
        val target = socket ?: dispatcher.sockets.firstOrNull()
        val deadline = System.currentTimeMillis() + 5_000
        fun terminated(): Boolean = target != null && (dispatcher.closedPerSocket.containsKey(target) || dispatcher.failedPerSocket.containsKey(target))
        while (!terminated() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(
            "the transport never terminated the socket it owned " +
                "(closed=${target?.let { dispatcher.closedPerSocket[it] }}, " +
                "failed=${target?.let { dispatcher.failedPerSocket[it] }})",
            terminated(),
        )
    }
}
