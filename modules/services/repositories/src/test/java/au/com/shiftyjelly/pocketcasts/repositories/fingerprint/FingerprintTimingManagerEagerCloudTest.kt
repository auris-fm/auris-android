package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.playback.ExoPlayerDataSourceFactory
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackState
import au.com.shiftyjelly.pocketcasts.repositories.podcast.ChapterManager
import au.com.shiftyjelly.pocketcasts.utils.fingerprint.FingerprintDecodePolicy
import com.automattic.eventhorizon.EventHorizon
import dagger.Lazy
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.fingerprint_uniffi.CheckpointMatcher
import uniffi.fingerprint_uniffi.MatchResult
import uniffi.fingerprint_uniffi.StreamingWindowedFingerprinter
import uniffi.fingerprint_uniffi.WindowedFingerprint

/**
 * A decoded episode must score the Auris reference for the windows it emits DURING decoding and for
 * the windows released by the final flush. Skipping either loses anchors: the streamed ones are most
 * of the map, and the tail is the last seconds of every episode.
 *
 * The reference reaches the manager the way it does in production — through the fetcher and the
 * store — so a break in that resolution shows up here too. Only the two boundaries that need a
 * device are supplied: the audio stream and the fingerprinter.
 */
@Config(manifest = Config.NONE)
@RunWith(RobolectricTestRunner::class)
class FingerprintTimingManagerEagerCloudTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var manager: FingerprintTimingManager
    private lateinit var cloudMatcher: CheckpointMatcher
    private lateinit var fetcher: CloudReferenceSource
    private lateinit var audioFile: File
    private lateinit var cloud: MockWebServer
    private lateinit var builtFrom: MutableList<Int>
    private var lastFetch: String = "none"
    private val cloudSeen = mutableListOf<String>()

    /** One decoded block of silence; long enough for the fake fingerprinter to emit a window. */
    private val blockBytes = 4096

    @Before
    fun setUp() {
        val playbackManager = mock(PlaybackManager::class.java)
        whenever(playbackManager.playbackStateFlow).thenReturn(MutableStateFlow(PlaybackState()))
        cloudMatcher = mock(CheckpointMatcher::class.java)
        // The reference travels the REAL fetch path against a stub endpoint; the library's matcher
        // is built from the bytes it serves, and that build is the one call needing the native
        // library, so it is the only thing replaced.
        builtFrom = mutableListOf()
        val realFetcher = CloudFingerprintReferenceFetcher(alwaysMintToken())
        fetcher = object : CloudReferenceSource {
            // The bytes come from the real transport; only the native build is replaced.
            override suspend fun fetchReference(
                baseUrl: String,
                episodeUuid: String,
                podcastUuid: String,
                cachedEtag: String?,
            ): CloudFingerprintReferenceFetcher.Fetch {
                val r = realFetcher.fetchReference(baseUrl, episodeUuid, podcastUuid, cachedEtag)
                lastFetch = (r::class.simpleName ?: "?") + " etag=" + cachedEtag + " seen=" + cloudSeen.joinToString(",")
                return r
            }

            override fun buildMatcher(data: ByteArray): CheckpointMatcher {
                builtFrom += data.size
                return cloudMatcher
            }
        }
        cloud = MockWebServer().also { it.start() }
        cloud.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                cloudSeen += request.path!!
                return if (request.path!!.contains("fingerprints")) {
                    MockResponse().setResponseCode(200).setBody(String(aurisPayload()))
                } else {
                    MockResponse().setResponseCode(200).setBody(
                        """{"access_token":"t","token_type":"Bearer","expires_in":900,"refresh_token":"r"}""",
                    )
                }
            }
        }
        audioFile = temp.newFile("episode.mp3").apply { writeBytes(ByteArray(blockBytes)) }

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
            cloudFetcher = fetcher,
            cloudReferenceStore = mock(CloudReferenceStore::class.java),
          
  decodePolicy = mock(FingerprintDecodePolicy::class.java),
        )
        manager.debugTrackingEnabled = true
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

    /** Three one-second checkpoints; the fetcher refuses a reference with none. */
    private fun aurisPayload(): ByteArray {
        val checkpoints = listOf(0, 1, 2).joinToString(",") { index ->
            val delta = if (index == 0) 0 else 1
            val buffer = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            for (hash in longArrayOf(7L, 9L, 11L)) buffer.putInt((hash and 0xFFFFFFFFL).toInt())
            """[$delta, "${java.util.Base64.getEncoder().encodeToString(buffer.array())}"]"""
        }
        return """
            {
              "format": "${ReferenceFingerprint.AURIS_FORMAT}",
              "total_duration": 100.0,
              "checkpoint_interval": 1,
              "checkpoint_duration": 8,
              "timestamp_quantum": 1,
              "checkpoints": [$checkpoints]
            }
        """.trimIndent().toByteArray()
    }

    /**
     * Runs the decode with [streamedWindows] emitted during decoding and [tailWindows] on flush.
     * The transcript reference is absent, so any committed mapping can only be Auris-derived.
     */
    private fun runEagerDecode(
        streamedWindows: List<WindowedFingerprint>,
        tailWindows: List<WindowedFingerprint>,
    ) = runTest {
        val extractor = mock(MediaExtractor::class.java)
        whenever(extractor.readSampleData(any(), any())).thenReturn(-1)

        val codec = mock(MediaCodec::class.java)
        whenever(codec.dequeueInputBuffer(any())).thenReturn(-1)
        val info = MediaCodec.BufferInfo()
        whenever(codec.dequeueOutputBuffer(any(), any())).thenAnswer { invocation ->
            val out = invocation.getArgument<MediaCodec.BufferInfo>(0)
            if (!delivered) {
                delivered = true
                out.set(0, blockBytes, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                0
            } else {
                out.set(0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                -1
            }
        }
        whenever(codec.getOutputBuffer(0)).thenReturn(
            ByteBuffer.allocate(blockBytes).order(ByteOrder.nativeOrder()),
        )
        whenever(codec.outputFormat).thenReturn(mock(MediaFormat::class.java))

        val streamer = mock(StreamingWindowedFingerprinter::class.java)
        whenever(streamer.pushSamplesF32(any(), any())).thenReturn(streamedWindows)
        whenever(streamer.flush()).thenReturn(tailWindows)
        whenever(streamer.durationMs()).thenReturn(0u)

        manager.prepareForTest(
            episodeUuid = "episode",
            audioFilePath = audioFile.absolutePath,
            extractor = extractor,
            codec = codec,
            format = mock(MediaFormat::class.java),
            sampleRate = 44100,
            channelCount = 2,
            streamer = streamer,
        )
    }

    private var delivered = false

    private fun window(second: Int) = WindowedFingerprint(
        timestampMs = (second * 1000).toUInt(),
        durationMs = FingerprintConstants.WINDOW_DURATION_MS.toUInt(),
        hashes = listOf(7u, 9u, 11u),
    )

    /** A confident best with a distant rival far behind: the acceptance rule accepts it. */
    private val accepted = listOf(
        MatchResult(timestamp = 40f, score = 0.90f),
        MatchResult(timestamp = 41f, score = 0.88f),
        MatchResult(timestamp = 90f, score = 0.40f),
    )

    @After
    fun tearDown() = runBlocking { manager.shutdownForTest() }

    /** Off Default: a preparation that outlives the test must not surface in the next class. */
    private fun CoroutineScope.separate() = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    @Test
    fun `the eager decode scores the Auris reference for its streamed windows`() {
        whenever(cloudMatcher.findTopMatches(any(), any())).thenReturn(accepted)

        runEagerDecode(
            streamedWindows = listOf(window(0), window(1), window(2)),
            tailWindows = emptyList(),
        )

        assertEquals(
            "the eager decode committed nothing from the Auris reference",
            listOf(0.0, 1.0, 2.0),
            manager.mappingSnapshot.map { it.playbackTime },
        )
        assertEquals(listOf(40.0, 40.0, 40.0), manager.mappingSnapshot.map { it.referenceTime })
    }

    @Test
    fun `the eager decode scores the Auris reference for its flushed tail too`() {
        whenever(cloudMatcher.findTopMatches(any(), any())).thenReturn(accepted)

        // Nothing during decoding; the whole map arrives in the flush. Skipping the tail would
        // lose the last windows of every downloaded episode.
        runEagerDecode(
            streamedWindows = emptyList(),
            tailWindows = listOf(window(0), window(1), window(2)),
        )

        assertEquals(3, manager.mappingSnapshot.size)
    }

    @Test
    fun `the eager decode refuses what the acceptance rule refuses`() {
        // No candidate more than two seconds away: fail closed, even above the floor.
        whenever(cloudMatcher.findTopMatches(any(), any())).thenReturn(
            listOf(
                MatchResult(timestamp = 40f, score = 0.95f),
                MatchResult(timestamp = 41f, score = 0.94f),
            ),
        )

        runEagerDecode(
            streamedWindows = listOf(window(0), window(1), window(2)),
            tailWindows = emptyList(),
        )

        assertTrue(manager.mappingSnapshot.isEmpty())
        assertEquals(3, manager.debugRejectionsSnapshot.size)
    }
}
