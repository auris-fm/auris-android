package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import kotlin.math.PI
import kotlin.math.sin
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
}
