package au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M1: score the wake word while the user is still speaking, without changing what is scored.
 *
 * The native detector only considers classifier windows whose endpoint lies within
 * [EarlyWakeScore.CUTOFF_SAMPLES] of speech onset, so a segment truncated at that boundary
 * produces exactly the same score as the finished segment would. These tests pin that
 * equivalence at the policy boundary, where the decision to score early is made.
 */
class EarlyWakeScoreTest {

    // 16 kHz mono, one sample per unit time. Onset is 0 unless stated.
    private val sampleRate = 16_000

    private fun samples(seconds: Double): Int = (seconds * sampleRate).toInt()

    @Test
    fun `the cutoff is the boundary the detector scores up to`() {
        // 2.0s from onset: the native onset cutoff is 32000 samples at 16 kHz. Everything the
        // detector can score ends inside this, so a window cut here loses nothing.
        assertEquals(32_000, EarlyWakeScore.CUTOFF_SAMPLES)
        assertEquals(2_000L, EarlyWakeScore.cutoffMillis(sampleRate))
    }

    @Test
    fun `an utterance shorter than the cutoff is not scored early`() {
        // There is no audio yet at the boundary: scoring now would score a partial window and
        // then have to score again when the rest arrives. Short utterances run at end of speech.
        val request = EarlyWakeScore.request(
            speechOnsetSample = 0,
            availableSamples = samples(1.2),
            sampleRateHz = sampleRate,
            isGraceActive = false,
        )
        assertNull("nothing to score before the boundary exists", request)
    }

    @Test
    fun `an utterance that reaches the cutoff is scored early, once`() {
        val request = EarlyWakeScore.request(
            speechOnsetSample = 0,
            availableSamples = samples(2.5),
            sampleRateHz = sampleRate,
            isGraceActive = false,
        )
        assertTrue("the boundary has passed, so the window is complete", request != null)
        // Exactly the cutoff length from onset: the same samples the finished segment scores.
        assertEquals(EarlyWakeScore.CUTOFF_SAMPLES, request!!.lengthSamples)
        assertEquals(0, request.startSample)
    }

    @Test
    fun `the early window starts at speech onset, not at the segment start`() {
        // Pre-roll before onset is not part of what the detector scores: onset-relative windows
        // are what make the early and finished scores comparable.
        val onset = 1_600
        val request = EarlyWakeScore.request(
            speechOnsetSample = onset,
            availableSamples = onset + samples(3.0),
            sampleRateHz = sampleRate,
            isGraceActive = false,
        )
        assertEquals(onset, request!!.startSample)
        assertEquals(EarlyWakeScore.CUTOFF_SAMPLES, request.lengthSamples)
    }

    @Test
    fun `outside grace asr waits for a positive early result`() {
        // The point of scoring early: when it is positive, ASR may start before the segment
        // closes instead of after. Outside grace a negative result still skips ASR entirely.
        val positive = EarlyWakeScore.request(0, samples(3.0), sampleRate, isGraceActive = false)!!
        assertTrue("a positive early score lets ASR start", positive.letsAsrStartEarly(wakePositive = true))
        assertFalse("a negative early score does not", positive.letsAsrStartEarly(wakePositive = false))
    }

    @Test
    fun `during grace asr never waits for the score`() {
        // Grace means the user is already talking to Auris: waiting for the wake word would add
        // latency to every follow-up, so the score runs alongside ASR rather than gating it.
        val inGrace = EarlyWakeScore.request(0, samples(3.0), sampleRate, isGraceActive = true)!!
        assertTrue("grace starts ASR regardless of the score", inGrace.letsAsrStartEarly(wakePositive = false))
        assertTrue(inGrace.letsAsrStartEarly(wakePositive = true))
    }

    @Test
    fun `an unknown sample rate does not score early`() {
        // The cutoff is defined in samples at a known rate; an unknown rate cannot be placed at
        // the boundary, and guessing would score a window the detector then scores differently.
        assertNull(EarlyWakeScore.request(0, samples(3.0), 0, isGraceActive = false))
        assertNull(EarlyWakeScore.request(0, samples(3.0), -1, isGraceActive = false))
    }
}
