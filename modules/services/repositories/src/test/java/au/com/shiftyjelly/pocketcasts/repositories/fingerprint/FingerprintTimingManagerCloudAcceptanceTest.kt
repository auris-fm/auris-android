package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import java.io.File
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
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.fingerprint_uniffi.CheckpointMatcher
import uniffi.fingerprint_uniffi.MatchResult
import uniffi.fingerprint_uniffi.WindowedFingerprint

/**
 * The Auris-reference match path applies the same acceptance rule as the pure-function cases:
 * a distant rival must lead by the gap, and with no distant candidate the window is refused.
 *
 * The windows reach the rule through the real preparation and decode loop. Only the boundaries
 * needing a device are replaced, so these run without the native library.
 */
@Config(manifest = Config.NONE)
@RunWith(RobolectricTestRunner::class)
class FingerprintTimingManagerCloudAcceptanceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var matcher: CheckpointMatcher
    private lateinit var harness: FingerprintDecodeHarness

    @Before
    fun setUp() {
        matcher = mock(CheckpointMatcher::class.java)
        val audioFile = temp.newFile("episode.mp3").apply { writeBytes(ByteArray(4096)) }
        harness = FingerprintDecodeHarness(
            audioFile = audioFile,
            cloudReferenceSource = FingerprintDecodeHarness.source(
                data = FingerprintDecodeHarness.payload(ReferenceFingerprint.AURIS_FORMAT),
                matcher = matcher,
            ),
            cloudBaseUrl = "https://auris.invalid",
            transcriptMatcher = mock(CheckpointMatcher::class.java),
        )
    }

    @After
    fun tearDown() = harness.close()

    private val manager get() = harness.manager

    /** One window per second of playback, each scored by [ranked], through the real decode loop. */
    private fun feed(seconds: Int, ranked: List<MatchResult>) {
        whenever(matcher.findTopMatches(any(), any())).thenReturn(ranked)
        harness.streamedWindows = (0 until seconds).map { second ->
            WindowedFingerprint(
                timestampMs = (second * 1000).toUInt(),
                durationMs = FingerprintConstants.WINDOW_DURATION_MS.toUInt(),
                hashes = listOf(7u, 9u, 11u),
            )
        }
        harness.tailWindows = emptyList()
        harness.prepareAndAwaitDecode()
    }

    @Test
    fun `a near neighbour does not veto an otherwise confident match`() {
        // Best at 40s; the runner-up is its one-second neighbour, only 0.02 behind. Today's
        // runner-up rule rejects this; the distant-rival rule accepts it because the nearest
        // candidate more than two seconds away trails by far more than the gap.
        feed(
            seconds = 3,
            ranked = listOf(
                MatchResult(timestamp = 40f, score = 0.90f),
                MatchResult(timestamp = 41f, score = 0.88f),
                MatchResult(timestamp = 60f, score = 0.40f),
            ),
        )

        assertEquals(3, manager.mappingSnapshot.size)
        assertEquals(40.0, manager.mappingSnapshot.first().referenceTime, 0.001)
    }

    @Test
    fun `a distant rival within the gap refuses the window`() {
        feed(
            seconds = 3,
            ranked = listOf(
                MatchResult(timestamp = 40f, score = 0.90f),
                MatchResult(timestamp = 60f, score = 0.88f),
            ),
        )

        assertTrue(manager.mappingSnapshot.isEmpty())
        assertEquals(3, manager.debugRejectionsSnapshot.size)
    }

    @Test
    fun `no distant candidate means the window is refused even above the floor`() {
        // A short reference: every candidate lies within two seconds of the best, so nothing
        // establishes that the best leads. Fail closed rather than fall back to the floor.
        feed(
            seconds = 3,
            ranked = listOf(
                MatchResult(timestamp = 40f, score = 0.95f),
                MatchResult(timestamp = 41f, score = 0.94f),
            ),
        )

        assertTrue(manager.mappingSnapshot.isEmpty())
        assertEquals(3, manager.debugRejectionsSnapshot.size)
    }

    @Test
    fun `the query asks for enough candidates to see past the neighbours`() {
        feed(
            seconds = 1,
            ranked = listOf(MatchResult(timestamp = 40f, score = 0.90f)),
        )

        org.mockito.kotlin.verify(matcher).findTopMatches(
            any(),
            eq(FingerprintConstants.REFERENCE_CANDIDATES.toUInt()),
        )
    }

    @Test
    fun `playback time comes from the window's milliseconds`() {
        feed(
            seconds = 3,
            ranked = listOf(
                MatchResult(timestamp = 40f, score = 0.90f),
                MatchResult(timestamp = 90f, score = 0.40f),
            ),
        )

        // The library reports milliseconds; treating them as seconds would place every anchor
        // a thousand times too late.
        assertEquals(listOf(0.0, 1.0, 2.0), manager.mappingSnapshot.map { it.playbackTime })
    }
}
