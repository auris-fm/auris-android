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
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.OboeConfig
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceAudioProcessor
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.UtteranceFilter
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.VoiceAsrEngine
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer
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
import kotlinx.coroutines.cancelChildren
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
 * REPRODUCTION of the device-observed stop/restart cycle on the installed multiturn build
 * (owner log):
 *
 * wake hit -> `other_app_playing` blocks briefly -> capture stops / VAD exits -> gate allowed
 * again during teardown -> engine stopped again -> route Unknown -> external acquisition ->
 * input stream started + vad started -> route Headset -> engine start request -> vad
 * stopped/restarted.
 *
 * Driven through the REAL production surfaces — the service's mode observer, ListeningModePolicy,
 * VoiceControlGate, AndroidAudioRouteMonitor and RouteAcquisition — with only native boundaries
 * (Oboe hardware, ASR models/engines, notifications) stood in for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceControlCaptureRestartTest {
    companion object {
        /** The id the harness gives the SCO input once the link is up (device ids are platform-owned). */
        private const val SCO_INPUT_ID = 41

        /** One scheduler step per pass of a wait; the budget below is counted in these. */
        private const val WAIT_STEP_MS = 25L

        /**
         * How much SIMULATED time a wait may give its subject before declaring it stuck, read from
         * the scheduler's own clock so every advance counts — this loop's and the outer pump's alike.
         * Generous against the longest debounce and poll interval the stack uses, so expiry means the
         * subject really did not advance rather than that the host was slow to pump it.
         */
        private const val WAIT_BUDGET_MS = 60_000L

        /**
         * Real-time ceiling on the outer pump only. It exists so a wedged harness cannot hang the
         * suite forever, not to judge the subject: a loaded host legitimately needs far more wall
         * clock to deliver the same simulated time, so this is deliberately much larger than the
         * waits it carries.
         */
        private const val PUMP_BUDGET_MS = 180_000L
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
     * - AirPods connected-but-unrouted enumerate an A2DP output and a sco input (the hfp input
     * device exists while the profile is idle), and `bluetoothCandidates` names that SCO input;
     * - `startBluetoothSco` returns immediately and the link establishes asynchronously (~150ms
     * of HFP negotiation on device). Robolectric's ShadowAudioManager implements neither the
     * call nor the establishment, so the harness models both: a background timer flips the link
     * flag after the establishment delay and posts the platform's SCO_AUDIO_STATE_UPDATED
     * broadcast to the main looper, where the production route monitor receives it -- exactly
     * as on device. The polling loop inside RouteAcquisition then observes `isBluetoothScoOn`.
     * - opening/starting/closing the input stream goes through the REAL MicrophoneCapture, so
     * capture generations advance exactly as in production.
     */
    private inner class FakeScoPlatform(
        private val microphoneCapture: MicrophoneCapture,
    ) : au.com.shiftyjelly.pocketcasts.voicecontrol.route.RouteAcquisitionPlatform {
        var scoStarts = 0
        var scoStops = 0

        @Volatile var scoLinkUp = false
            private set

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
            Thread {
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
            }.apply { isDaemon = true }.start()
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

        // Device truth: once the HFP link is up, the opened stream selects the SCO input device.
        override fun deviceType(deviceId: Int): Int? = if (deviceId == SCO_INPUT_ID) AudioDeviceInfo.TYPE_BLUETOOTH_SCO else null

        override fun openInputStream(): Int = microphoneCapture.openStreamForAcquisition()

        override fun startInputStream(): Boolean = microphoneCapture.startAcquiredStream()

        override fun openStreamGeneration(): Long? = microphoneCapture.openStreamGeneration

        override fun closeInputStream(generation: Long?) = microphoneCapture.closeAcquiredStream(generation)
    }

    /** Stands in for Oboe: counts attach (VAD start), teardown (VAD stop), open/start/close. */
    private class FakeHardware(private val scoReady: () -> Boolean) : CaptureHardware {
        var capturing = false
        var attachCount = 0
        var vadStopCount = 0
        var openCount = 0
        var startStreamCount = 0
        var closeCount = 0

        override fun startCaptureAndVad(sampleRate: Int, channels: Int, assetManager: android.content.res.AssetManager): Boolean {
            // Native attach only succeeds on a RUNNING stream (AAudio state check); production
            // guarantees start-before-attach, so the fake enforces the same contract.
            if (!capturing) return false
            attachCount++
            return true
        }

        override fun getSpeechPcmRange(out: ShortArray, fromSample: Int): Int = 0

        override fun stopCaptureAndVad() {
            vadStopCount++
            capturing = false
        }
        override fun openInputCapture(sampleRate: Int, channels: Int): Int {
            // Mirror the platform contract: the HFP input is selectable only while the SCO link
            // exists; opening before establishment returns no device on real hardware too.
            if (!scoReady()) return -1
            openCount++
            return 41
        }
        override fun startInputStream(): Boolean {
            startStreamCount++
            capturing = true
            return true
        }
        override fun closeInputCapture() {
            closeCount++
        }
        override fun isCapturing() = capturing

        override fun waitForVadEvent(timeoutMs: Int, generationOut: LongArray): Int {
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
        override fun getInProgressSnapshot(buffer: ShortArray, header: LongArray): Int {
            header[0] = 0
            header[1] = 0
            header[2] = 0
            return 0
        }
        override fun setEarlyWakeCutoff(samples: Int) = Unit
        override fun getSpeechEndSample() = 0
        override fun getSpeechEndStreamPosition(): Long = 0L

        /**
         * A live stream keeps delivering, so the counter advances by one poll's worth of frames
         * each time it is read. A constant count would trip the starvation check after 2.5 s and end
         * a capture these tests mean to keep alive, making their stop/attach assertions depend on
         * wall-clock time rather than on the behaviour under test.
         */
        override fun getFramesConsumed(): Long = if (capturing) consumedFrames.addAndGet(OboeConfig.FRAMES_PER_POLL.toLong()) else 0L
        private val consumedFrames = java.util.concurrent.atomic.AtomicLong(0L)
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
     * on the scheduler's own dispatcher: suspending there lets runCurrent drain every pending
     * Main continuation (acquisition polls, observer body, engine start) cooperatively.
     */
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
                // The budget is SIMULATED time, not wall clock. Each pass advances the scheduler by
                // a fixed step, so this counts how much of the subject's own timeline has been given
                // to it rather than how long the host took to get there. A starved machine buys fewer
                // steps per real second; with a wall-clock deadline that expires the wait while the
                // subject is still strictly behind, which reads as a product race and is not one.
                // Measured from the scheduler's OWN clock, not by counting this loop's passes: the
                // outer pump advances the same scheduler too, so a pass count would understate how
                // much simulated time the subject has actually been given.
                val startedAtMs = testScheduler.currentTime
                var last = ""
                while (!predicate()) {
                    val waitedMs = testScheduler.currentTime - startedAtMs
                    if (waitedMs >= WAIT_BUDGET_MS) {
                        startProbe(last)
                        error("timed out waiting for $what after ${waitedMs}ms of simulated time; last=$last")
                    }
                    testScheduler.advanceTimeBy(WAIT_STEP_MS.milliseconds)
                    kotlinx.coroutines.delay(WAIT_STEP_MS)
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
            // Real time bounds ONLY this outer pump, and generously: its job is to keep the host
            // moving, so a slow machine needs more of it, not less. Expiry here means the harness
            // itself stopped making progress, which is a different failure from the subject's.
            val pumpDeadline = System.currentTimeMillis() + PUMP_BUDGET_MS
            while (!outcome.isCompleted) {
                if (System.currentTimeMillis() > pumpDeadline) {
                    error("harness pump stalled waiting for $what after ${PUMP_BUDGET_MS}ms of real time")
                }
                testScheduler.runCurrent()
                testScheduler.advanceTimeBy(WAIT_STEP_MS.milliseconds)
                // Drain at most one pending main-looper task per pump cycle to avoid infinite
                // idle loops when production code continuously schedules work.
                runCatching { shadowOf(Looper.getMainLooper()).runOneTask() }
                Thread.sleep(10)
                kotlinx.coroutines.yield()
            }
            outcome.await().getOrThrow()
        } finally {
            job.cancel()
        }
    }

    /**
     * The device set the monitor enumerates, published through its own injected source: the route
     * surfaces are StateFlows read from it, so a change here is what a platform broadcast would be.
     */
    private var enumeratedDevices = AndroidAudioRouteMonitor.EnumeratedDevices(emptyList(), emptyList())
    private val deviceSet = java.util.concurrent.atomic.AtomicReference(enumeratedDevices)

    private fun publishDevices(devices: AndroidAudioRouteMonitor.EnumeratedDevices) {
        enumeratedDevices = devices
        deviceSet.set(devices)
    }

    private fun deliverDevicesChanged(monitor: AndroidAudioRouteMonitor) = monitor.onDevicesChanged()

    /**
     * Let the monitor's debounce run and publish the surface it just read. The debounce runs on the
     * monitor's own Dispatchers.Default scope, so real time must pass for it; pumping the test
     * scheduler alone would never deliver that read. Returns once [expected] is published.
     */
    private fun settleRouteRead(monitor: AndroidAudioRouteMonitor, expected: AudioRoute) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && monitor.availability.value != expected) {
            testScheduler.runCurrent()
            runCatching { shadowOf(Looper.getMainLooper()).runOneTask() }
            Thread.sleep(20)
        }
        assertEquals("the route read did not publish the expected surface", expected, monitor.availability.value)
    }

    /** As [settleRouteRead], for a change whose exact answer the control does not pin. */
    private fun settleRouteReadUntil(monitor: AndroidAudioRouteMonitor, settled: (AudioRoute) -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !settled(monitor.availability.value)) {
            testScheduler.runCurrent()
            runCatching { shadowOf(Looper.getMainLooper()).runOneTask() }
            Thread.sleep(20)
        }
        assertTrue("the route read did not settle", settled(monitor.availability.value))
    }

    @Test
    fun `the observed stop-restart cycle reproduces and is pinned`() = runBlocking {
        // Connected-but-unrouted AirPods: A2DP output enumerated, SCO input present, link off.
        shadowOf(audioManager).setOutputDevices(
            listOf(AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).build()),
        )
        shadowOf(audioManager).setInputDevices(
            listOf(AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).build()),
        )

        // The REAL condition: ensureModelsReady() flips it, and the gate must see models_loading ->
        // Allowed for the observer's composite to recompute. A mock's no-op update left the gate in
        // Unknown forever, which is what stalled the harness -- not production.
        val modelsReady = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.ModelsReadyCondition(isReady = false)
        val userRule = FakeRule(EnabledByUserCondition.ID, VoiceControlRuleGroup.Setup)
        val otherAppRule = FakeRule("other_app_playing", VoiceControlRuleGroup.Conflicts)
        val foregroundRule = FakeRule("app_in_foreground", VoiceControlRuleGroup.Context)
        val gate = VoiceControlGate(
            listOf(userRule, otherAppRule, foregroundRule, modelsReady),
            CoroutineScope(Dispatchers.Main.immediate),
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
            collectionAuthority = { au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision(false, false) },
            retentionSink = org.mockito.kotlin.mock(),
            segmentObserver = org.mockito.kotlin.mock(),
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

        val policy = ListeningModePolicy(gate, monitor, GracePeriodSignal(), CoroutineScope(Dispatchers.Main.immediate))

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
        val service = VoiceControlService().also { it.voiceSessionGate = au.com.shiftyjelly.pocketcasts.voicecontrol.service.VoiceSessionGate() }
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
        setField(service, "voicePlaybackIntentExecutor", StubVoiceExecutor())
        setField(service, "voiceDialogManager", mock(au.com.shiftyjelly.pocketcasts.voicecontrol.dialog.VoiceDialogManager::class.java))
        setField(service, "playbackContextMonitor", mock(PlaybackContextMonitor::class.java))
        setField(service, "audioRouteMonitor", monitor)
        setField(service, "listeningModePolicy", policy)
        setField(service, "voiceAsrEngine", Lazy { engine })
        setField(service, "asrBackendSelector", backendSelector)
        setField(service, "playbackBufferRecorder", playbackBufferRecorder)
        val modelManager = mock(ModelManager::class.java)
        whenever(modelManager.isModelReady(any())).thenReturn(true)
        // EnsureModelsReady reads the digest-verified readiness, not the cached flag; an
        // Unstubbed verified check returns a null Boolean and the unboxing NPE silently kills
        // The IO start job, so the observer never installs.
        whenever(modelManager.isModelReadyVerified(any())).thenReturn(true)
        whenever(modelManager.isLfmModelReady()).thenReturn(true)
        whenever(modelManager.ensureModel(any())).thenReturn(Result.success(Unit))
        whenever(modelManager.ensureLfmModel()).thenReturn(Result.success(Unit))
        setField(service, "modelManager", modelManager)
        setField(service, "modelsReadyCondition", modelsReady)
        setField(service, "audioFeedbackRenderer", mock(AudioFeedbackRenderer::class.java))
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
        try {
            // onStartCommand is package-private in android.app.Service; call it reflectively.
            VoiceControlService::class.java.getDeclaredMethod(
                "onStartCommand",
                Intent::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }.invoke(service, null, 0, 1)
            // startVoiceControl launches model prep on Dispatchers.IO and installs the observer on
            // Main afterwards; wait for the observer job to exist.
            awaitState("observer install") { getField(service, "modeJob") != null }

            // Phase 1: gate allowed + candidate present -> OFF branch acquires externally: SCO
            // opens, input stream opens+starts, confirmed selection publishes Headset, observer
            // starts the engine, which attaches VAD.
            println("[REPRO] pre-attach: capturing=${hardware.isCapturing()} route=${monitor.route.value} avail=${monitor.availability.value}")
            awaitState(
                "first VAD attach",
                probe = {
                    "open=${hardware.openCount} startStream=${hardware.startStreamCount} close=${hardware.closeCount} scoStarts=${scoPlatform.scoStarts} scoOnNow=${scoPlatform.scoLinkUp} acquiring=${getField(service, "acquisitionJob") != null} route=${monitor.route.value} avail=${monitor.availability.value} mode=${policy.mode.value}"
                },
            ) { hardware.attachCount >= 1 }
            assertEquals(AudioRoute.Headset(true), monitor.route.value)
            assertEquals(1, hardware.openCount)
            assertEquals(1, hardware.attachCount)

            // Phase 2: the transient conflict — other_app_playing blocks. The observer stops the
            // engine (VAD teardown) and releases routing (SCO off).
            otherAppRule.block("other_app_playing")
            awaitState("first VAD teardown") { hardware.vadStopCount >= 1 }

            // Phase 3: the conflict clears BEFORE anything else settles — the same churn the owner
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
            // Capture state at the assertion boundary for review.
            val finalAttach = hardware.attachCount
            val finalVadStop = hardware.vadStopCount
            val finalOpen = hardware.openCount
            val finalClose = hardware.closeCount
            println("[REPRO] Phase4 final: attach=$finalAttach vadStop=$finalVadStop open=$finalOpen close=$finalClose mode=${policy.mode.value}")
            assertEquals("attachCount", 2, finalAttach)
            // The second VAD is still active at this point — only the first was torn down.
            // vadStopCount==1 is correct: the first VAD stopped during the conflict,
            // the second VAD is still running.
            assertEquals("vadStopCount", 1, finalVadStop)
            assertEquals("openCount", 2, finalOpen)
            // Both streams were handed off to the engine via RouteAcquisition.handOffStream().
            // After handoff, release() skips closeInputStream — the engine owns teardown
            // (RouteAcquisition.kt:293). The first capture WAS torn down through engine stop
            // (nativeStopCaptureAndVad deletes gCapture), but that path does not call
            // closeInputCapture counted by FakeHardware. closeCount tracks explicit
            // closeInputStream calls only, not all stream teardown.
            assertEquals("closeCount (explicit close, not engine teardown)", 0, finalClose)
        } finally {
            runCatching {
                (getField(service, "modeJob") as? kotlinx.coroutines.Job)?.cancel()
            }
            runCatching { microphoneCapture.stopCapture() }
        }
    }

    /**
     * A failed attempt is recorded against the surface it was tried on, and the next attempt is
     * refused while that surface is unchanged. A route change can take that surface away and bring
     * it back without the user's devices changing at all — a SCO link dropping and returning leaves
     * the enumerated set identical. The refusal then outlives the situation it describes: capture
     * stays off for the rest of the session even though the headset microphone is usable again.
     */
    @Test
    fun `a capture that stopped on a route change comes back when a usable microphone returns`() = runBlocking {
        publishDevices(
            AndroidAudioRouteMonitor.EnumeratedDevices(
                outputs = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
                inputs = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
            ),
        )
        val modelsReady = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.ModelsReadyCondition(isReady = true)
        val gate = VoiceControlGate(
            listOf(FakeRule(EnabledByUserCondition.ID, VoiceControlRuleGroup.Setup), modelsReady),
            CoroutineScope(Dispatchers.Main.immediate),
        )
        var scoLinkUp: () -> Boolean = { false }
        val hardware = FakeHardware(scoReady = { scoLinkUp() })
        val microphoneCapture = MicrophoneCapture(context, hardware, mock(EpisodeReferenceEligibility::class.java))
        val monitor = AndroidAudioRouteMonitor(
            context,
            GracePeriodSignal(),
            enumeratedDeviceTypes = { deviceSet.get() },
        )
        val scoPlatform = FakeScoPlatform(microphoneCapture).also { scoLinkUp = { it.scoLinkUp } }
        val acquisition = RouteAcquisition(scoPlatform, timeoutMs = 1_200L, pollIntervalMs = 10L)

        val service = VoiceControlService().also { it.voiceSessionGate = VoiceSessionGate() }
        android.content.ContextWrapper::class.java.getDeclaredMethod(
            "attachBaseContext",
            Context::class.java,
        ).apply { isAccessible = true }.invoke(service, context)
        setField(service, "gate", gate)
        setField(service, "audioRouteMonitor", monitor)
        setField(service, "routeAcquisition", acquisition)
        setField(service, "modelsReadyCondition", modelsReady)
        setField(service, "gracePeriodSignal", GracePeriodSignal())
        setField(service, "captureStateHolder", VoiceControlCaptureStateHolder())

        // The production watcher of the offered surface, started exactly as the service starts it.
        val watcher = VoiceControlService::class.java.getDeclaredMethod("watchOfferedSurface").apply { isAccessible = true }

        try {
            awaitState("the gate to allow") { gate.state.value.allowed }
            assertEquals("precondition: the candidate surface is present", AudioRoute.BluetoothA2dpOnly, monitor.availability.value)
            val identityBefore = monitor.connectedDeviceIdentity()
            watcher.invoke(service)

            // The failure a real attempt leaves behind: recorded against the answer it was tried on.
            replaceField(service, "failedAttemptAvailability", AudioRoute.BluetoothA2dpOnly)
            replaceField(service, "failedAttemptSkipLogged", true)
            service.acquireExternalMicrophoneIfCandidate()
            assertNull(
                "precondition: while that surface is unchanged the attempt is refused",
                getField(service, "acquisitionJob"),
            )

            // The route leaves that surface: nothing usable, then a wired headset. The record stays —
            // it names the answer the attempt was tried on, not the one now present.
            publishDevices(AndroidAudioRouteMonitor.EnumeratedDevices(emptyList(), emptyList()))
            deliverDevicesChanged(monitor)
            settleRouteRead(monitor, AudioRoute.Unknown)
            publishDevices(
                AndroidAudioRouteMonitor.EnumeratedDevices(
                    outputs = listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES),
                    inputs = listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET),
                ),
            )
            deliverDevicesChanged(monitor)
            settleRouteReadUntil(monitor) { it != AudioRoute.BluetoothA2dpOnly }
            assertEquals(
                "precondition: the failure record survived the route change",
                AudioRoute.BluetoothA2dpOnly,
                getField(service, "failedAttemptAvailability"),
            )

            // ...and the SAME Bluetooth devices return, so the user's device set ends up identical.
            publishDevices(
                AndroidAudioRouteMonitor.EnumeratedDevices(
                    outputs = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
                    inputs = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
                ),
            )
            // An open SCO link classifies these same devices as a live headset; the candidate surface
            // is what is offered when the link is down, which is the situation this control describes.
            scoPlatform.stopBluetoothSco()
            deliverDevicesChanged(monitor)
            settleRouteRead(monitor, AudioRoute.BluetoothA2dpOnly)
            assertEquals("precondition: the user's device set is the same one", identityBefore, monitor.connectedDeviceIdentity())

            // The record still names this answer, but the surface has been through a route change
            // since the attempt failed against it. A usable microphone must be acquired again.
            service.acquireExternalMicrophoneIfCandidate()
            assertNotNull(
                "a usable microphone returning through a route change must be acquired again",
                getField(service, "acquisitionJob"),
            )
        } finally {
            val attempt = getField(service, "acquisitionJob") as? kotlinx.coroutines.Job
            runCatching { attempt?.cancel() }
            runCatching {
                (getField(service, "serviceScope") as? CoroutineScope)?.coroutineContext?.get(kotlinx.coroutines.Job)?.cancelChildren()
            }
            runCatching { microphoneCapture.stopCapture() }
            runCatching { attempt?.join() }
            testScheduler.runCurrent()
        }
    }
}
