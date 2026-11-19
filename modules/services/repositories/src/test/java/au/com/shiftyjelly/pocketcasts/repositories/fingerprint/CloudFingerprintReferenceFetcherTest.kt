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

    private class FixedTokenProvider(private val token: String?) :
        au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTokenProviding {
        override suspend fun currentToken(): String? = token
    }

    /** Serves a stale token until refreshed, then a fresh one — the turn route's 401 posture. */
    private class RefreshingTokenProvider :
        au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTokenProviding {
        var refreshes = 0
        override suspend fun currentToken(): String = if (refreshes == 0) "stale-token" else "fresh-token"
        override suspend fun refreshToken() {
            refreshes++
        }
    }

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
        val matcher = CloudFingerprintReferenceFetcher(FixedTokenProvider("user_test"))
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
        val matcher = CloudFingerprintReferenceFetcher(FixedTokenProvider("user_test"))
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
        val matcher = CloudFingerprintReferenceFetcher(FixedTokenProvider("user_test"))
            .buildMatcher(json.toByteArray())
        assertNull(matcher)
    }

    @Test
    fun `fetchReference sends the Auris access token and the podcast id`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(compactV2Json()))
        server.start()

        try {
            val fetcher = CloudFingerprintReferenceFetcher(FixedTokenProvider("auris-access-token-1"))
            val matcher = runBlocking {
                fetcher.fetchReference(server.url("/").toString().trimEnd('/'), "ep_1", "pod_1")
            }

            assertNotNull(matcher)
            assertEquals(3, matcher!!.count)

            val request = server.takeRequest()
            assertEquals("/api/v1/episodes/ep_1/fingerprints?podcast_id=pod_1", request.path)
            // The SAME credential the turn route presents -- never the TOFU user_<uuid>.
            assertEquals("Bearer auris-access-token-1", request.getHeader("Authorization"))
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
            val tokens = RefreshingTokenProvider()
            val fetcher = CloudFingerprintReferenceFetcher(tokens)
            val matcher = runBlocking {
                fetcher.fetchReference(server.url("/").toString().trimEnd('/'), "ep_1", "pod_1")
            }

            assertNotNull("the retry with the replacement token must succeed", matcher)
            assertEquals(2, server.requestCount)
            assertEquals("Bearer stale-token", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer fresh-token", server.takeRequest().getHeader("Authorization"))
            assertEquals("exactly one forced refresh", 1, tokens.refreshes)
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
            val tokens = RefreshingTokenProvider()
            val fetcher = CloudFingerprintReferenceFetcher(tokens)
            val matcher = runBlocking {
                fetcher.fetchReference(server.url("/").toString().trimEnd('/'), "ep_1", "pod_1")
            }
            assertNull(matcher)
            assertEquals("one retry, never a loop", 2, server.requestCount)
            assertEquals(1, tokens.refreshes)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `fetchReference fails closed without a token and never sends the request`() {
        val server = MockWebServer()
        server.start()

        try {
            val fetcher = CloudFingerprintReferenceFetcher(FixedTokenProvider(null))
            val matcher = runBlocking {
                fetcher.fetchReference(server.url("/").toString().trimEnd('/'), "ep_1", "pod_1")
            }
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
            val fetcher = CloudFingerprintReferenceFetcher(FixedTokenProvider("user_test"))
            val matcher = runBlocking { fetcher.fetchReference(server.url("/").toString().trimEnd('/'), "ep_missing", "pod_1") }
            assertNull(matcher)
        } finally {
            server.shutdown()
        }
    }
}
