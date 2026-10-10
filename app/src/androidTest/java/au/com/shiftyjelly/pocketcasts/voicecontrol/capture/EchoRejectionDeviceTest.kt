package au.com.shiftyjelly.pocketcasts.voicecontrol.capture

import android.Manifest
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintPcmTap
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.MicrophoneCapture
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceSegmenterResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackCrossCorrelator
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.UtteranceFilter
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AndroidAudioRouteMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.tts.AndroidPlatformTtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Echo-only acceptance on the device, using a real app renderer as the source.
 *
 * The TTS path is the already-wired renderer: it speaks through the app-owned sink AND records the
 * accepted prefix into the shared echo reference, so the reference is production-supplied rather than
 * filled by the test. The microphone stays live and the VAD stays in the path, so a segment that forms
 * while the app's own speech is in the air is what the filter judges.
 *
 * Two counters are kept apart on purpose:
 *  - `nativeFrames`  — samples the Oboe capture delivered to the native VAD ([MicrophoneCapture.framesConsumed])
 *  - `segments`      — VAD segments emitted to Kotlin
 * A zero in the second means nothing was judged; a zero in the first means the acoustic part never ran.
 * Reporting them as one number made those two failures indistinguishable.
 */
@RunWith(AndroidJUnit4::class)
class EchoRejectionDeviceTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val tag = "EchoDevice"

    @Test
    fun appSpeechHeardByTheMicIsRejected() = runBlocking<Unit> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        val capture = MicrophoneCapture(ctx)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val segments = mutableListOf<FloatArray>()
        var captureFailure: Throwable? = null
        val job = scope.launch {
            try {
                capture.startCapture().collect { result ->
                    if (result is VoiceSegmenterResult.SpeechEnded) {
                        segments += result.frames.flatMap { f -> f.samples.map { it / 32768f } }.toFloatArray()
                    }
                }
            } catch (t: Throwable) {
                Log.e(tag, "capture failed: ${t::class.simpleName}: ${t.message}", t)
                captureFailure = t
            }
        }

        withTimeoutOrNull(5_000) { delay(1_000) }
        val nativeBefore = MicrophoneCapture.framesConsumed()

        // The app's own speech, through the real renderer: plays audibly and records the accepted
        // prefix into the reference the filter reads.
        val engine = AndroidPlatformTtsEngine(ctx, recorder)
        engine.warmUp("en")
        withTimeoutOrNull(30_000) {
            engine.speak("the quick brown fox jumps over the lazy dog and keeps running", "en")
        }
        val nativeAfterSpeech = MicrophoneCapture.framesConsumed()
        // Let the loopback land in the microphone and any segment close.
        withTimeoutOrNull(10_000) { delay(4_000) }

        val nativeTotal = MicrophoneCapture.framesConsumed()
        val reference = recorder.reference()
        val mic = segments.lastOrNull()
        Log.i(
            tag,
            "nativeFrames=$nativeTotal (before=$nativeBefore afterSpeech=$nativeAfterSpeech) " +
                "segments=${segments.size} referenceSamples=${reference.samples.size} mic=${mic?.size ?: 0} " +
                "failure=${captureFailure?.message}",
        )

        // The acoustic part must have run: without samples reaching the native VAD, nothing below is a
        // statement about rejection.
        assertTrue("capture must deliver samples to the native VAD (nativeFrames=$nativeTotal)", nativeTotal > nativeBefore)
        assertTrue("the app's speech must be in the reference", reference.samples.isNotEmpty())

        // A segmented utterance is required before the filter can be asked anything. Its absence is
        // reported as "not demonstrated" rather than converted into a pass.
        assertTrue(
            "the VAD must have emitted a segment around the app's own speech; none formed " +
                "(nativeFrames=$nativeTotal segments=${segments.size})",
            segments.isNotEmpty(),
        )

        val filter = UtteranceFilter(PlaybackCrossCorrelator(), AndroidAudioRouteMonitor(ctx, GracePeriodSignal()))
        val accepted = filter.shouldProcessReference(mic!!, false, 0, reference)
        Log.i(tag, "echo-only accepted=$accepted")

        assertTrue(
            "output the app itself spoke, heard by its own microphone, must not be accepted as user speech",
            !accepted,
        )

        engine.release()
        job.cancel()
        capture.stopCapture()
    }

    @Test
    fun theDetectionPathAcceptsGenuineSpeechLevelAudio() = runBlocking<Unit> {
        // The positive control @spec requires, and the reason it is not optional: with no control, zero
        // segments cannot distinguish correct echo rejection from a scenario that would never have
        // produced a segment whatever the input. This drives the shipped detector with speech-level PCM
        // so the path is shown to be capable of accepting speech; the echo run below then has a baseline.
        val segmenter = au.com.shiftyjelly.pocketcasts.voicecontrol.audio.EnergyVoiceAudioSegmenter()

        // Speech-like bursts well above the detector's own threshold, alternating with trailing silence
        // so a segment closes naturally rather than being truncated.
        fun frame(amplitude: Double): au.com.shiftyjelly.pocketcasts.voicecontrol.audio.PcmAudioFrame {
            val samples = ShortArray(512) { i ->
                val env = 0.6 + 0.4 * kotlin.math.sin(2.0 * kotlin.math.PI * 5.0 * i / 512.0)
                (amplitude * env * kotlin.math.sin(2.0 * kotlin.math.PI * 220.0 * i / 16_000.0)).toInt().toShort()
            }
            return au.com.shiftyjelly.pocketcasts.voicecontrol.audio.PcmAudioFrame(samples, 16_000)
        }

        val results = mutableListOf<VoiceSegmenterResult>()
        repeat(12) { results += segmenter.process(frame(8_000.0)) }
        repeat(8) { results += segmenter.process(frame(0.0)) }

        val segments = results.filterIsInstance<VoiceSegmenterResult.SpeechEnded>()
        // Counted, not dumped: the frame contents are large and their shape is already asserted by the
        // segmenter's own tests.
        Log.i(
            tag,
            "positive control: segments=${segments.size} frames=${segments.sumOf { it.frames.size }} " +
                "kinds=${results.map { it::class.simpleName }.distinct()}",
        )
        assertTrue(
            "the detection path must accept speech-level audio, or a zero-segment echo run proves nothing",
            segments.isNotEmpty(),
        )
    }
}
