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

    /** One acquisition, driven directly: the object owns claim + I/O + outcome, publishing nothing. */
    private suspend fun acquireOnce(
        baseUrl: String,
        episodeUuid: String,
        fetcher: CloudFingerprintReferenceFetcher = CloudFingerprintReferenceFetcher(noCredentialProvider()),
    ): CloudReferenceAcquisition.Outcome = CloudReferenceAcquisition(
        generation = 1L,
        episodeUuid = episodeUuid,
        podcastUuid = "pod_1",
        baseUrl = baseUrl,
        fetcher = fetcher,
        store = store,
    ).also { it.claim() }.acquire()

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
            val outcome = acquireOnce("https://offline.invalid", "ep_keep")

            assertTrue("a failed revalidation must keep the stored reference", outcome is CloudReferenceAcquisition.Outcome.Ready)
            assertEquals(3, (outcome as CloudReferenceAcquisition.Outcome.Ready).matcher.count)
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

                val outcome = acquireOnce(server.url("/").toString().trimEnd('/'), "ep_none", fetcher)

                assertEquals(CloudReferenceAcquisition.Outcome.Retryable, outcome)
                assertEquals("one attempt per preparation, never a second request inside it", 1, server.requestCount)
                auth.shutdown()
            } finally {
                server.shutdown()
            }
        }
    }

    /**
     * The cloud reference has its own attempt state. Its retry rule must be independent of
     * transcript readiness — that independence is the whole point: a transient cloud failure used
     * to vanish behind a working transcript mapping.
     */
    @Test
    fun `a transient cloud failure is retryable and a definitive miss is not`() {
        fun retry(
            status: CloudReferenceAcquisition.Status?,
            definitiveMiss: Boolean = false,
            configured: Boolean = true,
        ) = FingerprintTimingManager.shouldRetryCloudReference(
            status = status,
            definitiveMiss = definitiveMiss,
            cloudConfigured = configured,
        )

        assertTrue("never tried: fetch", retry(null))
        assertTrue("not tried yet: fetch", retry(CloudReferenceAcquisition.Status.NotTried))
        assertTrue("a transient failure retries on the next play", retry(CloudReferenceAcquisition.Status.Failed))
        assertFalse("an in-flight fetch is never duplicated", retry(CloudReferenceAcquisition.Status.Fetching))
        assertFalse("a ready reference is not refetched", retry(CloudReferenceAcquisition.Status.Ready))
        assertFalse(
            "a definitive 403/404 ends the attempts for this process",
            retry(CloudReferenceAcquisition.Status.Failed, definitiveMiss = true),
        )
        assertFalse("cutover off means no cloud attempt", retry(CloudReferenceAcquisition.Status.Failed, configured = false))
    }

    @Test
    fun `a fresh manager has no cloud attempt and no definitive miss`() {
        assertNull(manager.cloudReferenceStatus())
        assertFalse(manager.isDefinitiveCloudMiss("ep_1"))
    }

    // --- triggers, driven through the REAL entry point (play, then resume) ---

    private class Harness(
        val manager: FingerprintTimingManager,
        val playbackStates: MutableStateFlow<PlaybackState>,
        val episodeUuid: String,
        val referenceServer: MockWebServer,
        val authServer: MockWebServer,
        val switchTo: (String) -> Unit,
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
        onEpisode: (String) -> Unit = {},
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
        var currentUuid = episodeUuid
        val episode = PodcastEpisode(
            uuid = episodeUuid,
            podcastUuid = "pod_1",
            publishedDate = Date(),
            duration = 2146.0,
            downloadUrl = "https://example.invalid/audio.mp3",
        )
        val playbackManager = mock(PlaybackManager::class.java)
        whenever(playbackManager.playbackStateFlow).thenReturn(states)
        whenever(playbackManager.getCurrentEpisode()).thenAnswer {
            onEpisode(currentUuid)
            episode.copy(uuid = currentUuid)
        }
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
        return Harness(manager, states, episodeUuid, referenceServer, auth) { currentUuid = it }
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

    private fun awaitTranscriptState(
        h: Harness,
        expected: FingerprintTimingManager.State,
    ): FingerprintTimingManager.State {
        repeat(200) {
            if (h.manager.state == expected) return expected
            Thread.sleep(25)
        }
        return h.manager.state
    }

    /** The fetch runs on the manager's own dispatcher; wait for it to settle rather than racing it. */
    private fun awaitCloudState(
        h: Harness,
        expected: CloudReferenceAcquisition.Status,
    ): CloudReferenceAcquisition.Status? {
        repeat(200) {
            if (h.manager.cloudReferenceStatus() == expected) return expected
            Thread.sleep(25)
        }
        return h.manager.cloudReferenceStatus()
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
                CloudReferenceAcquisition.Status.Failed,
                awaitCloudState(h, CloudReferenceAcquisition.Status.Failed),
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
                CloudReferenceAcquisition.Status.Ready,
                awaitCloudState(h, CloudReferenceAcquisition.Status.Ready),
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

    /**
     * The control reviewer asked for: a WORKING transcript mapping must survive a cloud retry.
     * `state != Idle` was too weak — Unavailable passes it — so this asserts the real mapping.
     */
    @Test
    fun `a cloud retry keeps a working transcript mapping intact`() {
        val h = harness(
            referenceResponses = listOf(
                MockResponse().setResponseCode(503),
                MockResponse().setBody(compactV2Payload().toString(Charsets.UTF_8)).setHeader("ETag", "\"v1\""),
            ),
        )
        try {
            h.play()
            assertEquals(1, awaitRequests(h, 1))
            awaitCloudState(h, CloudReferenceAcquisition.Status.Failed)

            // A committed transcript alignment, as the tap produces it: a real mapping the user
            // can seek with. (The native CheckpointMatcher cannot load in a JVM unit test, so the
            // mapping is seeded through the manager's own seam rather than by decoding.)
            h.manager.insert(FingerprintTimingManager.TimeMappingEntry(playbackTime = 12.0, referenceTime = 12.0))
            assertEquals(12_000, h.manager.playbackTimeMs(12.0))

            h.pause()
            awaitIdle(400)
            h.play()

            assertEquals("the resume makes one fresh cloud attempt", 2, awaitRequests(h, 2))
            assertEquals(
                CloudReferenceAcquisition.Status.Ready,
                awaitCloudState(h, CloudReferenceAcquisition.Status.Ready),
            )
            assertEquals(
                "the working transcript mapping must survive the cloud retry",
                12_000,
                h.manager.playbackTimeMs(12.0),
            )
        } finally {
            h.referenceServer.shutdown()
            h.authServer.shutdown()
        }
    }

    /**
     * Cloud-only recovery: the transcript reference is missing, so nothing was collecting PCM. A
     * successful retry must start collection, or the matcher it installs is read by nobody.
     */
    @Test
    fun `a successful retry starts cloud-only collection when nothing was collecting`() {
        val h = harness(
            referenceResponses = listOf(
                MockResponse().setResponseCode(503),
                MockResponse().setBody(compactV2Payload().toString(Charsets.UTF_8)).setHeader("ETag", "\"v1\""),
            ),
        )
        try {
            h.play()
            assertEquals(1, awaitRequests(h, 1))
            awaitCloudState(h, CloudReferenceAcquisition.Status.Failed)
            assertEquals(
                "with no transcript reference and no cloud matcher there is nothing to align",
                FingerprintTimingManager.State.Unavailable(h.episodeUuid),
                h.manager.state,
            )

            h.pause()
            awaitIdle(400)
            h.play()

            assertEquals(2, awaitRequests(h, 2))
            assertEquals(
                CloudReferenceAcquisition.Status.Ready,
                awaitCloudState(h, CloudReferenceAcquisition.Status.Ready),
            )
            assertEquals(
                "cloud-only collection must be running, so the new matcher is actually fed",
                FingerprintTimingManager.State.Preparing,
                awaitTranscriptState(h, FingerprintTimingManager.State.Preparing),
            )
        } finally {
            h.referenceServer.shutdown()
            h.authServer.shutdown()
        }
    }

    /**
     * A definitive answer outlives a generation: A → B → A in one process must not ask the server
     * about A again. The record is per episode, not per preparation.
     */
    @Test
    fun `a definitive miss survives switching away and back`() {
        val h = harness(
            referenceResponses = listOf(
                MockResponse().setResponseCode(404),
                MockResponse().setResponseCode(404),
                MockResponse().setBody(compactV2Payload().toString(Charsets.UTF_8)).setHeader("ETag", "\"v1\""),
            ),
        )
        try {
            h.play()
            awaitRequests(h, 1)
            awaitCloudState(h, CloudReferenceAcquisition.Status.Failed)
            assertTrue(h.manager.isDefinitiveCloudMiss(h.episodeUuid))

            // Away to B...
            h.switchTo("ep_other")
            h.pause()
            awaitIdle(400)
            h.playbackStates.value = PlaybackState(
                state = PlaybackState.State.PLAYING,
                episodeUuid = "ep_other",
                isPrepared = true,
            )
            awaitIdle(800)

            // ...and back to A.
            h.switchTo(h.episodeUuid)
            h.pause()
            awaitIdle(400)
            h.play()
            awaitIdle(1200)

            assertTrue(
                "the definitive answer for A must survive the round trip",
                h.manager.isDefinitiveCloudMiss(h.episodeUuid),
            )
            assertEquals(
                "A must not be asked again after B; only B's own attempt may have gone out",
                2,
                h.referenceServer.requestCount,
            )
        } finally {
            h.referenceServer.shutdown()
            h.authServer.shutdown()
        }
    }

    /**
     * The publication rule, pinned deterministically: an outcome may only be applied while its
     * generation is still the current one. A fetch started for A that lands after a switch to B
     * must change nothing — no matcher, no state, no per-episode record.
     */
    @Test
    fun `an outcome from a superseded generation publishes nothing`() {
        val h = harness(referenceResponses = listOf(MockResponse().setResponseCode(503)))
        try {
            h.play()
            awaitRequests(h, 1)
            awaitCloudState(h, CloudReferenceAcquisition.Status.Failed)

            // Move to another episode: this bumps the generation and discards A's attempt.
            h.switchTo("ep_other")
            h.pause()
            awaitIdle(400)
            h.playbackStates.value = PlaybackState(
                state = PlaybackState.State.PLAYING,
                episodeUuid = "ep_other",
                isPrepared = true,
            )
            awaitIdle(800)

            val before = h.manager.state
            runBlocking {
                val staleGen = h.manager.currentGenerationForTest() - 1
                // A's late answer arrives now, carrying the generation it was started under.
                h.manager.applyCloudOutcomeForTest(
                    staleGen,
                    h.episodeUuid,
                    CloudReferenceAcquisition.Outcome.DefinitiveMiss,
                )
            }

            assertFalse(
                "a superseded generation must not record a definitive answer",
                h.manager.isDefinitiveCloudMiss(h.episodeUuid),
            )
            assertEquals("a superseded generation must not change preparation state", before, h.manager.state)
            assertNull(
                "no mapping may be published from a superseded episode's reference",
                h.manager.playbackTimeMs(1.0),
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
            awaitCloudState(h, CloudReferenceAcquisition.Status.Failed)
            assertTrue("a 404 is recorded per episode for this process", h.manager.isDefinitiveCloudMiss(h.episodeUuid))

            h.pause()
            awaitIdle(400)
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

                val outcome = acquireOnce(server.url("/").toString().trimEnd('/'), "ep_retry", fetcher)

                assertTrue(outcome is CloudReferenceAcquisition.Outcome.Ready)
                assertEquals(3, (outcome as CloudReferenceAcquisition.Outcome.Ready).matcher.count)
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

                val outcome = acquireOnce(server.url("/").toString().trimEnd('/'), "ep_revalidate", fetcher)

                assertTrue(
                    "a transient revalidation failure must not drop a working reference",
                    outcome is CloudReferenceAcquisition.Outcome.Ready,
                )
                assertEquals(3, (outcome as CloudReferenceAcquisition.Outcome.Ready).matcher.count)
                auth.shutdown()
            } finally {
                server.shutdown()
            }
        }
    }
}
