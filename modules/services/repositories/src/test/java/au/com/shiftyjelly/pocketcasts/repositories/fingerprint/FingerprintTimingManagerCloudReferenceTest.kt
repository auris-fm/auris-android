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
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
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

    private fun compactV3Payload(format: String = ReferenceFingerprint.AURIS_FORMAT): ByteArray {
        // Placeholder values: these cases pin the revalidation fallback, not the scores.
        val windows = listOf(
            longArrayOf(7L, 9L, 11L),
            longArrayOf(7L, 9L, 11L),
            longArrayOf(7L, 9L, 11L),
        )
        val checkpoints = windows.mapIndexed { index, window ->
            val buffer = ByteBuffer.allocate(window.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (hash in window) buffer.putInt((hash and 0xFFFFFFFFL).toInt())
            val delta = if (index == 0) 0 else 1
            """[$delta, "${Base64.getEncoder().encodeToString(buffer.array())}"]"""
        }.joinToString(",")
        return """
            {
              "format": "$format",
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
            store.save("ep_keep", compactV3Payload(), "\"v1\"")

            // No credential -> fetchReference is Unavailable without touching the network.
            val kept = manager.resolveCloudReferencePayload("https://offline.invalid", "ep_keep", "pod_1")

            assertNotNull("a failed revalidation must keep the stored reference", kept)
            assertEquals(3, ReferenceFingerprint.decode(kept!!)!!.libraryCheckpoints().size)
        }
    }

    @Test
    fun `a stored reference in another format is not kept by a failed revalidation`() {
        kotlinx.coroutines.runBlocking {
            // The rollout window: a copy cached before the endpoint moved to its current format.
            // Keeping it would align against values this build's Auris path does not accept.
            store.save("ep_stale", compactV3Payload(ReferenceFingerprint.TRANSCRIPT_FORMAT), "\"v1\"")

            val kept = manager.resolveCloudReferencePayload("https://offline.invalid", "ep_stale", "pod_1")

            assertNull("a stored payload in another format is not an Auris reference", kept)
        }
    }

    @Test
    fun `a failure with no stored reference stays unavailable after exactly one fetch`() {
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

                val payload = runBlocking {
                    managerWithServer.resolveCloudReferencePayload(server.url("/").toString().trimEnd('/'), "ep_none", "pod_1")
                }

                assertNull(payload)
                assertEquals("Unavailable must not fire a second attempt", 1, server.requestCount)
                auth.shutdown()
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun `a 304 against an unusable stored payload retries once without the etag`() {
        kotlinx.coroutines.runBlocking {
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "\"stale\""))
            server.enqueue(MockResponse().setBody(compactV3Payload().toString(Charsets.UTF_8)).setHeader("ETag", "\"fresh\""))
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

                val payload = runBlocking {
                    managerWithServer.resolveCloudReferencePayload(server.url("/").toString().trimEnd('/'), "ep_retry", "pod_1")
                }

                assertNotNull(payload)
                assertEquals(3, ReferenceFingerprint.decode(payload!!)!!.libraryCheckpoints().size)
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
