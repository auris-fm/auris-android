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
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import uniffi.fingerprint_uniffi.CheckpointMatcher
import uniffi.fingerprint_uniffi.MatchResult
import uniffi.fingerprint_uniffi.WindowedFingerprint

/**
 * The Auris-reference match path applies the same acceptance rule as the pure-function cases:
 * a distant rival must lead by the gap, and with no distant candidate the window is refused.
 *
 * The matcher is a fake over the library's own type (its test constructor takes no native
 * pointer), so these cases run without the native library while still exercising the real
 * production path from window to committed mapping.
 */
class FingerprintTimingManagerCloudAcceptanceTest {

    private lateinit var manager: FingerprintTimingManager
    private lateinit var matcher: CheckpointMatcher

    @Before
    fun setUp() {
        val playbackManager = mock(PlaybackManager::class.java)
        whenever(playbackManager.playbackStateFlow).thenReturn(MutableStateFlow(PlaybackState()))
        matcher = mock(CheckpointMatcher::class.java)
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
        manager.installCloudMatcherForTest(matcher)
    }

    /** One window per second of playback, each scored by [ranked]. */
    private fun feed(seconds: Int, ranked: List<MatchResult>) {
        whenever(matcher.findTopMatches(any(), any())).thenReturn(ranked)
        val windows = (0 until seconds).map { second ->
            WindowedFingerprint(
                timestampMs = (second * 1000).toUInt(),
                durationMs = FingerprintConstants.WINDOW_DURATION_MS.toUInt(),
                hashes = listOf(7u, 9u, 11u),
            )
        }
        manager.processCloudMatchesForTest(windows, startOffset = 0.0)
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