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
    private fun providerFor(credential: String?, access: String, authServer: MockWebServer?): au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisTokenProvider {
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
    private fun refreshingProvider(authServer: MockWebServer): au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisTokenProvider {
        val minted = java.util.concurrent.atomic.AtomicInteger(0)
        authServer.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = mintResponse(if (minted.incrementAndGet() == 1) "stale-token" else "fresh-token")
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

    /** Convenience: fetch and unwrap Fresh's payload, else null. */
    private fun fetchPayload(
        fetcher: CloudFingerprintReferenceFetcher,
        server: MockWebServer,
        episodeUuid: String = "ep_1",
        cachedEtag: String? = null,
    ): ByteArray? = runBlocking {
        val outcome = fetcher.fetchReference(server.url("/").toString().trimEnd('/'), episodeUuid, "pod_1", cachedEtag)
        (outcome as? CloudFingerprintReferenceFetcher.Fetch.Fresh)?.payload
    }

    private fun mintResponse(access: String): MockResponse = MockResponse().setResponseCode(200)
        .setBody("""{"access_token":"$access","token_type":"Bearer","expires_in":900,"refresh_token":"r-1"}""")

    /** Three one-second checkpoints; the values are placeholders, since these cases pin transport. */
    private fun compactV3Json(format: String = ReferenceFingerprint.AURIS_FORMAT): String {
        val windows = listOf(
            longArrayOf(7L, 9L, 11L),
            longArrayOf(7L, 9L, 11L),
            longArrayOf(7L, 9L, 11L),
        )
        val checkpoints = windows.mapIndexed { index, window -> encodeCheckpoint(window, index) }.joinToString(",")
        return """
            {
              "format": "$format",
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
    fun `an undecodable body is not a usable reference`() {
        // The fetch decides usability from the payload alone; building the matcher needs the
        // native library and happens where the reference is used.
        assertNull(ReferenceFingerprint.decode("not json".toByteArray()))
    }

    @Test
    fun `a reference with no checkpoints is not usable`() {
        val json = """
            {
              "format": "fingerprint-compact-v3",
              "total_duration": 10.0,
              "checkpoint_interval": 1,
              "checkpoint_duration": 8,
              "timestamp_quantum": 1,
              "checkpoints": []
            }
        """.trimIndent()
        val decoded = ReferenceFingerprint.decode(json.toByteArray())
        assertNotNull(decoded)
        assertTrue(decoded!!.libraryCheckpoints().isEmpty())
    }

    @Test
    fun `a 200 in another format is not a usable Auris reference`() {
        // Only the Auris endpoint's own format counts here. A body in the transcript host's format
        // parses, but it is not what this endpoint serves, so the fetch treats it as unusable.
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(compactV3Json(ReferenceFingerprint.TRANSCRIPT_FORMAT)))
        server.start()

        try {
            val auth = MockWebServer()
            auth.start()
            val fetcher = CloudFingerprintReferenceFetcher(providerFor("session", "token", auth))

            assertNull(
                "a payload in another format must not be accepted at the Auris site",
                fetchPayload(fetcher, server),
            )
            auth.shutdown()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `fetchReference sends the Auris access token and the podcast id`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(compactV3Json()))
        server.start()

        try {
            val auth = MockWebServer()
            auth.start()
            val fetcher = CloudFingerprintReferenceFetcher(providerFor("session", "auris-access-token-1", auth))
            val payload = fetchPayload(fetcher, server)

            assertNotNull(payload)
            assertEquals(3, ReferenceFingerprint.decode(payload!!)!!.libraryCheckpoints().size)

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
        server.enqueue(MockResponse().setBody(compactV3Json()))
        server.start()

        try {
            val auth = MockWebServer()
            auth.start()
            val fetcher = CloudFingerprintReferenceFetcher(refreshingProvider(auth))
            val payload = fetchPayload(fetcher, server)

            assertNotNull("the retry with the replacement token must succeed", payload)
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
            val payload = fetchPayload(fetcher, server)
            assertNull(payload)
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
            val payload = fetchPayload(fetcher, server)
            assertNull(payload)
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

            assertTrue(
                "a 304 against the stored etag must be NotModified",
                outcome is CloudFingerprintReferenceFetcher.Fetch.NotModified,
            )
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
        server.enqueue(MockResponse().setBody(compactV3Json()).setHeader("ETag", "\"v8\""))
        server.start()

        try {
            val auth = MockWebServer()
            auth.start()
            val fetcher = CloudFingerprintReferenceFetcher(providerFor("session", "tok", auth))
            val fresh = runBlocking {
                fetcher.fetchReference(server.url("/").toString().trimEnd('/'), "ep_1", "pod_1", cachedEtag = "\"v7\"")
            } as CloudFingerprintReferenceFetcher.Fetch.Fresh

            assertEquals("\"v8\"", fresh.etag)
            assertEquals(3, ReferenceFingerprint.decode(fresh.payload)!!.libraryCheckpoints().size)
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
