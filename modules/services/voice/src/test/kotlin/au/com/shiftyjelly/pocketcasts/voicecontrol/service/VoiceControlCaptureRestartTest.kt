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
 * (owner log, 2026-10-17):
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
 * Provenance (@spec 3d4098f5 directive): git -S over origin/main..161092964 shows
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
            Thread {
                try {
                    Thread.sleep(150)
                    scoLinkUp = true
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
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
    private suspend fun awaitState(
        what: String,
        probe: () -> String = { "" },
        startProbe: (String) -> Unit = {},
        predicate: () -> Boolean,
    ) {
        val outcome = kotlinx.coroutines.CompletableDeferred<Result<Unit>>()
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate,
        ).launch {
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
        // Drive both the test scheduler (Main) and real dispatchers (IO) until the watcher
        // settles. Production code launches work on Dispatchers.IO which runs independently
        // of testScheduler; without yielding to real threads, that work never completes.
        while (!outcome.isCompleted) {
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(25.milliseconds)
            Thread.sleep(10)
            kotlinx.coroutines.yield()
        }
        outcome.await().getOrThrow()
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
                startProbe = { mark ->
                    println("[REPRO] timeout snapshot: $mark")
                    val acq = getField(service, "routeAcquisition")!!
                    for (name in listOf("inProgress", "openedStream", "openedDeviceId")) {
                        runCatching {
                            val f = acq.javaClass.getDeclaredField(name).apply { isAccessible = true }
                            println("[REPRO] acq.$name=${f.get(acq)}")
                        }
                    }
                    println("[REPRO] hasConfirmedStream=${acq.javaClass.getMethod("getHasConfirmedStream").invoke(acq)}")
                    println("[REPRO] acquisitionJob=${getField(service, "acquisitionJob")}")
                    println("[REPRO] currentMode=${getField(service, "currentMode")} restartBudget=${getField(service, "captureRestartBudget")}")
                    runCatching {
                        val log = getField(service, "lastObserverDecision")
                        println("[REPRO] lastObserverDecision=$log")
                    }
                    println("[REPRO] engineStart.isStarted=${getField(getField(service, "engineStart")!!, "isStarted")} generation=${(generationField.get(engineStartProbe) as java.util.concurrent.atomic.AtomicInteger).get()}")
                    println("[REPRO] modeJobActive=${(getField(service, "modeJob") as? kotlinx.coroutines.Job)?.isActive}")
                    // Read the engine state directly at the stall.
                    val es = getField(service, "engineStart")!!
                    println("[REPRO] isStarted=${es.javaClass.getDeclaredField("isStarted").apply { isAccessible = true }.get(es)}")
                    // Ask production WHY a start does not commit: invoke the real suspend
                    // startEngine once via its compiled continuation overload.
                    val startMethod = VoiceControlService::class.java.declaredMethods.firstOrNull {
                        it.name.contains("startEngine") && it.parameterCount == 2
                    }
                    if (startMethod == null) {
                        println("[REPRO] startEngine method not found")
                    } else {
                        startMethod.isAccessible = true
                        val done = java.util.concurrent.CountDownLatch(1)
                        val box = arrayOfNulls<String>(1)
                        val cont = object : kotlin.coroutines.Continuation<Any?> {
                            override val context = kotlin.coroutines.EmptyCoroutineContext
                            override fun resumeWith(result: kotlin.Result<Any?>) {
                                box[0] = result.fold({ "returned $it" }, { "threw $it" })
                                done.countDown()
                            }
                        }
                        runCatching {
                            startMethod.invoke(service, au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningMode.WakeWord, cont)
                        }.onFailure { box[0] = "reflect failed: $it" }
                        done.await(6, java.util.concurrent.TimeUnit.SECONDS)
                        println("[REPRO] direct startEngine(WakeWord) -> ${box[0]}")
                    }
                },
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
            awaitState("second VAD attach (the restart cycle)") { hardware.attachCount >= 2 }
            // Pin what the device log showed: the full sequence ran through production code.
            assertEquals(2, hardware.attachCount)
            assertEquals(2, hardware.vadStopCount)
            assertEquals(2, hardware.openCount)
            assertEquals(1, hardware.closeCount)
        } finally {
            runCatching {
                (getField(service, "modeJob") as? kotlinx.coroutines.Job)?.cancel()
            }
            runCatching { microphoneCapture.stopCapture() }
        }
    }
}
