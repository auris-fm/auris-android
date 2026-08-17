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
        val matcher = CloudFingerprintReferenceFetcher(CloudIdentity(RuntimeEnvironment.getApplication()))
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
        val matcher = CloudFingerprintReferenceFetcher(CloudIdentity(RuntimeEnvironment.getApplication()))
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
        val matcher = CloudFingerprintReferenceFetcher(CloudIdentity(RuntimeEnvironment.getApplication()))
            .buildMatcher(json.toByteArray())
        assertNull(matcher)
    }

    @Test
    fun `fetchReference hits the cloud endpoint with bearer auth and parses`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody(compactV2Json()))
        server.start()

        try {
            val fetcher = CloudFingerprintReferenceFetcher(CloudIdentity(RuntimeEnvironment.getApplication()))
            val matcher = runBlocking { fetcher.fetchReference(server.url("/").toString().trimEnd('/'), "ep_1") }

            assertNotNull(matcher)
            assertEquals(3, matcher!!.count)

            val request = server.takeRequest()
            assertEquals("/api/v1/episodes/ep_1/fingerprints", request.path)
            val auth = request.getHeader("Authorization")
            assertTrue("Authorization must be Bearer user_…, was: $auth", auth?.startsWith("Bearer user_") == true)
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
            val fetcher = CloudFingerprintReferenceFetcher(CloudIdentity(RuntimeEnvironment.getApplication()))
            val matcher = runBlocking { fetcher.fetchReference(server.url("/").toString().trimEnd('/'), "ep_missing") }
            assertNull(matcher)
        } finally {
            server.shutdown()
        }
    }
}
