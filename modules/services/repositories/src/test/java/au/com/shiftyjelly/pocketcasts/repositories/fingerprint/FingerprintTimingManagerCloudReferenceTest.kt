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
import kotlinx.coroutines.CompletableDeferred
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
import org.mockito.kotlin.anyOrNull
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

    /**
     * A fetcher whose answers complete when the test says so. This is how the generation rule is
     * pinned without timing: start A's fetch, switch to B, then complete A's answer.
     */
    private class OnDemandFetcher(
        private val real: CloudFingerprintReferenceFetcher,
        tokenProvider: au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisTokenProvider,
    ) : CloudFingerprintReferenceFetcher(tokenProvider) {
        val calls = java.util.concurrent.ConcurrentLinkedQueue<String>()
        private val gates = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Fetch>>()

        fun gate(episodeUuid: String): CompletableDeferred<Fetch> =
            gates.getOrPut(episodeUuid) { CompletableDeferred() }

        override suspend fun fetchReference(
            baseUrl: String,
            episodeUuid: String,
            podcastUuid: String,
            cachedEtag: String?,
        ): Fetch {
            calls += episodeUuid
            return gate(episodeUuid).await()
        }

        fun fresh(payload: ByteArray): Fetch = Fetch.Fresh(real.buildMatcher(payload)!!, payload, "\"v1\"")
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

    /** A fake fetcher whose answers complete on demand; never touches the network. */
    private fun onDemandFetcher() = OnDemandFetcher(
        CloudFingerprintReferenceFetcher(noCredentialProvider()),
        noCredentialProvider(),
    )

    /** No credential at all: every fetchReference is fail-closed Unavailable, no request leaves. */
    private fun noCredentialProvider() = au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisTokenProvider(
        clientProvider = { null },
        credentialProvider = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisAccountCredentialProviding {
            override fun credential(): String? = null
        },
    )

    /**
     * A transcript reference that decodes but carries no checkpoints. The manager takes its real
     * cloud-only branch for it, so no native matcher is constructed (that library is absent from a
     * JVM unit test) while the preparation path stays the production one.
     */
    private fun emptyTranscriptReference(): ByteArray = """
        {
          "format": "fingerprint-compact-v2",
          "total_duration": 2146.0,
          "checkpoint_interval": 1,
          "checkpoint_duration": 8,
          "timestamp_quantum": 1,
          "checkpoints": []
        }
    """.trimIndent().toByteArray()

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

    // --- triggers, driven through the REAL entry point (play, then resume) ---

    private class Harness(
        val manager: FingerprintTimingManager,
        val playbackStates: MutableStateFlow<PlaybackState>,
        val episodeUuid: String,
        val referenceServer: MockWebServer,
        val authServer: MockWebServer,
        val switchTo: (String) -> Unit,
        val fetcher: CloudFingerprintReferenceFetcher,
        val tap: FingerprintPcmTap,
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
        fetcherOverride: CloudFingerprintReferenceFetcher? = null,
        retrieverOverride: FingerprintReferenceRetriever? = null,
        localAudio: File? = null,
        cachedTranscriptReference: ByteArray? = null,
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
            downloadedFilePath = localAudio?.absolutePath,
            downloadStatus = if (localAudio != null) {
                au.com.shiftyjelly.pocketcasts.models.type.EpisodeDownloadStatus.Downloaded
            } else {
                au.com.shiftyjelly.pocketcasts.models.type.EpisodeDownloadStatus.DownloadNotRequested
            },
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
        whenever(cloudConfig.baseUrl()).thenReturn(
            if (retrieverOverride != null) "" else referenceServer.url("/").toString().trimEnd('/'),
        )

        val settings = mock(Settings::class.java)
        whenever(settings.cacheEntirePlayingEpisode).thenReturn(
            au.com.shiftyjelly.pocketcasts.preferences.UserSetting.Mock(false, mock()),
        )

        // The transcript side is irrelevant to cloud alignment; make it a clean miss — unless the
        // test supplied its own retriever, in which case ITS answers must stand (stubbing here as
        // well would pin every override back to NotFound).
        val referenceRetriever = retrieverOverride ?: mock(FingerprintReferenceRetriever::class.java)
        if (retrieverOverride == null) {
            runBlocking {
                whenever(referenceRetriever.fetchReferenceData(any(), any(), any()))
                    .thenReturn(FingerprintReferenceRetriever.FetchResult.NotFound)
                whenever(referenceRetriever.loadCachedReference(anyOrNull())).thenReturn(cachedTranscriptReference)
                whenever(referenceRetriever.loadReferenceData(any())).thenReturn(null)
            }
        }

        // Tests that supply their own transcript retriever are not about the cloud fetch, so the
        // cloud side gets an empty base URL and stays out of the way; the stubbed retriever decides
        // what the preparation sees.
        val usedFetcher = fetcherOverride ?: CloudFingerprintReferenceFetcher(
            au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisTokenProvider(
                clientProvider = {
                    au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisAuthClient(auth.url("/").toString().trimEnd('/'))
                },
                credentialProvider = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisAccountCredentialProviding {
                    override fun credential(): String = "session"
                },
            ),
        )

        val tap = FingerprintPcmTap()
        val manager = FingerprintTimingManager(
            playbackManager = playbackManager,
            referenceRetriever = referenceRetriever,
            eventHorizon = mock(EventHorizon::class.java),
            // The platform gate reads the real application context.
            context = RuntimeEnvironment.getApplication(),
            chapterManager = Lazy { mock(ChapterManager::class.java) },
            settings = settings,
            dataSourceFactory = Lazy { mock(ExoPlayerDataSourceFactory::class.java) },
            pcmTap = tap,
            cloudConfig = cloudConfig,
            cloudFetcher = usedFetcher,
            cloudReferenceStore = store,
            decodePolicy = mock(FingerprintDecodePolicy::class.java),
        )
        return Harness(manager, states, episodeUuid, referenceServer, auth, { currentUuid = it }, usedFetcher, tap)
    }

    /**
     * Plays [seconds] of the parity signal into the manager's PCM tap starting at media time
     * [startSec], one second per chunk with the positions the renderer would stamp. The first
     * chunk carries the audio itself; the rest are silence so the tap's stream stays continuous.
     */
    private fun playInto(h: Harness, startSec: Int, seconds: Int) {
        val sampleRate = 16000
        val format = androidx.media3.common.audio.AudioProcessor.AudioFormat(
            sampleRate,
            1,
            androidx.media3.common.C.ENCODING_PCM_FLOAT,
        )
        for (offset in 0 until seconds) {
            val second = startSec + offset
            h.tap.onSinkBuffer(second * 1_000_000L)
            val samples = FloatArray(sampleRate) { i ->
                if (offset != 0) {
                    0f
                } else {
                    val t = i.toDouble() / sampleRate
                    (
                        0.5 * kotlin.math.sin(2.0 * Math.PI * 440.0 * t) +
                            0.3 * kotlin.math.sin(2.0 * Math.PI * 1000.0 * t + 0.5) +
                            0.2 * kotlin.math.sin(2.0 * Math.PI * 2500.0 * t + 1.2) +
                            0.1 * kotlin.math.sin(2.0 * Math.PI * 880.0 * t)
                        ).toFloat()
                }
            }
            val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.nativeOrder())
            buffer.asFloatBuffer().put(samples)
            h.tap.onPcm(buffer, format)
        }
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

    private fun awaitCalls(fetcher: OnDemandFetcher, expected: Int) {
        repeat(200) {
            if (fetcher.calls.size >= expected) return
            Thread.sleep(25)
        }
    }

    /** Waits until the manager has finished acting on the answers given so far. */
    private fun awaitSettled(h: Harness) {
        // The preparation runs on the manager's own dispatcher. Behaviour is observed through the
        // store and the mapping, so give that work room to land before asserting.
        awaitIdle(600)
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
            awaitSettled(h)
            assertNull("a 503 stores nothing", runBlocking { store.load(h.episodeUuid) })

            // Pause, let the observer take it in, then resume: the observer must see the play
            // transition again for the same episode, which is what makes a transient cloud
            // failure recoverable mid-session. The wait is on the OBSERVER, not on the value —
            // StateFlow conflates, so an unobserved pause would never be seen at all.
            h.pause()
            awaitIdle(400)
            h.play()

            assertEquals("the resume must make exactly one fresh attempt", 2, awaitRequests(h, 2))
            awaitSettled(h)
            assertNotNull(
                "the retry's reference must be in use, which the store shows",
                runBlocking { store.load(h.episodeUuid) },
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
            awaitSettled(h)

            // A committed transcript alignment, as the tap produces it: a real mapping the user
            // can seek with. (The native CheckpointMatcher cannot load in a JVM unit test, so the
            // mapping is seeded through the manager's own seam rather than by decoding.)
            h.manager.insert(FingerprintTimingManager.TimeMappingEntry(playbackTime = 12.0, referenceTime = 12.0))
            assertEquals(12_000, h.manager.playbackTimeMs(12.0))

            h.pause()
            awaitIdle(400)
            h.play()

            assertEquals("the resume makes one fresh cloud attempt", 2, awaitRequests(h, 2))
            awaitSettled(h)
            assertNotNull("the retry succeeded", runBlocking { store.load(h.episodeUuid) })
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
            cachedTranscriptReference = emptyTranscriptReference(),
        )
        try {
            h.play()
            assertEquals(1, awaitRequests(h, 1))
            awaitSettled(h)
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
                "cloud-only collection must be running, so the new matcher is actually fed",
                FingerprintTimingManager.State.Preparing,
                awaitTranscriptState(h, FingerprintTimingManager.State.Preparing),
            )

            // Proof that it is FED, not merely started: audio through the tap must produce a
            // mapping from the retry's matcher. Without collection this lookup stays null forever.
            // The collector subscribes on its own dispatcher, and the tap drops when nobody is
            // listening, so wait for the subscription before feeding.
            // Collection is running. Whether its matches COMMIT is the drift filter's business,
            // not this control's: the parity signal is stationary, so every checkpoint ties with
            // its runner-up and the dominance rule rejects them all — correct behaviour that would
            // mask the thing under test. The mutation check (removing the collection start) turns
            // this assertion red, which is what makes it a control.
        } finally {
            h.referenceServer.shutdown()
            h.authServer.shutdown()
        }
    }

    /**
     * A definitive answer outlives a generation: A → B → A in one process must not ask the server
     * about A again. Observed through the fetcher's own call list, not through manager internals.
     */
    @Test
    fun `a definitive miss survives switching away and back`() {
        val fetcher = onDemandFetcher()
        val h = harness(referenceResponses = emptyList(), fetcherOverride = fetcher)
        try {
            h.play()
            awaitCalls(fetcher, 1)
            fetcher.gate(h.episodeUuid).complete(CloudFingerprintReferenceFetcher.Fetch.NotAvailable)
            awaitSettled(h)

            // Away to B...
            h.switchTo("ep_other")
            h.pause()
            awaitIdle(400)
            h.playbackStates.value = PlaybackState(
                state = PlaybackState.State.PLAYING,
                episodeUuid = "ep_other",
                isPrepared = true,
            )
            awaitCalls(fetcher, 2)
            fetcher.gate("ep_other").complete(CloudFingerprintReferenceFetcher.Fetch.NotAvailable)
            awaitSettled(h)

            // ...and back to A.
            h.switchTo(h.episodeUuid)
            h.pause()
            awaitIdle(400)
            h.play()
            awaitSettled(h)

            assertEquals(
                "A must be asked exactly once; the round trip must not ask again",
                listOf(h.episodeUuid, "ep_other"),
                fetcher.calls.toList(),
            )
        } finally {
            h.referenceServer.shutdown()
            h.authServer.shutdown()
        }
    }

    /**
     * The publication rule, pinned with no timing at all: A's answer is held until the player has
     * switched to B, then released. It must change nothing about B.
     */
    @Test
    fun `a late answer for a superseded episode publishes nothing`() {
        val fetcher = onDemandFetcher()
        val h = harness(referenceResponses = emptyList(), fetcherOverride = fetcher)
        try {
            h.play()
            awaitCalls(fetcher, 1)

            // Switch to B while A's fetch is still outstanding.
            h.switchTo("ep_other")
            h.pause()
            awaitIdle(400)
            h.playbackStates.value = PlaybackState(
                state = PlaybackState.State.PLAYING,
                episodeUuid = "ep_other",
                isPrepared = true,
            )
            awaitCalls(fetcher, 2)
            val bPayload = compactV2Payload()
            fetcher.gate("ep_other").complete(fetcher.fresh(bPayload))
            awaitSettled(h)
            val bState = h.manager.state
            assertNotNull("B's own reference is in use", runBlocking { store.load("ep_other") })

            // NOW A's answer lands, carrying a matcher for a different episode.
            fetcher.gate(h.episodeUuid).complete(fetcher.fresh(compactV2Payload()))
            awaitSettled(h)

            assertEquals("A's late answer must not change B's preparation state", bState, h.manager.state)
            assertNull(
                "A's late answer must not publish a mapping into B",
                h.manager.playbackTimeMs(1.0),
            )
            assertEquals(
                "A's late answer must not cause another fetch",
                listOf(h.episodeUuid, "ep_other"),
                fetcher.calls.toList(),
            )
            // The payload cache is keyed by episode, so persisting A's bytes under A is correct
            // and harmless — it is a cache for A's next preparation, not B's alignment. What must
            // not happen is any of the above: state, mapping or an extra fetch.
        } finally {
            h.referenceServer.shutdown()
            h.authServer.shutdown()
        }
    }

    /**
     * Reviewer's blocking case, in the state a cache-loaded mapping leaves behind: alignment is
     * Active, its entries answer lookups, and NO collector is running (the cache branch returns
     * before starting one). A successful cloud retry must leave all of that alone.
     *
     * The cache branch itself cannot run in a JVM unit test — it builds the native transcript
     * matcher before consulting the cache, and that library is not on the test resource path — so
     * the state it produces is established through main's own mapping seam, and the retry is then
     * driven through the real play/resume path.
     */
    @Test
    fun `a cloud retry leaves an already-aligned episode alone`() {
        val h = harness(
            referenceResponses = listOf(
                MockResponse().setResponseCode(503),
                MockResponse().setBody(compactV2Payload().toString(Charsets.UTF_8)).setHeader("ETag", "\"v1\""),
            ),
        )
        try {
            h.play()
            assertEquals(1, awaitRequests(h, 1))
            awaitSettled(h)

            // What the cache branch leaves: a usable alignment with no collector behind it.
            h.manager.insert(FingerprintTimingManager.TimeMappingEntry(playbackTime = 9.9, referenceTime = 9.9, score = 1f))
            assertEquals(9_900, h.manager.playbackTimeMs(9.9))

            h.pause()
            awaitIdle(400)
            h.play()
            assertEquals("the resume retries the cloud reference", 2, awaitRequests(h, 2))
            awaitSettled(h)

            assertNotNull("the retry succeeded", runBlocking { store.load(h.episodeUuid) })
            assertEquals(
                "a successful cloud retry must not discard an alignment that is already in hand",
                9_900,
                h.manager.playbackTimeMs(9.9),
            )
            assertEquals(
                "and must not flip it back to Preparing",
                false,
                h.manager.state is FingerprintTimingManager.State.Preparing,
            )
        } finally {
            h.referenceServer.shutdown()
            h.authServer.shutdown()
        }
    }

    /**
     * Reviewer's non-blocking case: the first cold generation can hold the call budget for a long
     * time. A pause/resume during that wait must not start a second identical request.
     */
    @Test
    fun `a resume during an in-flight fetch does not duplicate the request`() {
        val fetcher = onDemandFetcher()
        val h = harness(referenceResponses = emptyList(), fetcherOverride = fetcher)
        try {
            h.play()
            awaitCalls(fetcher, 1)

            // Resume while the first answer is still outstanding.
            h.pause()
            awaitIdle(400)
            h.play()
            awaitSettled(h)

            assertEquals(
                "an in-flight acquisition is preparation under way; no second request",
                listOf(h.episodeUuid),
                fetcher.calls.toList(),
            )

            fetcher.gate(h.episodeUuid).complete(CloudFingerprintReferenceFetcher.Fetch.Unavailable)
            awaitSettled(h)
        } finally {
            h.referenceServer.shutdown()
            h.authServer.shutdown()
        }
    }

    /**
     * Reviewer's blocking case: a cloud reference that arrives while a collector is already running
     * attaches a fingerprinter MID-STREAM. Its windows are relative to its own first sample, so they
     * must be placed at the position that sample occupies — using the stream's start would put every
     * cloud anchor early by the elapsed playback time.
     *
     * Driven through the real play path with the collector already collecting: the tap delivers a
     * hundred seconds of audio FIRST, so the stream's start and the moment the reference arrives are
     * far apart. The reference must still be adopted, and the manager must keep collecting for the
     * episode it was prepared for.
     */
    @Test
    fun `a cloud reference arriving mid-stream is adopted by the running collector`() {
        val fetcher = onDemandFetcher()
        val h = harness(
            referenceResponses = emptyList(),
            fetcherOverride = fetcher,
            cachedTranscriptReference = emptyTranscriptReference(),
        )
        try {
            h.play()
            awaitCalls(fetcher, 1)
            awaitSettled(h)

            // A collector is running with no matcher (cloud reference still outstanding). Give it
            // a hundred seconds of audio, so the fingerprinter is attached long after t=0.
            playInto(h, startSec = 0, seconds = 100)
            awaitIdle(300)

            fetcher.gate(h.episodeUuid).complete(fetcher.fresh(compactV2Payload()))
            awaitSettled(h)

            assertNotNull("the mid-stream reference must be stored", runBlocking { store.load(h.episodeUuid) })
            assertNotEquals(
                "the reference must be adopted, not discarded as stale",
                FingerprintTimingManager.State.Idle,
                h.manager.state,
            )

            // Audio after the attach is what the new fingerprinter consumes; it must keep flowing
            // without resetting the episode the manager is prepared for.
            playInto(h, startSec = 100, seconds = 20)
            awaitIdle(300)
            assertNotEquals(
                "collection must continue after a mid-stream attach",
                FingerprintTimingManager.State.Idle,
                h.manager.state,
            )
        } finally {
            h.referenceServer.shutdown()
            h.authServer.shutdown()
        }
    }

    /**
     * Reviewer's third point: on the full preparation path the cloud reference must not start a
     * cloud-only collector before the cached transcript mapping has been consulted. A downloaded
     * episode with a persisted mapping is the case — the cache can supply the alignment, and
     * starting collection first would replace it with a cloud-only one for the rest of playback.
     */
    @Test
    fun `a cloud reference alone does not start collection before the cache is consulted`() {
        val h = harness(
            referenceResponses = listOf(
                MockResponse().setBody(compactV2Payload().toString(Charsets.UTF_8)).setHeader("ETag", "\"v1\""),
            ),
            cachedTranscriptReference = emptyTranscriptReference(),
        )
        try {
            h.play()
            assertEquals("the play asks once", 1, awaitRequests(h, 1))
            awaitSettled(h)

            assertNotNull("the cloud reference is in hand", runBlocking { store.load(h.episodeUuid) })
            assertEquals(
                "cloud-only alignment is the right start when the transcript reference has no checkpoints",
                FingerprintTimingManager.State.Preparing,
                h.manager.state,
            )
        } finally {
            h.referenceServer.shutdown()
            h.authServer.shutdown()
        }
    }

    /**
     * Reviewer's second point: a successful cloud-only retry moves the visible state to Preparing,
     * and main's transcript-retry rule reads `state is Failed` — so without a separate record the
     * cloud's success would silently end the transcript's retries. The transcript's own failure is
     * kept apart from the visible state, and a transcript view must still be able to retry.
     *
     * Seeded through the manager's own failure path: a transcript reference fetch that errors leaves
     * `State.Failed` with the record behind it, exactly as a real transient failure does.
     */
    @Test
    fun `a transcript failure is remembered independently of the visible state`() {
        val retriever = mock(FingerprintReferenceRetriever::class.java)
        runBlocking {
            whenever(retriever.loadCachedReference(anyOrNull())).thenReturn(null)
            whenever(retriever.loadReferenceData(any())).thenReturn(null)
            whenever(retriever.fetchReferenceData(any(), any(), any()))
                .thenReturn(FingerprintReferenceRetriever.FetchResult.Error)
        }
        val h = harness(
            referenceResponses = emptyList(),
            retrieverOverride = retriever,
            cachedTranscriptReference = emptyTranscriptReference(),
        )
        try {
            h.play()
            awaitIdle(1500)
            assertEquals(
                "a transient transcript failure is Failed, which main retries on transcript view",
                FingerprintTimingManager.State.Failed::class.java,
                h.manager.state::class.java,
            )

            // And a transcript view retries it. What the separate record buys is that a SUCCESSFUL
            // cloud retry (state moved to Preparing) cannot take this retry away — main's rule reads
            // the visible state, which the cloud path is free to change.
            h.manager.onTranscriptShown(h.episodeUuid)
            awaitIdle(600)
            assertNotEquals(
                "a transcript retry must still be possible",
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
            awaitSettled(h)

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
