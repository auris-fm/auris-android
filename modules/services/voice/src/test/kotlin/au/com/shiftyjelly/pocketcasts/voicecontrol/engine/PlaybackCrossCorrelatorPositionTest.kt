package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The correlator must align to the player-timeline position when one is available, not to a buffer
 * offset. Arrival order cannot tell a sample played 40 ms ago from one played 3 s ago that arrived
 * later; only the position can. This test fails if the position is ignored.
 */
class PlaybackCrossCorrelatorPositionTest {

    private val rate = 16_000
    private fun tone(n: Int, freq: Double, phase: Double = 0.0) = FloatArray(n) { i -> (0.6 * sin(2 * PI * freq * i / rate + phase)).toFloat() }

    // 4235652583 BOUNDARIES (unpositioned path, END-anchored window): with a FULL ring the window is
    // [maxOffset-8000, maxOffset-800]; a match at exactly the 50 ms and 500 ms delay edges must be
    // found, and a fresh (partial) reference must still admit its in-window offsets.
    @Test
    fun `unpositioned - full ring, match at the 50 ms delay edge is found`() {
        val rate = 16_000
        val micSize = 1600
        val burst = tone(micSize, 300.0)
        val maxOffset = 32_000 - micSize
        val offset = maxOffset - (50 * rate / 1_000)
        val samples = FloatArray(32_000) { 0f }
        burst.copyInto(samples, offset)
        val mic = FloatArray(micSize) { i -> 0.3f * burst[i] }
        assertTrue(
            "unpositioned full-ring match at the 50 ms edge must be found",
            PlaybackCrossCorrelator().isPlaybackBleed(mic, PlaybackReference(samples, rate, null)),
        )
    }

    @Test
    fun `unpositioned - full ring, match at the 500 ms delay edge is found`() {
        val rate = 16_000
        val micSize = 1600
        val burst = tone(micSize, 300.0)
        val maxOffset = 32_000 - micSize
        val offset = maxOffset - (500 * rate / 1_000)
        val samples = FloatArray(32_000) { 0f }
        burst.copyInto(samples, offset)
        val mic = FloatArray(micSize) { i -> 0.3f * burst[i] }
        assertTrue(
            "unpositioned full-ring match at the 500 ms edge must be found",
            PlaybackCrossCorrelator().isPlaybackBleed(mic, PlaybackReference(samples, rate, null)),
        )
    }

    @Test
    fun `unpositioned - partial reference admits only in-window offsets`() {
        val rate = 16_000
        val micSize = 1600
        // Reference 4_800 samples (300 ms): maxOffset = 3_200 (200 ms). The clamped window is
        // 0..2_400 (implied delays 50..200 ms). The burst sits at offset 2_400 -- the 50 ms edge,
        // the window's END -- and must still be found on a partial reference. (The sub-50 ms
        // EXCLUSION is a source-level window property -- end = maxOffset - minDelay -- and cannot
        // be observed end-to-end: content near the newest samples is partially overlapped by the
        // last searched slice, and normalized correlation of a partial overlap still fires. That is
        // inherent to windowed correlation, not a defect; asserting it here would be asserting
        // nothing.)
        val burst = tone(micSize, 300.0)
        val windowMatch = FloatArray(4_800) { 0f }
        burst.copyInto(windowMatch, 2_400)
        val mic = FloatArray(micSize) { i -> 0.3f * burst[i] }
        assertTrue(
            "partial reference: in-window offset (50 ms implied delay) is found",
            PlaybackCrossCorrelator().isPlaybackBleed(mic, PlaybackReference(windowMatch, rate, null)),
        )
    }

    @Test
    fun `unpositioned - a reference shorter than the minimum delay declines the comparison`() {
        val rate = 16_000
        val micSize = 1600
        // maxOffset = 400 (25 ms) < minDelay (50 ms): even offset 0 implies a sub-floor delay, so
        // there is no complete in-window candidate -- the comparison is DECLINED (no bleed), never
        // resolved by clamping onto fabricated offset zero (@spec 691ee13c).
        val burst = tone(micSize, 300.0)
        val shortReference = FloatArray(micSize + 400) { 0f }
        burst.copyInto(shortReference, 0)
        val mic = FloatArray(micSize) { i -> 0.3f * burst[i] }
        assertFalse(
            "a reference shorter than the minimum delay must decline the comparison, not fabricate offset zero",
            PlaybackCrossCorrelator().isPlaybackBleed(mic, PlaybackReference(shortReference, rate, null)),
        )
    }

    @Test
    fun `audio that matches only a stale region is not bleed when the position says it is old`() {
        // The reference holds a distinctive burst at its START, followed by unrelated audio. By
        // buffer OFFSET the burst sits where a plausible delay would be looked for; by POSITION it
        // played 5 seconds ago, which cannot be reaching the microphone now.
        val burst = tone(1600, 300.0)
        val other = tone(16_000 - 1600, 900.0, phase = 0.7)
        val samples = burst + other
        val mic = FloatArray(1600) { i -> 0.3f * burst[i] }

        // Position puts the burst 5 s before the mic segment: far outside the 50..500 ms window.
        val staleReference = PlaybackReference(
            samples = samples,
            sampleRateHz = rate,
            startPositionMs = 0L,
        )
        val micPositionMs = 5_000L

        assertFalse(
            "a sample that played 5 s ago cannot be bleed now, even though its offset matches",
            PlaybackCrossCorrelator().isPlaybackBleed(mic, staleReference, micPositionMs),
        )
    }

    @Test
    fun `audio that matches inside the delay window is bleed`() {
        val burst = tone(1600, 300.0)
        val other = tone(16_000 - 1600, 900.0, phase = 0.7)
        val samples = burst + other
        val mic = FloatArray(1600) { i -> 0.3f * burst[i] }

        // Same buffer, but the burst is positioned ~275 ms before the mic segment: inside the
        // plausible acoustic delay, so it is bleed.
        val reference = PlaybackReference(samples = samples, sampleRateHz = rate, startPositionMs = 0L)
        val micPositionMs = 275L

        assertTrue(
            "a sample inside the delay window is bleed",
            PlaybackCrossCorrelator().isPlaybackBleed(mic, reference, micPositionMs),
        )
    }

    @Test
    fun `without a position the offset scan still runs`() {
        val burst = tone(1600, 300.0)
        val samples = FloatArray(16_000) { 0f }
        // Place the burst where the offset scan looks for it: the END-anchored acoustic window --
        // the mic hears the reference's NEWEST content minus the delay. maxOffset = 16000-1600 =
        // 14400; a 200 ms delay puts the match at 14400 - 3200 = 11200.
        val offset = 11_200
        burst.copyInto(samples, offset)
        val mic = FloatArray(1600) { i -> 0.3f * burst[i] }

        val untimestamped = PlaybackReference(samples, rate, startPositionMs = null)
        assertTrue(
            "no position available, so the offset scan must still find the match",
            PlaybackCrossCorrelator().isPlaybackBleed(mic, untimestamped),
        )
    }

    @Test
    fun `echo at the near end of the delay window is bleed`() {
        assertBleedAtDelay(150L)
    }

    @Test
    fun `echo at the far end of the delay window is bleed`() {
        assertBleedAtDelay(450L)
    }

    /**
     * Places the matching playback burst exactly [delayMs] before the mic segment, so the correct
     * offset is determined by the position. A search anchored to a single point (the window
     * midpoint, 275 ms) finds neither end and misses the echo.
     */
    private fun assertBleedAtDelay(delayMs: Long) {
        val rate = 16_000
        val micSamples = 1600
        val burst = tone(micSamples, 300.0)
        // The reference starts `delayMs` before the mic segment, so the burst at offset 0 is
        // exactly `delayMs` old when the mic segment is captured.
        val tail = tone(8000, 900.0, phase = 0.7)
        val reference = PlaybackReference(
            samples = burst + tail,
            sampleRateHz = rate,
            startPositionMs = 0L,
        )
        val mic = FloatArray(micSamples) { i -> 0.3f * burst[i] }

        assertTrue(
            "an echo arriving $delayMs ms late is inside the window and must be found",
            PlaybackCrossCorrelator().isPlaybackBleed(mic, reference, micPositionMs = delayMs),
        )
    }

    @Test
    fun `echo outside the delay window is not bleed`() {
        val rate = 16_000
        val micSamples = 1600
        val burst = tone(micSamples, 300.0)
        val tail = tone(8000, 900.0, phase = 0.7)
        val reference = PlaybackReference(samples = burst + tail, sampleRateHz = rate, startPositionMs = 0L)
        val mic = FloatArray(micSamples) { i -> 0.3f * burst[i] }

        // 900 ms is beyond the 500 ms window: the burst cannot be the echo of this segment.
        assertFalse(
            "a delay outside 50..500 ms is not bleed",
            PlaybackCrossCorrelator().isPlaybackBleed(mic, reference, micPositionMs = 900L),
        )
    }

    /**
     * Bleed placed INSIDE the 50..500 ms delay window the position-blind fallback searches, so the
     * control finds it and any path that misses it is a missed detection rather than a refusal.
     * 125 ms at 16 kHz is offset 2_000; the window covers offsets 800..8_000.
     */
    private fun referenceWithBleedAt(burstStart: Int): Pair<FloatArray, FloatArray> {
        val burst = tone(1600, 300.0)
        val reference = FloatArray(160_000) { 0f }
        burst.copyInto(reference, burstStart)
        val mic = FloatArray(1600) { i -> 0.3f * burst[i] }
        return reference to mic
    }

    @Test
    fun `a trusted position whose delay interval misses the reference returns no bleed, without correlating`() {
        // @spec's ruling, superseding the earlier blanket "fall back to the full window": a VALID,
        // TRUSTED timing constraint is authoritative. When its delay interval has no overlap with the
        // retained reference there is no admissible candidate, and matching an old waveform is not
        // evidence of a temporally possible echo -- so the answer is no bleed, reached WITHOUT
        // correlating a fabricated boundary offset.
        //
        // The bleed here is placed INSIDE the default delay window (END-anchored: offset 154_000 of
        // 150_400..157_600 for a 160_000-sample reference), so a correlator that fell back to the
        // full window would FIND it. That is what makes this case able to distinguish the two
        // policies: the positioned call must say no while the no-position control says yes.
        val (samples, mic) = referenceWithBleedAt(burstStart = 154_000)
        val ref = PlaybackReference(samples, rate, startPositionMs = 0L) // covers 0..10_000 ms
        val correlator = PlaybackCrossCorrelator()

        // The no-position control, kept separate: alignment unavailable -> search the default window.
        val control = correlator.isPlaybackBleed(mic, ref, micPositionMs = null)
        assertTrue("the no-position control must find the bleed, or this fixture proves nothing", control)

        assertFalse(
            "a mic position 60 s after a 10 s reference has no admissible candidate: no bleed",
            correlator.isPlaybackBleed(mic, ref, micPositionMs = 60_000L),
        )
        assertFalse(
            "and 60 s before has none either",
            correlator.isPlaybackBleed(mic, ref, micPositionMs = -60_000L),
        )
    }

    @Test
    fun `an in-span stale region is not bleed even though its offset matches the delay window`() {
        // @spec's separate case: a reference LONGER than the mic position, so the position is valid
        // and the delay interval overlaps the reference -- but the matching audio sits far outside the
        // interval. This is the case the older fixture's name and comment describe, and it is
        // deliberately NOT a rewrite of that fixture: the 16_000-sample one stays as it is, asserting
        // the out-of-span behaviour.
        val burst = tone(1600, 300.0)
        val other = tone(160_000 - 1600, 900.0, phase = 0.7)
        val samples = burst + other // 10 s of reference
        val mic = FloatArray(1600) { i -> 0.3f * burst[i] }

        val reference = PlaybackReference(samples, rate, startPositionMs = 0L)
        val correlator = PlaybackCrossCorrelator()

        // The burst sits at offset 0..1600; a mic position of 5_000 ms admits offsets around
        // 4_500..4_950, so the matching region is far outside the admissible interval.
        assertFalse(
            "audio that played 5 s ago is not bleed now, though a full-window search would match it",
            correlator.isPlaybackBleed(mic, reference, micPositionMs = 5_000L),
        )
        // And the same reference DOES report bleed when the position puts the burst inside the window.
        assertTrue(
            "the same waveform inside the delay window is bleed",
            correlator.isPlaybackBleed(mic, reference, micPositionMs = 275L),
        )
    }

    @Test
    fun `both overlap edges are pinned at equality and one millisecond outside it`() {
        // @reviewer's strengthening: BOTH clamp boundaries carry matching audio, so the mutation has a
        // failing signal at either end rather than only where the bleed happened to sit. Every value
        // below is computed from the reference's real geometry, not chosen:
        //
        //   reference 160_000 samples = 10_000 ms; last usable offset = 158_400 (= 9_900 ms)
        //   interval for a mic at p ms = offsets [(p-500), (p-50)] ms
        //   NEAR edge: the interval reaches offset 0 when p = 50 ms; below 50 ms it is entirely
        //              before the reference
        //   FAR edge:  the interval's LOWER edge lands exactly on the last usable offset 158_400 when
        //              (p - 500) * 16 == 158_400, i.e. p == 10_400 ms; one millisecond later the whole
        //              interval is past it.
        val rate16 = rate
        val nearSamples = FloatArray(160_000) { 0f }.also {
            tone(1_600, 300.0).copyInto(it, 0) // matching audio AT offset 0
        }
        val farSamples = FloatArray(160_000) { 0f }.also {
            tone(1_600, 300.0).copyInto(it, 158_400) // matching audio AT the last usable offset
        }
        val mic = FloatArray(1_600) { i -> 0.3f * tone(1_600, 300.0)[i] }
        val correlator = PlaybackCrossCorrelator()

        val nearRef = PlaybackReference(nearSamples, rate16, startPositionMs = 0L)
        val farRef = PlaybackReference(farSamples, rate16, startPositionMs = 0L)

        // Controls: each boundary's audio is genuinely findable when the interval admits it.
        assertTrue(
            "the near-edge audio is found when the interval reaches offset 0",
            correlator.isPlaybackBleed(mic, nearRef, micPositionMs = 550L),
        )
        assertTrue(
            "the far-edge audio is found exactly at its boundary (p = 10_400 ms, where the interval's " +
                "lower edge lands on the last usable offset)",
            correlator.isPlaybackBleed(mic, farRef, micPositionMs = 10_400L),
        )

        // EQUALITY at each edge: the interval still overlaps, so it is searched -- and because the
        // clamp target holds matching audio, a clamping implementation would FIND it here.
        assertFalse(
            "at the near edge (p = 50 ms) the interval reaches offset 0 but the timing still admits " +
                "it, so the boundary audio is legitimately found",
            !correlator.isPlaybackBleed(mic, nearRef, micPositionMs = 50L),
        )

        // ONE MILLISECOND OUTSIDE each edge: the interval no longer overlaps the reference, so the
        // trusted timing excludes it and the answer must be no bleed -- NOT a clamp onto the boundary
        // offset, which would find the audio sitting exactly there.
        assertFalse(
            "1 ms inside the near boundary (p = 49 ms) the interval is before the reference: no bleed " +
                "even though matching audio sits at offset 0",
            correlator.isPlaybackBleed(mic, nearRef, micPositionMs = 49L),
        )
        assertFalse(
            "1 ms past the far boundary (p = 10_401 ms) the interval is past the last usable offset: " +
                "no bleed even though matching audio sits there",
            correlator.isPlaybackBleed(mic, farRef, micPositionMs = 10_401L),
        )
    }

// REVIEWER 4356625095 BOUNDARY: a real VAD segment is at least 41 x 1024 = 41 984 samples while
// the recorder's reference buffer is 2 s = 32 000 samples, so a reference-shorter-than-segment
// bail-out would skip correlation for EVERY real segment and let the device's own answer audio
// be transcribed and executed. The analysis window must be BOUNDED to the reference length, not
// used to decline the comparison.
    class PlaybackCrossCorrelatorLongSegmentTest {

        private val rate = 16_000

        private fun tone(n: Int, freq: Double) = FloatArray(n) { i -> (0.6 * sin(2 * PI * freq * i / rate)).toFloat() }

        @Test
        fun `a minimum-length VAD segment is still checked for bleed against a full 2 s reference`() {
            // DISTINCTIVE, NONPERIODIC echo INSIDE the searched offsets, and placed entirely in the
            // segment's LAST 6 000 samples so the PREFIX window (first 24 000) contains NONE of it
            // (Claude 4366645238): a pure tone self-correlates (~0.72) at any aligned offset, and an
            // echo overlapping the prefix window lets a prefix-revert mutant still find it. The
            // unpositioned bounded search covers offsets 0..7 200 (24 000-sample analysis tail in a
            // 32 000 reference); the echo sits at reference offset 0 (the 500 ms delay edge,
            // admissible). A prefix-revert mutant analyzes pure silence and must fail.
            val burst = FloatArray(6_000) { i ->
                var x = (i * 2_654_435_761L + 1_234_567_891L) % 4_294_967_296L
                x = x xor (x shr 13)
                (((x shr 16) and 0xFF) - 128) / 128f
            }
            // The burst itself starts at REFERENCE OFFSET 18 000; the matching ANALYSIS WINDOW
            // starts at search offset 0 (implied delay 500 ms, the admissible edge): the tail's
            // analysis[j] = 0.3f * ref[j] for every j, because tail index 18 000 + j hits the
            // burst copy that begins at reference 18 000.
            val reference = FloatArray(32_000)
            burst.copyInto(reference, 18_000)
            val segment = FloatArray(41_984)
            for (j in 0 until 6_000) segment[35_984 + j] = 0.3f * burst[j]
            assertTrue(
                "segment longer than the reference must still be bleed-checked",
                PlaybackCrossCorrelator().isPlaybackBleed(segment, PlaybackReference(reference, rate, null)),
            )
        }

        @Test
        fun `a long segment of pure speech with no bleed is still accepted`() {
            val reference = tone(32_000, 300.0)
            val segment = tone(41_984, 97.0)
            assertFalse(
                PlaybackCrossCorrelator().isPlaybackBleed(segment, PlaybackReference(reference, rate, null)),
            )
        }
    }

// REVIEW 6381507658 BLOCKING: a real VAD segment is LONGER than the 2 s reference, and the
// recorder's reference holds the NEWEST 2 s. The bounded window must therefore be the segment's
// TAIL with the position anchor SHIFTED to the tail's first sample - the old prefix analysis
// correlated the segment's OLDEST samples, whose position window lies BEFORE the reference
// entirely, so echo rejection was inert for every real segment and the device's own answer
// could be transcribed and executed.
//
// Discrimination: the segment starts at player position -900 ms (before the reference's window);
// its tail starts at 600 ms. The correct tail+shift analysis searches delays 50..500 ms from the
// tail anchor and finds the burst; the prefix analysis anchors at -900 ms, whose ENTIRE delay
// window lies before offset zero, so it must return not-bleed - a prefix mutant cannot pass by
// content alone.
    @Test
    fun `long segment - bleed in the NEWEST window is found via the tail with a shifted anchor`() {
        val segLen = 48_000 // 3 s - longer than the 2 s reference, like a real VAD segment
        val refLen = 32_000 // 2 s
        // A DISTINCTIVE waveform, not a pure tone: a pure tone self-correlates at any aligned
        // offset, so the control would pass even if the tail search missed the burst entirely
        // (Claude review 40080514049). PRBS-like noise only correlates when sample-aligned.
        val burst = FloatArray(24_000) { i ->
            var x = (i * 2_654_435_761L + 1_234_567_891L) % 4_294_967_296L
            x = x xor (x shr 13)
            (((x shr 16) and 0xFF) - 128) / 128f
        }
        val samples = FloatArray(refLen)
        burst.copyInto(samples, 5_600)

        // Segment first sample at player -900 ms; tail (last 24 000 samples) first sample at
        // -900 + 1500 = 600 ms. A 250 ms acoustic delay puts the tail's match at reference
        // offset (600 - 250) * 16 = 5 600.
        val mic = FloatArray(segLen) { i -> 0.01f * sin(i * 0.0007f) }
        val tailStart = segLen - 24_000 // 24 000
        for (i in 0 until 24_000) mic[tailStart + i] = 0.3f * burst[i]

        val reference = PlaybackReference(samples = samples, sampleRateHz = rate, startPositionMs = 0L)
        assertTrue(
            "the device's own answer bleeding into the segment's newest window must be rejected as bleed",
            PlaybackCrossCorrelator().isPlaybackBleed(mic, reference, -900L),
        )
    }
}
