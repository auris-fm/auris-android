package au.com.shiftyjelly.pocketcasts.repositories.cloud

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Best-effort playback-start prefetch hint (cloud-assistant.md): accepted or
 * skipped on success, silent on any failure, and never more than one attempt.
 */
class CloudPrefetchClientTest {

    private val userId = "user_79424ba0-f09b-013b-249c-566ad7a4dc9d"

    @Test
    fun `accepted hint sends episode and podcast ids with bearer auth`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(202)
                    .setBody("""{"status":"accepted"}"""),
            )
            server.start()

            val client = CloudPrefetchClient(server.url("/").toString().trimEnd('/'), userId)
            val outcome = client.prefetch(episodeId = "ep-1", podcastId = "pod-1")

            assertEquals(CloudPrefetchClient.Outcome.ACCEPTED, outcome)
            val recorded = server.takeRequest()
            assertEquals("POST /api/v1/cloud/context/prefetch HTTP/1.1", recorded.requestLine)
            assertEquals("Bearer $userId", recorded.getHeader("Authorization"))
            val body = recorded.body.readUtf8()
            assertTrue(body.contains("\"episode_id\":\"ep-1\""))
            assertTrue(body.contains("\"podcast_id\":\"pod-1\""))
        }
    }

    @Test
    fun `skipped hint is reported as skipped`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(202).setBody("""{"status":"skipped"}"""))
            server.start()

            val outcome = CloudPrefetchClient(server.url("/").toString().trimEnd('/'), userId)
                .prefetch("ep-1")

            assertEquals(CloudPrefetchClient.Outcome.SKIPPED, outcome)
        }
    }

    @Test
    fun `no token means no hint is dialed`() = runBlocking {
        MockWebServer().use { server ->
            server.start()

            val noToken = object : CloudTokenProviding {
                override suspend fun currentToken(): String? = null
            }
            val outcome = CloudPrefetchClient(server.url("/").toString().trimEnd('/'), noToken)
                .prefetch("ep-1")

            assertEquals(CloudPrefetchClient.Outcome.NOT_SENT, outcome)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `server failure is swallowed with a single attempt`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            server.start()

            val outcome = CloudPrefetchClient(server.url("/").toString().trimEnd('/'), userId)
                .prefetch("ep-1")

            assertEquals(CloudPrefetchClient.Outcome.NOT_SENT, outcome)
            // No retry loop: exactly one request reached the server.
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `unauthorized hint is swallowed`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"unauthorized"}"""))
            server.start()

            val outcome = CloudPrefetchClient(server.url("/").toString().trimEnd('/'), userId)
                .prefetch("ep-1")

            assertEquals(CloudPrefetchClient.Outcome.NOT_SENT, outcome)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `nothing is sent without identity or an episode id`() = runBlocking {
        MockWebServer().use { server ->
            server.start()

            assertEquals(
                CloudPrefetchClient.Outcome.NOT_SENT,
                CloudPrefetchClient(server.url("/").toString().trimEnd('/'), "").prefetch("ep-1"),
            )
            assertEquals(
                CloudPrefetchClient.Outcome.NOT_SENT,
                CloudPrefetchClient(server.url("/").toString().trimEnd('/'), userId).prefetch(""),
            )
            // A blank gateway URL (cloud not configured) never dials out.
            assertEquals(
                CloudPrefetchClient.Outcome.NOT_SENT,
                CloudPrefetchClient("", userId).prefetch("ep-1"),
            )
            assertEquals(0, server.requestCount)
        }
    }
}
