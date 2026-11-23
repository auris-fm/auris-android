package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.models.type.EpisodeDownloadStatus
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.preferences.UserSetting
import au.com.shiftyjelly.pocketcasts.repositories.playback.ExoPlayerDataSourceFactory
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackState
import au.com.shiftyjelly.pocketcasts.repositories.podcast.ChapterManager
import au.com.shiftyjelly.pocketcasts.utils.fingerprint.FingerprintDecodePolicy
import au.com.shiftyjelly.pocketcasts.utils.fingerprint.FingerprintPolicy
import com.automattic.eventhorizon.EventHorizon
import dagger.Lazy
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import uniffi.fingerprint_uniffi.CheckpointMatcher
import uniffi.fingerprint_uniffi.StreamingWindowedFingerprinter

/**
 * A reference fetch suspends. If the user switches episodes while it is in flight, the preparation
 * that was superseded must not install its reference into the manager: the new episode's mapping
 * would be recorded against another episode's Auris identity, and its windows scored against the
 * wrong reference.
 *
 * The interleaving is forced, not raced: the first episode's fetch blocks on a latch until the
 * second preparation has installed its own reference, then returns. No sleeps decide the outcome.
 */
@Config(manifest = Config.NONE)
@RunWith(RobolectricTestRunner::class)
class FingerprintCloudIdentityRaceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var transcriptHost: MockWebServer

    /** Released once the second episode's reference is installed. */
    private val secondInstalled = CountDownLatch(1)

    /** Which episode's fetch is allowed to proceed immediately. */
    private val slowEpisode = "ep_slow"

    @After
    fun tearDown() {
        scope.cancel()
        runCatching { transcriptHost.shutdown() }
    }

    @Test
    fun `a superseded preparation does not install its reference over the current episode's`() {
        val audioSlow = temp.newFile("slow.mp3").apply { writeBytes(ByteArray(4096)) }
        val audioFast = temp.newFile("fast.mp3").apply { writeBytes(ByteArray(4096)) }
        transcriptHost = MockWebServer().also { it.start() }
        transcriptHost.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody(
                    okio.Buffer().write(FingerprintDecodeHarness.payload(ReferenceFingerprint.TRANSCRIPT_FORMAT)),
                )
        }

        val currentEpisode = java.util.concurrent.atomic.AtomicReference("ep_slow")

        val episodeA = episode("ep_slow", audioSlow)
        val episodeB = episode("ep_fast", audioFast)
        val playbackManager = mock(PlaybackManager::class.java)
        whenever(playbackManager.playbackStateFlow).thenReturn(MutableStateFlow(PlaybackState()))
        whenever(playbackManager.getCurrentEpisode()).thenAnswer {
            if (currentEpisode.get() == "ep_slow") episodeA else episodeB
        }
        whenever(playbackManager.isPlaybackRemote()).thenReturn(false)

        // The slow fetch waits until the fast one has installed, so the overwrite is deterministic.
        val source = object : CloudReferenceSource {
            override suspend fun fetchReference(
                baseUrl: String,
                episodeUuid: String,
                podcastUuid: String,
                cachedEtag: String?,
            ): CloudFingerprintReferenceFetcher.Fetch {
                if (episodeUuid == slowEpisode) {
                    slowFetchStarted = true
                    secondInstalled.await(5, TimeUnit.SECONDS)
                }
                return CloudFingerprintReferenceFetcher.Fetch.Fresh(
                    payloadFor(episodeUuid),
                    "etag-$episodeUuid",
                )
            }

            override fun buildMatcher(data: ByteArray): CheckpointMatcher? = null
        }

        val manager = newManager(playbackManager, source)

        // Recording what the manager installs, by watching the store writes each resolution makes.
        manager.prepareForCurrentEpisode(FingerprintTimingManager.PrepareTrigger.PLAYBACK)
        // Wait until the slow fetch is actually in flight before switching.
        awaitUntil("the first preparation never started its fetch") { slowFetchStarted }
        currentEpisode.set("ep_fast")
        manager.prepareForCurrentEpisode(FingerprintTimingManager.PrepareTrigger.PLAYBACK)

        // Release the superseded fetch only once the current preparation has CONFIGURED, which is
        // after it installs. Waiting on the store write instead is too early: that happens before
        // the install, so the latch could open inside the window and the case could pass unfixed.
        awaitUntil("the current episode never configured its reference") {
            File(FingerprintReferenceRetriever.referencePath(audioFast.absolutePath)).exists()
        }
        secondInstalled.countDown()

        // Give the superseded preparation every chance to overwrite.
        Thread.sleep(SETTLE_MS)

        // A full mapping so the persist actually writes; its identity comes from the manager's state.
        manager.insert(FingerprintTimingManager.TimeMappingEntry(playbackTime = 99.0, referenceTime = 99.0, score = 0.9f))

        // Switching away persists what the manager holds. Which Auris identity that mapping
        // carries is the observable: it must be the episode it was built for.
        currentEpisode.set("ep_slow")
        manager.prepareForCurrentEpisode(FingerprintTimingManager.PrepareTrigger.PLAYBACK)
        awaitUntil("the mapping was never written") {
            File(FingerprintMappingCache.mappingPath(audioFast.absolutePath)).exists()
        }
        val recordedHash = savedAurisHash(audioFast)
        assertEquals(
            "the mapping was recorded against the superseded episode's Auris reference",
            ReferenceFingerprint.sha256(payloadFor("ep_fast")),
            recordedHash,
        )
    }

    /** Distinct bytes per episode, so a recorded hash names which reference produced the mapping. */
    private fun payloadFor(episodeUuid: String): ByteArray {
        val base = String(FingerprintDecodeHarness.payload(ReferenceFingerprint.AURIS_FORMAT))
        return base.replace("\"total_duration\": 100.0", "\"total_duration\": ${if (episodeUuid == slowEpisode) 100.0 else 101.0}").toByteArray()
    }

    /** The Auris content hash a saved mapping recorded, or null when the file has none. */
    private fun savedAurisHash(audio: File): String? {
        val path = File(FingerprintMappingCache.mappingPath(audio.absolutePath))
        if (!path.exists()) return null
        val json = org.json.JSONObject(path.readText())
        return json.optJSONObject("auris")?.optString("payloadHash")
    }

    @Volatile
    private var slowFetchStarted = false

    /** Records which episode's reference the manager persisted, in order. */
    private val recorded = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val recordingStore = object : CloudReferenceStore {
        override suspend fun load(episodeUuid: String): CachedCloudReference? = null
        override suspend fun save(episodeUuid: String, payload: ByteArray, etag: String?) {
            recorded += etag ?: episodeUuid
        }
    }

    private fun newManager(
        playbackManager: PlaybackManager,
        source: CloudReferenceSource,
    ): FingerprintTimingManager {
        val settings = mock(Settings::class.java)
        @Suppress("UNCHECKED_CAST")
        val off = mock(UserSetting::class.java) as UserSetting<Boolean>
        whenever(off.value).thenReturn(false)
        whenever(settings.warnOnMeteredNetwork).thenReturn(off)
        whenever(settings.cacheEntirePlayingEpisode).thenReturn(off)

        val chapterManager = object : ChapterManager {
            override suspend fun updateChapters(episodeUuid: String, chapters: List<au.com.shiftyjelly.pocketcasts.models.to.DbChapter>) = Unit
            override suspend fun selectChapter(episodeUuid: String, chapterIndex: Int, select: Boolean) = Unit
            override suspend fun hasChapters(episodeUuid: String) = false
            override suspend fun hasGeneratedChapters(episodeUuid: String) = false
            override fun observerChaptersForEpisode(episodeUuid: String) =
                MutableStateFlow(au.com.shiftyjelly.pocketcasts.models.to.Chapters())
        }
        val decodePolicy = mock(FingerprintDecodePolicy::class.java)
        whenever(decodePolicy.current()).thenReturn(FingerprintPolicy.PLATFORM)

        return FingerprintTimingManager(
            playbackManager = playbackManager,
            referenceRetriever = FingerprintReferenceRetriever(OkHttpClient(), RuntimeEnvironment.getApplication()),
            eventHorizon = mock(EventHorizon::class.java),
            context = RuntimeEnvironment.getApplication(),
            chapterManager = Lazy { chapterManager },
            settings = settings,
            dataSourceFactory = Lazy { mock(ExoPlayerDataSourceFactory::class.java) },
            pcmTap = FingerprintPcmTap(),
            cloudConfig = object : CloudConfig {
                override fun baseUrl(): String = "https://auris.invalid"
            },
            cloudFetcher = source,
            cloudReferenceStore = recordingStore,
            decodePolicy = decodePolicy,
            scope = scope,
            audioStreamOpener = object : AudioStreamOpener {
                override suspend fun open(
                    audioFilePath: String,
                    startingAt: Double,
                    sharedCacheKey: String?,
                    isCurrent: () -> Boolean,
                    followPlayerCache: Boolean,
                ): FingerprintTimingManager.AudioStream = throw IllegalStateException("no decode expected here")
            },
            fingerprinterFactory = WindowedFingerprinterFactory { _, _ -> mock(StreamingWindowedFingerprinter::class.java) },
            matcherFactory = CheckpointMatcherFactory { mock(CheckpointMatcher::class.java) },
            transcriptBaseUrl = transcriptHost.url("/generated_transcripts/").toString(),
        )
    }

    private fun episode(uuid: String, audio: File) = PodcastEpisode(
        uuid = uuid,
        publishedDate = Date(),
        downloadStatus = EpisodeDownloadStatus.Downloaded,
        downloadedFilePath = audio.absolutePath,
        duration = 100.0,
    )

    private fun awaitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + BUDGET_MS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError(message)
    }

    private companion object {
        const val BUDGET_MS = 5_000L
        const val POLL_MS = 20L
        const val SETTLE_MS = 500L
    }
}
