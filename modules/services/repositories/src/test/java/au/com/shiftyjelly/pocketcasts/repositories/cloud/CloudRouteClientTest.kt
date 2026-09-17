package au.com.shiftyjelly.pocketcasts.repositories.cloud

import app.cash.turbine.test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pre-stream HTTP failures (400/401) are surfaced as a single [CloudRouteEvent.Error]
 * on the Flow rather than throwing, so Task 2 can handle all outcomes uniformly.
 */
class CloudRouteClientTest {

    private val userId = "user_79424ba0-f09b-013b-249c-566ad7a4dc9d"

    private val sampleContext = CloudRouteContext(
        episodeId = "79424ba0-f09b-013b-249c-566ad7a4dc9d",
        podcastId = "da7aba5e-f11e-f11e-f11e-da7aba5ef11e",
        referencePositionMs = 1_230_000L,
        clientPositionMs = 1_234_567L,
        recentReferencePositions = listOf(1_130_000L),
        previousReferencePositionMs = 1_230_000L,
    )

    @Test
    fun `mixed stream emits action tokens and done`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                sseResponse(
                    """
                    event: action
                    data: {"tool":"playback","action":"seek_to","params":{"reference_position_ms":1130000}}

                    event: token
                    data: {"text":"She"}

                    event: token
                    data: {"text":" is arguing."}

                    event: done
                    data: {"input_tokens":500,"output_tokens":80}
                    """.trimIndent(),
                ),
            )
            server.start()

            val client = CloudRouteClient(server.url("/").toString().trimEnd('/'), userId)

            client.route("What did she mean?", sampleContext).test {
                assertEquals(
                    CloudRouteEvent.Action(
                        tool = "playback",
                        action = "seek_to",
                        params = mapOf("reference_position_ms" to 1_130_000L),
                    ),
                    awaitItem(),
                )
                assertEquals(CloudRouteEvent.Token("She"), awaitItem())
                assertEquals(CloudRouteEvent.Token(" is arguing."), awaitItem())
                assertEquals(CloudRouteEvent.Done(inputTokens = 500, outputTokens = 80), awaitItem())
                awaitComplete()
            }

            assertRouteRequest(server.takeRequest())
        }
    }

    @Test
    fun `action-only stream completes after action and done`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                sseResponse(
                    """
                    event: action
                    data: {"tool":"playback","action":"pause","params":{}}

                    event: done
                    data: {"input_tokens":10,"output_tokens":0}
                    """.trimIndent(),
                ),
            )
            server.start()

            CloudRouteClient(server.url("/").toString().trimEnd('/'), userId)
                .route("pause", sampleContext)
                .test {
                    assertEquals(
                        CloudRouteEvent.Action("playback", "pause", emptyMap()),
                        awaitItem(),
                    )
                    assertEquals(CloudRouteEvent.Done(10, 0), awaitItem())
                    awaitComplete()
                }
        }
    }

    @Test
    fun `token-only stream emits tokens then done`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                sseResponse(
                    """
                    event: token
                    data: {"text":"Hello"}

                    event: token
                    data: {"text":" world"}

                    event: done
                    data: {"input_tokens":20,"output_tokens":5}
                    """.trimIndent(),
                ),
            )
            server.start()

            CloudRouteClient(server.url("/").toString().trimEnd('/'), userId)
                .route("summarize", sampleContext)
                .test {
                    assertEquals(CloudRouteEvent.Token("Hello"), awaitItem())
                    assertEquals(CloudRouteEvent.Token(" world"), awaitItem())
                    assertEquals(CloudRouteEvent.Done(20, 5), awaitItem())
                    awaitComplete()
                }
        }
    }

    @Test
    fun `400 invalid_request emits Error before stream`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setBody("""{"code":"invalid_request","message":"missing request"}"""),
            )
            server.start()

            CloudRouteClient(server.url("/").toString().trimEnd('/'), userId)
                .route("", sampleContext)
                .test {
                    assertEquals(
                        CloudRouteEvent.Error("invalid_request", "missing request"),
                        awaitItem(),
                    )
                    awaitComplete()
                }
        }
    }

    @Test
    fun `401 emits Error before stream`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setBody("""{"message":"unauthorized"}"""),
            )
            server.start()

            CloudRouteClient(server.url("/").toString().trimEnd('/'), userId)
                .route("hello", sampleContext)
                .test {
                    val error = awaitItem() as CloudRouteEvent.Error
                    assertEquals("unauthorized", error.code)
                    awaitComplete()
                }
        }
    }

    @Test
    fun `inline error event limit_exceeded`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                sseResponse(
                    """
                    event: error
                    data: {"code":"limit_exceeded","message":"You've used 10/10 free requests today."}
                    """.trimIndent(),
                ),
            )
            server.start()

            CloudRouteClient(server.url("/").toString().trimEnd('/'), userId)
                .route("hello", sampleContext)
                .test {
                    assertEquals(
                        CloudRouteEvent.Error(
                            "limit_exceeded",
                            "You've used 10/10 free requests today.",
                        ),
                        awaitItem(),
                    )
                    awaitComplete()
                }
        }
    }

    @Test
    fun `multi-line data field is joined before parsing`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                sseResponse(
                    """
                    event: token
                    data: {"text":"line1
                    data: line2"}

                    event: done
                    data: {"input_tokens":1,"output_tokens":1}
                    """.trimIndent(),
                ),
            )
            server.start()

            CloudRouteClient(server.url("/").toString().trimEnd('/'), userId)
                .route("hello", sampleContext)
                .test {
                    assertEquals(CloudRouteEvent.Token("line1\nline2"), awaitItem())
                    assertEquals(CloudRouteEvent.Done(1, 1), awaitItem())
                    awaitComplete()
                }
        }
    }

    @Test
    fun `malformed payload emits invalid_response not connection_lost`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                sseResponse(
                    """
                    event: token
                    data: {"text": not-valid-json}

                    """.trimIndent(),
                ),
            )
            server.start()

            CloudRouteClient(server.url("/").toString().trimEnd('/'), userId)
                .route("hello", sampleContext)
                .test {
                    val error = awaitItem() as CloudRouteEvent.Error
                    assertEquals("invalid_response", error.code)
                    awaitComplete()
                }
        }
    }

    @Test
    fun `mid-stream connection drop emits connection error`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                sseResponse(
                    """
                    event: token
                    data: {"text":"partial"}

                    event: token
                    data: {"text":"incomplete"
                    """.trimIndent(),
                ).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
            )
            server.start()

            CloudRouteClient(server.url("/").toString().trimEnd('/'), userId)
                .route("hello", sampleContext)
                .test {
                    assertEquals(CloudRouteEvent.Token("partial"), awaitItem())
                    val error = awaitItem() as CloudRouteEvent.Error
                    assertEquals("connection_lost", error.code)
                    awaitComplete()
                }
        }
    }

    @Test
    fun `client cancellation cancels okhttp call`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                sseResponse(
                    """
                    event: token
                    data: {"text":"first"}

                    event: token
                    data: {"text":"second"}

                    event: done
                    data: {"input_tokens":1,"output_tokens":1}
                    """.trimIndent(),
                ),
            )
            server.start()

            var canceledCall: Call? = null
            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .eventListener(object : EventListener() {
                    override fun canceled(call: Call) {
                        canceledCall = call
                    }
                })
                .build()

            val client = CloudRouteClient(
                baseUrl = server.url("/").toString().trimEnd('/'),
                userId = userId,
                okHttpClient = okHttpClient,
            )

            client.route("hello", sampleContext).test {
                assertEquals(CloudRouteEvent.Token("first"), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }

            assertTrue(canceledCall?.isCanceled() == true)
        }
    }

    @Test
    fun `route request sends Bearer authorization only on Auris route`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                sseResponse(
                    """
                    event: done
                    data: {"input_tokens":1,"output_tokens":0}
                    """.trimIndent(),
                ),
            )
            server.start()

            CloudRouteClient(server.url("/").toString().trimEnd('/'), userId)
                .route("hello", sampleContext)
                .test {
                    awaitItem()
                    awaitComplete()
                }

            val request = server.takeRequest()
            assertEquals("/api/v1/cloud/route", request.path)
            assertEquals("Bearer $userId", request.getHeader("Authorization"))
        }
    }

    @Test
    fun `client uses connect and read timeouts above server budget`() {
        val client = CloudRouteClient("https://example.com", userId)
        val timeouts = client.connectTimeoutSeconds to client.readTimeoutSeconds
        assertTrue("connect timeout should exceed 5s server budget", timeouts.first >= 15)
        assertTrue("read timeout should exceed 5s server budget", timeouts.second >= 15)
    }

    @Test
    fun `events stream before the body completes so play_quote fires mid-turn`() {
        // Raw socket so the body can be held open deterministically: the
        // action event is written immediately, the done event only after a
        // long hold. A buffered implementation cannot emit the action before
        // EOF, so it misses the elapsed-time bound; a streaming one passes.
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val bodyHoldMs = 2500L
        val actionPart =
            (
                "event: action\n" +
                    "data: {\"tool\":\"playback\",\"action\":\"play_quote\"," +
                    "\"params\":{\"reference_position_ms\":1130000}}\n\n"
                )
        val donePart =
            (
                "event: done\n" +
                    "data: {\"input_tokens\":1,\"output_tokens\":1}\n\n"
                )
        val fullBody = (actionPart + donePart).toByteArray()
        var writerFailure: Throwable? = null
        val writer = thread(start = true, isDaemon = true) {
            try {
                server.accept().use { socket ->
                    val out = socket.getOutputStream()
                    // Exact Content-Length: OkHttp must see a clean EOF at the
                    // end of the done event, not a truncated-body error.
                    out.write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/event-stream\r\n" +
                                "Content-Length: " + fullBody.size + "\r\n" +
                                "Connection: close\r\n" +
                                "\r\n"
                            ).toByteArray(),
                    )
                    out.flush()
                    out.write(actionPart.toByteArray())
                    out.flush()
                    Thread.sleep(bodyHoldMs)
                    out.write(donePart.toByteArray())
                    out.flush()
                }
            } catch (t: Throwable) {
                writerFailure = t
            }
        }

        try {
            val client = CloudRouteClient(
                "http://" + server.inetAddress.hostAddress + ":" + server.localPort,
                userId,
            )

            val startedAt = System.nanoTime()
            var actionElapsedMs = -1L
            var doneElapsedMs = -1L
            val events = mutableListOf<CloudRouteEvent>()
            runBlocking {
                client.route("play the quote", sampleContext).collect { event ->
                    val elapsed = (System.nanoTime() - startedAt) / 1_000_000
                    if (actionElapsedMs < 0) actionElapsedMs = elapsed
                    if (event is CloudRouteEvent.Done) doneElapsedMs = elapsed
                    events.add(event)
                }
            }

            assertEquals(
                CloudRouteEvent.Action(
                    tool = "playback",
                    action = "play_quote",
                    params = mapOf("reference_position_ms" to 1_130_000L),
                ),
                events.firstOrNull(),
            )
            assertEquals(CloudRouteEvent.Done(inputTokens = 1, outputTokens = 1), events.lastOrNull())
            // The action must have been emitted while the body was still
            // open — well before the EOF that unblocks the done event.
            // Self-calibrating: the done event can only arrive after the
            // writer's hold, so doneElapsedMs IS "the buffered number".
            // Buffered (emit-at-EOF): both ~= hold → ratio ~1 → fails.
            // Streaming: action within ms of connect, done at ~hold → ~100x margin.
            assertTrue(
                "action at ${actionElapsedMs}ms vs done at ${doneElapsedMs}ms — flow is buffering, not streaming",
                actionElapsedMs < doneElapsedMs / 2,
            )
        } finally {
            server.close()
            writer.join(5_000)
            writerFailure?.let { throw it }
        }
    }

    private fun sseResponse(body: String): MockResponse {
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(body)
    }

    private fun assertRouteRequest(recorded: okhttp3.mockwebserver.RecordedRequest) {
        assertEquals("POST /api/v1/cloud/route HTTP/1.1", recorded.requestLine)
        assertEquals("Bearer $userId", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"request\":\"What did she mean?\""))
        assertTrue(body.contains("\"episode_id\":\"79424ba0-f09b-013b-249c-566ad7a4dc9d\""))
        assertTrue(body.contains("\"podcast_id\":\"da7aba5e-f11e-f11e-f11e-da7aba5ef11e\""))
        assertTrue(body.contains("\"reference_position_ms\":1230000"))
        assertTrue(body.contains("\"client_position_ms\":1234567"))
        assertTrue(body.contains("\"recent_reference_positions\":[1130000]"))
        assertTrue(body.contains("\"previous_reference_position_ms\":1230000"))
    }
}
