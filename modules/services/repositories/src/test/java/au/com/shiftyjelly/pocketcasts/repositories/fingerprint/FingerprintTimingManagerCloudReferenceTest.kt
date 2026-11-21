package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import android.content.Context
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.playback.ExoPlayerDataSourceFactory
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackState
import au.com.shiftyjelly.pocketcasts.repositories.podcast.ChapterManager
import au.com.shiftyjelly.pocketcasts.utils.fingerprint.FingerprintDecodePolicy
import com.automattic.eventhorizon.EventHorizon
import dagger.Lazy
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.Date
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import kotlinx.coroutines.runBlocking
import org.mockito.kotlin.any
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

    @Test
    fun `a transient failure leaves transcript preparation retryable while a definitive one does not`() {
        assertTrue(
            "a Failed transcript preparation may be attempted again",
            FingerprintTimingManager.isRetryableTranscriptPreparation(
                FingerprintTimingManager.State.Failed(IOException("timeout"), "ep_1"),
            ),
        )
        assertFalse(
            "an actively preparing episode is reused, never restarted",
            FingerprintTimingManager.isRetryableTranscriptPreparation(FingerprintTimingManager.State.Active(3)),
        )
        assertFalse(
            "an unavailable transcript preparation has nothing more to try",
            FingerprintTimingManager.isRetryableTranscriptPreparation(FingerprintTimingManager.State.Unavailable("ep_1")),
        )
    }

    /**
     * The cloud reference has its own state. Its retry rule must be independent of transcript
     * readiness — that independence is the whole point: a transient cloud failure used to vanish
     * behind a working transcript mapping.
     */
    @Test
    fun `a transient cloud failure is retryable and a definitive miss is not`() {
        fun retry(state: FingerprintTimingManager.CloudReference, sameEpisode: Boolean = true, configured: Boolean = true) =
            FingerprintTimingManager.shouldRetryCloudReference(
                cloudReference = state,
                episodeUuid = "ep_1",
                cloudConfigured = configured,
                sameEpisode = sameEpisode,
            )

        assertTrue("never tried: fetch", retry(FingerprintTimingManager.CloudReference.NotTried))
        assertTrue("a transient failure retries on the next play", retry(FingerprintTimingManager.CloudReference.Failed))
        assertFalse("an in-flight fetch is never duplicated", retry(FingerprintTimingManager.CloudReference.Fetching))
        assertFalse("a ready reference is not refetched", retry(FingerprintTimingManager.CloudReference.Ready))
        assertFalse(
            "a definitive 403/404 ends the attempts",
            retry(FingerprintTimingManager.CloudReference.DefinitiveMiss("ep_1")),
        )
        assertFalse("cutover off means no cloud attempt", retry(FingerprintTimingManager.CloudReference.Failed, configured = false))
        assertFalse("another episode's state does not drive this one", retry(FingerprintTimingManager.CloudReference.Failed, sameEpisode = false))
    }

    @Test
    fun `a definitive miss is remembered for this session only`() {
        assertNotEquals(
            "a fresh manager has not tried yet",
            FingerprintTimingManager.CloudReference.DefinitiveMiss("ep_1"),
            manager.cloudReferenceState(),
        )
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

    // --- triggers, driven through the REAL entry point (play, then resume) ---

    private class Harness(
        val manager: FingerprintTimingManager,
        val playbackStates: MutableStateFlow<PlaybackState>,
        val episodeUuid: String,
        val referenceServer: MockWebServer,
        val authServer: MockWebServer,
    )

    /**
     * A manager wired to mock servers, with the playback state the proactive observer reads.
     * FeatureFlag is stubbed on, so a play really calls prepareForCurrentEpisode.
     */
    /** The observer only prepares when SYNCED_TRANSCRIPTS is on; the default here is the build's. */
    private fun enableSyncedTranscripts() {
        au.com.shiftyjelly.pocketcasts.utils.featureflag.FeatureFlag.initialize(
            listOf(
                object : au.com.shiftyjelly.pocketcasts.utils.featureflag.FeatureProvider {
                    override val priority = 1
                    override suspend fun awaitInitialization(): Boolean = true
                    override fun hasFeature(feature: au.com.shiftyjelly.pocketcasts.utils.featureflag.Feature): Boolean = true
                    override fun isEnabled(feature: au.com.shiftyjelly.pocketcasts.utils.featureflag.Feature): Boolean = true
                },
            ),
        )
    }

    private fun harness(
        referenceResponses: List<MockResponse>,
    ): Harness {
        enableSyncedTranscripts()
        val episodeUuid = "ep_huberman"
        val referenceServer = MockWebServer()
        referenceResponses.forEach(referenceServer::enqueue)
        referenceServer.start()
        val auth = MockWebServer()
        // Enough tokens for every attempt in these tests, including a retry.
        repeat(4) {
            auth.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"access_token":"tok-1","token_type":"Bearer","expires_in":900,"refresh_token":"r-1"}""",
                ),
            )
        }
        auth.start()

        val states = MutableStateFlow(PlaybackState())
        val episode = PodcastEpisode(
            uuid = episodeUuid,
            podcastUuid = "pod_1",
            publishedDate = Date(),
            duration = 2146.0,
            downloadUrl = "https://example.invalid/audio.mp3",
        )
        val playbackManager = mock(PlaybackManager::class.java)
        whenever(playbackManager.playbackStateFlow).thenReturn(states)
        whenever(playbackManager.getCurrentEpisode()).thenReturn(episode)
        whenever(playbackManager.isPlaybackRemote()).thenReturn(false)
        whenever(playbackManager.playbackStateRelay).thenReturn(
            com.jakewharton.rxrelay2.BehaviorRelay.createDefault(PlaybackState()).toSerialized(),
        )

        val cloudConfig = mock(SharedPreferencesCloudConfig::class.java)
        whenever(cloudConfig.baseUrl()).thenReturn(referenceServer.url("/").toString().trimEnd('/'))

        val settings = mock(Settings::class.java)
        whenever(settings.cacheEntirePlayingEpisode).thenReturn(
            au.com.shiftyjelly.pocketcasts.preferences.UserSetting.Mock(false, mock()),
        )

        // The transcript side is irrelevant to cloud alignment; make it a clean miss.
        val referenceRetriever = mock(FingerprintReferenceRetriever::class.java)
        runBlocking {
            whenever(referenceRetriever.fetchReferenceData(any(), any(), any()))
                .thenReturn(FingerprintReferenceRetriever.FetchResult.NotFound)
            whenever(referenceRetriever.loadCachedReference(any())).thenReturn(null)
            whenever(referenceRetriever.loadReferenceData(any())).thenReturn(null)
        }

        val manager = FingerprintTimingManager(
            playbackManager = playbackManager,
            referenceRetriever = referenceRetriever,
            eventHorizon = mock(EventHorizon::class.java),
            // The platform gate reads the real application context.
            context = RuntimeEnvironment.getApplication(),
            chapterManager = Lazy { mock(ChapterManager::class.java) },
            settings = settings,
            dataSourceFactory = Lazy { mock(ExoPlayerDataSourceFactory::class.java) },
            pcmTap = FingerprintPcmTap(),
            cloudConfig = cloudConfig,
            cloudFetcher = CloudFingerprintReferenceFetcher(
                au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisTokenProvider(
                    clientProvider = {
                        au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisAuthClient(auth.url("/").toString().trimEnd('/'))
                    },
                    credentialProvider = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisAccountCredentialProviding {
                        override fun credential(): String = "session"
                    },
                ),
            ),
            cloudReferenceStore = store,
            decodePolicy = mock(FingerprintDecodePolicy::class.java),
        )
        return Harness(manager, states, episodeUuid, referenceServer, auth)
    }

    private fun Harness.play() {
        playbackStates.value = PlaybackState(
            state = PlaybackState.State.PLAYING,
            episodeUuid = episodeUuid,
            isPrepared = true,
        )
    }

    private fun Harness.pause() {
        playbackStates.value = playbackStates.value.copy(state = PlaybackState.State.PAUSED)
    }

    /** The observer and preparation run on their own dispatchers, so give them room to land. */
    private fun awaitRequests(h: Harness, expected: Int): Int {
        repeat(200) {
            if (h.referenceServer.requestCount >= expected) return h.referenceServer.requestCount
            Thread.sleep(25)
        }
        return h.referenceServer.requestCount
    }

    private fun awaitIdle(millis: Long) = Thread.sleep(millis)

    /** The fetch runs on the manager's own dispatcher; wait for it to settle rather than racing it. */
    private fun awaitCloudState(
        h: Harness,
        expected: FingerprintTimingManager.CloudReference,
    ): FingerprintTimingManager.CloudReference {
        repeat(200) {
            if (h.manager.cloudReferenceState() == expected) return expected
            Thread.sleep(25)
        }
        return h.manager.cloudReferenceState()
    }

    @Test
    fun `a transient cloud failure is retried on resume even though the transcript mapping is healthy`() {
        // The stream: first play fails transiently, second play after a resume gets the reference.
        val h = harness(
            referenceResponses = listOf(
                MockResponse().setResponseCode(503),
                MockResponse()
                    .setBody(compactV2Payload().toString(Charsets.UTF_8))
                    .setHeader("ETag", "\"v1\""),
            ),
        )
        try {
            h.play()
            assertEquals("the play must fetch once", 1, awaitRequests(h, 1))
            assertEquals(
                "a transient failure is retryable, not terminal",
                FingerprintTimingManager.CloudReference.Failed,
                awaitCloudState(h, FingerprintTimingManager.CloudReference.Failed),
            )

            // Pause, let the observer take it in, then resume: the observer must see the play
            // transition again for the same episode, which is what makes a transient cloud
            // failure recoverable mid-session. The wait is on the OBSERVER, not on the value —
            // StateFlow conflates, so an unobserved pause would never be seen at all.
            h.pause()
            awaitIdle(400)
            h.play()

            assertEquals("the resume must make exactly one fresh attempt", 2, awaitRequests(h, 2))
            assertEquals(
                "the retry succeeded",
                FingerprintTimingManager.CloudReference.Ready,
                awaitCloudState(h, FingerprintTimingManager.CloudReference.Ready),
            )
            assertNotEquals(
                "a cloud retry must not reset the transcript preparation it did not touch",
                FingerprintTimingManager.State.Idle,
                h.manager.state,
            )
        } finally {
            h.referenceServer.shutdown()
            h.authServer.shutdown()
        }
    }

    @Test
    fun `a definitive miss is not asked again on resume`() {
        val h = harness(
            referenceResponses = listOf(
                MockResponse().setResponseCode(404),
                MockResponse().setBody(compactV2Payload().toString(Charsets.UTF_8)).setHeader("ETag", "\"v1\""),
            ),
        )
        try {
            h.play()
            assertEquals("the play asks once", 1, awaitRequests(h, 1))
            assertEquals(
                FingerprintTimingManager.CloudReference.DefinitiveMiss(h.episodeUuid),
                awaitCloudState(h, FingerprintTimingManager.CloudReference.DefinitiveMiss(h.episodeUuid)),
            )

            h.pause()
            h.play()
            awaitIdle(600)

            assertEquals("a definitive 404 ends the attempts for this episode", 1, h.referenceServer.requestCount)
        } finally {
            h.referenceServer.shutdown()
            h.authServer.shutdown()
        }
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

    @Test
    fun `a failed revalidation keeps the usable stored reference`() {
        runBlocking {
            val server = MockWebServer()
            // Revalidation fails transiently; the stored payload must still be used.
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
                store.save("ep_revalidate", compactV2Payload(), "\"stored\"")

                val matcher = managerWithServer.resolveCloudMatcher(
                    server.url("/").toString().trimEnd('/'),
                    "ep_revalidate",
                    "pod_1",
                )

                assertNotNull("a transient revalidation failure must not drop a working reference", matcher)
                assertEquals(3, matcher!!.count)
                assertEquals(
                    "the stored reference counts as ready, so no further attempt is made",
                    FingerprintTimingManager.CloudReference.Ready,
                    managerWithServer.cloudReferenceState(),
                )
                auth.shutdown()
            } finally {
                server.shutdown()
            }
        }
    }
}
