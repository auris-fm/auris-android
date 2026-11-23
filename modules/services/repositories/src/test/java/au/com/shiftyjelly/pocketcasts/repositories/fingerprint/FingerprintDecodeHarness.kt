package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.models.to.Chapters
import au.com.shiftyjelly.pocketcasts.models.to.DbChapter
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.robolectric.RuntimeEnvironment
import uniffi.fingerprint_uniffi.CheckpointMatcher
import uniffi.fingerprint_uniffi.StreamingWindowedFingerprinter
import uniffi.fingerprint_uniffi.WindowedFingerprint

/**
 * Drives [FingerprintTimingManager.prepareForCurrentEpisode] — the production entry point — with
 * only the boundaries that need a device replaced: the audio stream, the windowed fingerprinter and
 * the native matcher build. Everything between them is the real code, including reference
 * resolution, the eager-pass decision and the decode loop.
 *
 * The manager's scope belongs to the caller, so [close] ends every job the preparation started.
 */
class FingerprintDecodeHarness(
    private val audioFile: File,
    private val cloudReferenceSource: CloudReferenceSource,
    private val cloudBaseUrl: String = "",
    private val cloudReferenceStore: CloudReferenceStore = mock(CloudReferenceStore::class.java),
    private val hasGeneratedChapters: Boolean = true,
    /** The transcript matcher, when a transcript reference is present. Native, so supplied. */
    private val transcriptMatcher: CheckpointMatcher? = null,
) : AutoCloseable {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val eventHorizon: EventHorizon = mock(EventHorizon::class.java)

    /** Serves the transcript host. 404 by default: no transcript reference, so alignment is Auris-only. */
    val transcriptHost = MockWebServer().also { it.start() }

    /**
     * The transcript reference the host serves. A transcript reference is what production requires
     * before it takes the eager full-file decode; null serves 404 and leaves alignment to the tap.
     */
    var transcriptPayload: ByteArray? = payload(ReferenceFingerprint.TRANSCRIPT_FORMAT)

    /** Set before [prepare]; the windows the fake fingerprinter emits during decoding and on flush. */
    var streamedWindows: List<WindowedFingerprint> = emptyList()
    var tailWindows: List<WindowedFingerprint> = emptyList()

    private var delivered = false

    val manager: FingerprintTimingManager

    init {
        val episode = PodcastEpisode(
            uuid = EPISODE_UUID,
            publishedDate = Date(),
            downloadStatus = EpisodeDownloadStatus.Downloaded,
            downloadedFilePath = audioFile.absolutePath,
            duration = 100.0,
        )
        val playbackManager = mock(PlaybackManager::class.java)
        whenever(playbackManager.playbackStateFlow).thenReturn(MutableStateFlow(PlaybackState()))
        whenever(playbackManager.getCurrentEpisode()).thenReturn(episode)
        whenever(playbackManager.isPlaybackRemote()).thenReturn(false)

        val settings = mock(Settings::class.java)

        @Suppress("UNCHECKED_CAST")
        val off = mock(UserSetting::class.java) as UserSetting<Boolean>
        whenever(off.value).thenReturn(false)
        whenever(settings.warnOnMeteredNetwork).thenReturn(off)
        whenever(settings.cacheEntirePlayingEpisode).thenReturn(off)

        // A real implementation, not a mock: mocking a suspend function returns null here.
        val chapterManager = object : ChapterManager {
            override suspend fun updateChapters(episodeUuid: String, chapters: List<DbChapter>) = Unit
            override suspend fun selectChapter(episodeUuid: String, chapterIndex: Int, select: Boolean) = Unit
            override suspend fun hasChapters(episodeUuid: String): Boolean = hasGeneratedChapters
            override suspend fun hasGeneratedChapters(episodeUuid: String): Boolean = hasGeneratedChapters
            override fun observerChaptersForEpisode(episodeUuid: String): Flow<Chapters> = MutableStateFlow(Chapters())
        }

        val decodePolicy = mock(FingerprintDecodePolicy::class.java)
        whenever(decodePolicy.current()).thenReturn(FingerprintPolicy.PLATFORM)

        transcriptHost.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val data = transcriptPayload
                return if (data == null) {
                    MockResponse().setResponseCode(404)
                } else {
                    MockResponse().setResponseCode(200).setBody(okio.Buffer().write(data))
                }
            }
        }

        manager = FingerprintTimingManager(
            playbackManager = playbackManager,
            // The real retriever against a stub host: mocking its suspend fetch returns null.
            referenceRetriever = FingerprintReferenceRetriever(
                OkHttpClient(),
                RuntimeEnvironment.getApplication(),
            ),
            eventHorizon = eventHorizon,
            // A real application context: the platform check reads the package manager.
            context = RuntimeEnvironment.getApplication(),
            chapterManager = Lazy { chapterManager },
            settings = settings,
            dataSourceFactory = Lazy { mock(ExoPlayerDataSourceFactory::class.java) },
            pcmTap = FingerprintPcmTap(),
            cloudConfig = object : CloudConfig {
                override fun baseUrl(): String = cloudBaseUrl
            },
            cloudFetcher = cloudReferenceSource,
            cloudReferenceStore = cloudReferenceStore,
            decodePolicy = decodePolicy,
            scope = scope,
            audioStreamOpener = FakeAudioStreamOpener(),
            fingerprinterFactory = WindowedFingerprinterFactory { _, _ -> fakeStreamer() },
            matcherFactory = CheckpointMatcherFactory { reference ->
                if (reference.libraryCheckpoints().isEmpty()) null else transcriptMatcher
            },
            transcriptBaseUrl = transcriptHost.url("/generated_transcripts/").toString(),
        )
        manager.debugTrackingEnabled = true
    }

    /** Starts the real preparation and waits for the decode it launches to finish. */
    fun prepareAndAwaitDecode() {
        manager.prepareForCurrentEpisode(FingerprintTimingManager.PrepareTrigger.PLAYBACK)
        val deadline = System.currentTimeMillis() + DECODE_BUDGET_MS
        while (System.currentTimeMillis() < deadline) {
            if (decoded.get()) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("the preparation never reached the decode loop: ${manager.state}")
    }

    private val decoded = java.util.concurrent.atomic.AtomicBoolean(false)

    /** One decoded block of silence, then end of stream. */
    private inner class FakeAudioStreamOpener : AudioStreamOpener {
        override suspend fun open(
            audioFilePath: String,
            startingAt: Double,
            sharedCacheKey: String?,
            isCurrent: () -> Boolean,
            followPlayerCache: Boolean,
        ): FingerprintTimingManager.AudioStream {
            val extractor = mock(MediaExtractor::class.java)
            whenever(extractor.readSampleData(any(), any())).thenReturn(-1)

            val codec = mock(MediaCodec::class.java)
            whenever(codec.dequeueInputBuffer(any())).thenReturn(-1)
            whenever(codec.dequeueOutputBuffer(any(), any())).thenAnswer { invocation ->
                val info = invocation.getArgument<MediaCodec.BufferInfo>(0)
                if (!delivered) {
                    delivered = true
                    info.set(0, BLOCK_BYTES, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    0
                } else {
                    info.set(0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    -1
                }
            }
            whenever(codec.getOutputBuffer(0)).thenReturn(
                ByteBuffer.allocate(BLOCK_BYTES).order(ByteOrder.nativeOrder()),
            )
            whenever(codec.outputFormat).thenReturn(mock(MediaFormat::class.java))

            return FingerprintTimingManager.AudioStream(
                extractor = extractor,
                codec = codec,
                format = mock(MediaFormat::class.java),
                sampleRate = 44_100,
                channelCount = 2,
                startSec = startingAt,
            )
        }
    }

    private fun fakeStreamer(): StreamingWindowedFingerprinter {
        val streamer = mock(StreamingWindowedFingerprinter::class.java)
        whenever(streamer.pushSamplesF32(any(), any())).thenReturn(streamedWindows)
        whenever(streamer.flush()).thenAnswer {
            // The flush is the last thing the decode loop does, so it marks the decode complete.
            decoded.set(true)
            tailWindows
        }
        whenever(streamer.durationMs()).thenReturn(0u)
        return streamer
    }

    override fun close() {
        scope.cancel()
        runCatching { transcriptHost.shutdown() }
    }

    companion object {
        const val EPISODE_UUID = "ep_harness"
        private const val BLOCK_BYTES = 4096
        private const val DECODE_BUDGET_MS = 5_000L
        private const val POLL_MS = 20L

        /** A compact reference in [format] with three one-second checkpoints carrying real values. */
        fun payload(format: String): ByteArray {
            val checkpoints = (0 until 3).joinToString(",") { index ->
                val buffer = ByteBuffer.allocate(3 * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (hash in longArrayOf(7L, 9L, 11L)) buffer.putInt((hash and 0xFFFFFFFFL).toInt())
                val delta = if (index == 0) 0 else 1
                """[$delta, "${java.util.Base64.getEncoder().encodeToString(buffer.array())}"]"""
            }
            return """
                {
                  "format": "$format",
                  "total_duration": 100.0,
                  "checkpoint_interval": 1,
                  "checkpoint_duration": 8,
                  "timestamp_quantum": 1,
                  "checkpoints": [$checkpoints]
                }
            """.trimIndent().toByteArray()
        }

        /** A source whose bytes come from [data] and whose matcher build returns [matcher]. */
        fun source(data: ByteArray?, matcher: CheckpointMatcher?): CloudReferenceSource = object : CloudReferenceSource {
            override suspend fun fetchReference(
                baseUrl: String,
                episodeUuid: String,
                podcastUuid: String,
                cachedEtag: String?,
            ): CloudFingerprintReferenceFetcher.Fetch = if (data == null) {
                CloudFingerprintReferenceFetcher.Fetch.Unavailable
            } else {
                CloudFingerprintReferenceFetcher.Fetch.Fresh(data, "etag")
            }

            override fun buildMatcher(data: ByteArray): CheckpointMatcher? = matcher
        }
    }
}
