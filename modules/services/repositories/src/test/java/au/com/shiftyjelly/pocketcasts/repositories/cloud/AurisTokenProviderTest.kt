package au.com.shiftyjelly.pocketcasts.repositories.cloud

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Token provider postures (task #33 client half): single-flight refresh, an
 * expiry skew, re-acquire on a 401, and fail-closed in every other case.
 */
class AurisTokenProviderTest {

    private fun tokensResponse(access: String, refresh: String? = "r-1", expiresIn: Long = 900): String = """{"access_token":"$access","token_type":"Bearer","expires_in":$expiresIn,"refresh_token":${refresh?.let { "\"$it\"" } ?: "null"}}"""

    private class FakeCredential(private var value: String?) : AurisAccountCredentialProviding {
        var calls = 0
        override suspend fun credential(): String? {
            calls += 1
            return value
        }
    }

    @Test
    fun `exchanges the credential once and caches the access token`() = runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                    .setResponseCode(200)
                    .setBody(tokensResponse("access-1"))
            }
            server.start()

            val credential = FakeCredential("pc-session")
            val provider = AurisTokenProvider(
                clientProvider = { AurisAuthClient(server.url("/").toString().trimEnd('/')) },
                credentialProvider = credential,
            )

            assertEquals("access-1", provider.currentToken())
            assertEquals("access-1", provider.currentToken())
            // The cached token is reused: exactly one exchange reached the server.
            assertEquals(1, server.requestCount)
            assertEquals(1, credential.calls)
        }
    }

    @Test
    fun `an expired cached token is refreshed exactly once, single-flight`() = runTest {
        val refreshCalls = AtomicInteger()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return if (path.contains("/auth/refresh")) {
                        refreshCalls.incrementAndGet()
                        MockResponse().setResponseCode(200).setBody(tokensResponse("access-2", "r-2"))
                    } else {
                        MockResponse().setResponseCode(200).setBody(tokensResponse("access-1", "r-1", expiresIn = 0))
                    }
                }
            }
            server.start()

            val provider = AurisTokenProvider(
                clientProvider = { AurisAuthClient(server.url("/").toString().trimEnd('/')) },
                credentialProvider = FakeCredential("pc-session"),
            )

            // First token has expires_in = 0, so it is stale immediately.
            assertEquals("access-1", provider.currentToken())

            // Concurrent callers must share one refresh (a double refresh would
            // rotate twice and revoke the chain).
            val results = (1..8).map { async { provider.currentToken() } }.awaitAll()

            assertTrue(results.all { it == "access-2" })
            assertEquals(1, refreshCalls.get())
        }
    }

    @Test
    fun `a 401 from refresh falls back to re-acquiring from the credential`() = runTest {
        var exchangeCalls = 0
        var refreshCalls = 0
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.contains("/auth/refresh") -> {
                            refreshCalls += 1
                            MockResponse().setResponseCode(401).setBody("""{"code":"refresh_reused"}""")
                        }

                        else -> {
                            exchangeCalls += 1
                            // First exchange yields a stale-but-refreshable pair,
                            // the second (post-401) yields a usable token.
                            val body = if (exchangeCalls == 1) {
                                tokensResponse("access-stale", "r-1", expiresIn = 0)
                            } else {
                                tokensResponse("access-exchanged", "r-2")
                            }
                            MockResponse().setResponseCode(200).setBody(body)
                        }
                    }
                }
            }
            server.start()

            val provider = AurisTokenProvider(
                clientProvider = { AurisAuthClient(server.url("/").toString().trimEnd('/')) },
                credentialProvider = FakeCredential("pc-session"),
            )

            // Freshly fetched but already-stale pair: the next call must refresh.
            provider.currentToken()
            val second = provider.currentToken()

            assertEquals("access-exchanged", second)
            assertEquals(1, refreshCalls)
            assertEquals(2, exchangeCalls)
        }
    }

    @Test
    fun `no credential fails closed with no request`() = runTest {
        MockWebServer().use { server ->
            server.start()

            val provider = AurisTokenProvider(
                clientProvider = { AurisAuthClient(server.url("/").toString().trimEnd('/')) },
                credentialProvider = FakeCredential(null),
            )

            assertNull(provider.currentToken())
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `unconfigured auth fails closed without dialing`() = runTest {
        MockWebServer().use { server ->
            server.start()

            val provider = AurisTokenProvider(
                clientProvider = { null },
                credentialProvider = FakeCredential("pc-session"),
            )

            assertNull(provider.currentToken())
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `an inconclusive failure keeps a still-valid token instead of failing the request`() = runTest {
        var phase = 0
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (phase > 0) {
                        // Both upstream paths unreachable: verification is
                        // inconclusive, not a rejection.
                        return MockResponse().setResponseCode(503).setHeader("Retry-After", "5")
                    }
                    // 10s lifetime: stale under the 30s skew (so the refresh
                    // path is attempted) yet still unexpired.
                    return MockResponse().setResponseCode(200).setBody(tokensResponse("access-valid", expiresIn = 10))
                }
            }
            server.start()

            val provider = AurisTokenProvider(
                clientProvider = { AurisAuthClient(server.url("/").toString().trimEnd('/')) },
                credentialProvider = FakeCredential("pc-session"),
            )

            assertEquals("access-valid", provider.currentToken())

            // Upstream verification path now fails inconclusively: the client
            // must not dial unauthenticated, must not sign the user out, and
            // must keep serving the token that is still within its lifetime.
            phase = 1
            assertEquals("access-valid", provider.currentToken())
        }
    }

    @Test
    fun `an inconclusive exchange failure fails closed and caches nothing`() = runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(503)
            }
            server.start()

            val provider = AurisTokenProvider(
                clientProvider = { AurisAuthClient(server.url("/").toString().trimEnd('/')) },
                credentialProvider = FakeCredential("pc-session"),
            )

            assertNull(provider.currentToken())
            assertNull(provider.currentToken())
            // Each attempt is one request; nothing is cached or retried in-turn.
            assertEquals(2, server.requestCount)
        }
    }
}
