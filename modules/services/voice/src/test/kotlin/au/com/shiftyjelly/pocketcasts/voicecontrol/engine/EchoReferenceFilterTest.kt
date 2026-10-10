package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintPcmTap
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AndroidAudioRouteMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute
import kotlin.math.PI
import kotlin.math.sin
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
@Config(sdk = [30])
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

    @Test
    fun `echo-only input is rejected once the reference holds the submitted playback`() {
        val (recorder, filter) = buildFilter()
        val playback = tone(8000, 220.0)
        recorder.write(playback)

        // The microphone hears exactly what we played (a delayed copy), scaled down as bleed would be.
        val echoOnly = FloatArray(1600) { i -> 0.3f * playback[i] }

        assertFalse(
            "echo-only audio must be dropped, not treated as user speech",
            filter.shouldProcess(echoOnly, hasSpeakerId = false, speakerIndex = 0, playbackBuffer = recorder.snapshot()),
        )
    }

    @Test
    fun `simultaneous user speech is preserved while playback is active`() {
        val (recorder, filter) = buildFilter()
        val playback = tone(8000, 220.0)
        recorder.write(playback)

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
}
