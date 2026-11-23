package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import android.content.Context
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.playback.ExoPlayerDataSourceFactory
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackState
import au.com.shiftyjelly.pocketcasts.repositories.podcast.ChapterManager
import au.com.shiftyjelly.pocketcasts.utils.fingerprint.FingerprintDecodePolicy
import com.automattic.eventhorizon.EventHorizon
import dagger.Lazy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import uniffi.fingerprint_uniffi.CheckpointMatcher

/**
 * The Auris reference reaches the manager from the endpoint — through the fetcher and the store —
 * not through a matcher handed to it from outside.
 *
 * PR 125 left an `installCloudMatcherForTest` hook so these paths could be driven without the
 * native library. That seam proved the acceptance rule but bypassed the resolution deciding WHICH
 * reference survives, so nothing would have noticed if it stopped finding a reference at all. These
 * cases drive the real resolution against a real fetcher and assert on the payload it chose.
 */
class FingerprintCloudReferenceSourceTest {

    private lateinit var manager: FingerprintTimingManager
    private lateinit var store: FakeCloudReferenceStore
    private lateinit var cloud: MockWebServer

    @Before
    fun setUp() {
        val playbackManager = mock(PlaybackManager::class.java)
        whenever(playbackManager.playbackStateFlow).thenReturn(MutableStateFlow(PlaybackState()))
        store = FakeCloudReferenceStore()
        cloud = MockWebServer().also { it.start() }

        manager = FingerprintTimingManager(
            playbackManager = playbackManager,
            referenceRetriever = mock(FingerprintReferenceRetriever::class.java),
            eventHorizon = mock(EventHorizon::class.java),
            context = mock(Context::class.java),
            chapterManager = Lazy { mock(ChapterManager::class.java) },
            settings = mock(Settings::class.java),
            dataSourceFactory = Lazy { mock(ExoPlayerDataSourceFactory::class.java) },
            pcmTap = FingerprintPcmTap(),
            cloudConfig = object : CloudConfig {
                override fun baseUrl(): String = cloud.url("/").toString().trimEnd('/')
            },
            cloudFetcher = CloudFingerprintReferenceFetcher(alwaysMintToken()),
            cloudReferenceStore = store,
            decodePolicy = mock(FingerprintDecodePolicy::class.java),
        )
        // Nothing this manager launches may outlive the test.
        manager.scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    private fun alwaysMintToken() =
        au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisTokenProvider(
            clientProvider = {
                au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisAuthClient(
                    cloud.url("/").toString().trimEnd('/'),
                )
            },
            credentialProvider = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisAccountCredentialProviding {
                override fun credential(): String = "session"
            },
        )

    /** Three one-second checkpoints the client accumulates into usable values. */
    private fun compactV3(asString: String = ReferenceFingerprint.AURIS_FORMAT): String {
        val checkpoints = listOf(0, 1, 2).joinToString(",") { index ->
            val delta = if (index == 0) 0 else 1
            val buffer = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            for (hash in longArrayOf(7L, 9L, 11L)) buffer.putInt((hash and 0xFFFFFFFFL).toInt())
            """[$delta, "${Base64.getEncoder().encodeToString(buffer.array())}"]"""
        }
        return """
            {
              "format": "$asString",
              "total_duration": 10.0,
              "checkpoint_interval": 1,
              "checkpoint_duration": 8,
              "timestamp_quantum": 1,
              "checkpoints": [$checkpoints]
            }
        """.trimIndent()
    }

    private fun mintAndReference() {
        // The token mint and the reference both come from this server; the order is whatever the
        // fetcher asks for first, so answer by path.
        cloud.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.contains("fingerprints")) {
                    MockResponse().setResponseCode(200).setBody(compactV3())
                } else {
                    MockResponse().setResponseCode(200).setBody(
                        """{"access_token":"t","token_type":"Bearer","expires_in":900,"refresh_token":"r"}""",
                    )
                }
        }
    }

    @After
    fun tearDown() = runBlocking { manager.shutdownForTest() }

    /** Off Default: a preparation that outlives the test must not surface in the next class. */
    private fun CoroutineScope.separate() = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    @Test
    fun `a freshly fetched Auris reference is kept as the reference in use`() = runTest {
        mintAndReference()

        val resolved = manager.resolveCloudReference(cloud.url("/").toString().trimEnd('/'), "episode", "podcast")

        assertNotNull("a fresh reference must survive resolution", resolved)
        // The identity recorded for the mapping cache describes exactly these bytes.
        assertEquals(ReferenceFingerprint.AURIS_FORMAT, resolved!!.format)
        assertEquals(ReferenceFingerprint.sha256(resolved.payload), resolved.payloadHash)
        // And the payload is stored, so the next preparation can revalidate instead of refetching.
        assertNotNull(store.load("episode"))
    }

    @Test
    fun `a stored reference surviving revalidation is kept as the reference in use`() = runTest {
        val payload = compactV3().toByteArray()
        store.cached = CachedCloudReference(payload, etag = "\"v1\"")
        // 304: the endpoint says the stored bytes are still current.
        cloud.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.contains("fingerprints")) {
                    MockResponse().setResponseCode(304)
                } else {
                    MockResponse().setResponseCode(200).setBody(
                        """{"access_token":"t","token_type":"Bearer","expires_in":900,"refresh_token":"r"}""",
                    )
                }
        }

        val resolved = manager.resolveCloudReference(cloud.url("/").toString().trimEnd('/'), "episode", "podcast")

        assertNotNull("a revalidated reference must still survive resolution", resolved)
        // The stored bytes are the answer, not a second copy of them.
        assertEquals(ReferenceFingerprint.sha256(payload), resolved!!.payloadHash)
    }

    @Test
    fun `no stored reference and an unreachable endpoint leaves no reference`() = runTest {
        cloud.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.contains("fingerprints")) {
                    MockResponse().setResponseCode(503)
                } else {
                    MockResponse().setResponseCode(200).setBody(
                        """{"access_token":"t","token_type":"Bearer","expires_in":900,"refresh_token":"r"}""",
                    )
                }
        }

        val resolved = manager.resolveCloudReference(cloud.url("/").toString().trimEnd('/'), "episode", "podcast")

        assertNull("with nothing stored and nothing served there is no reference", resolved)
    }

    @Test
    fun `a stored payload in another format is not a usable Auris reference`() = runTest {
        // The endpoint serves one format. A stored payload in another is stale, and would align
        // against values this build no longer computes.
        store.cached = CachedCloudReference(
            compactV3(ReferenceFingerprint.TRANSCRIPT_FORMAT).toByteArray(),
            etag = "\"v1\"",
        )
        cloud.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.contains("fingerprints")) {
                    MockResponse().setResponseCode(304)
                } else {
                    MockResponse().setResponseCode(200).setBody(
                        """{"access_token":"t","token_type":"Bearer","expires_in":900,"refresh_token":"r"}""",
                    )
                }
        }

        val resolved = manager.resolveCloudReference(cloud.url("/").toString().trimEnd('/'), "episode", "podcast")

        assertNull("a stored payload in another format is not a usable Auris reference", resolved)
    }

    /** Records what the manager stored, and hands it back on the next load. */
    private class FakeCloudReferenceStore : CloudReferenceStore {
        var cached: CachedCloudReference? = null
        override suspend fun load(episodeUuid: String): CachedCloudReference? = cached
        override suspend fun save(episodeUuid: String, payload: ByteArray, etag: String?) {
            cached = CachedCloudReference(payload, etag ?: "")
        }
    }
}
