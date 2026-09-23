package au.com.shiftyjelly.pocketcasts.repositories.cloud

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Auth endpoint contracts (task #33): JSON exchange and refresh, with 401
 * classified as definitive and everything else as inconclusive-but-fail-closed.
 */
class AurisAuthClientTest {

    private val tokenResponse = """
        {"access_token":"auris-access","token_type":"Bearer","expires_in":900,
         "refresh_token":"auris-refresh","refresh_expires_in":2592000,
         "issuer":"https://auth.auris.fm","subject":"user-uuid-1"}
    """.trimIndent()

    @Test
    fun `exchange posts the credential and parses the token pair`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(tokenResponse))
            server.start()

            val client = AurisAuthClient(server.url("/").toString().trimEnd('/'))
            val result = client.exchange(
                credential = "pc-session-credential",
                device = AurisDeviceInfo(platform = "android", appVersion = "8.18"),
            )

            val tokens = (result as AurisAuthResult.Success).tokens
            assertEquals("auris-access", tokens.accessToken)
            assertEquals(900L, tokens.expiresIn)
            assertEquals("auris-refresh", tokens.refreshToken)
            assertEquals("user-uuid-1", tokens.subject)

            val recorded = server.takeRequest()
            assertEquals("POST /api/v1/auth/token HTTP/1.1", recorded.requestLine)
            val body = recorded.body.readUtf8()
            assertTrue(body.contains("\"credential\":\"pc-session-credential\""))
            assertTrue(body.contains("\"platform\":\"android\""))
        }
    }

    @Test
    fun `refresh posts the refresh token to the refresh endpoint`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(tokenResponse))
            server.start()

            val result = AurisAuthClient(server.url("/").toString().trimEnd('/'))
                .refresh("old-refresh")

            assertTrue(result is AurisAuthResult.Success)
            val recorded = server.takeRequest()
            assertEquals("POST /api/v1/auth/refresh HTTP/1.1", recorded.requestLine)
            assertEquals("{\"refresh_token\":\"old-refresh\"}", recorded.body.readUtf8())
        }
    }

    @Test
    fun `401 is definitive and carries the error code`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(401)
                    .setBody("""{"code":"refresh_reused","message":"reuse detected"}"""),
            )
            server.start()

            val result = AurisAuthClient(server.url("/").toString().trimEnd('/')).refresh("rotated-out")

            assertEquals("refresh_reused", (result as AurisAuthResult.Unauthorized).code)
        }
    }

    @Test
    fun `transport failure is inconclusive`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.start()

            val result = AurisAuthClient(server.url("/").toString().trimEnd('/')).refresh("r")

            assertEquals(AurisAuthResult.Unavailable, result)
        }
    }

    @Test
    fun `unparseable success body is inconclusive rather than a token`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("not-json"))
            server.start()

            val result = AurisAuthClient(server.url("/").toString().trimEnd('/')).exchange("cred")

            assertEquals(AurisAuthResult.Unavailable, result)
        }
    }

    @Test
    fun `no base url configured never dials`() = runBlocking {
        MockWebServer().use { server ->
            server.start()

            val result = AurisAuthClient("").exchange("cred")

            assertEquals(AurisAuthResult.Unavailable, result)
            assertEquals(0, server.requestCount)
        }
    }
}
