package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@Config(manifest = Config.NONE)
@RunWith(RobolectricTestRunner::class)
class CloudFingerprintReferenceFetcherTest {

    /**
     * A REAL AurisTokenProvider against a mock auth issuer. [credential] null models no signed-in
     * account (fail closed); otherwise the issuer mints [access].
     */
    private fun providerFor(credential: String?, access: String, authServer: MockWebServer?):
        au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisTokenProvider {
        authServer?.enqueue(mintResponse(access))
        return au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisTokenProvider(
            clientProvider = {
                authServer?.let {
                    au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisAuthClient(it.url("/").toString().trimEnd('/'))
                }
            },
            credentialProvider = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisAccountCredentialProviding {
                override fun credential(): String? = credential
            },
        )
    }

    /** An issuer that mints stale-token first, then fresh-token — the turn route's 401 posture. */
    private fun refreshingProvider(authServer: MockWebServer):
        au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisTokenProvider {
        val minted = java.util.concurrent.atomic.AtomicInteger(0)
        authServer.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                mintResponse(if (minted.incrementAndGet() == 1) "stale-token" else "fresh-token")
        }
        return au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisTokenProvider(
            clientProvider = {
                au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisAuthClient(authServer.url("/").toString().trimEnd('/'))
            },
            credentialProvider = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisAccountCredentialProviding {
                override fun credential(): String = "session"
            },
        )
    }


    /** Convenience: fetch and unwrap Fresh, else null — the pre-ETag matcher contract. */
    private fun fetchMatcher(
        fetcher: CloudFingerprintReferenceFetcher,
        server: MockWebServer,
        episodeUuid: String = "ep_1",
        cachedEtag: String? = null,
    ): CloudReferenceMatcher? = runBlocking {
        val outcome = fetcher.fetchReference(server.url("/").toString().trimEnd('/'), episodeUuid, "pod_1", cachedEtag)
        (outcome as? CloudFingerprintReferenceFetcher.Fetch.Fresh)?.matcher
    }

    private fun mintResponse(access: String): MockResponse = MockResponse().setResponseCode(200)
        .setBody("""{"access_token":"$access","token_type":"Bearer","expires_in":900,"refresh_token":"r-1"}""")

    private fun compactV2Json(): String {
        val windows = CloudFingerprintParityData.signal16kMonoWindows
        val checkpoints = windows.mapIndexed { index, window -> encodeCheckpoint(window, index) }.joinToString(",")
        return """
            {
              "format": "fingerprint-compact-v2",
              "total_duration": 10.0,
              "checkpoint_interval": 1,
              "checkpoint_duration": 8,
              "timestamp_quantum": 1,
              "checkpoints": [$checkpoints]
            }
        """.trimIndent()
    }

    // Delta 0 for the first window, then 1 per 1s stride (client re-accumulates).
    private fun encodeCheckpoint(hashes: LongArray, index: Int): String {
        val delta = if (index == 0) 0 else 1
        val buffer = ByteBuffer.allocate(hashes.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (hash in hashes) buffer.putInt((hash and 0xFFFFFFFFL).toInt())
        return """[$delta, "${Base64.getEncoder().encodeToString(buffer.array())}"]"""
    }

    @Test
    fun `buildMatcher parses compact-v2 checkpoints with correct hashes`() {
        val matcher = CloudFingerprintReferenceFetcher(providerFor(null, "unused", null))
            .buildMatcher(compactV2Json().toByteArray())

        assertNotNull(matcher)
        assertEquals(3, matcher!!.count)

        // The parity window hashes must match the parsed reference checkpoints
        // exactly — this is the client/server matching contract. The signal is
        // stationary so every window carries the same hashes; the matcher must
        // score 1.0 against checkpoints at their accumulated timestamps.
        val matches = matcher.findTopMatches(CloudFingerprintParityData.signal16kMonoWindows[1], 3)
        assertTrue(matches.size == 3)
        assertTrue(matches.all { it.score == 1.0f })
        assertTrue(matches.any { it.timestampSeconds == 1f })
    }

    @Test
    fun `buildMatcher returns null for invalid payload`() {
        val matcher = CloudFingerprintReferenceFetcher(providerFor(null, "unused", null))
            .buildMatcher("not json".toByteArray())
        assertNull(matcher)
    }

    @Test
    fun `buildMatcher returns null for empty checkpoints`() {
        val json = """
            {
              "format": "fingerprint-compact-v2",
              "total_duration": 10.0,
              "checkpoint_interval": 1,
              "checkpoint_duration": 8,
              "timestamp_quantum": 1,
              "checkpoints": []
            }
        """.trimIndent()
        val matcher = CloudFingerprintReferenceFetcher(providerFor(null, "unused", null))
            .buildMatcher(json.toByteArray())
        assertNull(matcher)
    }

    @Test
    fun `fetchReference sends the Auris access token and the podcast id`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(compactV2Json()))
        server.start()

        try {
            val auth = MockWebServer()
            auth.start()
            val fetcher = CloudFingerprintReferenceFetcher(providerFor("session", "auris-access-token-1", auth))
            val matcher = fetchMatcher(fetcher, server)

            assertNotNull(matcher)
            assertEquals(3, matcher!!.count)

            val request = server.takeRequest()
            assertEquals("/api/v1/episodes/ep_1/fingerprints?podcast_id=pod_1", request.path)
            // The SAME credential the turn route presents -- never the TOFU user_<uuid>.
            assertEquals("Bearer auris-access-token-1", request.getHeader("Authorization"))
            auth.shutdown()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a 401 refreshes the token and retries the request exactly once`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody(compactV2Json()))
        server.start()

        try {
            val auth = MockWebServer()
            auth.start()
            val fetcher = CloudFingerprintReferenceFetcher(refreshingProvider(auth))
            val matcher = fetchMatcher(fetcher, server)

            assertNotNull("the retry with the replacement token must succeed", matcher)
            assertEquals(2, server.requestCount)
            assertEquals("Bearer stale-token", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer fresh-token", server.takeRequest().getHeader("Authorization"))
            assertEquals("one initial mint plus exactly one forced refresh", 2, auth.requestCount)
            auth.shutdown()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a second 401 gives up rather than looping`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))
        server.start()

        try {
            val auth = MockWebServer()
            auth.start()
            val fetcher = CloudFingerprintReferenceFetcher(refreshingProvider(auth))
            val matcher = fetchMatcher(fetcher, server)
            assertNull(matcher)
            assertEquals("one retry, never a loop", 2, server.requestCount)
            assertEquals(2, auth.requestCount)
            auth.shutdown()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `fetchReference fails closed without a token and never sends the request`() {
        val server = MockWebServer()
        server.start()

        try {
            val fetcher = CloudFingerprintReferenceFetcher(providerFor(null, "unused", null))
            val matcher = fetchMatcher(fetcher, server)
            assertNull(matcher)
            assertEquals("no request may leave without a credential", 0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `fetchReference returns null on non-success status`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"catalog_not_found"}"""))
        server.start()

        try {
            val fetcher = CloudFingerprintReferenceFetcher(providerFor(null, "unused", null))
            val outcome = runBlocking { fetcher.fetchReference(server.url("/").toString().trimEnd('/'), "ep_missing", "pod_1") }
            assertTrue("a definitive 404 stays unavailable", outcome is CloudFingerprintReferenceFetcher.Fetch.Unavailable)
        } finally {
            server.shutdown()
        }
    }

// --- ETag revalidation (delta: fingerprint revalidation) ---

@Test
fun `a cached etag is sent as If-None-Match and a 304 yields NotModified`() {
    val server = MockWebServer()
    server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "\"v7\""))
    server.start()

    try {
        val auth = MockWebServer()
        auth.start()
        val fetcher = CloudFingerprintReferenceFetcher(providerFor("session", "tok", auth))
        val outcome = runBlocking {
            fetcher.fetchReference(server.url("/").toString().trimEnd('/'), "ep_1", "pod_1", cachedEtag = "\"v7\"")
        }

        assertTrue("a 304 against the stored etag must be NotModified",
            outcome is CloudFingerprintReferenceFetcher.Fetch.NotModified)
        assertEquals("\"v7\"", (outcome as CloudFingerprintReferenceFetcher.Fetch.NotModified).etag)
        assertEquals("\"v7\"", server.takeRequest().getHeader("If-None-Match"))
        auth.shutdown()
    } finally {
        server.shutdown()
    }
}

@Test
fun `a 200 after revalidation carries the payload and the new etag`() {
    val server = MockWebServer()
    server.enqueue(MockResponse().setBody(compactV2Json()).setHeader("ETag", "\"v8\""))
    server.start()

    try {
        val auth = MockWebServer()
        auth.start()
        val fetcher = CloudFingerprintReferenceFetcher(providerFor("session", "tok", auth))
        val fresh = runBlocking {
            fetcher.fetchReference(server.url("/").toString().trimEnd('/'), "ep_1", "pod_1", cachedEtag = "\"v7\"")
        } as CloudFingerprintReferenceFetcher.Fetch.Fresh

        assertEquals("\"v8\"", fresh.etag)
        assertEquals(3, fresh.matcher.count)
        assertEquals("the stored etag was still sent", "\"v7\"", server.takeRequest().getHeader("If-None-Match"))
        auth.shutdown()
    } finally {
        server.shutdown()
    }
}

@Test
fun `a 401 refresh keeps the revalidation etag on the retry`() {
    val server = MockWebServer()
    server.enqueue(MockResponse().setResponseCode(401))
    server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "\"v7\""))
    server.start()

    try {
        val auth = MockWebServer()
        auth.start()
        val fetcher = CloudFingerprintReferenceFetcher(refreshingProvider(auth))
        val outcome = runBlocking {
            fetcher.fetchReference(server.url("/").toString().trimEnd('/'), "ep_1", "pod_1", cachedEtag = "\"v7\"")
        }

        assertTrue(outcome is CloudFingerprintReferenceFetcher.Fetch.NotModified)
        server.takeRequest()
        assertEquals("\"v7\"", server.takeRequest().getHeader("If-None-Match"))
        auth.shutdown()
    } finally {
        server.shutdown()
    }
}
}
