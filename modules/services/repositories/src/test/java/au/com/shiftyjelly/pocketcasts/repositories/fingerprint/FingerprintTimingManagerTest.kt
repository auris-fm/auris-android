package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintTimingManager.TimeMappingEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerprintTimingManagerTest {

    private fun newManager(): FingerprintTimingManager {
        val chapterManager = org.mockito.kotlin.mock<au.com.shiftyjelly.pocketcasts.repositories.podcast.ChapterManager>()
        return FingerprintTimingManager(
            playbackManager = org.mockito.kotlin.mock(),
            referenceRetriever = org.mockito.kotlin.mock(),
            eventHorizon = org.mockito.kotlin.mock(),
            context = org.mockito.kotlin.mock(),
            chapterManager = dagger.Lazy { chapterManager },
            settings = org.mockito.kotlin.mock(),
            dataSourceFactory = org.mockito.kotlin.mock(),
            pcmTap = org.mockito.kotlin.mock(),
            cloudIdentity = org.mockito.kotlin.mock(),
            cloudConfig = org.mockito.kotlin.mock(),
            cloudFetcher = org.mockito.kotlin.mock(),
            decodePolicy = org.mockito.kotlin.mock(),
        )
    }

    private fun setPrivateField(target: Any, name: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun replaceMapping(manager: FingerprintTimingManager, entries: List<FingerprintTimingManager.TimeMappingEntry>) {
        val field = manager.javaClass.getDeclaredField("mapping")
        field.isAccessible = true
        (field.get(manager) as MappingAccumulator)
            .replaceAll(entries)
    }

    @Test
    fun `episode, digest and matched position are bound to one map snapshot across a replacement`() {
        // Reviewer finding 1: reading matchedReferenceTime and the version separately lets a
        // reset/rebind or a same-episode reference replacement pair an OLD position with a NEW
        // digest. The digest is published together with the entries, so a read is always one whole
        // snapshot: the version never runs ahead of the entries it belongs to.
        val manager = newManager()
        setPrivateField(manager, "activeEpisodeUuid", "ep")
        setPrivateField(manager, "activeReferenceVersion", "v1")
        // A dense-enough map (gaps well under the highlight gap) around playback 15s:
        // playback p maps to reference 2p.
        for (p in 10..20) {
            manager.insert(FingerprintTimingManager.TimeMappingEntry(playbackTime = p.toDouble(), referenceTime = (p * 2).toDouble()))
        }

        val first = manager.activeReference(15_000)!!
        assertEquals("ep", first.episodeUuid)
        assertEquals("v1", first.referenceVersion)
        assertEquals("old pair", Pair("v1", 30_000L), Pair(first.referenceVersion, first.matchedReferenceTimeMs))

        // THE REPLACEMENT: same episode, a DIFFERENT map (playback p maps to reference 3p) and new
        // reference bytes. Until the new map is published, a read still sees the WHOLE old snapshot;
        // after it, the whole new one. Neither pairs v2 with the old map's position, and the new
        // position differs from the old one, so a mixed pair could not pass silently.
        setPrivateField(manager, "activeReferenceVersion", "v2")
        assertEquals(
            "before publication the read is still entirely the old snapshot",
            Pair("v1", 30_000L),
            manager.activeReference(15_000)!!.let { Pair(it.referenceVersion, it.matchedReferenceTimeMs) },
        )
        replaceMapping(
            manager,
            (10..20).map { FingerprintTimingManager.TimeMappingEntry(playbackTime = it.toDouble(), referenceTime = (it * 3).toDouble()) },
        )
        manager.insert(FingerprintTimingManager.TimeMappingEntry(playbackTime = 21.0, referenceTime = 63.0))
        val second = manager.activeReference(15_000)!!
        assertEquals(
            "after publication the read is entirely the new snapshot",
            Pair("v2", 45_000L),
            Pair(second.referenceVersion, second.matchedReferenceTimeMs),
        )

        // RESET: the whole snapshot is retired, not just the digest.
        val reset = manager.javaClass.getDeclaredMethod("resetState")
        reset.isAccessible = true
        reset.invoke(manager)
        assertEquals(null, manager.activeReference(15_000))
    }

    @Test
    fun `unknown provenance keeps the episode and mapped position in the real manager`() {
        // Reviewer correction: activeReference used to early-return when the digest was cleared,
        // discarding a valid mapped position. The cloud-only path clears the digest while
        // processCloudMatches still publishes usable entries -- the position must survive with a
        // null version; only the version is omitted downstream.
        val manager = newManager()
        setPrivateField(manager, "activeEpisodeUuid", "ep")
        setPrivateField(manager, "activeReferenceVersion", null)
        for (p in 10..20) {
            manager.insert(FingerprintTimingManager.TimeMappingEntry(playbackTime = p.toDouble(), referenceTime = (p * 2).toDouble()))
        }

        val active = manager.activeReference(15_000)!!
        assertEquals("ep", active.episodeUuid)
        assertEquals(null, active.referenceVersion)
        assertEquals(30_000L, active.matchedReferenceTimeMs)
    }

    @Test
    fun `a playback time outside matched content yields no position but the snapshot is still one object`() {
        val manager = newManager()
        setPrivateField(manager, "activeEpisodeUuid", "ep")
        setPrivateField(manager, "activeReferenceVersion", "v1")
        manager.insert(FingerprintTimingManager.TimeMappingEntry(playbackTime = 10.0, referenceTime = 20.0))

        // Far outside the map: no matched position, and the caller must not send provenance for it.
        val outside = manager.activeReference(999_000)!!
        assertEquals("v1", outside.referenceVersion)
        assertEquals(null, outside.matchedReferenceTimeMs)
    }

    @Test
    fun `interpolate returns null for empty list`() {
        val result = FingerprintTimingManager.interpolate(
            time = 5.0,
            entries = emptyList(),
            keySelector = { it.playbackTime },
            valueSelector = { it.referenceTime },
        )
        assertNull(result)
    }

    @Test
    fun `interpolate extrapolates before first entry`() {
        val entries = listOf(
            TimeMappingEntry(playbackTime = 10.0, referenceTime = 20.0),
        )
        val result = FingerprintTimingManager.interpolate(
            time = 5.0,
            entries = entries,
            keySelector = { it.playbackTime },
            valueSelector = { it.referenceTime },
        )
        assertEquals(15.0, result!!, 0.001)
    }

    @Test
    fun `interpolate extrapolates after last entry`() {
        val entries = listOf(
            TimeMappingEntry(playbackTime = 10.0, referenceTime = 20.0),
        )
        val result = FingerprintTimingManager.interpolate(
            time = 15.0,
            entries = entries,
            keySelector = { it.playbackTime },
            valueSelector = { it.referenceTime },
        )
        assertEquals(25.0, result!!, 0.001)
    }

    @Test
    fun `interpolate returns exact value at entry`() {
        val entries = listOf(
            TimeMappingEntry(playbackTime = 10.0, referenceTime = 20.0),
            TimeMappingEntry(playbackTime = 20.0, referenceTime = 40.0),
        )
        val result = FingerprintTimingManager.interpolate(
            time = 10.0,
            entries = entries,
            keySelector = { it.playbackTime },
            valueSelector = { it.referenceTime },
        )
        assertEquals(20.0, result!!, 0.001)
    }

    @Test
    fun `interpolate linearly between two entries`() {
        val entries = listOf(
            TimeMappingEntry(playbackTime = 10.0, referenceTime = 20.0),
            TimeMappingEntry(playbackTime = 20.0, referenceTime = 40.0),
        )
        val result = FingerprintTimingManager.interpolate(
            time = 15.0,
            entries = entries,
            keySelector = { it.playbackTime },
            valueSelector = { it.referenceTime },
        )
        assertEquals(30.0, result!!, 0.001)
    }

    @Test
    fun `interpolate handles single entry`() {
        val entries = listOf(
            TimeMappingEntry(playbackTime = 10.0, referenceTime = 20.0),
        )
        val result = FingerprintTimingManager.interpolate(
            time = 10.0,
            entries = entries,
            keySelector = { it.playbackTime },
            valueSelector = { it.referenceTime },
        )
        assertEquals(20.0, result!!, 0.001)
    }

    @Test
    fun `interpolate uses binary search for many entries`() {
        val entries = (0..100).map {
            TimeMappingEntry(playbackTime = it.toDouble(), referenceTime = it * 2.0)
        }
        val result = FingerprintTimingManager.interpolate(
            time = 50.5,
            entries = entries,
            keySelector = { it.playbackTime },
            valueSelector = { it.referenceTime },
        )
        assertEquals(101.0, result!!, 0.001)
    }

    @Test
    fun `computeEager runs for downloaded episode with generated chapters`() {
        val eager = FingerprintTimingManager.computeEager(
            hasGeneratedChapters = true,
            isDownloaded = true,
        )
        assertTrue(eager)
    }

    @Test
    fun `computeEager skips streaming episode`() {
        // Streaming episodes never eager-decode the whole file; the PCM tap plus the bounded
        // on-demand resolver cover them without pulling the whole file.
        val eager = FingerprintTimingManager.computeEager(
            hasGeneratedChapters = true,
            isDownloaded = false,
        )
        assertFalse(eager)
    }

    @Test
    fun `computeEager skips episode without generated chapters`() {
        val eager = FingerprintTimingManager.computeEager(
            hasGeneratedChapters = false,
            isDownloaded = true,
        )
        assertFalse(eager)
    }

    @Test
    fun `shouldBlockOnDemandResolve never blocks downloaded episodes`() {
        val blocked = FingerprintTimingManager.shouldBlockOnDemandResolve(
            isDownloaded = true,
            warnOnMeteredNetwork = true,
            isUnmetered = { error("network should not be queried") },
        )
        assertFalse(blocked)
    }

    @Test
    fun `shouldBlockOnDemandResolve allows streaming on metered network by default`() {
        val blocked = FingerprintTimingManager.shouldBlockOnDemandResolve(
            isDownloaded = false,
            warnOnMeteredNetwork = false,
            isUnmetered = { false },
        )
        assertFalse(blocked)
    }

    @Test
    fun `shouldBlockOnDemandResolve blocks streaming on metered network when user warns on data use`() {
        val blocked = FingerprintTimingManager.shouldBlockOnDemandResolve(
            isDownloaded = false,
            warnOnMeteredNetwork = true,
            isUnmetered = { false },
        )
        assertTrue(blocked)
    }

    @Test
    fun `shouldBlockOnDemandResolve never blocks on unmetered network`() {
        val blocked = FingerprintTimingManager.shouldBlockOnDemandResolve(
            isDownloaded = false,
            warnOnMeteredNetwork = true,
            isUnmetered = { true },
        )
        assertFalse(blocked)
    }

    @Test
    fun `alignToWindowGrid aligns to stride boundary`() {
        val aligned = FingerprintTimingManager.alignToWindowGrid(5.7)
        assertEquals(5.0, aligned, 0.001)
    }

    @Test
    fun `alignToWindowGrid clamps negative to zero`() {
        val aligned = FingerprintTimingManager.alignToWindowGrid(-1.0)
        assertEquals(0.0, aligned, 0.001)
    }

    @Test
    fun `alignToWindowGrid zero stays zero`() {
        val aligned = FingerprintTimingManager.alignToWindowGrid(0.0)
        assertEquals(0.0, aligned, 0.001)
    }

    @Test
    fun `searchWindow cold searches forward from reference time`() {
        val window = FingerprintTimingManager.searchWindow(referenceTimeSec = 600.0, estimatedPlaybackSec = null)
        assertEquals(600.0, window.startSec, 0.001)
        assertEquals(600.0 + FingerprintConstants.ON_DEMAND_COLD_BUDGET_SECONDS, window.endSec, 0.001)
    }

    @Test
    fun `searchWindow warm brackets the estimate within budgets`() {
        val window = FingerprintTimingManager.searchWindow(referenceTimeSec = 600.0, estimatedPlaybackSec = 900.0)
        assertEquals(900.0 - FingerprintConstants.ON_DEMAND_BACKWARD_MAX_SECONDS, window.startSec, 0.001)
        assertEquals(900.0 + FingerprintConstants.ON_DEMAND_FORWARD_BUDGET_SECONDS, window.endSec, 0.001)
    }

    @Test
    fun `searchWindow warm starts at reference time when the estimate is just ahead`() {
        val window = FingerprintTimingManager.searchWindow(referenceTimeSec = 600.0, estimatedPlaybackSec = 650.0)
        assertEquals(600.0, window.startSec, 0.001)
        assertEquals(650.0 + FingerprintConstants.ON_DEMAND_FORWARD_BUDGET_SECONDS, window.endSec, 0.001)
    }

    @Test
    fun `searchWindow warm starts at the estimate when the offset is negative`() {
        val window = FingerprintTimingManager.searchWindow(referenceTimeSec = 600.0, estimatedPlaybackSec = 570.0)
        assertEquals(570.0, window.startSec, 0.001)
        assertEquals(570.0 + FingerprintConstants.ON_DEMAND_FORWARD_BUDGET_SECONDS, window.endSec, 0.001)
    }

    @Test
    fun `searchWindow warm keeps the backward bound below a far-back estimate`() {
        val window = FingerprintTimingManager.searchWindow(referenceTimeSec = 600.0, estimatedPlaybackSec = 300.0)
        assertEquals(300.0, window.startSec, 0.001)
        assertEquals(300.0 + FingerprintConstants.ON_DEMAND_FORWARD_BUDGET_SECONDS, window.endSec, 0.001)
    }

    @Test
    fun `searchWindow warm never starts below zero`() {
        val window = FingerprintTimingManager.searchWindow(referenceTimeSec = 10.0, estimatedPlaybackSec = -20.0)
        assertEquals(0.0, window.startSec, 0.001)
    }

    @Test
    fun `isResolveTargetCovered requires minimum anchors`() {
        val acc = MappingAccumulator()
        acc.insert(TimeMappingEntry(playbackTime = 650.0, referenceTime = 620.0))
        assertFalse(FingerprintTimingManager.isResolveTargetCovered(acc, targetReferenceSec = 600.0))
    }

    @Test
    fun `isResolveTargetCovered requires reference past target with margin`() {
        val acc = MappingAccumulator()
        acc.insert(TimeMappingEntry(playbackTime = 620.0, referenceTime = 590.0))
        acc.insert(TimeMappingEntry(playbackTime = 632.0, referenceTime = 602.0))
        assertFalse(FingerprintTimingManager.isResolveTargetCovered(acc, targetReferenceSec = 600.0))
    }

    @Test
    fun `isResolveTargetCovered stops once anchors bracket the target`() {
        val acc = MappingAccumulator()
        acc.insert(TimeMappingEntry(playbackTime = 620.0, referenceTime = 590.0))
        acc.insert(TimeMappingEntry(playbackTime = 636.0, referenceTime = 606.0))
        assertTrue(FingerprintTimingManager.isResolveTargetCovered(acc, targetReferenceSec = 600.0))
    }

    @Test
    fun `densePlaybackSec interpolates between close anchors`() {
        val entries = listOf(
            TimeMappingEntry(playbackTime = 130.0, referenceTime = 100.0),
            TimeMappingEntry(playbackTime = 134.0, referenceTime = 104.0),
        )
        val result = FingerprintTimingManager.densePlaybackSec(referenceTimeSec = 102.0, entries = entries)
        assertEquals(132.0, result!!, 0.001)
    }

    @Test
    fun `densePlaybackSec returns null outside the mapped range`() {
        val entries = listOf(
            TimeMappingEntry(playbackTime = 130.0, referenceTime = 100.0),
            TimeMappingEntry(playbackTime = 134.0, referenceTime = 104.0),
        )
        assertNull(FingerprintTimingManager.densePlaybackSec(referenceTimeSec = 99.0, entries = entries))
        assertNull(FingerprintTimingManager.densePlaybackSec(referenceTimeSec = 104.0 + FingerprintConstants.TAP_TRAILING_GRACE_SECONDS + 1.0, entries = entries))
    }

    @Test
    fun `densePlaybackSec extrapolates within the trailing grace past the last anchor`() {
        val entries = listOf(
            TimeMappingEntry(playbackTime = 130.0, referenceTime = 100.0),
            TimeMappingEntry(playbackTime = 134.0, referenceTime = 104.0),
        )
        val result = FingerprintTimingManager.densePlaybackSec(referenceTimeSec = 110.0, entries = entries)
        assertEquals(140.0, result!!, 0.001)
    }

    @Test
    fun `densePlaybackSec drops the trailing grace when the live edge is unmatched`() {
        val entries = listOf(
            TimeMappingEntry(playbackTime = 130.0, referenceTime = 100.0),
            TimeMappingEntry(playbackTime = 134.0, referenceTime = 104.0),
        )
        assertNull(FingerprintTimingManager.densePlaybackSec(referenceTimeSec = 110.0, entries = entries, allowTrailingGrace = false))
        val bracketed = FingerprintTimingManager.densePlaybackSec(referenceTimeSec = 102.0, entries = entries, allowTrailingGrace = false)
        assertEquals(132.0, bracketed!!, 0.001)
    }

    @Test
    fun `isLiveEdgeUnmatched tolerates isolated missed matches`() {
        assertFalse(FingerprintTimingManager.isLiveEdgeUnmatched(frontierSec = 102.0, lastAnchorPlaybackSec = 100.0))
        assertTrue(FingerprintTimingManager.isLiveEdgeUnmatched(frontierSec = 103.5, lastAnchorPlaybackSec = 100.0))
        assertFalse(FingerprintTimingManager.isLiveEdgeUnmatched(frontierSec = Double.NaN, lastAnchorPlaybackSec = 100.0))
        assertFalse(FingerprintTimingManager.isLiveEdgeUnmatched(frontierSec = 103.5, lastAnchorPlaybackSec = null))
    }

    @Test
    fun `densePlaybackSec returns null when reference gap is too wide`() {
        val entries = listOf(
            TimeMappingEntry(playbackTime = 130.0, referenceTime = 100.0),
            TimeMappingEntry(playbackTime = 150.0, referenceTime = 120.0),
        )
        assertNull(FingerprintTimingManager.densePlaybackSec(referenceTimeSec = 110.0, entries = entries))
    }

    @Test
    fun `densePlaybackSec returns null when playback gap spans an ad boundary`() {
        val entries = listOf(
            TimeMappingEntry(playbackTime = 130.0, referenceTime = 100.0),
            TimeMappingEntry(playbackTime = 190.0, referenceTime = 104.0),
        )
        assertNull(FingerprintTimingManager.densePlaybackSec(referenceTimeSec = 102.0, entries = entries))
    }
}
