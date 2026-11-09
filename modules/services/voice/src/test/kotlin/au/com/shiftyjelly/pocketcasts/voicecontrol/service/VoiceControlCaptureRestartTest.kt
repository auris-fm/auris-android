@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.EpisodeReferenceEligibility
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintPcmTap
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackend
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackendSelector
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrCapabilities
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.ModelSpec
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.CaptureHardware
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.MicrophoneCapture
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceAudioProcessor
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.UtteranceFilter
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.VoiceAsrEngine
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconId
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.EnabledByUserCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRule
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRuleGroup
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRuleState
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningModePolicy
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.IntentRoutingInput
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.ModelManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognitionContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContextMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackIntentExecutor
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AndroidAudioRouteMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AndroidRouteAcquisitionPlatform
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.RouteAcquisition
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordDetector
import dagger.Lazy
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioManager

/**
 * Reproduction of the device-observed stop/restart cycle on the installed multiturn build:
 *
 *   wake hit -> `other_app_playing` blocks briefly -> capture stops / VAD exits -> gate allowed
 *   again during teardown -> engine stopped again -> route Unknown -> external acquisition ->
 *   input stream started + VAD started -> route Headset -> engine start request -> VAD
 *   stopped/restarted.
 *
 * Driven through the REAL production surfaces — the service's mode observer, ListeningModePolicy,
 * VoiceControlGate, AndroidAudioRouteMonitor and RouteAcquisition — with only native boundaries
 * (Oboe hardware, ASR models/engines, notifications) stood in for.
 *
 * Provenance: git -S over origin/main..161092964 shows
 * `invalidatePendingStart`, `startIfNotRunning` and `captureRestartSignal` already present at the
 * pre-task baseline; 161092964..HEAD changed only the availability-sourced acquisition branch and
 * its logging in VoiceControlService, and OtherAppPlayingCondition is untouched by this task. The
 * sequence pinned here is therefore PRE-EXISTING lifecycle behavior, not a multiturn regression.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceControlCaptureRestartTest {
    companion object {
        /** The id the harness gives the SCO input once the link is up (device ids are platform-owned). */
        private const val SCO_INPUT_ID = 41
    }

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager

    @Before
    fun setUp() {
        timber.log.Timber.plant(timber.log.Timber.DebugTree())
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        testScheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
        // Before anything touches Dispatchers.Main: production's serviceScope uses Main.immediate;
        // a TestDispatcher-backed Main makes its delay()/withTimeoutOrNull continuations resumable
        // by pumping the shared scheduler from the test thread (the pattern
        // VoiceControlServiceAcquisitionTest relies on).
        Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- fakes at the native boundary -------------------------------------

    /**
     * A self-contained acquisition platform for this harness, mirroring the device contract the
     * production [au.com.shiftyjelly.pocketcasts.voicecontrol.route.AndroidRouteAcquisitionPlatform]
     * encodes:
     *
     *  - AirPods connected-but-unrouted enumerate an A2DP OUTPUT and a SCO INPUT (the HFP input
     *    device exists while the profile is idle), and `bluetoothCandidates()` names that SCO input;
     *  - `startBluetoothSco()` returns IMMEDIATELY and the link establishes ASYNCHRONOUSLY (~150ms
     *    of HFP negotiation on device). Robolectric's ShadowAudioManager implements neither the
     *    call nor the establishment, so the harness models both: a background timer flips the link
     *    flag after the establishment delay and posts the platform's SCO_AUDIO_STATE_UPDATED
     *    broadcast to the main looper, where the production route monitor receives it -- exactly
     *    as on device. The polling loop inside RouteAcquisition then observes `isBluetoothScoOn`.
     *  - opening/starting/closing the input stream goes through the REAL MicrophoneCapture, so
     *    capture generations advance exactly as in production.
     */
    private inner class FakeScoPlatform(
        private val microphoneCapture: MicrophoneCapture,
    ) : au.com.shiftyjelly.pocketcasts.voicecontrol.route.RouteAcquisitionPlatform {
        /** Live establishment threads. Harness-owned: teardown joins them, nothing outlives a test. */
        private val establishmentThreads = java.util.concurrent.CopyOnWriteArrayList<Thread>()

        /** Join every establishment thread; returns whether all finished. Bounded, asserted. */
        fun awaitEstablishmentSettled(timeoutMs: Long = 5_000L): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            for (thread in establishmentThreads) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return false
                thread.join(remaining)
                if (thread.isAlive) return false
            }
            establishmentThreads.clear()
            return true
        }

        var scoStarts = 0
        var scoStops = 0

        @Volatile var scoLinkUp = false
            private set
        var modeSets = 0
        var lastMode = -1

        override fun bluetoothCandidates(): List<au.com.shiftyjelly.pocketcasts.voicecontrol.route.BluetoothCandidate> = listOf(
            au.com.shiftyjelly.pocketcasts.voicecontrol.route.BluetoothCandidate(
                deviceId = SCO_INPUT_ID,
                transport = au.com.shiftyjelly.pocketcasts.voicecontrol.route.BluetoothTransport.ClassicSco,
            ),
        )

        override fun startBluetoothSco() {
            scoStarts++
            // Asynchronous establishment: see the class doc. The flag flips on the timer thread;
            // the state-changed broadcast is posted to the MAIN looper (platform broadcasts are
            // delivered on the main thread) and drained when the test pumps `idle()`.
            val establishment = Thread {
                try {
                    Thread.sleep(150)
                    scoLinkUp = true
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        // Update the shadow audio manager so the monitor observes an active SCO
                        // headset and transitions availability from BluetoothA2dpOnly to Headset.
                        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
                        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        setShadowBluetoothScoOn(am, true)
                        shadowOf(am).setInputDevices(
                            listOf(
                                AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).build(),
                            ),
                        )
                        @Suppress("DEPRECATION") // The SCO-intent constants the production monitor listens for are deprecated on 34.
                        deliver(
                            Intent(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                                .putExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_CONNECTED),
                        )
                    }
                } catch (_: InterruptedException) {
                    // Interrupted establishment: the link never comes up, like a failed HFP session.
                }
            }.apply { isDaemon = true }
            establishmentThreads += establishment
            establishment.start()
        }

        override fun stopBluetoothSco() {
            scoStops++
            scoLinkUp = false
            // Reset the shadow audio manager so the monitor transitions back.
            val app = ApplicationProvider.getApplicationContext<android.app.Application>()
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            setShadowBluetoothScoOn(am, false)
            shadowOf(am).setInputDevices(emptyList())
            @Suppress("DEPRECATION")
            deliver(
                Intent(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                    .putExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_DISCONNECTED),
            )
        }

        override fun isBluetoothScoOn(): Boolean = scoLinkUp

        private fun deliver(intent: Intent) {
            val app = ApplicationProvider.getApplicationContext<android.app.Application>()
            for (wrapper in shadowOf(app).registeredReceivers) {
                if (wrapper.intentFilter.hasAction(intent.action!!)) {
                    runCatching { wrapper.broadcastReceiver.onReceive(app, intent) }
                }
            }
        }

        override fun setCommunicationDevice(deviceId: Int): Boolean = false
        override fun communicationDeviceId(): Int? = null
        override fun clearCommunicationDevice() = Unit

        // Production awaits MODE_IN_COMMUNICATION before confirming an SCO capture stream; the
        // shadow implements setMode/getMode, so keep the real semantics here.
        override fun audioMode(): Int = audioManager.mode
        override fun setAudioMode(mode: Int) {
            modeSets++
            lastMode = mode
            audioManager.mode = mode
        }

        // Device truth: once the HFP link is up, the opened stream selects the SCO input device.
        override fun deviceType(deviceId: Int): Int? = if (deviceId == SCO_INPUT_ID) AudioDeviceInfo.TYPE_BLUETOOTH_SCO else null

        override fun openInputStream(): Int = microphoneCapture.openStreamForAcquisition()

        override fun startInputStream(): Boolean = microphoneCapture.startAcquiredStream()

        override fun closeInputStream() = microphoneCapture.closeAcquiredStream()
    }

    /**
     * Stands in for Oboe. Beyond counts, it keeps a per-stream ledger: every open gets a stable id,
     * and the attach/handoff/close events are recorded against that id. A global count cannot say
     * WHICH stream a close belonged to, which is the only thing that distinguishes "this abandoned
     * attempt was cleaned up" from "the engine-owned stream was closed behind its back".
     */
    private class FakeHardware(private val scoReady: () -> Boolean) : CaptureHardware {
        var capturing = false
        var attachCount = 0
        var vadStopCount = 0
        var openCount = 0
        var startStreamCount = 0
        var closeCount = 0

        /** Sequence for stable stream ids; ids are never reused, so identity survives a close. */
        private val nextStreamId = java.util.concurrent.atomic.AtomicInteger(0)

        /** Every stream this fake ever opened, in open order. */
        val streams = java.util.concurrent.CopyOnWriteArrayList<Stream>()

        /**
         * The single capture slot, mirroring native `gCapture`: installed by open, cleared by stop
         * and close. Being installed is separate from being started, exactly as in the native layer.
         */
        @Volatile
        private var installed: Stream? = null

        /**
         * When set, the next attach refuses, mimicking a start that fails AFTER the stream was
         * confirmed -- the `attached == false` branch in `startEngine`. Deliberately not a
         * `startInputStream()` failure: that one fires inside `performAcquisition`, so acquisition
         * releases the stream itself, which reaches a different branch.
         */
        @Volatile
        var failNextAttach = false

        /** How many attaches the forced-refusal lever actually refused. */
        @Volatile
        var refusedAttachCount = 0

        override fun startCaptureAndVad(sampleRate: Int, channels: Int, assetManager: android.content.res.AssetManager): Boolean {
            // Native attach only succeeds on a RUNNING stream (AAudio state check); production
            // guarantees start-before-attach, so the fake enforces the same contract.
            //
            // Attach targets the INSTALLED capture and requires it to be running (jni_bridge.cpp
            // :205-215 starts only a stream that exists). A stopped stream is GONE, not idle, because
            // nativeStopCaptureAndVad retires the VAD and DELETES gCapture (jni_bridge.cpp:236-247);
            // nothing is ever reused after a VAD stop.
            val stream = installed ?: return false
            if (!stream.started) return false
            if (failNextAttach) {
                failNextAttach = false
                refusedAttachCount++
                return false
            }
            attachCount++
            stream.attached = true
            // A successful attach IS the hand-off, observed at the hardware boundary: from here the
            // engine owns the stream, so an explicit close of it is a violation. No production seam
            // is needed to establish that.
            stream.handedOff = true
            stream.handOffCount++
            capturing = true
            return true
        }

        override fun stopCaptureAndVad() {
            vadStopCount++
            capturing = false
            // NATIVE CONTRACT (jni_bridge.cpp:236-247): stop retires VAD, DELETES gCapture and
            // clears the slot -- even when the stream was never started. The engine tearing down its
            // own stream is expected, not an acquisition close.
            installed?.let { closeStream(it, caller = "engine_stop_teardown") }
            installed = null
        }

        /** Retire a stream the way the native layer does: mark closed, with the responsible caller. */
        private fun closeStream(stream: Stream?, caller: String) {
            if (stream == null || stream.closedAt != null) return
            if (stream.closedAt == null) stream.closedAt = System.nanoTime()
            stream.closeCalls += caller
            stream.closeThread = Thread.currentThread().name
            stream.handedOffAtClose = stream.handedOff
        }

        override fun openInputCapture(sampleRate: Int, channels: Int): Int {
            // Mirror the platform contract: the HFP input is selectable only while the SCO link
            // exists; opening before establishment returns no device on real hardware too.
            if (!scoReady()) return -1
            openCount++
            // NATIVE CONTRACT (jni_bridge.cpp:180-199): opening exits early when it cannot open, and
            // otherwise retires VAD and DELETES the previous gCapture before installing the new one
            // as the single slot. `gCapture` is installed HERE, at open -- not at start -- and a
            // replacement while the previous capture is still installed therefore destroys it.
            installed?.let { previous ->
                if (previous.closedAt == null) {
                    closeStream(previous, caller = "native_open_replaced")
                }
            }
            val stream = Stream(id = nextStreamId.incrementAndGet())
            streams += stream
            installed = stream
            return SCO_INPUT_ID
        }

        override fun startInputStream(): Boolean {
            startStreamCount++
            // nativeStartInputStream starts whatever gCapture is installed and fails when none is.
            val stream = installed ?: return false
            if (stream.started) return true
            stream.started = true
            capturing = true
            return true
        }

        override fun closeInputCapture() {
            closeCount++
            // nativeCloseInputCapture deletes whatever gCapture is installed and is idempotent:
            // with no slot installed it is a no-op, which is what the "empty ledger" closes were.
            val stream = installed ?: return
            closeStream(stream, caller = closeCaller())
            installed = null
        }

        /** The owning frame that decided to close, for the ledger. */
        private fun closeCaller(): String = Thread.currentThread().stackTrace
            .firstOrNull { f ->
                f.className.contains("voicecontrol") &&
                    f.methodName != "closeInputCapture" &&
                    !f.className.endsWith("MicrophoneCapture") &&
                    !f.className.contains("VoiceControlCaptureRestartTest") &&
                    !f.className.contains("FakeScoPlatform")
            }
            ?.let { f -> f.className.substringAfterLast('.') + "." + f.methodName }
            ?: "unknown"

        /** One opened stream and what happened to it. */
        class Stream(val id: Int) {
            @Volatile var started = false

            @Volatile var attached = false

            @Volatile var vadStopped = false

            @Volatile var handedOff = false

            /** Every hand-off this stream ever received; >1 means the flag was reset and re-set. */
            @Volatile var handOffCount = 0

            @Volatile var closedAt: Long? = null

            /** Every close this stream received, in order: a repeat close must be visible. */
            val closeCalls = java.util.concurrent.CopyOnWriteArrayList<String>()
            val closeCaller: String? get() = closeCalls.lastOrNull()

            @Volatile var closeThread: String? = null

            /** The flag's value at close: false here can mean "reset by a previous release". */
            @Volatile var handedOffAtClose: Boolean? = null

            /** True if this stream was EVER handed to the engine, regardless of the flag at close. */
            val everHandedOff: Boolean get() = handOffCount > 0

            val isOpen: Boolean get() = closedAt == null

            override fun toString() = "Stream#$id(attach=$attached vadStop=$vadStopped handOffs=$handOffCount " +
                "closed=${closedAt != null} by=$closeCaller@$closeThread handedOffAtClose=$handedOffAtClose)"
        }
        data class CloseEvent(val caller: String, val thread: String, val openCountAtClose: Int)
        override fun isCapturing() = capturing

        override fun waitForVadEvent(timeoutMs: Int): Int {
            // Block while capture is live so the collected flow stays alive; return "no event"
            // once capture has ended so a disposed engine's loop exits promptly.
            val deadline = System.currentTimeMillis() + timeoutMs
            while (capturing && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
            return 0
        }

        override fun getSpeechPcm(buffer: ShortArray) = 0
        override fun getSpeechPcmSize() = 0
        override fun getSpeechOnsetSample() = 0
        override fun getSpeechEndSample() = 0
        override fun getSpeechEndStreamPosition(): Long = 0L
    }

    private class FakeRule(
        override val id: String,
        override val group: VoiceControlRuleGroup,
    ) : VoiceControlRule {
        private val stateFlow = MutableStateFlow<VoiceControlRuleState>(VoiceControlRuleState.Allowed)
        override val state: StateFlow<VoiceControlRuleState> = stateFlow
        fun block(reason: String) {
            stateFlow.value = VoiceControlRuleState.Blocked(reason)
        }
        fun allow() {
            stateFlow.value = VoiceControlRuleState.Allowed
        }
    }

    private class FakeBackend : AsrBackend {
        override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)
        override suspend fun transcribe(samples: FloatArray, sampleRateHz: Int): AsrResult = AsrResult(text = "")
        override val requiredModel = ModelSpec(files = emptyList(), targetDir = "test")
        override val capabilities = AsrCapabilities(supportedLanguages = setOf("en"))
        override fun release() = Unit
    }

    private class NoopRecognizer : VoiceRecognizer {
        override suspend fun ensureReady() = Result.success(Unit)
        override suspend fun recognize(input: IntentRoutingInput, context: VoiceRecognitionContext, logicalRequestId: String) = throw IllegalStateException("not used in this reproduction")
        override fun release() = Unit
    }

    // ---- harness -----------------------------------------------------------

    private fun setShadowBluetoothScoOn(am: AudioManager, on: Boolean) {
        val method = org.robolectric.shadows.ShadowAudioManager::class.java
            .getDeclaredMethod("setBluetoothScoOn", Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        method.invoke(shadowOf(am), on)
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val field: Field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun getField(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    /** Replace one private field. */
    private fun replaceField(target: Any, name: String, value: Any?) {
        val clazz = generateSequence<Class<*>>(target.javaClass) { it.superclass }
            .first { c -> c.declaredFields.any { it.name == name } }
        val f = clazz.getDeclaredField(name)
        f.isAccessible = true
        f.set(target, value)
    }

    /**
     * Poll until [predicate] holds, letting Robolectric's main looper drain between probes. Plain
     * Thread.sleep keeps REAL time flowing for the production timeouts (withTimeoutOrNull in
     * acquisition); no test scheduler is involved anywhere in this harness.
     */
    private lateinit var testScheduler: kotlinx.coroutines.test.TestCoroutineScheduler

    /**
     * Wait for `predicate`. Production's service coroutines are dispatched to Dispatchers.Main --
     * backed by a TestDispatcher on [testScheduler] -- and their `delay` continuations execute
     * only when that scheduler is pumped from a thread that is not busy. So wait INSIDE a launch
     * on the scheduler's own dispatcher: suspending there lets runCurrent() drain every pending
     * Main continuation (acquisition polls, observer body, engine start) cooperatively.
     */
    // Wait, bounded, until `predicate` holds, pumping the same runtimes awaitState pumps. Used by
    // teardown to ASSERT completion rather than sleep for an arbitrary number of rounds: a timeout
    // here FAILS the test, because work outliving a test makes the next one non-deterministic.
    private suspend fun awaitTeardown(
        what: String,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(10.milliseconds)
            shadowOf(Looper.getMainLooper()).runOneTask()
            Thread.sleep(5)
            kotlinx.coroutines.yield()
        }
        throw IllegalStateException("teardown did not complete: $what")
    }

    private suspend fun awaitState(
        what: String,
        probe: () -> String = { "" },
        startProbe: (String) -> Unit = {},
        predicate: () -> Boolean,
    ) {
        val outcome = kotlinx.coroutines.CompletableDeferred<Result<Unit>>()
        val job = kotlinx.coroutines.SupervisorJob()
        val scope = kotlinx.coroutines.CoroutineScope(job + kotlinx.coroutines.Dispatchers.Main.immediate)
        scope.launch {
            try {
                val deadline = System.currentTimeMillis() + 8_000
                var last = ""
                while (!predicate()) {
                    if (System.currentTimeMillis() > deadline) {
                        startProbe(last)
                        error("timed out waiting for $what; last=$last")
                    }
                    testScheduler.advanceTimeBy(25.milliseconds)
                    kotlinx.coroutines.delay(25)
                    last = probe()
                }
                outcome.complete(Result.success(Unit))
            } catch (e: Throwable) {
                outcome.complete(Result.failure(e))
            }
        }
        try {
            // Drive the test scheduler, Robolectric's shadow main looper, and real dispatcher
            // threads until the watcher settles. Production code uses Dispatchers.IO (real threads)
            // and posts broadcasts via Handler(Looper.getMainLooper()) — all three must be pumped.
            while (!outcome.isCompleted) {
                testScheduler.runCurrent()
                testScheduler.advanceTimeBy(25.milliseconds)
                // Drain at most one pending main-looper task per pump cycle to avoid infinite
                // idle loops when production code continuously schedules work.
                shadowOf(Looper.getMainLooper()).runOneTask()
                Thread.sleep(10)
                kotlinx.coroutines.yield()
            }
            outcome.await().getOrThrow()
        } finally {
            job.cancel()
        }
    }

    /**
     * Diagnostic: attribute every explicit close instead of inferring it from `closeCount`.
     *
     * CI failed the pinned test with `open=2 close=1`, which is consistent with two explanations
     * that a count cannot separate: an abandoned acquisition cleaned up by `release()` (correct), or
     * an engine-owned stream closed by someone who should not have (the ownership violation the
     * pinned assertion guards). CI caught it once in fifteen-plus local runs, so waiting for a
     * rerun is not a measurement. This test forces a start that fails AFTER the stream is opened and
     * confirmed -- the one window where `release()` is still allowed to close -- and records what
     * actually happened.
     */
    @Test
    fun `a start that fails after confirming the stream closes only its own unowned stream`() = runBlocking {
        val cues = java.util.concurrent.atomic.AtomicInteger(0)
        val recordingRenderer = mock(AudioFeedbackRenderer::class.java)
        whenever(recordingRenderer.playEarcon(any())).thenAnswer { invocation ->
            if (invocation.getArgument<Any>(0) == EarconId.LISTENING_START) cues.incrementAndGet()
            true
        }
        withServiceHarness(recordingRenderer) { harness ->
            awaitState("first engine start", probe = { "attach=${harness.hardware.attachCount}" }) {
                harness.hardware.attachCount >= 1
            }
            val firstStream = harness.hardware.streams.single { it.attached }
            assertEquals("the user's start cues once, before any failure", 1, cues.get())

            // Fail the engine's own attach AFTER RouteAcquisition.confirmCaptureStream() opened and
            // confirmed a stream: this is the `!attached` branch in startEngine. Not a
            // startInputStream failure, which fires inside acquisition instead.
            harness.hardware.failNextAttach = true
            harness.otherAppRule.block("other_app_playing")
            awaitState("teardown", probe = { "vadStop=${harness.hardware.vadStopCount}" }) {
                harness.hardware.vadStopCount >= 1
            }
            harness.otherAppRule.allow()

            // Observable completion, not a sleep: wait for the refused attach to be consumed and
            // for the service to reach the state it reaches when the start is refused.
            awaitState("forced attach refusal consumed", probe = { "failNextAttach=${harness.hardware.failNextAttach}" }) {
                !harness.hardware.failNextAttach
            }
            awaitState("refused start released its stream", probe = { "streams=${harness.hardware.streams}" }) {
                harness.hardware.streams.any { !it.everHandedOff && it.closeCalls.contains("RouteAcquisition.release") }
            }

            // The intended path continues: once the refusal is past, the observer starts capture
            // again and a fresh stream attaches. Without this the control would claim only that a
            // refusal was survivable, not that the service recovers from it.
            awaitState("recovered attachment after the refusal", probe = { "attach=${harness.hardware.attachCount} streams=${harness.hardware.streams}" }) {
                harness.hardware.attachCount >= 2
            }
            val streams = harness.hardware.streams

            // 1. The forced refusal actually happened: exactly one attach was refused, and the
            //    recovered attachment is a different stream from the one that was abandoned.
            assertEquals("the forced attach refusal occurred", 1, harness.hardware.refusedAttachCount)

            // 2. The IDENTIFIED abandoned stream -- the one release closed -- received exactly one
            //    close. Counting release invocations instead would also count idempotent cleanup on
            //    an empty slot, which is not a stream event at all.
            val abandoned = streams.filter { it.closeCalls.contains("RouteAcquisition.release") }
            assertEquals("exactly one stream was closed by release: $streams", 1, abandoned.size)
            val stream = abandoned.single()
            assertEquals(
                "the abandoned stream is closed exactly once: $stream",
                listOf("RouteAcquisition.release"),
                stream.closeCalls.toList(),
            )
            assertFalse("the abandoned stream was not engine-owned: $stream", stream.everHandedOff)

            // 3. No engine-owned stream was ever closed by acquisition.
            streams.filter { it.everHandedOff }.forEach { owned ->
                assertFalse(
                    "acquisition closed an engine-owned stream: $owned",
                    owned.closeCalls.contains("RouteAcquisition.release"),
                )
            }

            // 4. The start the user made is still the only one that cued.
            assertEquals("a refused automatic start adds no cue", 1, cues.get())
            assertTrue("the first capture attached: $streams", firstStream.attached)
        }
    }

    /**
     * The owner-reported defect, as a control on the service itself.
     *
     * A predicate test cannot see this. `onStarted` runs on every fresh engine start,
     * `clearCaptureState()` marks the engine stopped, and `recoverCapture()` nudges the mode observer
     * into starting it again -- so the cue decision executes a second time, and any predicate that
     * looks only at [au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningMode] answers exactly
     * as it did for the first start. The bug lives in which start this is, so the control has to
     * drive a real start, termination and recovery and count the cues.
     *
     * The harness is the pinned reproduction below, with one difference that matters: the renderer
     * is a real recording stand-in rather than a mock, because the cue is the subject.
     */
    @Test
    fun `an automatic capture recovery does not cue the start earcon again`() = runBlocking {
        val cues = java.util.concurrent.atomic.AtomicInteger(0)
        val recordingRenderer = mock(AudioFeedbackRenderer::class.java)
        whenever(recordingRenderer.playEarcon(any())).thenAnswer { invocation ->
            val id = invocation.getArgument<Any>(0)
            if (id == EarconId.LISTENING_START) cues.incrementAndGet()
            true
        }

        withServiceHarness(recordingRenderer) { harness ->
            awaitState("first engine start", probe = { "attach=${harness.hardware.attachCount} cues=${cues.get()}" }) { harness.hardware.attachCount >= 1 }
            assertEquals("the start the user asked for still cues, once", 1, cues.get())

            // Capture ends on its own: the other app took the microphone. The service clears its
            // capture state and recovers, which starts the engine a second time.
            harness.otherAppRule.block("other_app_playing")
            awaitState("teardown", probe = { "vadStop=${harness.hardware.vadStopCount} attach=${harness.hardware.attachCount} cues=${cues.get()}" }) { harness.hardware.vadStopCount >= 1 }
            System.err.println("[PROBE] after teardown: mode-ish cues=" + cues.get())
            harness.otherAppRule.allow()
            awaitState("recovered engine start", probe = { "attach=${harness.hardware.attachCount} cues=${cues.get()}" }) { harness.hardware.attachCount >= 2 }

            assertEquals("the recovery restarted capture, so the cue decision ran twice", 2, harness.hardware.attachCount)
            assertEquals("an automatic recovery must not beep: that is the audio fight the owner reported", 1, cues.get())
        }
    }

    /**
     * The service, driven through its real entry point with a connected-but-unrouted headset, so a
     * test can watch acquisition, engine starts and cue decisions happen for real.
     *
     * The restart cycle these tests exercise is the owner-reported background case: capture ends
     * because another app took the microphone, the service clears capture state and recovers, and
     * the engine starts again. Both tests here assert a different property of that one cycle -- the
     * pinned test the hardware counts, the cue test the earcons -- so they share the wiring instead
     * of keeping two copies of it that can drift apart.
     */
    private inner class ServiceHarness(
        val service: VoiceControlService,
        val otherAppRule: FakeRule,
        val hardware: FakeHardware,
        val scoPlatform: FakeScoPlatform,
        val monitor: AndroidAudioRouteMonitor,
        val policy: ListeningModePolicy,
        val microphoneCapture: MicrophoneCapture,
    )

    private suspend fun withServiceHarness(
        renderer: AudioFeedbackRenderer,
        block: suspend (ServiceHarness) -> Unit,
    ) {
        // Connected-but-unrouted AirPods: A2DP output enumerated, SCO input present, link off.
        shadowOf(audioManager).setOutputDevices(
            listOf(AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).build()),
        )
        shadowOf(audioManager).setInputDevices(
            listOf(AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).build()),
        )

        val modelsReady = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.ModelsReadyCondition(isReady = false)
        val userRule = FakeRule(EnabledByUserCondition.ID, VoiceControlRuleGroup.Setup)
        val otherAppRule = FakeRule("other_app_playing", VoiceControlRuleGroup.Conflicts)
        val foregroundRule = FakeRule("app_in_foreground", VoiceControlRuleGroup.Context)
        // Harness-owned scope: the gate collects into it, so the harness must be able to finish it.
        val harnessJob = kotlinx.coroutines.SupervisorJob()
        val harnessScope = CoroutineScope(harnessJob + Dispatchers.Main.immediate)
        val gate = VoiceControlGate(
            listOf(userRule, otherAppRule, foregroundRule, modelsReady),
            harnessScope,
        )

        // Late-wired: FakeHardware refuses an input capture while the SCO link is down (device
        // contract: the HFP microphone is not selectable on a closed link).
        var scoLinkUp: () -> Boolean = { false }
        val hardware = FakeHardware(scoReady = { scoLinkUp() })
        val microphoneCapture = MicrophoneCapture(
            context,
            hardware,
            mock(EpisodeReferenceEligibility::class.java),
        )
        val voiceAudioProcessor = VoiceAudioProcessor(microphoneCapture)
        val engine = VoiceAsrEngine(
            voiceAudioProcessor = voiceAudioProcessor,
            utteranceFilter = UtteranceFilter(mock(), mock()),
            intentRecognizer = NoopRecognizer(),
            wakeWordDetector = mock(WakeWordDetector::class.java),
            gracePeriodSignal = GracePeriodSignal(),
            audioFeedbackRenderer = mock(AudioFeedbackRenderer::class.java),
            translationStage = object : au.com.shiftyjelly.pocketcasts.voicecontrol.asr.TranslationStage {
                override suspend fun ensureReady(sourceLanguage: String) = Result.success(Unit)
                override suspend fun translate(text: String, sourceLanguage: String): Result<String> = Result.failure(IllegalStateException("unused"))
            },
            context = context,
        )

        val backendSelector = mock(AsrBackendSelector::class.java)
        whenever(backendSelector.select()).thenReturn(FakeBackend())

        val playbackBufferRecorder = PlaybackBufferRecorder(FingerprintPcmTap())
        // Device parity: the AirPods connected earlier, so by the time voice control starts the
        // platform enumerates A2DP output plus the idle SCO input pair (the HFP input device exists
        // while the profile is idle; the mic becomes SELECTABLE only once startBluetoothSco brings
        // the link up). The monitor's ctor reads the tables as they are NOW, so seed them first.
        val monitor = AndroidAudioRouteMonitor(context, GracePeriodSignal())
        val scoPlatform = FakeScoPlatform(microphoneCapture).also { scoLinkUp = { it.scoLinkUp } }
        val acquisition = RouteAcquisition(scoPlatform, timeoutMs = 6_000L, pollIntervalMs = 10L)

        val policy = ListeningModePolicy(gate, monitor, GracePeriodSignal(), harnessScope)

        val notificationManager = mock(VoiceControlNotificationManager::class.java)
        whenever(notificationManager.notificationId).thenReturn(1001)
        whenever(notificationManager.createListeningNotification(any())).thenReturn(mock(Notification::class.java))
        whenever(notificationManager.createDownloadingNotification()).thenReturn(mock(Notification::class.java))

        // The controller's own start/stop bookkeeping is irrelevant here (and its stop hook would
        // re-enter the harness); a mock records that the service confirmed running.
        // Plain construction (the Render/Acquisition tests' pattern): Robolectric's buildService
        // would run Hilt's onCreate injection, which needs an @HiltAndroidApp application; this
        // harness supplies every injected field itself. attachBaseContext via reflection so
        // filesDir/checkSelfPermission work.
        val service = VoiceControlService()
        // android.app.Service overrides attachBaseContext (final); ContextWrapper's is the one
        // that actually stores the base.
        android.content.ContextWrapper::class.java.getDeclaredMethod(
            "attachBaseContext",
            Context::class.java,
        ).apply { isAccessible = true }.invoke(service, context)
        // Grant both permissions the production path checks.
        shadowOf(context as android.app.Application).grantPermissions(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE,
        )
        setField(service, "gate", gate)
        setField(service, "notificationManager", notificationManager)
        setField(service, "voiceRecognizer", NoopRecognizer())
        setField(service, "voicePlaybackIntentExecutor", mock(VoicePlaybackIntentExecutor::class.java))
        setField(service, "voiceDialogManager", mock(au.com.shiftyjelly.pocketcasts.voicecontrol.dialog.VoiceDialogManager::class.java))
        setField(service, "playbackContextMonitor", mock(PlaybackContextMonitor::class.java))
        setField(service, "audioRouteMonitor", monitor)
        setField(service, "listeningModePolicy", policy)
        setField(service, "voiceAsrEngine", Lazy { engine })
        setField(service, "asrBackendSelector", backendSelector)
        setField(service, "playbackBufferRecorder", playbackBufferRecorder)
        val modelManager = mock(ModelManager::class.java)
        whenever(modelManager.isModelReady(any())).thenReturn(true)
        whenever(modelManager.isLfmModelReady()).thenReturn(true)
        whenever(modelManager.ensureModel(any())).thenReturn(Result.success(Unit))
        whenever(modelManager.ensureLfmModel()).thenReturn(Result.success(Unit))
        setField(service, "modelManager", modelManager)
        setField(service, "modelsReadyCondition", modelsReady)
        setField(service, "audioFeedbackRenderer", renderer)
        setField(service, "gracePeriodSignal", GracePeriodSignal())
        setField(service, "routeAcquisition", acquisition)
        setField(service, "captureStateHolder", VoiceControlCaptureStateHolder())
        setField(service, "voiceControlServiceController", mock(VoiceControlServiceController::class.java))
        // Watch the real coordinator's generation so an invalidation during Phase 1 is visible.
        val engineStartProbe = getField(service, "engineStart")!!
        val generationField = engineStartProbe.javaClass.getDeclaredField("startGeneration").apply { isAccessible = true }

        // Drive the production entry point. Robolectric's ShadowService accepts startForeground on
        // an unattached-notification context, and the notification manager creates its channel.
        // Dispatchers.Main must be a TestDispatcher for the shared scheduler: production's
        // serviceScope uses Main.immediate, whose delay() falls back to the real DefaultExecutor
        // when Main is not a TestDispatcher -- and that executor does not run while the Robolectric
        // main thread sits inside runBlocking. With the shared scheduler, acquisition's poll-delay
        // resumes on this thread during idle().
        val harness = ServiceHarness(service, otherAppRule, hardware, scoPlatform, monitor, policy, microphoneCapture)
        try {
            startHarnessedService(service)
            block(harness)
        } finally {
            // End the lifecycle through the service's OWN teardown rather than reaching into
            // fields: onDestroy -> stopVoiceControl, which cancels modeJob and serviceScope
            // (VoiceControlService:946/968). A throwing teardown FAILS the test -- swallowing it
            // would let a broken teardown look like an isolated one.
            try {
                service.onDestroy()
                microphoneCapture.stopCapture()
                harnessJob.cancel()
                // Completion, not a request: cancellation is asynchronous, so await it.
                awaitTeardown("harness job completion") { harnessJob.isCompleted }
                awaitTeardown("service scope completion") {
                    val scope = getField(service, "serviceScope") as kotlinx.coroutines.CoroutineScope
                    scope.coroutineContext[kotlinx.coroutines.Job]?.isCompleted == true
                }
                awaitTeardown("SCO establishment threads") { scoPlatform.awaitEstablishmentSettled() }
                // Queued Main work this test posted must not run against the next test's Main.
                awaitTeardown("pending main-loop work") { shadowOf(Looper.getMainLooper()).isIdle }
            } catch (failure: Throwable) {
                // Surface with the pump loop still available so the message names the stuck stage.
                throw IllegalStateException("harness teardown failed", failure)
            }
        }
    }

    /**
     * Drives the production entry point and waits for the observer to install.
     *
     * Robolectric's ShadowService accepts startForeground on an unattached-notification context, and
     * the notification manager creates its channel. Dispatchers.Main must be a TestDispatcher for the
     * shared scheduler: production's serviceScope uses Main.immediate, whose delay() falls back to the
     * real DefaultExecutor when Main is not a TestDispatcher -- and that executor does not run while
     * the Robolectric main thread sits inside runBlocking. With the shared scheduler, acquisition's
     * poll-delay resumes on this thread during idle().
     */
    private suspend fun startHarnessedService(service: VoiceControlService) {
        // onStartCommand is package-private in android.app.Service; call it reflectively.
        VoiceControlService::class.java.getDeclaredMethod(
            "onStartCommand",
            Intent::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(service, null, 0, 1)
        // startVoiceControl launches model prep on Dispatchers.IO and installs the observer on Main
        // afterwards; wait for the observer job to exist.
        awaitState("observer install") { getField(service, "modeJob") != null }
    }

    @Test
    fun `the observed stop-restart cycle reproduces and is pinned`() = runBlocking {
        withServiceHarness(mock(AudioFeedbackRenderer::class.java)) { harness ->
            val otherAppRule = harness.otherAppRule
            val hardware = harness.hardware
            val scoPlatform = harness.scoPlatform
            val monitor = harness.monitor
            val policy = harness.policy

            // Phase 1: gate allowed + candidate present -> OFF branch acquires externally: SCO
            // opens, input stream opens+starts, confirmed selection publishes Headset, observer
            // starts the engine, which attaches VAD.
            println("[REPRO] pre-attach: capturing=${hardware.isCapturing()} route=${monitor.route.value} avail=${monitor.availability.value}")
            awaitState(
                "first VAD attach",
                probe = {
                    "open=${hardware.openCount} startStream=${hardware.startStreamCount} close=${hardware.closeCount} scoStarts=${scoPlatform.scoStarts} scoOnNow=${scoPlatform.scoLinkUp} route=${monitor.route.value} avail=${monitor.availability.value} mode=${policy.mode.value}"
                },
            ) { hardware.attachCount >= 1 }
            assertEquals(AudioRoute.Headset(true), monitor.route.value)
            assertEquals(1, hardware.openCount)
            assertEquals(1, hardware.attachCount)

            // Phase 2: the transient conflict -- other_app_playing blocks. The observer stops the
            // engine (VAD teardown) and releases routing (SCO off).
            otherAppRule.block("other_app_playing")
            awaitState("first VAD teardown") { hardware.vadStopCount >= 1 }

            // Phase 3: the conflict clears BEFORE anything else settles -- the same churn the owner
            // saw (a command response keeps music active for a moment).
            otherAppRule.allow()

            // Phase 4: the restart cycle completes: a SECOND acquisition opens a stream, the
            // engine starts again, VAD attaches a second time.
            awaitState(
                "second VAD attach (the restart cycle)",
                probe = {
                    "attach=${hardware.attachCount} vadStop=${hardware.vadStopCount} open=${hardware.openCount} close=${hardware.closeCount} scoStarts=${scoPlatform.scoStarts} route=${monitor.route.value} avail=${monitor.availability.value} mode=${policy.mode.value}"
                },
            ) { hardware.attachCount >= 2 }
            val finalAttach = hardware.attachCount
            val finalVadStop = hardware.vadStopCount
            val finalOpen = hardware.openCount
            val finalClose = hardware.closeCount
            println("[REPRO] Phase4 final: attach=$finalAttach vadStop=$finalVadStop open=$finalOpen close=$finalClose mode=${policy.mode.value}")
            assertEquals("attachCount", 2, finalAttach)
            // The second VAD is still active at this point -- only the first was torn down.
            // vadStopCount==1 is correct: the first VAD stopped during the conflict,
            // the second VAD is still running.
            assertEquals("vadStopCount", 1, finalVadStop)
            assertEquals("openCount", 2, finalOpen)
            // Both streams were handed off to the engine via RouteAcquisition.handOffStream().
            // After handoff, release() skips closeInputStream -- the engine owns teardown
            // (RouteAcquisition.kt:293). The first capture WAS torn down through engine stop
            // (nativeStopCaptureAndVad deletes gCapture), but that path does not call
            // closeInputCapture counted by FakeHardware. closeCount tracks explicit
            // closeInputStream calls only, not all stream teardown.
            assertEquals("closeCount (explicit close, not engine teardown)", 0, finalClose)
        }
    }
}
