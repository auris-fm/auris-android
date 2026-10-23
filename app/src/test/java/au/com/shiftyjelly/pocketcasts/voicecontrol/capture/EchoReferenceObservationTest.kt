package au.com.shiftyjelly.pocketcasts.voicecontrol.capture

import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintPcmTap
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.PcmAudioFrame
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceSegmenterResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackCrossCorrelator
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoReferenceObservationTest {
    @Test
    fun `a delayed endpoint retains the reference observed during that speech interval`() = runTest {
        var nowMs = 0L
        val recorder = recorder { nowMs }
        val admission = recorder.openAdmission(1L)
        val source = waveform(32_000)
        val segment = segment(source.copyOfRange(24_000, 28_800))
        val observations = mutableListOf<EchoReferenceObservation>()

        flow {
            emit(VoiceSegmenterResult.SpeechStarted)
            recorder.write(source, admission = admission)
            assertTrue("the recorder must first contain real signal", recorder.reference().samples.any { it != 0f })
            delay(25)
            recorder.retireProducer(admission)
            nowMs = 200L
            delay(25) // A later, partial retired tail must not replace complete live coverage.
            nowMs = 4_000L
            delay(25)
            emit(segment)
        }.collectEchoObservations(recorder::reference, observations::add)

        val delayedRead = recorder.reference()
        assertTrue("the original length-only precondition still passes", delayedRead.samples.isNotEmpty())
        assertTrue("production retirement must still remove the expired signal", delayedRead.samples.all { it == 0f })
        val paired = observations.single()
        assertNull("the intended interval needs its live reference, not the later zeroed array", paired.unusableReason())
        assertArrayEquals(source, paired.reference.samples, 0f)
        assertEquals("native onset/end exclude the segment's padding and drain", 4_800, paired.speech.size)
        assertArrayEquals(source.copyOfRange(24_000, 28_800), paired.speech, 0.0001f)
        assertTrue(PlaybackCrossCorrelator().isPlaybackBleed(paired.speech, paired.reference))
    }

    @Test
    fun `a nonempty delayed reference with zero signal is not usable coverage`() {
        var nowMs = 0L
        val recorder = recorder { nowMs }
        val admission = recorder.openAdmission(1L)
        val source = waveform(16_000)
        recorder.write(source, admission = admission)
        assertTrue(recorder.reference().samples.any { it != 0f })
        recorder.retireProducer(admission)
        nowMs = 4_000L

        val observation = EchoReferenceObservation.capture(segment(source.copyOfRange(2_000, 4_000)), recorder.reference())

        assertTrue(observation.reference.samples.isNotEmpty())
        assertNotNull(observation.unusableReason())
        assertFalse(PlaybackCrossCorrelator().isPlaybackBleed(observation.speech, observation.reference))
    }

    @Test
    fun `reference signal must cover the reported speech and a delay candidate`() {
        val speech = waveform(2_000)
        val sparse = FloatArray(8_000).also { waveform(500).copyInto(it, 1_000) }
        val short = EchoReferenceObservation.capture(segment(speech), PlaybackReference(sparse, 16_000, null))
        val noDelay = EchoReferenceObservation.capture(segment(speech), PlaybackReference(speech, 16_000, null))

        assertNotNull(short.unusableReason())
        assertNotNull(noDelay.unusableReason())
    }

    @Test
    fun `unknown native speech bounds and mismatched rates cannot become measurements`() {
        val reference = PlaybackReference(waveform(8_000), 16_000, null)
        val unknown = VoiceSegmenterResult.SpeechEnded(listOf(PcmAudioFrame(shortArrayOf(1, 2), 16_000)))
        val wrongRate = VoiceSegmenterResult.SpeechEnded(listOf(PcmAudioFrame(shortArrayOf(1, 2), 48_000)), 0, 2)

        assertNotNull(EchoReferenceObservation.capture(unknown, reference).unusableReason())
        assertNotNull(EchoReferenceObservation.capture(wrongRate, reference).unusableReason())
    }

    @Test
    fun `the saved reference owns its samples`() {
        val source = waveform(8_000)
        val observation = EchoReferenceObservation.capture(segment(source.copyOfRange(1_000, 3_000)), PlaybackReference(source, 16_000, null))
        source.fill(0f)

        assertTrue(observation.reference.samples.any { it != 0f })
        assertNull(observation.unusableReason())
    }

    @Test
    fun `an endpoint without an onset cannot borrow a current reference`() = runTest {
        val source = waveform(8_000)
        val observations = mutableListOf<EchoReferenceObservation>()

        flowOf(segment(source.copyOfRange(1_000, 3_000)))
            .collectEchoObservations({ PlaybackReference(source, 16_000, null) }, observations::add)

        assertNotNull(observations.single().unusableReason())
    }

    @Test
    fun `a successor segment cannot inherit the first segment's reference`() = runTest {
        val source = waveform(8_000)
        var reference = PlaybackReference(source, 16_000, null)
        val observations = mutableListOf<EchoReferenceObservation>()
        val speech = segment(source.copyOfRange(1_000, 3_000))

        flow {
            emit(VoiceSegmenterResult.SpeechStarted)
            emit(speech)
            reference = PlaybackReference(FloatArray(8_000), 16_000, null)
            emit(VoiceSegmenterResult.SpeechStarted)
            delay(25)
            emit(speech)
        }.collectEchoObservations({ reference }, observations::add)

        assertNull(observations[0].unusableReason())
        assertNotNull(observations[1].unusableReason())
    }

    private fun recorder(now: () -> Long) = PlaybackBufferRecorder(FingerprintPcmTap()).also {
        // The recorder's existing deterministic clock seam is internal to :voice. Reflection keeps
        // this app-level, test-only control from adding a production API just to cross that boundary.
        PlaybackBufferRecorder::class.java.getDeclaredField("monotonicNowMs").apply { isAccessible = true }.set(it, now)
    }

    private fun segment(speech: FloatArray): VoiceSegmenterResult.SpeechEnded {
        val padding = 160
        val samples = ShortArray(speech.size + 2 * padding)
        speech.forEachIndexed { index, value -> samples[index + padding] = (value * 32767f).toInt().toShort() }
        return VoiceSegmenterResult.SpeechEnded(
            frames = listOf(PcmAudioFrame(samples, 16_000)),
            speechOnsetSample = padding,
            speechEndSample = padding + speech.size,
        )
    }

    private fun waveform(size: Int): FloatArray {
        val random = java.util.Random(572L)
        return FloatArray(size) { random.nextFloat() - 0.5f }
    }
}
