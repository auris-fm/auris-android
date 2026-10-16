package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintPcmTap
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AndroidAudioRouteMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRouteMonitor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two-sided acceptance for the echo reference, driven through the production path: the real
 * [PlaybackBufferRecorder] filled from the real [FingerprintPcmTap], read by the real
 * [UtteranceFilter] over the real correlator.
 *
 * This is the test that fails while the reference has no writer: with an empty buffer the correlator
 * short-circuits (`playbackBuffer.size < micAudio.size`) and every utterance passes, so an echo-only
 * segment is accepted. Filling the buffer from the tap is what makes the first case pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EchoReferenceFilterTest {

    private val sampleRate = 16_000

    /** A deterministic non-silent waveform; different phases give decorrelated signals. */
    private fun tone(length: Int, freqHz: Double, phase: Double = 0.0): FloatArray = FloatArray(length) { i -> (0.6 * sin(2 * PI * freqHz * i / sampleRate + phase)).toFloat() }

    private fun buildFilter(): Pair<PlaybackBufferRecorder, UtteranceFilter> {
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        // Route is irrelevant to the bleed check as long as it is not a headset; a no-headset route
        // keeps the correlator in play.
        val routeMonitor = AndroidAudioRouteMonitor(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            gracePeriodSignal = GracePeriodSignal(),
        )
        val filter = UtteranceFilter(PlaybackCrossCorrelator(), routeMonitor)
        assertTrue("precondition: route is not a headset", routeMonitor.route.value !is AudioRoute.Headset)
        return recorder to filter
    }

    // END TO END THROUGH THE FILTER: after the clock passes the retired tail's expiry, speech that
    // resembles the stopped answer is user speech again. Same harness as the echo-rejection controls.

    @Test
    fun `speech resembling an expired stopped answer is accepted by the filter`() {
        var nowMs = 2_000_000L
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        recorder.monotonicNowMs = { nowMs }
        val routeMonitor = au.com.shiftyjelly.pocketcasts.voicecontrol.route.AndroidAudioRouteMonitor(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            gracePeriodSignal = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal(),
        )
        val filter = UtteranceFilter(PlaybackCrossCorrelator(), routeMonitor)
        assertTrue(routeMonitor.route.value !is au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute.Headset)

        val answer = FloatArray(8_000) { i -> (0.6 * sin(2 * PI * 220.0 * i / 16_000)).toFloat() }
        recorder.write(answer, admission = admFor(recorder, 41L))
        val echoLikeSpeech = FloatArray(1_600) { i -> 0.3f * answer[i] }
        assertFalse(
            "precondition: while the answer is live its echo is bleed",
            filter.shouldProcess(echoLikeSpeech, hasSpeakerId = false, speakerIndex = 0, playbackBuffer = recorder.snapshot()),
        )

        recorder.retireProducer(admFor(recorder, 41L))
        nowMs += PlaybackBufferRecorder.DELAY_TAIL_SAMPLES * 1_000 / PlaybackBufferRecorder.SAMPLE_RATE + 1
        assertTrue(
            "after expiry, speech resembling the STOPPED answer must be accepted, not suppressed forever",
            filter.shouldProcess(echoLikeSpeech, hasSpeakerId = false, speakerIndex = 0, playbackBuffer = recorder.snapshot()),
        )
    }

    // PAUSE EXPIRY THROUGH THE FILTER (@reviewer acceptance control): the same two-sided decision as
    // the stopped-answer control, but the retirement comes from the SINK PAUSE EVENT -- the real tap
    // drives the real recorder through its production collector, tap.onSinkPaused() ends the audible
    // reference, and after clock-only expiry speech resembling the PAUSED episode is accepted by the
    // production correlating filter instead of being suppressed forever.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `speech resembling a paused episode is accepted by the filter after the pause-expired tail`() = runTest {
        var nowMs = 4_000_000L
        val tap = FingerprintPcmTap()
        val recorder = PlaybackBufferRecorder(tap)
        recorder.monotonicNowMs = { nowMs }
        val routeMonitor = AndroidAudioRouteMonitor(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            gracePeriodSignal = GracePeriodSignal(),
        )
        val filter = UtteranceFilter(PlaybackCrossCorrelator(), routeMonitor)
        assertTrue("precondition: route is not a headset", routeMonitor.route.value !is AudioRoute.Headset)
        val job = recorder.start(this)
        runCurrent()

        // One second of episode audio through the real tap (sine at 220 Hz, amplitude ~0.6).
        val episode = FloatArray(8_000) { i -> (0.6 * sin(2 * PI * 220.0 * i / sampleRate)).toFloat() }
        var anchorUs = 0L
        // HEAP buffer, as the production decoder path delivers: a DIRECT ByteBuffer is silently
        // dropped by the tap (observed under diagnosis: snapshot stayed empty), so the control must
        // exercise the array-backed shape production actually sends.
        val pcm = ByteBuffer.allocate(episode.size * 2).order(ByteOrder.nativeOrder())
        episode.forEach { pcm.putShort((it * 32767).toInt().toShort()) }
        tap.onSinkBuffer(presentationTimeUs = 0)
        tap.onPcm(ByteBuffer.wrap(pcm.array()), AudioProcessor.AudioFormat(16_000, 1, C.ENCODING_PCM_16BIT))
        runCurrent()
        val echoLikeSpeech = FloatArray(1_600) { i -> 0.3f * episode[i] }
        assertFalse(
            "precondition: while the episode plays its echo is bleed",
            filter.shouldProcess(echoLikeSpeech, hasSpeakerId = false, speakerIndex = 0, playbackBuffer = recorder.snapshot()),
        )

        // PAUSE: the sink pause event retires the audible reference. No further writes.
        tap.onSinkPaused()
        nowMs += PlaybackBufferRecorder.DELAY_TAIL_SAMPLES * 1_000 / PlaybackBufferRecorder.SAMPLE_RATE + 1
        assertTrue(
            "after the PAUSE-expired tail, speech resembling the paused episode must be accepted",
            filter.shouldProcess(echoLikeSpeech, hasSpeakerId = false, speakerIndex = 0, playbackBuffer = recorder.snapshot()),
        )
        job.cancel()
    }

    @Test
    fun `echo-only input is rejected once the reference holds the submitted playback`() {
        val (recorder, filter) = buildFilter()
        val playback = tone(8000, 220.0)
        recorder.write(playback, admission = admFor(recorder, 81L))

        // The microphone hears exactly what we played (a delayed copy), scaled down as bleed would be.
        val echoOnly = FloatArray(1600) { i -> 0.3f * playback[i] }

        assertFalse(
            "echo-only audio must be dropped, not treated as user speech",
            filter.shouldProcess(echoOnly, hasSpeakerId = false, speakerIndex = 0, playbackBuffer = recorder.snapshot()),
        )
    }

    // THE DECODER-FORMAT BOUNDARY, END TO END: a 44.1 kHz stereo episode goes through the REAL tap
    // into the recorder, and the microphone segment that matches what actually sounded must be
    // rejected as bleed. Before the recorder normalized rate/channels, the reference held raw
    // interleaved frames -- ~2.8x time-compressed, L/R alternating -- so nothing correlated, the echo
    // was accepted, and the podcast's own audio reached recognition as a user command.
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `a 44100 Hz stereo episode played into the reference rejects its own echo at the microphone`() = runTest {
        val tap = FingerprintPcmTap()
        val recorder = PlaybackBufferRecorder(tap)
        val job = recorder.start(this)
        runCurrent()

        // 0.5 s of 220 Hz at 44.1 kHz stereo, L == R (a centered image -- downmix preserves it).
        val srcRate = 44_100
        val halfSecond = srcRate / 2
        val data = ShortArray(halfSecond * 2) { i ->
            val v = (0.6 * sin(2 * PI * 220.0 * (i / 2) / srcRate)).toFloat()
            (v * 32767).toInt().toShort()
        }
        val format = AudioProcessor.AudioFormat(srcRate, 2, C.ENCODING_PCM_16BIT)
        tap.onSinkBuffer(presentationTimeUs = 0)
        tap.onPcm(
            ByteBuffer.wrap(
                ByteBuffer.allocate(data.size * 2).order(ByteOrder.nativeOrder()).apply { data.forEach { putShort(it) } }.array(),
            ),
            format,
        )
        runCurrent()
        job.cancel()

        // THE MIC IS ITS OWN WAVEFORM, NOT A PROJECTION OF THE REFERENCE. Deriving the microphone
        // from recorder.snapshot() would inherit whatever conversion the recorder produced and could
        // never catch a wrong one. The episode plays a continuous 220 Hz tone; the microphone hears
        // that SAME continuous waveform, delayed 200 ms and attenuated, sampled at its own 16 kHz
        // capture rate. Correlation then tests the recorder against an independent truth.
        val delaySeconds = 0.2
        val echoOnly = FloatArray(1600) { i ->
            // Double arithmetic: Int/Int would truncate to zero and silently flatten the waveform.
            val t = i.toDouble() / sampleRate + delaySeconds
            (0.3 * 0.6 * sin(2 * PI * 220.0 * t)).toFloat()
        }

        assertFalse(
            "the episode's own audio, heard by the microphone, must be rejected as bleed",
            filterShouldProcess(recorder, echoOnly),
        )
    }

    private fun filterShouldProcess(recorder: PlaybackBufferRecorder, mic: FloatArray): Boolean {
        val routeMonitor = AndroidAudioRouteMonitor(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            gracePeriodSignal = GracePeriodSignal(),
        )
        return UtteranceFilter(PlaybackCrossCorrelator(), routeMonitor)
            .shouldProcess(mic, hasSpeakerId = false, speakerIndex = 0, playbackBuffer = recorder.snapshot())
    }

    @Test
    fun `simultaneous user speech is preserved while playback is active`() {
        val (recorder, filter) = buildFilter()
        val playback = tone(8000, 220.0)
        recorder.write(playback, admission = admFor(recorder, 81L))

        // User speech is a different waveform from what we are playing, so it must survive.
        val userSpeech = tone(1600, 700.0, phase = 1.1)

        assertTrue(
            "genuine simultaneous speech must reach recognition",
            filter.shouldProcess(userSpeech, hasSpeakerId = false, speakerIndex = 0, playbackBuffer = recorder.snapshot()),
        )
    }

    @Test
    fun `with no playback submitted nothing is dropped`() {
        val (recorder, filter) = buildFilter()
        assertTrue(
            "an empty reference cannot reject anything",
            filter.shouldProcess(tone(1600, 700.0), hasSpeakerId = false, speakerIndex = 0, playbackBuffer = recorder.snapshot()),
        )
    }

    @Test
    fun `on a headset the bleed check is skipped, so the reference cannot drop speech`() {
        // With a headset there is no acoustic path from the speaker to the microphone, so anything the
        // mic hears is the user and the correlator must not be consulted at all. This is the branch that
        // decides whether the check runs, and getting it wrong is silent in both directions: trusting
        // the correlator here would drop genuine speech whenever it happened to match the playback.
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        // The reference is accumulating playback, so it is longer than one mic segment: the correlator
        // searches reference offsets, which only exist when there is room for the segment past them.
        val submitted = FloatArray(48_000) { (0.6 * sin(2 * PI * 220.0 * it / sampleRate)).toFloat() }
        recorder.write(submitted, admission = admFor(recorder, 81L))

        // The mic segment is the playback delayed by 120 ms — inside the correlator's 50..500 ms
        // window, so a consulted correlator rejects it. A segment at zero delay sits outside the window
        // and would be accepted either way, which would make this test unable to see the branch.
        val delaySamples = (sampleRate * 0.120).toInt()
        val delayedMic = FloatArray(8000) { i ->
            val src = i - delaySamples
            if (src >= 0) submitted[src] else 0f
        }

        // First establish that the correlator DOES reject this input on a route with an acoustic path,
        // so the assertion on the headset below is about the branch and not about a correlator that
        // cannot fire on this stimulus.
        val speakerFilter = UtteranceFilter(
            PlaybackCrossCorrelator(),
            object : AudioRouteMonitor {
                override val route = kotlinx.coroutines.flow.MutableStateFlow<AudioRoute>(AudioRoute.Speaker)
                override val availability = kotlinx.coroutines.flow.MutableStateFlow<AudioRoute>(AudioRoute.Speaker)
            },
        )
        assertFalse(
            "precondition: this delayed echo must be rejected on a speaker route",
            speakerFilter.shouldProcessReference(delayedMic, hasSpeakerId = false, speakerIndex = 0, reference = recorder.reference()),
        )

        val headsetFilter = UtteranceFilter(
            PlaybackCrossCorrelator(),
            object : AudioRouteMonitor {
                override val route = kotlinx.coroutines.flow.MutableStateFlow<AudioRoute>(
                    AudioRoute.Headset(hasMicrophone = true),
                )
                override val availability = kotlinx.coroutines.flow.MutableStateFlow<AudioRoute>(
                    AudioRoute.Headset(hasMicrophone = true),
                )
            },
        )
        assertTrue(
            "on a headset the same utterance must be processed without consulting the correlator",
            headsetFilter.shouldProcessReference(delayedMic, hasSpeakerId = false, speakerIndex = 0, reference = recorder.reference()),
        )
    }

    private val testAdmissions = HashMap<Pair<PlaybackBufferRecorder, Long>, PlaybackBufferRecorder.Admission>()

    private fun admFor(recorder: PlaybackBufferRecorder, id: Long): PlaybackBufferRecorder.Admission = testAdmissions.getOrPut(recorder to id) { recorder.openAdmission(id) }
}
