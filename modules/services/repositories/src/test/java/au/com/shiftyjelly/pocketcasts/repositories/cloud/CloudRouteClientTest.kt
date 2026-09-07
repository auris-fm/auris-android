package au.com.shiftyjelly.pocketcasts.repositories.cloud

import app.cash.turbine.test
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
