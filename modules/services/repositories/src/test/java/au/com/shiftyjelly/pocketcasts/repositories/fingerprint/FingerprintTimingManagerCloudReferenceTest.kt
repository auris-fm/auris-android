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
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The revalidation fallback contract (delta: fingerprint revalidation): a failed or unusable
 * revalidation must never drop cloud alignment that a stored reference still supports, and a
 * 304 against an unusable stored payload must retry once without the etag.
 */
@Config(manifest = Config.NONE)
@RunWith(RobolectricTestRunner::class)
class FingerprintTimingManagerCloudReferenceTest {

    private lateinit var manager: FingerprintTimingManager
    private lateinit var store: FileCloudReferenceStore

    @Before
    fun setUp() {
        val playbackManager = mock(PlaybackManager::class.java)
        whenever(playbackManager.playbackStateFlow).thenReturn(MutableStateFlow(PlaybackState()))
        val decodePolicy = mock(FingerprintDecodePolicy::class.java)
        val fetcher = CloudFingerprintReferenceFetcher(noCredentialProvider())
        store = FileCloudReferenceStore(RuntimeEnvironment.getApplication())
        manager = FingerprintTimingManager(
            playbackManager = playbackManager,
            referenceRetriever = mock(FingerprintReferenceRetriever::class.java),
            eventHorizon = mock(EventHorizon::class.java),
            context = mock(Context::class.java),
            chapterManager = Lazy { mock(ChapterManager::class.java) },
            settings = mock(Settings::class.java),
            dataSourceFactory = Lazy { mock(ExoPlayerDataSourceFactory::class.java) },
            pcmTap = FingerprintPcmTap(),
            cloudConfig = mock(SharedPreferencesCloudConfig::class.java),
            cloudFetcher = fetcher,
            cloudReferenceStore = store,
            decodePolicy = decodePolicy,
        )
    }

    /** No credential at all: every fetchReference is fail-closed Unavailable, no request leaves. */
    private fun noCredentialProvider() = au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisTokenProvider(
        clientProvider = { null },
        credentialProvider = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisAccountCredentialProviding {
            override fun credential(): String? = null
        },
    )

    private fun compactV2Payload(): ByteArray {
        val windows = CloudFingerprintParityData.signal16kMonoWindows
        val checkpoints = windows.mapIndexed { index, window ->
            val buffer = ByteBuffer.allocate(window.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (hash in window) buffer.putInt((hash and 0xFFFFFFFFL).toInt())
            val delta = if (index == 0) 0 else 1
            """[$delta, "${Base64.getEncoder().encodeToString(buffer.array())}"]"""
        }.joinToString(",")
        return """
            {
              "format": "fingerprint-compact-v2",
              "total_duration": 10.0,
              "checkpoint_interval": 1,
              "checkpoint_duration": 8,
              "timestamp_quantum": 1,
              "checkpoints": [$checkpoints]
            }
        """.trimIndent().toByteArray()
    }

    @Test
    fun `a failed revalidation keeps the stored reference instead of dropping alignment`() = runBlocking {
        kotlinx.coroutines.runBlocking {
            store.save("ep_keep", compactV2Payload(), "\"v1\"")

            // No credential -> fetchReference is Unavailable without touching the network.
            val matcher = manager.resolveCloudMatcher("https://offline.invalid", "ep_keep", "pod_1")

            assertNotNull("a failed revalidation must keep the stored reference", matcher)
            assertEquals(3, matcher!!.count)
        }
    }

    @Test
    fun `a transient failure with no stored reference still issues exactly one request per call`() {
        kotlinx.coroutines.runBlocking {
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(503))
            server.start()

            try {
                val playbackManager = mock(PlaybackManager::class.java)
                whenever(playbackManager.playbackStateFlow).thenReturn(MutableStateFlow(PlaybackState()))
                val auth = MockWebServer()
                auth.enqueue(
                    MockResponse().setResponseCode(200).setBody(
                        """{"access_token":"tok-1","token_type":"Bearer","expires_in":900,"refresh_token":"r-1"}""",
                    ),
                )
                auth.start()
                val fetcher = CloudFingerprintReferenceFetcher(
                    au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisTokenProvider(
                        clientProvider = {
                            au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisAuthClient(auth.url("/").toString().trimEnd('/'))
                        },
                        credentialProvider = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisAccountCredentialProviding {
                            override fun credential(): String = "session"
                        },
                    ),
                )
                val managerWithServer = FingerprintTimingManager(
                    playbackManager = playbackManager,
                    referenceRetriever = mock(FingerprintReferenceRetriever::class.java),
                    eventHorizon = mock(EventHorizon::class.java),
                    context = mock(Context::class.java),
                    chapterManager = Lazy { mock(ChapterManager::class.java) },
                    settings = mock(Settings::class.java),
                    dataSourceFactory = Lazy { mock(ExoPlayerDataSourceFactory::class.java) },
                    pcmTap = FingerprintPcmTap(),
                    cloudConfig = mock(SharedPreferencesCloudConfig::class.java),
                    cloudFetcher = fetcher,
                    cloudReferenceStore = store,
                    decodePolicy = mock(FingerprintDecodePolicy::class.java),
                )

                val matcher = runBlocking {
                    managerWithServer.resolveCloudMatcher(server.url("/").toString().trimEnd('/'), "ep_none", "pod_1")
                }

                assertNull(matcher)
                assertEquals("one attempt per preparation, never a second request inside it", 1, server.requestCount)
                auth.shutdown()
            } finally {
                server.shutdown()
            }
        }
    }

    /**
     * The preparation gate's whole point: a failed reference fetch must leave the episode
     * RETRYABLE, so the next play or turn can try again rather than losing alignment for the
     * whole session.
     */
    @Test
    fun `a transient failure leaves preparation retryable while a definitive answer does not`() {
        // The rule the prepare gate applies on every play, resume or turn.
        assertTrue(
            "a Failed preparation may be attempted again",
            FingerprintTimingManager.isRetryablePreparation(
                state = FingerprintTimingManager.State.Failed(IOException("timeout"), "ep_1"),
            ),
        )
        assertTrue(
            "an Unavailable preparation is retryable unless the episode is a known definitive miss",
            FingerprintTimingManager.isRetryablePreparation(
                state = FingerprintTimingManager.State.Unavailable("ep_1"),
            ),
        )
        assertFalse(
            "a definitively absent reference is not worth another request",
            FingerprintTimingManager.isRetryablePreparation(
                state = FingerprintTimingManager.State.Unavailable("ep_1"),
                definitiveMiss = true,
            ),
        )
        assertFalse(
            "an actively preparing episode is reused, never restarted",
            FingerprintTimingManager.isRetryablePreparation(
                state = FingerprintTimingManager.State.Active(3),
            ),
        )
    }

    /**
     * A definitive 403/404 must only suppress attempts for THIS PROCESS. A reference generated
     * later, or an entitlement that changes, has to be fetchable again after a restart.
     */
    @Test
    fun `a definitive miss is remembered for this session only`() {
        assertFalse("a fresh process knows nothing", manager.isDefinitiveCloudMiss("ep_1"))

        manager.recordDefinitiveCloudMiss("ep_1")
        assertTrue("within the session the answer is remembered", manager.isDefinitiveCloudMiss("ep_1"))
        assertFalse("another episode is unaffected", manager.isDefinitiveCloudMiss("ep_2"))

        // A NEW manager is what the next launch builds: the record is gone, so the episode is
        // fetchable again and a reference generated later can be picked up.
        val afterRestart = FingerprintTimingManager(
            playbackManager = mock(PlaybackManager::class.java),
            referenceRetriever = mock(FingerprintReferenceRetriever::class.java),
            eventHorizon = mock(EventHorizon::class.java),
            context = mock(Context::class.java),
            chapterManager = Lazy { mock(ChapterManager::class.java) },
            settings = mock(Settings::class.java),
            dataSourceFactory = Lazy { mock(ExoPlayerDataSourceFactory::class.java) },
            pcmTap = FingerprintPcmTap(),
            cloudConfig = mock(SharedPreferencesCloudConfig::class.java),
            cloudFetcher = CloudFingerprintReferenceFetcher(noCredentialProvider()),
            cloudReferenceStore = store,
            decodePolicy = mock(FingerprintDecodePolicy::class.java),
        )
        assertFalse("nothing durable carried the miss across launches", afterRestart.isDefinitiveCloudMiss("ep_1"))
    }

    @Test
    fun `a failed fetch leaves the episode retryable while a definitive answer does not`() {
        // The decision the preparation gate applies on every play, resume or turn: one attempt,
        // then either the preparation is terminal for the episode or the next play tries again.
        assertFalse(
            "no token, transport failure, 500 or 503: the next play must try again",
            FingerprintTimingManager.isTerminalCloudFailure(CloudFingerprintReferenceFetcher.Fetch.Unavailable),
        )
        assertTrue(
            "a 404 or 403 is the server's final word for this episode",
            FingerprintTimingManager.isTerminalCloudFailure(CloudFingerprintReferenceFetcher.Fetch.NotAvailable),
        )
    }

    @Test
    fun `a 304 against an unusable stored payload retries once without the etag`() {
        kotlinx.coroutines.runBlocking {
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "\"stale\""))
            server.enqueue(MockResponse().setBody(compactV2Payload().toString(Charsets.UTF_8)).setHeader("ETag", "\"fresh\""))
            server.start()

            try {
                val playbackManager = mock(PlaybackManager::class.java)
                whenever(playbackManager.playbackStateFlow).thenReturn(MutableStateFlow(PlaybackState()))
                val auth = MockWebServer()
                // One mint: the provider caches the access token for the whole revalidation chain.
                auth.enqueue(
                    MockResponse().setResponseCode(200).setBody(
                        """{"access_token":"tok-1","token_type":"Bearer","expires_in":900,"refresh_token":"r-1"}""",
                    ),
                )
                auth.start()
                val fetcher = CloudFingerprintReferenceFetcher(
                    au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisTokenProvider(
                        clientProvider = {
                            au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisAuthClient(auth.url("/").toString().trimEnd('/'))
                        },
                        credentialProvider = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisAccountCredentialProviding {
                            override fun credential(): String = "session"
                        },
                    ),
                )
                val managerWithServer = FingerprintTimingManager(
                    playbackManager = playbackManager,
                    referenceRetriever = mock(FingerprintReferenceRetriever::class.java),
                    eventHorizon = mock(EventHorizon::class.java),
                    context = mock(Context::class.java),
                    chapterManager = Lazy { mock(ChapterManager::class.java) },
                    settings = mock(Settings::class.java),
                    dataSourceFactory = Lazy { mock(ExoPlayerDataSourceFactory::class.java) },
                    pcmTap = FingerprintPcmTap(),
                    cloudConfig = mock(SharedPreferencesCloudConfig::class.java),
                    cloudFetcher = fetcher,
                    cloudReferenceStore = store,
                    decodePolicy = mock(FingerprintDecodePolicy::class.java),
                )
                // Corrupt stored entry: a 304 against it must not trust it.
                store.save("ep_retry", "not json".toByteArray(), "\"stale\"")

                val matcher = runBlocking {
                    managerWithServer.resolveCloudMatcher(server.url("/").toString().trimEnd('/'), "ep_retry", "pod_1")
                }

                assertNotNull(matcher)
                assertEquals(3, matcher!!.count)
                assertEquals("\"stale\"", server.takeRequest().getHeader("If-None-Match"))
                assertNull("the retry must be unconditional", server.takeRequest().getHeader("If-None-Match"))
                // The fresh payload replaced the corrupt one for next time.
                val stored = store.load("ep_retry")
                assertEquals("\"fresh\"", stored!!.etag)
                auth.shutdown()
            } finally {
                server.shutdown()
            }
        }
    }
}
