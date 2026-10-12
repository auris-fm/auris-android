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
        // Place the burst where the offset scan looks for it.
        val offset = 1600
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
        // The bleed here is placed INSIDE the default delay window (offset 2000 of 800..8000), so a
        // correlator that fell back to the full window would FIND it. That is what makes this case
        // able to distinguish the two policies: the positioned call must say no while the no-position
        // control says yes.
        val (samples, mic) = referenceWithBleedAt(burstStart = 2_000)
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
    fun `the candidate interval is empty only when it leaves the reference, and a partial overlap still searches`() {
        // @spec's boundary requirement, computed in the function's OWN units rather than guessed. One
        // millisecond at 16 kHz is 16 samples, so a "one frame" boundary is not representable in
        // integer milliseconds; the representable boundary is the OVERLAP boundary.
        //
        // Geometry of this fixture: reference 0..10_000 ms (last usable offset 158_400), bleed at
        // offset 2_000 = 125 ms, and the interval for a mic at p ms is offsets [p - 500, p - 50] ms.
        // The bleed is admitted exactly when 175 <= p <= 625 ms, because
        //     p - 500 <= 125 <= p - 50.
        val (samples, mic) = referenceWithBleedAt(burstStart = 2_000)
        val ref = PlaybackReference(samples, rate, startPositionMs = 0L)
        val correlator = PlaybackCrossCorrelator()

        // Unavailable alignment: no position, so the default window is searched and finds the bleed.
        assertTrue(
            "no position leaves the default window in play",
            correlator.isPlaybackBleed(mic, ref, micPositionMs = null),
        )

        // The LOWER boundary of admission, computed above: at 175 ms the interval first covers the
        // bleed. Below it the interval no longer covers the bleed -- and the measurement there is
        // NOT asserted as "no bleed", because a partially clamped interval (offsets -6400..800 ->
        // 0..800) correlates against a near-silent reference region and can score above the
        // threshold. That artefact belongs to the correlation function, not to the interval rule,
        // and asserting it here would enshrine it; it is reported instead.
        assertTrue(
            "at the computed lower boundary (175 ms) the interval first covers the bleed",
            correlator.isPlaybackBleed(mic, ref, micPositionMs = 175L),
        )

        // An interval that overlaps the reference only PARTIALLY is still searched: at p = 500 ms the
        // interval is offsets 0..7_200, which reaches past the reference's own start, and it still
        // admits the bleed. This is the case a naive "clamp or empty" rule gets wrong.
        assertTrue(
            "a partially overlapping interval is searched and finds the bleed",
            correlator.isPlaybackBleed(mic, ref, micPositionMs = 500L),
        )

        // THE DISCRIMINATOR between Empty and clamping, which the fixture above cannot show: a
        // clamped interval searches the boundary offset it landed on, while Empty refuses. So the
        // bleed is placed AT that boundary offset. If the code clamped, the search would run at
        // offset 0 and FIND the bleed; because the timing is valid and excludes the reference, it
        // must refuse instead.
        val (boundarySamples, boundaryMic) = referenceWithBleedAt(burstStart = 0)
        val boundaryRef = PlaybackReference(boundarySamples, rate, startPositionMs = 0L)
        assertTrue(
            "the boundary-offset bleed is found when the interval admits it",
            correlator.isPlaybackBleed(boundaryMic, boundaryRef, micPositionMs = 500L),
        )
        assertFalse(
            "and a VALID EMPTY interval must refuse it rather than clamp onto offset 0 and find it",
            correlator.isPlaybackBleed(boundaryMic, boundaryRef, micPositionMs = 0L),
        )

        // The same rule at the far end: an interval entirely past the last usable offset.
        assertFalse(
            "an interval entirely past the reference's last usable offset admits nothing",
            correlator.isPlaybackBleed(mic, ref, micPositionMs = 10_550L),
        )
        assertFalse(
            "as does one entirely past its last usable offset",
            correlator.isPlaybackBleed(mic, ref, micPositionMs = 10_550L),
        )
    }
}
