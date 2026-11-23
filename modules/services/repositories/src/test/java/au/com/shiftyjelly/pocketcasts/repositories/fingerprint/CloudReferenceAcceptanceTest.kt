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
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever

/**
 * Acceptance for matching against the Auris reference
 * (docs/specs/timeline-alignment.md#reference-matching).
 *
 * A window is accepted at score 0.30, and its lead is measured against the best checkpoint
 * more than 2 s away rather than the runner-up: consecutive checkpoints share seven of their
 * eight seconds, so the runner-up scores almost the same and would reject nearly every real
 * window. The transcript-sync matcher keeps its own values; nothing here touches it.
 */
class CloudReferenceAcceptanceTest {

    private lateinit var manager: FingerprintTimingManager

    @Before
    fun setUp() {
        val playbackManager = mock(PlaybackManager::class.java)
        whenever(playbackManager.playbackStateFlow).thenReturn(MutableStateFlow(PlaybackState()))
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
    }

    /** Hashes 1..[count], so a query sharing [shared] of them scores exactly shared/count. */
    private fun hashes(count: Int, from: Int = 1): LongArray =
        LongArray(count) { (from + it).toLong() }

    /**
     * A reference of one-second checkpoints from 0 to [lastSecond], each holding [querySize]
     * hashes. A checkpoint shares [sharedAt] of the query's hashes at its own second; every
     * other checkpoint holds only its own, so it scores zero. Neighbours therefore behave like
     * real neighbouring windows — close in time, weaker in score — without being tuned by hand.
     */
    private fun reference(querySize: Int, lastSecond: Int, sharedAt: Map<Int, Int>): CloudReferenceMatcher {
        val matcher = CloudReferenceMatcher()
        for (t in 0..lastSecond) {
            val shared = sharedAt[t] ?: 0
            val own = hashes(querySize, from = 1_000_000 + t * 1000)
            val mixed = LongArray(querySize) { i -> if (i < shared) (i + 1).toLong() else own[i] }
            matcher.add(t.toFloat(), mixed)
        }
        return matcher
    }

    private fun windowAt(seconds: Int, querySize: Int) =
        listOf(CloudFingerprinter.Window(timestampSec = seconds, hashes = hashes(querySize)))

    /** Runs the cloud path over one window and returns what the drift filter was offered. */
    private fun candidatesFrom(matcher: CloudReferenceMatcher, seconds: Int, querySize: Int): List<FingerprintTimingManager.TimeMappingEntry> {
        manager.stubCloudMatcher(matcher)
        manager.processCloudWindowsForTest(windowAt(seconds, querySize), startOffset = 0.0)
        return manager.cloudCandidatesForTest
    }

    @Test
    fun `a window scoring 0_40 reaches the drift filter`() {
        // 4 of 10 hashes shared: above the 0.30 acceptance mark, below both old marks
        // (0.5 floor, 0.65 anchor), so the old rules never offered it to the filter at all.
        val matcher = reference(querySize = 10, lastSecond = 40, sharedAt = mapOf(30 to 4))

        val offered = candidatesFrom(matcher, seconds = 30, querySize = 10)

        assertEquals("a 0.40 window must reach the drift filter", 1, offered.size)
        assertEquals(30.0, offered[0].referenceTime, 0.001)
        assertEquals(0.4f, offered[0].score, 0.0001f)
    }

    @Test
    fun `unrelated audio never reaches the drift filter`() {
        val matcher = reference(querySize = 10, lastSecond = 40, sharedAt = emptyMap())

        val offered = candidatesFrom(matcher, seconds = 30, querySize = 10)

        assertTrue("unrelated audio must not be offered: $offered", offered.isEmpty())
    }

    @Test
    fun `a window just under the acceptance mark is not offered`() {
        // 29 of 100: below 0.30. Pins the mark itself rather than only "lower than before".
        val matcher = reference(querySize = 100, lastSecond = 40, sharedAt = mapOf(30 to 29))

        val offered = candidatesFrom(matcher, seconds = 30, querySize = 100)

        assertTrue("0.29 must stay below the mark: $offered", offered.isEmpty())
    }

    @Test
    fun `a neighbouring checkpoint scoring almost the same does not block the match`() {
        // Best 0.40 at t=30, its neighbour 0.39 at t=31: a lead of only 0.01 over the runner-up,
        // which the old rule rejected. Everything more than 2 s away scores zero, so the window
        // is unambiguous and must be offered.
        val matcher = reference(querySize = 100, lastSecond = 40, sharedAt = mapOf(30 to 40, 31 to 39))

        val offered = candidatesFrom(matcher, seconds = 30, querySize = 100)

        assertEquals("the neighbouring checkpoint must not block", 1, offered.size)
        assertEquals(30.0, offered[0].referenceTime, 0.001)
    }

    @Test
    fun `a distant checkpoint within the lead gap blocks the match`() {
        // Best 0.40 at t=30, neighbour 0.39 at t=31, and 0.38 at t=20 — ten seconds from the
        // best, so the distant competitor, with a lead of only 0.02. The neighbour OUTRANKS it,
        // so the old runner-up rule measured 0.01 and rejected this for the wrong reason; the
        // new rule must reject it because the distant rival is within the gap. Both reject,
        // and the test below is the one that tells the two rules apart.
        val matcher = reference(
            querySize = 100,
            lastSecond = 40,
            sharedAt = mapOf(30 to 40, 31 to 39, 20 to 38),
        )

        val offered = candidatesFrom(matcher, seconds = 30, querySize = 100)

        assertTrue("a distant rival within the gap must block: $offered", offered.isEmpty())
    }

    @Test
    fun `the lead check has a checkpoint more than two seconds away to compare against`() {
        // Without enough ranked candidates the distant competitor is never returned and the
        // lead check passes by having nothing to compare — the guard would be vacuous.
        val matcher = reference(
            querySize = 100,
            lastSecond = 40,
            sharedAt = mapOf(30 to 40, 31 to 39, 20 to 38),
        )

        val ranked = matcher.findTopMatches(hashes(100), FingerprintConstants.CLOUD_REFERENCE_CANDIDATES)
        val best = ranked.first().timestampSeconds

        assertTrue(
            "the ranked list must contain a checkpoint more than 2 s from the best match, got " +
                ranked.map { it.timestampSeconds },
            ranked.drop(1).any { abs(it.timestampSeconds - best) > 2f },
        )
        assertTrue(
            "asking for only two candidates hides the distant competitor",
            matcher.findTopMatches(hashes(100), 2).drop(1).none { abs(it.timestampSeconds - best) > 2f },
        )
    }

    @Test
    fun `an accepted window commits an anchor once the drift filter has its sequence`() {
        // Three consecutive windows, each played 400 ms after its checkpoint: the shift the
        // spec measured, at a score the old marks rejected outright.
        val matcher = reference(
            querySize = 10,
            lastSecond = 40,
            sharedAt = mapOf(30 to 4, 31 to 4, 32 to 4),
        )
        manager.stubCloudMatcher(matcher)
        val windows = (30..32).map { CloudFingerprinter.Window(timestampSec = it, hashes = hashes(10)) }

        manager.processCloudWindowsForTest(windows, startOffset = 0.4)

        assertEquals(
            "three consistent 0.40 windows must commit anchors",
            3,
            manager.mappingSnapshot.size,
        )
        assertEquals(30.0, manager.mappingSnapshot.first().referenceTime, 0.001)
        assertEquals(30.4, manager.mappingSnapshot.first().playbackTime, 0.001)
    }
}
