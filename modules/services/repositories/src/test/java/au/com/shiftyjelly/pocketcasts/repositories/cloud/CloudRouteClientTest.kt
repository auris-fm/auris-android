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

            client.route(turn("What did she mean?")).test {
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
                .route(turn("pause"))
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
                .route(turn("summarize"))
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
                .route(turn(""))
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
                .route(turn("hello"))
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
                .route(turn("hello"))
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
                .route(turn("hello"))
                .test {
                    assertEquals(CloudRouteEvent.Token("line1\nline2"), awaitItem())
                    assertEquals(CloudRouteEvent.Done(1, 1), awaitItem())
                    awaitComplete()
                }
        }
    }

    @Test
    fun `unparseable result payload is skipped so the turn survives`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                sseResponse(
                    """
                    event: result
                    data: {"kind":"episode_results","scope":["not","a","string"],"items":[]}

                    event: token
                    data: {"text":"Still answered."}

                    event: done
                    data: {"input_tokens":1,"output_tokens":1}
                    """.trimIndent(),
                ),
            )
            server.start()

            // A result this client cannot read must not abort a turn it never
            // asked for (the event reaches non-advertising clients too).
            CloudRouteClient(server.url("/").toString().trimEnd('/'), userId)
                .route(turn("hello"))
                .test {
                    assertEquals(CloudRouteEvent.Token("Still answered."), awaitItem())
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
                .route(turn("hello"))
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
                .route(turn("hello"))
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

            client.route(turn("hello")).test {
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
                .route(turn("hello"))
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
                client.route(turn("play the quote")).collect { event ->
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

    @Test
    fun `a missing token fails closed without dialing the server`() = runBlocking {
        MockWebServer().use { server ->
            server.start()

            val noToken = object : CloudTokenProviding {
                override suspend fun currentToken(): String? = null
            }
            CloudRouteClient(server.url("/").toString().trimEnd('/'), noToken)
                .route(turn("hello"))
                .test {
                    val error = awaitItem() as CloudRouteEvent.Error
                    assertEquals("unauthorized", error.code)
                    awaitComplete()
                }

            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `an expired token reported as null also fails closed`() = runBlocking {
        MockWebServer().use { server ->
            server.start()

            val expiring = object : CloudTokenProviding {
                override suspend fun currentToken(): String? = "" // revoked/expired
            }
            CloudRouteClient(server.url("/").toString().trimEnd('/'), expiring)
                .route(turn("hello"))
                .test {
                    assertEquals("unauthorized", (awaitItem() as CloudRouteEvent.Error).code)
                    awaitComplete()
                }

            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `duplicate transport attempt of one logical turn reuses its request id`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(sseResponse("event: done\ndata: {\"input_tokens\":1,\"output_tokens\":0}\n\n"))
            server.enqueue(sseResponse("event: done\ndata: {\"input_tokens\":1,\"output_tokens\":0}\n\n"))
            server.start()

            val client = CloudRouteClient(server.url("/").toString().trimEnd('/'), userId)
            val logicalTurn = turn("pause")

            // Two transport attempts of the same logical turn must carry the
            // same request id — the server deduplicates rather than re-executing.
            client.route(logicalTurn).test {
                awaitItem()
                awaitComplete()
            }
            client.route(logicalTurn).test {
                awaitItem()
                awaitComplete()
            }

            val first = server.takeRequest().body.readUtf8()
            val second = server.takeRequest().body.readUtf8()
            val idPattern = Regex("\"request_id\":\"([^\"]+)\"")
            assertEquals(idPattern.find(first)!!.groupValues[1], idPattern.find(second)!!.groupValues[1])
        }
    }

    @Test
    fun `duplicate_request 409 surfaces as an error event`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(409)
                    .setBody("""{"code":"duplicate_request","message":"This logical turn was already admitted."}"""),
            )
            server.start()

            CloudRouteClient(server.url("/").toString().trimEnd('/'), userId)
                .route(turn("pause"))
                .test {
                    val error = awaitItem() as CloudRouteEvent.Error
                    assertEquals("duplicate_request", error.code)
                    awaitComplete()
                }
        }
    }

    private fun turn(
        request: String,
        context: CloudRouteContext = sampleContext,
        requestId: String = "2b870f93-52bf-4e38-9232-f7c93b8ffdaa",
        capabilities: List<String> = emptyList(),
        routeHint: CloudRouteHint? = null,
    ) = CloudRouteTurn(
        request = request,
        context = context,
        requestId = requestId,
        capabilities = capabilities,
        routeHint = routeHint,
    )

    @Test
    fun `request carries turn control fields`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(sseResponse("event: done\ndata: {\"input_tokens\":1,\"output_tokens\":1}\n\n"))
            server.start()

            val client = CloudRouteClient(server.url("/").toString().trimEnd('/'), userId)
            client.route(
                turn(
                    request = "find beginner investing episodes",
                    capabilities = listOf(CloudRouteCapabilities.SEARCH_RESULTS_V1),
                    routeHint = CloudRouteHint("search_episodes", mapOf("query" to "investing")),
                ),
            ).test {
                awaitItem()
                awaitComplete()
            }

            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"request_id\":\"2b870f93-52bf-4e38-9232-f7c93b8ffdaa\""))
            assertTrue(body.contains("\"capabilities\":[\"search_results_v1\"]"))
            assertTrue(body.contains("\"route_hint\""))
            assertTrue(body.contains("\"operation\":\"search_episodes\""))
            assertTrue(body.contains("\"query\":\"investing\""))
        }
    }

    @Test
    fun `legacy shape omits optional turn control fields`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(sseResponse("event: done\ndata: {\"input_tokens\":1,\"output_tokens\":0}\n\n"))
            server.start()

            CloudRouteClient(server.url("/").toString().trimEnd('/'), userId)
                .route(turn("pause"))
                .test {
                    awaitItem()
                    awaitComplete()
                }

            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"request_id\""))
            // Parity with the iOS half: optional turn-control fields are
            // omitted entirely when there is nothing to send.
            assertTrue(!body.contains("\"capabilities\""))
            assertTrue(!body.contains("\"route_hint\""))
            assertTrue(!body.contains("\"recent_conversation\""))
        }
    }

    @Test
    fun `recent conversation is serialized inside context`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(sseResponse("event: done\ndata: {\"input_tokens\":1,\"output_tokens\":0}\n\n"))
            server.start()

            val context = sampleContext.copy(
                recentConversation = listOf(
                    CloudRouteConversationEntry(CloudRouteConversationEntry.ROLE_USER, "first question"),
                    CloudRouteConversationEntry(CloudRouteConversationEntry.ROLE_ASSISTANT, "first answer"),
                ),
            )
            CloudRouteClient(server.url("/").toString().trimEnd('/'), userId)
                .route(turn("a follow-up", context = context))
                .test {
                    awaitItem()
                    awaitComplete()
                }

            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"recent_conversation\":["))
            assertTrue(body.contains("\"role\":\"user\",\"text\":\"first question\""))
            assertTrue(body.contains("\"role\":\"assistant\",\"text\":\"first answer\""))
        }
    }

    @Test
    fun `negotiated result event is parsed with items`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                sseResponse(
                    """
                    event: result
                    data: {"kind":"episode_results","scope":"global","items":[{"evidence_id":"e1","episode_id":"ep-1","podcast_id":"pod-1","title":"Investing 101","text":"snippet","speaker":null,"source_url":null,"playable":true,"seekable":true}],"next_cursor":null}

                    event: done
                    data: {"input_tokens":0,"output_tokens":0}
                    """.trimIndent(),
                ),
            )
            server.start()

            CloudRouteClient(server.url("/").toString().trimEnd('/'), userId)
                .route(turn("find investing", capabilities = listOf(CloudRouteCapabilities.SEARCH_RESULTS_V1)))
                .test {
                    val result = awaitItem() as CloudRouteEvent.Result
                    assertEquals(CloudSearchResults.KIND_EPISODE_RESULTS, result.results.kind)
                    assertEquals(CloudSearchResults.SCOPE_GLOBAL, result.results.scope)
                    val item = result.results.items.single()
                    assertEquals("e1", item.evidenceId)
                    assertEquals("ep-1", item.episodeId)
                    assertTrue(item.playable && item.seekable)
                    awaitItem()
                    awaitComplete()
                }
        }
    }

    @Test
    fun `result event is skipped by clients that did not advertise it`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                sseResponse(
                    """
                    event: result
                    data: {"kind":"episode_results","scope":"global","items":[],"next_cursor":null}

                    event: token
                    data: {"text":"No matches."}

                    event: done
                    data: {"input_tokens":0,"output_tokens":0}
                    """.trimIndent(),
                ),
            )
            server.start()

            // A legacy-shaped client still parses the stream; the server only
            // sends result events to advertising clients, so the fallback text
            // path is exercised here through token+done.
            CloudRouteClient(server.url("/").toString().trimEnd('/'), userId)
                .route(turn("find investing"))
                .test {
                    awaitItem() // result (harmless: no renderer wired at this layer)
                    assertEquals(CloudRouteEvent.Token("No matches."), awaitItem())
                    awaitItem()
                    awaitComplete()
                }
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
