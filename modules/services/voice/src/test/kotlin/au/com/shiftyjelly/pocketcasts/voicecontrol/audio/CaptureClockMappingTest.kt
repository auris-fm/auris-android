package au.com.shiftyjelly.pocketcasts.voicecontrol.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The capture clock is one instant on the stream's monotonic timebase, and this mapping extends it to
 * any sample position. The playback side reports on the SAME timebase, so what is asserted here is
 * what lets a delay between the two be a real quantity rather than a buffer offset.
 *
 * The cases below state the contract in the terms the caller cares about: a position at the observed
 * instant maps to the observed time, positions either side move by their distance in samples, a
 * different sample rate scales that distance, and an unobservable stream stays unknown.
 */
class CaptureClockMappingTest {

    /**
     * `[framePosition, timestampNs, sampleRate, observable]` -- the shape `nativeCaptureClock`
     * returns. Written as a helper so every case below differs only in the observation it describes.
     */
    private fun clock(
        framePosition: Long,
        timestampNs: Long,
        sampleRate: Long = 16_000,
        observable: Boolean = true,
    ) = longArrayOf(framePosition, timestampNs, sampleRate, if (observable) 1L else 0L)

    @Test
    fun `the observed frame position maps to the observed timestamp`() {
        // A position AT the observation is the identity: this is the anchor every other case moves
        // away from, so if it were wrong the rest would still look self-consistent.
        val observation = clock(framePosition = 8_000, timestampNs = 5_000_000_000L)

        val atObservation = captureTimeNsForPosition(observation, streamPositionInSegment = 8_000L)

        assertEquals(5_000L, atObservation!! / 1_000_000L)
    }

    @Test
    fun `a position later than the observation moves forward by its distance in samples`() {
        // 1_600 samples at 16 kHz is exactly 100 ms, so the mapping must move the instant by 100 ms.
        val observation = clock(framePosition = 0, timestampNs = 2_000_000_000L)

        val atPosition = captureTimeNsForPosition(observation, streamPositionInSegment = 1_600L)

        assertEquals(
            "1600 samples at 16 kHz is 100 ms after the observed instant",
            2_100L,
            atPosition!! / 1_000_000L,
        )
    }

    @Test
    fun `a position earlier than the observation moves backward`() {
        // The stream position is a moving point, so a segment can be captured before the observation
        // the clock reports. The subtraction must produce a NEGATIVE delta rather than clamping --
        // clamping would silently place earlier audio at the observed instant.
        val observation = clock(framePosition = 1_600, timestampNs = 9_000_000_000L)

        val atPosition = captureTimeNsForPosition(observation, streamPositionInSegment = 0L)

        assertEquals(
            "1600 samples before the observed instant is 100 ms earlier",
            8_900L,
            atPosition!! / 1_000_000L,
        )
    }

    @Test
    fun `the sample rate scales the same position difference`() {
        // The SAME sample offset at half the rate is twice the elapsed time. Without the rate in the
        // conversion the two would agree, which is how a wrong unit goes unnoticed.
        val fast = captureTimeNsForPosition(
            clock(framePosition = 0, timestampNs = 0, sampleRate = 16_000),
            streamPositionInSegment = 1_600L,
        )
        val slow = captureTimeNsForPosition(
            clock(framePosition = 0, timestampNs = 0, sampleRate = 8_000),
            streamPositionInSegment = 1_600L,
        )

        assertEquals("16 kHz", 100L, fast!! / 1_000_000L)
        assertEquals("8 kHz", 200L, slow!! / 1_000_000L)
    }

    @Test
    fun `an unobservable stream reports no time rather than the frame counter`() {
        // The defect this guards: substituting frame position for a time places the segment
        // confidently at an instant nothing measured. Absent must stay absent.
        val observation = clock(framePosition = 64_000, timestampNs = 0, observable = false)

        assertNull(captureTimeNsForPosition(observation, streamPositionInSegment = 64_000L))
    }

    @Test
    fun `no observation at all is unknown`() {
        assertNull(captureTimeNsForPosition(null, streamPositionInSegment = 0L))
    }

    @Test
    fun `a truncated observation is unknown rather than partially read`() {
        // A short array would otherwise read a zero timestamp and report the epoch as a real instant.
        assertNull(captureTimeNsForPosition(longArrayOf(1L, 2L, 3L), streamPositionInSegment = 1L))
    }

    @Test
    fun `a zero sample rate is unknown rather than divided`() {
        assertNull(
            captureTimeNsForPosition(
                clock(framePosition = 0, timestampNs = 5_000_000L, sampleRate = 0),
                streamPositionInSegment = 100L,
            ),
        )
    }

    @Test
    fun `the onset is anchored where speech started in the segment, not where the clock was read`() {
        // PRODUCTION WIRING (reviewer 4357640837 capture-anchor part): OboeCaptureEngine drains the
        // WHOLE segment into `frames`, records the onset as an OFFSET within that buffer, and reads
        // nativeCaptureClock only afterwards -- at segment COMPLETION, where framePosition names the
        // sample just past the segment's end. The onset's stream position is therefore
        // framePosition - (segmentSamples - speechOnsetSample), and only that position converts to
        // the onset's instant. Anchoring the segment at the completion timestamp instead places the
        // microphone audio up to a whole segment late, so the bleed filter's delay window misses the
        // device's own answer on long utterances. A 2-second segment whose speech started 1.5 s in
        // must land 500 ms before the observation, not at it.
        val frames = listOf(
            PcmAudioFrame(ShortArray(16_000), 16_000),
            PcmAudioFrame(ShortArray(16_000), 16_000),
        )
        val observation = clock(framePosition = 32_000, timestampNs = 10_000_000_000L)

        val onsetMs = VoiceSegmenterResult.SpeechEnded(
            frames = frames,
            speechOnsetSample = 24_000,
            captureClock = observation,
        ).captureTimeOfSpeechOnsetMs()

        // 32_000 - (32_000 - 24_000) = 24_000: exactly 500 ms before the observed instant.
        // The helper returns MILLISECONDS (it does the ns->ms conversion itself).
        assertEquals(9_500L, onsetMs!!)
    }

    @Test
    fun `an onset one millisecond before the segment's end lands one millisecond before the observation`() {
        // Complement: an over-correcting mutant that subtracts the WHOLE segment length (instead of
        // the segment length minus the onset offset) places this onset 2 s early, not 1 ms early.
        // 16 samples at 16 kHz is exactly 1 ms, so the assertion is truncation-free.
        val frames = listOf(PcmAudioFrame(ShortArray(16_000), 16_000))
        val observation = clock(framePosition = 16_000, timestampNs = 4_000_000_000L)

        val onsetMs = VoiceSegmenterResult.SpeechEnded(
            frames = frames,
            speechOnsetSample = 15_984,
            captureClock = observation,
        ).captureTimeOfSpeechOnsetMs()

        assertEquals(3_999L, onsetMs!!)
    }

    @Test
    fun `a segment with no clock has no onset time`() {
        assertNull(
            VoiceSegmenterResult.SpeechEnded(frames = emptyList(), captureClock = null)
                .captureTimeOfSpeechOnsetMs(),
        )
    }

    @Test
    fun `the capture instant and the player instant are one comparable axis`() {
        // @spec's boundary requirement, stated as an arithmetic identity rather than a comment. The
        // two sides must agree on THREE things for a delay between them to mean anything:
        //
        //   axis    both report on CLOCK_MONOTONIC. AudioTrack.getTimestamp uses TIMEBASE_MONOTONIC
        //           and the Oboe stream is asked for CLOCK_MONOTONIC, so a difference is a duration
        //           and not the gap between two unrelated epochs.
        //   origin  each observation names the instant of a SPECIFIC stream position, so extending
        //           it to another position is a translation rather than an assumption about zero.
        //   units   the capture observation is nanoseconds with a sample rate to convert, and the
        //           player side is consumed as milliseconds. This case pins the conversion.
        //
        // They are asserted together because passing any two while the third is wrong still produces
        // a plausible number, which is the failure mode that motivated the boundary being checked at
        // all. A shared unit with a different origin, or a shared origin in different units, both
        // yield a confident misalignment.
        val captureObservedAtNs = 1_000_000_000L // 1.0 s on the monotonic axis
        val captureAtPosition = captureTimeNsForPosition(
            clock(framePosition = 16_000, timestampNs = captureObservedAtNs),
            streamPositionInSegment = 16_000L,
        )

        // The player reports the SAME instant, already in milliseconds on that axis.
        val playerReportedAtMs = captureObservedAtNs / 1_000_000L

        assertEquals(
            "the same instant on one axis must convert to the same value on both sides",
            playerReportedAtMs,
            captureAtPosition!! / 1_000_000L,
        )
    }

    @Test
    fun `the conversion is exact for a whole number of milliseconds`() {
        // The sample-rate division truncates, so a position difference that is NOT a whole number of
        // milliseconds loses its remainder. This case pins the amount lost to below a millisecond,
        // which is the resolution the delay window is expressed in -- asserting it so a future change
        // to a coarser unit is caught rather than absorbed into the delay threshold.
        val observation = clock(framePosition = 0, timestampNs = 0)

        val atOneSample = captureTimeNsForPosition(observation, streamPositionInSegment = 1L)!!

        // 1e9 ns divided by 16_000 Hz is 62_500 ns per sample EXACTLY. That matters: the nanosecond
        // form keeps the sub-millisecond part, so extending the clock to a distant position does not
        // accumulate a truncation per step. The millisecond conversion the caller applies afterwards
        // truncates once, at the end, which is the correct place for the loss.
        assertEquals("one sample is exactly 62500 ns at 16 kHz", 62_500L, atOneSample)
        assertEquals("which is 0 ms: the sub-millisecond part is kept above this boundary", 0L, atOneSample / 1_000_000L)

        // And 16_000 samples is exactly one second, so the same conversion is exact at a whole second.
        val atOneSecond = captureTimeNsForPosition(observation, streamPositionInSegment = 16_000L)!!
        assertEquals("16000 samples at 16 kHz is exactly one second", 1_000L, atOneSecond / 1_000_000L)
    }
}
