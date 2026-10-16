package au.com.shiftyjelly.pocketcasts.voicecontrol.capture

import android.Manifest
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.EpisodeReferenceEligibility
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintPcmTap
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.MicrophoneCapture
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.OboeCaptureHardware
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
import org.junit.Assume.assumeTrue
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
        val capture = MicrophoneCapture(ctx, OboeCaptureHardware(), au.com.shiftyjelly.pocketcasts.repositories.fingerprint.EpisodeReferenceEligibility())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // 4236613848: EVERYTHING after resource acquisition is wrapped -- an assertion failure must
        // not leave the microphone open or the collector draining in the shared process. Each
        // cleanup step is protected so one failure cannot skip the others.
        // The REAL acquisition lifecycle (e878d156): open -> start -> confirmed attach. Without it
        // startCapture() is an emptyFlow and a zero-segment run says nothing. Frame deltas are taken
        // around THIS run, not read once: the counter is process-global, so another test's audio
        // could satisfy a bare "> 0".
        var engine: AndroidPlatformTtsEngine? = null
        var job: kotlinx.coroutines.Job? = null
        try {
            // Acquisition INSIDE the protected body (99c2d869): an Assume after opening the stream
            // but before the finally would skip cleanup and leak the acquired stream. Frame deltas
            // are scoped to THIS run; the counter is process-global.
            val runStartFrames = MicrophoneCapture.framesConsumed()
            val deviceId = capture.openStreamForAcquisition()
            // An open failure is not evidence of a missing environment: a native or configuration
            // regression returns the same negative. Unexplained open failure is a FAILURE.
            assertTrue("openStreamForAcquisition failed (deviceId=$deviceId)", deviceId >= 0)
            // Only the OPEN failure is an observed unavailable prerequisite (no input device).
            // start/attach failures have no independently identified environmental cause, so they
            // stay failures, not skips. The selected input device is NOT verified here -- attach is
            // "attached", not "confirmed to the chosen route".
            assertTrue("the acquired stream did not start", capture.startAcquiredStream())
            assertTrue("the native VAD did not attach to the acquired stream", capture.attachCapture())

            val segments = mutableListOf<FloatArray>()
            var captureFailure: Throwable? = null
            job = scope.launch {
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

            // Collect what the detector saw, so a zero-segment result says WHICH stage stopped it: a level
            // below the gate, a score below the speech threshold, or an endpoint that never closed.
            MicrophoneCapture.setDiagnosticsEnabled(true)
            val quietFloor = MicrophoneCapture.diagnostics()
            Log.i(tag, "quiet floor: $quietFloor")

            // The app's own speech, through the real renderer: plays audibly and records the accepted
            // prefix into the reference the filter reads.
            engine = AndroidPlatformTtsEngine(ctx, recorder)
            engine.warmUp("en")
            withTimeoutOrNull(30_000) {
                engine.speak("the quick brown fox jumps over the lazy dog and keeps running", "en")
            }
            val duringSpeech = MicrophoneCapture.diagnostics()
            Log.i(tag, "during app speech: $duringSpeech")
            val nativeAfterSpeech = MicrophoneCapture.framesConsumed()
            // Let the loopback land in the microphone and any segment close.
            withTimeoutOrNull(10_000) { delay(4_000) }

            val afterSpeech = MicrophoneCapture.diagnostics()
            Log.i(tag, "after app speech: $afterSpeech")
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
            val runDelta = nativeTotal - runStartFrames
            assertTrue(
                "capture must deliver samples to the native VAD during THIS run (runDelta=$runDelta nativeTotal=$nativeTotal)",
                runDelta > 0,
            )
            assertTrue("the app's speech must be in the reference", reference.samples.isNotEmpty())

            // A segmented utterance is required before the filter can be asked anything. Its absence is
            // reported as "not demonstrated" rather than converted into a pass.
            assertTrue(
                "the VAD must have emitted a segment around the app's own speech; none formed " +
                    "(runDelta=$runDelta segments=${segments.size})",
                segments.isNotEmpty(),
            )

            val filter = UtteranceFilter(PlaybackCrossCorrelator(), AndroidAudioRouteMonitor(ctx, GracePeriodSignal()))
            val accepted = filter.shouldProcessReference(mic!!, false, 0, reference)
            Log.i(tag, "echo-only accepted=$accepted")

            assertTrue(
                "output the app itself spoke, heard by its own microphone, must not be accepted as user speech",
                !accepted,
            )
        } finally {
            // Each step protected: one failure cannot skip the others.
            runCatching { engine?.release() }
            runCatching { job?.cancel() }
            runCatching { capture.stopCapture() }
            runCatching { capture.closeAcquiredStream() }
        }
    }

    @Test
    fun theDetectionPathAcceptsGenuineSpeechLevelAudio() = runBlocking<Unit> {
        // The positive control @spec requires, and the reason it is not optional: with no control, zero
        // segments cannot distinguish correct echo rejection from a scenario that would never have
        // produced a segment whatever the input. 4236631774: the control now drives the PRODUCTION
        // detector -- the same native Silero path via MicrophoneCapture.startCapture() the echo run
        // uses -- with a guaranteed speech source: the app's own platform TTS played audibly. (The
        // previous control drove EnergyVoiceAudioSegmenter, a different detector, so its pass proved
        // nothing about the shipped path.)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        val capture = MicrophoneCapture(ctx, OboeCaptureHardware(), EpisodeReferenceEligibility())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Same REAL acquisition lifecycle as the echo run. Acquisition is INSIDE the protected body
        // (99c2d869): a skip after opening must not leak the acquired stream.
        var job: kotlinx.coroutines.Job? = null
        var engine: AndroidPlatformTtsEngine? = null
        try {
            val runStartFrames = MicrophoneCapture.framesConsumed()
            val deviceId = capture.openStreamForAcquisition()
            // An open failure is not evidence of a missing environment: a native or configuration
            // regression returns the same negative. Unexplained open failure is a FAILURE.
            assertTrue("openStreamForAcquisition failed (deviceId=$deviceId)", deviceId >= 0)
            // Only the OPEN failure is an observed unavailable prerequisite (no input device).
            // start/attach failures have no independently identified environmental cause, so they
            // stay failures, not skips. The selected input device is NOT verified here -- attach is
            // "attached", not "confirmed to the chosen route".
            assertTrue("the acquired stream did not start", capture.startAcquiredStream())
            assertTrue("the native VAD did not attach to the acquired stream", capture.attachCapture())

            val segments = mutableListOf<FloatArray>()
            job = scope.launch {
                try {
                    capture.startCapture().collect { result ->
                        if (result is VoiceSegmenterResult.SpeechEnded) {
                            segments += result.frames.flatMap { f -> f.samples.map { it / 32768f } }.toFloatArray()
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(tag, "capture failed: ${t::class.simpleName}: ${t.message}", t)
                }
            }
            engine = AndroidPlatformTtsEngine(ctx, recorder)
            engine.warmUp("en")
            withTimeoutOrNull(30_000) {
                engine.speak("testing one two three four five six seven eight nine ten", "en")
            }
            // Let the loopback land in the microphone and any segment close.
            withTimeoutOrNull(10_000) { delay(4_000) }

            val nativeTotal = MicrophoneCapture.framesConsumed()
            val runDelta = nativeTotal - runStartFrames
            Log.i(tag, "positive control: runDelta=$runDelta segments=${segments.size}")
            // After VERIFIED acquisition/attach and an audible TTS delivery, zero delivered frames
            // is a FAILURE, not an inconclusive (99c2d869): capture is proven live by the
            // open/start/attach results above, and the speech source is the app's own audible output.
            assertTrue(
                "verified capture delivered no frames to the native VAD during audible speech " +
                    "(runDelta=$runDelta)",
                runDelta > 0,
            )
            assertTrue(
                "the production native detector must emit a segment for genuine audible speech " +
                    "(runDelta=$runDelta segments=${segments.size})",
                segments.isNotEmpty(),
            )
        } finally {
            runCatching { engine?.release() }
            runCatching { job?.cancel() }
            runCatching { capture.stopCapture() }
            runCatching { capture.closeAcquiredStream() }
        }
    }
}
