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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import uniffi.fingerprint_uniffi.CheckpointMatcher
import uniffi.fingerprint_uniffi.MatchResult
import uniffi.fingerprint_uniffi.WindowedFingerprint

/**
 * A downloaded episode with generated chapters decodes the whole file locally. That decode must
 * score BOTH references: the transcript reference and the Auris reference, exactly as the PCM tap
 * path does. Otherwise the eager path silently loses every Auris candidate.
 *
 * The decoder is driven through a fake extractor/codec pair so the real production loop runs —
 * input feeding, output extraction, window emission, flush — without a device. The fingerprinter
 * and both matchers are fakes over the library's own types, whose test constructors take no native
 * pointer, so nothing here needs the native library.
 */
@Config(manifest = Config.NONE)
@RunWith(RobolectricTestRunner::class)
class FingerprintTimingManagerEagerCloudTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var manager: FingerprintTimingManager
    private lateinit var cloudMatcher: CheckpointMatcher
    private lateinit var transcriptMatcher: CheckpointMatcher
    private lateinit var audioFile: File

    /** One decoded block of silence; long enough for the fake fingerprinter to emit a window. */
    private val blockBytes = 4096

    @Before
    fun setUp() {
        val playbackManager = mock(PlaybackManager::class.java)
        whenever(playbackManager.playbackStateFlow).thenReturn(MutableStateFlow(PlaybackState()))
        cloudMatcher = mock(CheckpointMatcher::class.java)
        transcriptMatcher = mock(CheckpointMatcher::class.java)
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
            cloudConfig = mock(SharedPreferencesCloudConfig::class.java),
            cloudFetcher = mock(CloudFingerprintReferenceFetcher::class.java),
            cloudReferenceStore = mock(CloudReferenceStore::class.java),
            decodePolicy = mock(FingerprintDecodePolicy::class.java),
        )
        manager.debugTrackingEnabled = true
        manager.installCloudMatcherForTest(cloudMatcher)
    }

    /**
     * Runs the eager decode with [streamedWindows] emitted during decoding and [tailWindows] on
     * flush. The transcript matcher returns nothing, so any committed mapping can only have come
     * from the Auris reference.
     */
    private fun runEagerDecode(
        streamedWindows: List<WindowedFingerprint>,
        tailWindows: List<WindowedFingerprint>,
    ) {
        whenever(transcriptMatcher.findTopMatches(any(), any())).thenReturn(emptyList())

        val extractor = mock(MediaExtractor::class.java)
        whenever(extractor.readSampleData(any(), any())).thenReturn(-1)

        val codec = mock(MediaCodec::class.java)
        whenever(codec.dequeueInputBuffer(any())).thenReturn(-1)
        // One output buffer carrying the block, flagged end-of-stream, then nothing further.
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

        val streamer = mock(uniffi.fingerprint_uniffi.StreamingWindowedFingerprinter::class.java)
        whenever(streamer.pushSamplesF32(any(), any())).thenReturn(streamedWindows)
        whenever(streamer.flush()).thenReturn(tailWindows)
        whenever(streamer.durationMs()).thenReturn(0u)

        manager.runEagerDecodeForTest(
            audioFilePath = audioFile.absolutePath,
            transcriptMatcher = transcriptMatcher,
            extractor = extractor,
            codec = codec,
            streamer = streamer,
            format = mock(MediaFormat::class.java),
            sampleRate = 44100,
            channelCount = 2,
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