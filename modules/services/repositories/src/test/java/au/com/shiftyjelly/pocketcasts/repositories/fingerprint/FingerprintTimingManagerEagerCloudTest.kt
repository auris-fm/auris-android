package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.fingerprint_uniffi.CheckpointMatcher
import uniffi.fingerprint_uniffi.MatchResult
import uniffi.fingerprint_uniffi.WindowedFingerprint

/**
 * A decoded episode must score the Auris reference for the windows it emits DURING decoding and for
 * the windows released by the final flush. Skipping either loses anchors: the streamed ones are most
 * of the map, and the tail is the last seconds of every episode.
 *
 * These cases start the real preparation. The reference travels the real fetch path against a stub
 * endpoint, so a break in that resolution shows up here too; only the boundaries needing a device —
 * the audio stream, the fingerprinter and the native matcher build — are replaced.
 */
@Config(manifest = Config.NONE)
@RunWith(RobolectricTestRunner::class)
class FingerprintTimingManagerEagerCloudTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var cloudMatcher: CheckpointMatcher
    private lateinit var audioFile: File
    private lateinit var cloud: MockWebServer
    private lateinit var harness: FingerprintDecodeHarness

    /** Bytes the real transport delivered to the matcher build; empty means no reference survived. */
    private val builtFrom = mutableListOf<Int>()

    @Before
    fun setUp() {
        cloudMatcher = mock(CheckpointMatcher::class.java)
        cloud = MockWebServer().also { it.start() }
        cloud.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.path!!.contains("fingerprints")) {
                MockResponse().setResponseCode(200)
                    .setBody(String(FingerprintDecodeHarness.payload(ReferenceFingerprint.AURIS_FORMAT)))
            } else {
                MockResponse().setResponseCode(200).setBody(
                    """{"access_token":"t","token_type":"Bearer","expires_in":900,"refresh_token":"r"}""",
                )
            }
        }
        audioFile = temp.newFile("episode.mp3").apply { writeBytes(ByteArray(4096)) }

        // The bytes come from the real transport; only the native build is replaced.
        val realFetcher = CloudFingerprintReferenceFetcher(alwaysMintToken())
        val source = object : CloudReferenceSource {
            override suspend fun fetchReference(
                baseUrl: String,
                episodeUuid: String,
                podcastUuid: String,
                cachedEtag: String?,
            ): CloudFingerprintReferenceFetcher.Fetch = realFetcher.fetchReference(baseUrl, episodeUuid, podcastUuid, cachedEtag)

            override fun buildMatcher(data: ByteArray): CheckpointMatcher {
                builtFrom += data.size
                return cloudMatcher
            }
        }

        harness = FingerprintDecodeHarness(
            audioFile = audioFile,
            cloudReferenceSource = source,
            cloudBaseUrl = cloud.url("/").toString().trimEnd('/'),
            transcriptMatcher = mock(CheckpointMatcher::class.java),
        )
    }

    private fun alwaysMintToken() = au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisTokenProvider(
        clientProvider = {
            au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisAuthClient(
                cloud.url("/").toString().trimEnd('/'),
            )
        },
        credentialProvider = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisAccountCredentialProviding {
            override fun credential(): String = "session"
        },
    )

    @After
    fun tearDown() {
        harness.close()
        runCatching { cloud.shutdown() }
    }

    private val manager get() = harness.manager

    /**
     * Runs the real preparation with [streamedWindows] emitted during decoding and [tailWindows] on
     * flush. No transcript reference exists, so any committed mapping can only be Auris-derived.
     */
    private fun runEagerDecode(
        streamedWindows: List<WindowedFingerprint>,
        tailWindows: List<WindowedFingerprint>,
    ) {
        harness.streamedWindows = streamedWindows
        harness.tailWindows = tailWindows
        harness.prepareAndAwaitDecode()
        assertTrue("no Auris reference reached the matcher build", builtFrom.isNotEmpty())
    }

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
        // The signal waits for the tail to be committed, so returning at all means the last
        // windows were scored. The map is complete the moment the harness reports completion.
        assertEquals(listOf(0.0, 1.0, 2.0), manager.mappingSnapshot.map { it.playbackTime })
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
