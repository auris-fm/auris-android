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
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRule
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRuleGroup
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRuleState
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.ModelsReadyCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningModePolicy
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.IntentRoutingInput
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.ModelManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognitionContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContextMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AndroidAudioRouteMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.BluetoothCandidate
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.BluetoothTransport
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.RouteAcquisition
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordDetector
import dagger.Lazy
import java.lang.reflect.Field
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/**
 * When listening is on and capture is not running, a headset with a microphone becoming
 * available — at launch or MID-SESSION (the AirPods reconnect) — must trigger acquisition.
 *
 * Device evidence (task #7, Pixel, 13:33-13:34): BT on -> capture started; BT off -> capture
 * stopped (EligibilityOff); BT on again -> three device-change events arrived, but the mode
 * observer NEVER re-ran: its `combine` watches mode + route + gate, and all three were
 * unchanged (route stays `Unknown`, mode stays `Off` — only `availability` flipped back to the
 * candidate). The Off branch, which is what attempts acquisition from availability, never
 * executed. The mic stayed dead until the app was restarted.
 *
 * The ruled fix: the observer must also observe `availability`, so an availability change
 * re-runs the decision. Each test here fails on the pre-fix code (no second emission -> no
 * acquisition -> no attach).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceControlModeObserverAvailabilityTest {
    companion object {
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
    private lateinit var testScheduler: TestCoroutineScheduler

    @Before
    fun plantCaptureStopTree() {
        captureStopTree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                // ONLY the capture-failure stop counts: route-change and user-requested stops
                // share the log line and must not release a fault-fixture wait.
                if (message.orEmpty().contains("mic_capture_stopped: reasons=capture_failure")) {
                    captureStoppedLogCount.incrementAndGet()
                }
            }
        }
        timber.log.Timber.plant(captureStopTree)
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        testScheduler = TestCoroutineScheduler()
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    }

    @After
    fun tearDown() {
        if (this::captureStopTree.isInitialized) timber.log.Timber.uproot(captureStopTree)
        Dispatchers.resetMain()
    }

    // ---- fakes at the native boundary (same contracts as VoiceControlCaptureRestartTest) ----

    private inner class FakeScoPlatform(
        private val microphoneCapture: MicrophoneCapture,
        var establish: Boolean = true,
        /** Present the input as a wired headset microphone rather than a classic SCO one. */
        val wiredInput: Boolean = false,
    ) : au.com.shiftyjelly.pocketcasts.voicecontrol.route.RouteAcquisitionPlatform {
        var scoStarts = 0
        var scoStops = 0

        @Volatile var scoLinkUp = false
            internal set

        override fun bluetoothCandidates() = listOf(
            BluetoothCandidate(deviceId = SCO_INPUT_ID, transport = BluetoothTransport.ClassicSco),
        )

        /** When set, [startBluetoothSco] does not bring the link up: the test stages the race
         * where the SCO state broadcast arrives before the acquisition observes selection. */
        var holdScoDown = false

        override fun startBluetoothSco() {
            scoStarts++
            if (!establish || holdScoDown) return
            bringScoUp()
        }

        /** The link comes up for real: the acquisition's selection check sees it. */
        fun bringScoUp() {
            scoLinkUp = true
            deliverScoUpBroadcast()
        }

        /** The platform reports the link up (shadow + broadcast) while [scoLinkUp] stays down:
         * the monitor classifies Headset and the observer may start the stream, but the
         * acquisition's own selection check has not observed selection yet. */
        fun deliverScoUpBroadcast() {
            setShadowBluetoothScoOn(audioManager, true)
            shadowOf(audioManager).setInputDevices(
                listOf(AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).build()),
            )
            @Suppress("DEPRECATION")
            deliver(
                Intent(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                    .putExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_CONNECTED),
            )
        }

        /** The link goes down WITHOUT touching the device set: no trigger, only a route flip. */
        fun deliverScoDownBroadcast() {
            scoLinkUp = false
            setShadowBluetoothScoOn(audioManager, false)
            @Suppress("DEPRECATION")
            deliver(
                Intent(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                    .putExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_DISCONNECTED),
            )
        }

        override fun stopBluetoothSco() {
            scoStops++
            scoLinkUp = false
            setShadowBluetoothScoOn(audioManager, false)
            // The SCO LINK DOES NOT CHANGE THE USER'S DEVICE SET (voice-control-core.md >
            // Route acquisition): a real device keeps the enumerated SCO input while the link is
            // down, so clearing it here would fake a device trigger and re-arm the budget.
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
        override fun audioMode(): Int = audioManager.mode
        override fun setAudioMode(mode: Int) {
            audioManager.mode = mode
        }

        override fun deviceType(deviceId: Int): Int? =
            when {
                deviceId != SCO_INPUT_ID -> null
                wiredInput -> AudioDeviceInfo.TYPE_WIRED_HEADSET
                else -> AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }

        /**
         * Presents the input as ALREADY selected before the service starts — an already-isolated
         * input, the way a headset that another session (a call, another app's routing) made the
         * active microphone looks when voice control turns on. The first capture then needs no
         * acquisition, which is the no-acquisition route surface the a2dp rows cannot produce:
         * a first capture there needs the SCO acquisition to observe a route at all.
         */
        fun seedAlreadySelectedInput() {
            scoLinkUp = true
            setShadowBluetoothScoOn(audioManager, true)
            shadowOf(audioManager).setInputDevices(
                listOf(AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).build()),
            )
            @Suppress("DEPRECATION")
            deliver(
                Intent(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                    .putExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_CONNECTED),
            )
        }

        override fun openInputStream(): Int = microphoneCapture.openStreamForAcquisition()
        override fun startInputStream(): Boolean = microphoneCapture.startAcquiredStream()
        override fun closeInputStream() = microphoneCapture.closeAcquiredStream()
    }

    private class FakeHardware(private val scoReady: () -> Boolean) : CaptureHardware {
        var capturing = false

        /** When set, waitForVadEvent reports the native VAD FAULT (-2): the engine's own
         *  death path -- the capture stream faults, the processing job fails, and the ENGINE
         *  invokes its real onTerminated callback. Fixtures never call service handlers
         *  reflectively: they can only hand in a generation production would never produce. */
        @Volatile
        var faultVad = false

        /** When set, the VAD attach always fails: simulates a start that never comes up. */
        var failVadAttach = false
        var attachCount = 0
        var vadStopCount = 0
        var openCount = 0
        var startStreamCount = 0
        var closeCount = 0

        override fun startCaptureAndVad(sampleRate: Int, channels: Int, assetManager: android.content.res.AssetManager): Boolean {
            if (!capturing || failVadAttach) return false
            attachCount++
            return true
        }

        override fun getSpeechPcmRange(out: ShortArray, fromSample: Int): Int = 0

        override fun stopCaptureAndVad() {
            vadStopCount++
            capturing = false
        }

        override fun openInputCapture(sampleRate: Int, channels: Int): Int {
            if (!scoReady()) return -1
            openCount++
            return SCO_INPUT_ID
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
            // ONE-SHOT: exactly the capture that is polling when the fault is armed dies; the
            // automatic restart gets clean polls from its first event wait on.
            if (faultVad) {
                faultVad = false
                return -2
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            while (capturing && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
            return 0
        }

        override fun getInProgressSnapshot(buffer: ShortArray, header: LongArray): Int = 0

        override fun setEarlyWakeCutoff(samples: Int) = Unit

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
        override suspend fun recognize(input: IntentRoutingInput, context: VoiceRecognitionContext, logicalRequestId: String) =
            throw IllegalStateException("not used in this harness")

        override fun release() = Unit
    }

    // ---- harness -------------------------------------------------------------

    private fun setShadowBluetoothScoOn(am: AudioManager, on: Boolean) {
        val method = org.robolectric.shadows.ShadowAudioManager::class.java
            .getDeclaredMethod("setBluetoothScoOn", Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        method.invoke(shadowOf(am), on)
    }

    private fun setOutputDevices(vararg types: Int) {
        shadowOf(audioManager).setOutputDevices(
            types.map { type -> AudioDeviceInfoBuilder.newBuilder().setType(type).build() },
        )
    }

    private fun setInputDevices(vararg types: Int) {
        shadowOf(audioManager).setInputDevices(
            types.map { type -> AudioDeviceInfoBuilder.newBuilder().setType(type).build() },
        )
    }

    /** AirPods connected-but-unrouted: A2DP output plus the idle SCO input pair. */
    private fun setAirPodsConnected() {
        setOutputDevices(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        setInputDevices(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
    }

    private fun getField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun setField(target: Any, name: String, value: Any?) {
        val field: Field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
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
                // PROGRESS-GATED virtual time (hardening): advancing the clock on a fixed cadence
                // let virtual time outrun the subject's REAL background work under CPU starvation --
                // the budget expired while the subject was strictly behind, which reads as a product
                // race and is not one. A step of virtual time is granted only after the host has
                // actually pumped (real sleep + main-looper task + yield), so simulated time tracks
                // work done, not wall-clock cadence. The real-time stall bound below still catches a
                // genuinely wedged harness.
                while (!predicate()) {
                    val waitedMs = testScheduler.currentTime - startedAtMs
                    if (waitedMs >= WAIT_BUDGET_MS) {
                        startProbe(last)
                        error("timed out waiting for $what after ${waitedMs}ms of simulated time; last=$last")
                    }
                    // Let the subject's real threads run BEFORE granting the next virtual step.
                    Thread.sleep(10)
                    runCatching { shadowOf(Looper.getMainLooper()).runOneTask() }
                    kotlinx.coroutines.yield()
                    testScheduler.advanceTimeBy(WAIT_STEP_MS.milliseconds)
                    last = probe()
                }
                outcome.complete(Result.success(Unit))
            } catch (e: Throwable) {
                outcome.complete(Result.failure(e))
            }
        }
        try {
            // Real time bounds ONLY this outer pump, and generously: its job is to keep the host
            // moving, so a slow machine needs more of it, not less. Expiry here means the harness
            // itself stopped making progress, which is a different failure from the subject's.
            val pumpDeadline = System.currentTimeMillis() + PUMP_BUDGET_MS
            while (!outcome.isCompleted) {
                if (System.currentTimeMillis() > pumpDeadline) {
                    error("harness pump stalled waiting for $what after ${PUMP_BUDGET_MS}ms of real time")
                }
                testScheduler.runCurrent()
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
     * Builds and starts the real service over the real gate/policy/monitor/acquisition stack,
     * exactly as VoiceControlCaptureRestartTest does. Returns the harness handles.
     */
    private suspend fun startService(
        modelsReady: ModelsReadyCondition,
        otherAppRule: FakeRule,
        foregroundRule: FakeRule,
        acquisitionTimeoutMs: Long = 6_000L,
        scoEstablishes: Boolean = true,
        alwaysCaptureReady: Boolean = false,
        wiredInput: Boolean = false,
        startIsolated: Boolean = false,
    ): Harness {
        val userRule = FakeRule(au.com.shiftyjelly.pocketcasts.voicecontrol.gate.EnabledByUserCondition.ID, VoiceControlRuleGroup.Setup)
        val gate = VoiceControlGate(
            listOf(userRule, otherAppRule, foregroundRule, modelsReady),
            CoroutineScope(Dispatchers.Main.immediate),
        )

        var scoLinkUp: () -> Boolean = { false }
        // Non-BT rows (wired) capture without an SCO link: the fake capture hardware accepts
        // the input unconditionally there.
        val hardware =
            if (alwaysCaptureReady) FakeHardware(scoReady = { true })
            else FakeHardware(scoReady = { scoLinkUp() })
        val microphoneCapture = MicrophoneCapture(context, hardware, mock(EpisodeReferenceEligibility::class.java))
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
                override suspend fun translate(text: String, sourceLanguage: String): Result<String> =
                    Result.failure(IllegalStateException("unused"))
            },
            collectionAuthority = { au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision(false, false) },
            retentionSink = org.mockito.kotlin.mock(),
            segmentObserver = org.mockito.kotlin.mock(),
            context = context,
        )

        val backendSelector = mock(AsrBackendSelector::class.java)
        whenever(backendSelector.select()).thenReturn(FakeBackend())

        val playbackBufferRecorder = PlaybackBufferRecorder(FingerprintPcmTap())
        val monitor = AndroidAudioRouteMonitor(context, GracePeriodSignal())
        val scoPlatform = FakeScoPlatform(
            microphoneCapture,
            establish = scoEstablishes,
            wiredInput = wiredInput,
        ).also { scoLinkUp = { it.scoLinkUp } }
        if (startIsolated) scoPlatform.seedAlreadySelectedInput()
        val acquisition = RouteAcquisition(scoPlatform, timeoutMs = acquisitionTimeoutMs, pollIntervalMs = 10L)

        val policy = ListeningModePolicy(gate, monitor, GracePeriodSignal(), CoroutineScope(Dispatchers.Main.immediate))

        val notificationManager = mock(VoiceControlNotificationManager::class.java)
        whenever(notificationManager.notificationId).thenReturn(1001)
        whenever(notificationManager.createListeningNotification(any())).thenReturn(mock(Notification::class.java))
        whenever(notificationManager.createDownloadingNotification()).thenReturn(mock(Notification::class.java))

        val service = VoiceControlService().also { it.voiceSessionGate = VoiceSessionGate() }
        android.content.ContextWrapper::class.java.getDeclaredMethod(
            "attachBaseContext",
            Context::class.java,
        ).apply { isAccessible = true }.invoke(service, context)
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

        VoiceControlService::class.java.getDeclaredMethod(
            "onStartCommand",
            Intent::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(service, null, 0, 1)

        return Harness(
            service = service,
            monitor = monitor,
            hardware = hardware,
            scoPlatform = scoPlatform,
            policy = policy,
            microphoneCapture = microphoneCapture,
            acquisition = acquisition,
        )
    }

    private data class Harness(
        val service: VoiceControlService,
        val monitor: AndroidAudioRouteMonitor,
        val hardware: FakeHardware,

        val scoPlatform: FakeScoPlatform,
        val policy: ListeningModePolicy,
        val microphoneCapture: MicrophoneCapture,
        val acquisition: RouteAcquisition,
    )

    /** Whether the service's engine coordinator has a live (committed) start. */
    private fun Harness.engineStarted(): Boolean =
        getField(service, "engineStart")!!.javaClass
            .getDeclaredMethod("isStarted").apply { isAccessible = true }
            .invoke(getField(service, "engineStart")) as Boolean

    /**
     * End the capture the way production dies: the capture stream FAULTS (FakeHardware reports
     * the native VAD fault), the engine's processing job fails, and the ENGINE calls its own
     * `onTerminated` -- dispatched through `serviceScope.launch(Dispatchers.Main)` with the
     * generation the engine itself captured at start. The service handler is never invoked
     * reflectively by a fixture.
     */
    private suspend fun endCaptureByFault(harness: Harness) {
        val before = captureStoppedLogCount.get()
        harness.hardware.faultVad = true
        // Deterministic, not a timed beat: the fault is consumed by the poll loop within one
        // poll interval (<=500ms real), then the engine's own onTerminated dispatches to the
        // service handler, which logs mic_capture_stopped for the death. Awaiting THAT log
        // line means the death is fully processed before any test-side outcome wait begins
        // (the engine-started flag can flip false and true again within one restart, so it is
        // not a sampleable edge).
        // settleUntil, NOT awaitState: no virtual time while the callback is in flight. The
        // capture-failure log identifies the callback; the caller's assertions verify the
        // recovery outcome.
        settleUntil("termination processed") { captureStoppedLogCount.get() > before }
    }

    /** Ordered count of capture-failure stops the fault fixture has processed so far. */
    private val captureStoppedLogCount = java.util.concurrent.atomic.AtomicInteger(0)
    private lateinit var captureStopTree: timber.log.Timber.Tree


    @Test
    fun `a headset capture that ends by itself re-acquires and restarts exactly once`() = runBlocking {
        setAirPodsConnected()
        val modelsReady = ModelsReadyCondition(isReady = false)
        val otherAppRule = FakeRule("other_app_playing", VoiceControlRuleGroup.Conflicts)
        val foregroundRule = FakeRule("app_in_foreground", VoiceControlRuleGroup.Context)
        val harness = startService(modelsReady, otherAppRule, foregroundRule)
        try {
            harness.awaitObserverInstalled()
            awaitState("capture up", probe = { harness.wedgeState() }) { harness.hardware.attachCount >= 1 }
            val scoStartsAfterFirst = harness.scoPlatform.scoStarts

            // Capture ends on its own (pipeline termination, no user change): exactly one
            // automatic restart — re-acquire plus engine start.
            endCaptureByFault(harness)
            awaitState("capture restarted", probe = { harness.wedgeState() }) { harness.hardware.attachCount >= 2 }
            assertEquals(
                "exactly one automatic restart re-acquired the headset",
                scoStartsAfterFirst + 1,
                harness.scoPlatform.scoStarts,
            )
        } finally {
            harness.stop()
        }
    }

    @Test
    fun `a granted restart that fails to start withholds further restarts`() = runBlocking {
        setAirPodsConnected()
        val modelsReady = ModelsReadyCondition(isReady = false)
        val otherAppRule = FakeRule("other_app_playing", VoiceControlRuleGroup.Conflicts)
        val foregroundRule = FakeRule("app_in_foreground", VoiceControlRuleGroup.Context)
        // The acquisition restart path: the restart's acquisition attempt fails.
        val harness = startService(modelsReady, otherAppRule, foregroundRule, acquisitionTimeoutMs = 400L)
        try {
            harness.awaitObserverInstalled()
            awaitState("capture up", probe = { harness.wedgeState() }) { harness.hardware.attachCount >= 1 }

            // Arm the failure BEFORE the fault: the observer may begin the restart as soon as
            // the death is processed, and a failure armed after that races it.
            harness.scoPlatform.establish = false
            endCaptureByFault(harness)
            awaitState("restart withheld", probe = { harness.wedgeState() }) {
                val budget = getField(harness.service, "captureRestartBudget")!!
                !(budget.javaClass.getDeclaredField("restartAvailable").apply { isAccessible = true }
                    .get(budget) as Boolean)
            }
            val scoStartsAfterFailure = harness.scoPlatform.scoStarts

            // No further automatic attempts.
            Thread.sleep(400)
            testScheduler.advanceTimeBy(400.milliseconds)
            assertEquals(
                "a failed restart leaves restarts withheld",
                scoStartsAfterFailure,
                harness.scoPlatform.scoStarts,
            )
        } finally {
            harness.stop()
        }
    }

    @Test
    fun `a granted restart whose engine start fails withholds further restarts`() = runBlocking {
        setAirPodsConnected()
        val modelsReady = ModelsReadyCondition(isReady = false)
        val otherAppRule = FakeRule("other_app_playing", VoiceControlRuleGroup.Conflicts)
        val foregroundRule = FakeRule("app_in_foreground", VoiceControlRuleGroup.Context)
        val harness = startService(modelsReady, otherAppRule, foregroundRule)
        try {
            harness.awaitObserverInstalled()
            awaitState("capture up", probe = { harness.wedgeState() }) { harness.hardware.attachCount >= 1 }

            // The restart's acquisition will succeed, but the engine start fails (the VAD
            // attach never happens): the withheld path through startEngine == false.
            // Arm the failure BEFORE the fault (same race as the acquisition-failure control).
            harness.hardware.capturing = false
            endCaptureByFault(harness)
            awaitState("restart withheld", probe = { harness.wedgeState() }) {
                val budget = getField(harness.service, "captureRestartBudget")!!
                !(budget.javaClass.getDeclaredField("restartAvailable").apply { isAccessible = true }
                    .get(budget) as Boolean)
            }
        } finally {
            harness.stop()
        }
    }

    @Test
    fun `a fast-dying capture with real acquire and release churn restarts once then stops`() = runBlocking {
        setAirPodsConnected()
        val modelsReady = ModelsReadyCondition(isReady = false)
        val otherAppRule = FakeRule("other_app_playing", VoiceControlRuleGroup.Conflicts)
        val foregroundRule = FakeRule("app_in_foreground", VoiceControlRuleGroup.Context)
        val harness = startService(modelsReady, otherAppRule, foregroundRule)
        try {
            harness.awaitObserverInstalled()
            awaitState("capture up", probe = { harness.wedgeState() }) { harness.hardware.attachCount >= 1 }
            val scoStartsAfterFirst = harness.scoPlatform.scoStarts

            // First self-end: one restart (the acquire/release churn this causes is not a
            // trigger and must not re-arm anything).
            endCaptureByFault(harness)
            awaitState("capture restarted", probe = { harness.wedgeState() }) { harness.hardware.attachCount >= 2 }
            val scoStartsAfterRestart = harness.scoPlatform.scoStarts
            Thread.sleep(300)
            testScheduler.advanceTimeBy(300.milliseconds)

            // The restarted capture dies fast again: withheld, and the churn of the second
            // release must not bring it back.
            endCaptureByFault(harness)
            awaitState("restarts withheld", probe = { harness.wedgeState() }) {
                val budget = getField(harness.service, "captureRestartBudget")!!
                !(budget.javaClass.getDeclaredField("restartAvailable").apply { isAccessible = true }
                    .get(budget) as Boolean)
            }
            Thread.sleep(400)
            testScheduler.advanceTimeBy(400.milliseconds)
            assertEquals(
                "exactly one restart, then none: the churn is not a trigger",
                scoStartsAfterRestart,
                harness.scoPlatform.scoStarts,
            )

            // A real device change re-arms and capture resumes. Let the removal's debounced
            // refresh land first, so the add is a distinct identity change.
            setOutputDevices(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            setInputDevices()
            harness.monitor.onDevicesChanged()
            awaitState("devices removed observed", probe = { harness.wedgeState() }) {
                harness.monitor.availability.value == AudioRoute.Speaker
            }
            setAirPodsConnected()
            harness.monitor.onDevicesChanged()
            // The add's debounced refresh drives the observer; settle it before asserting.
            Thread.sleep(500)
            testScheduler.advanceTimeBy(500.milliseconds)
            awaitState("capture back up after the real trigger", probe = { harness.wedgeState() }) {
                harness.hardware.attachCount >= 3
            }
        } finally {
            harness.stop()
        }
    }

    /**
     * An ordinary acquisition must leave the stream marked acquired — both when the
     * acquisition opens the stream itself and when the mode observer won the race and the
     * attempt returns through the reuse path. Either way the capture runs on the link the
     * acquisition selected, so its self-end restart must wait for the reacquire.
     */
    @Test
    fun `acquire marks the stream acquired on the normal and the reuse path`() = runBlocking {
        val microphoneCapture = MicrophoneCapture(
            ApplicationProvider.getApplicationContext(),
            FakeHardware(scoReady = { true }),
            mock(EpisodeReferenceEligibility::class.java),
        )
        setAirPodsConnected()

        // Normal path: the acquisition opens, confirms and starts the stream itself.
        val acquisition = RouteAcquisition(FakeScoPlatform(microphoneCapture), timeoutMs = 6_000L, pollIntervalMs = 10L)
        val normal = acquisition.acquire(AudioRoute.BluetoothA2dpOnly, gateAllows = { true })
        assertTrue("normal acquisition succeeds", normal is au.com.shiftyjelly.pocketcasts.voicecontrol.route.AcquisitionResult.Acquired)
        assertTrue(
            "the normal acquisition's stream is marked acquired",
            getField(acquisition, "acquiredForCapture") as Boolean,
        )

        // Reuse path: the observer already opened the stream; the attempt returns Acquired
        // through the reuse branch once selection completes.
        val racedPlatform = FakeScoPlatform(microphoneCapture)
        racedPlatform.holdScoDown = true
        val raced = RouteAcquisition(racedPlatform, timeoutMs = 6_000L, pollIntervalMs = 10L)
        assertTrue("the observer's early stream opens", raced.confirmCaptureStream())
        kotlinx.coroutines.coroutineScope {
            val result = async {
                raced.acquire(AudioRoute.BluetoothA2dpOnly, gateAllows = { true })
            }
            racedPlatform.bringScoUp()
            assertTrue("the raced acquisition succeeds", result.await() is au.com.shiftyjelly.pocketcasts.voicecontrol.route.AcquisitionResult.Acquired)
            assertTrue(
                "the reuse path's stream is marked acquired too",
                getField(raced, "acquiredForCapture") as Boolean,
            )
        }
    }

    /**
     * End to end on the ordinary path: an AirPods capture's self-end restart comes only
     * after its FRESH acquisition, never as a spend on the just-released routing.
     */
    @Test
    fun `an AirPods capture's restart comes only after its fresh acquisition`() = runBlocking {
        val modelsReady = ModelsReadyCondition(isReady = false)
        val otherAppRule = FakeRule("other_app_playing", VoiceControlRuleGroup.Conflicts)
        val foregroundRule = FakeRule("app_in_foreground", VoiceControlRuleGroup.Context)
        setAirPodsConnected()
        val harness = startService(modelsReady, otherAppRule, foregroundRule)
        try {
            harness.awaitObserverInstalled()
            awaitState("capture up", probe = { harness.wedgeState() }) { harness.hardware.attachCount >= 1 }
            awaitState("capture committed", probe = { harness.wedgeState() }) { harness.engineStarted() }
            val scoBeforeEnd = harness.scoPlatform.scoStarts
            val attaches = harness.hardware.attachCount

            endCaptureByFault(harness)
            awaitState("restarted", probe = { harness.wedgeState() }) { harness.hardware.attachCount >= attaches + 1 }
            assertEquals(
                "the restart followed a fresh acquisition",
                scoBeforeEnd + 1,
                harness.scoPlatform.scoStarts,
            )
        } finally {
            harness.stop()
        }
    }

    /**
     * The acquisition can lose the race against the mode observer: the SCO state broadcast
     * publishes the route while the acquisition is still waiting to observe selection, the
     * observer starts the stream itself, and the acquisition then returns Acquired through
     * the reuse path. That capture still runs on the link the acquisition selected, so its
     * self-end restart must WAIT for the reacquire — not spend inside the release's debounce
     * window and open capture on the routing the release is still tearing down.
     */
    @Test
    fun `a capture the observer starts mid-acquisition still waits its restart on the reacquire`() = runBlocking {
        val modelsReady = ModelsReadyCondition(isReady = false)
        val otherAppRule = FakeRule("other_app_playing", VoiceControlRuleGroup.Conflicts)
        val foregroundRule = FakeRule("app_in_foreground", VoiceControlRuleGroup.Context)
        setAirPodsConnected()
        val harness = startService(modelsReady, otherAppRule, foregroundRule)
        try {
            harness.awaitObserverInstalled()
            // The acquisition starts but selection never completes while the link is held down.
            harness.scoPlatform.holdScoDown = true
            awaitState(
                "acquisition started",
                probe = { "scoStarts=${harness.scoPlatform.scoStarts} " + harness.wedgeState() },
            ) { harness.scoPlatform.scoStarts >= 1 }

            // The SCO state broadcast arrives first: the route reads Headset and the observer
            // starts the stream itself, before the acquisition has observed selection.
            harness.scoPlatform.deliverScoUpBroadcast()
            // LOST-OR-LATE CLASSIFIER (spec): if the budget expires, keep waiting on the WALL
            // CLOCK (no virtual-time grants) well past it. Attaching later proves timing/starva-
            // tion (test fix: generous budget); never attaching proves the observer DROPS the
            // SCO-up trigger under contention (product fix with a red test).
            try {
                awaitState(
                    "capture up mid-acquisition",
                    probe = { "attach=${harness.hardware.attachCount} scoStarts=${harness.scoPlatform.scoStarts} " + harness.wedgeState() },
                ) { harness.hardware.attachCount >= 1 }
            } catch (e: IllegalStateException) {
                if (!e.message.orEmpty().contains("capture up mid-acquisition")) throw e
                val deadline = System.currentTimeMillis() + 180_000
                var attachedLate = false
                while (System.currentTimeMillis() < deadline) {
                    testScheduler.runCurrent()
                    runCatching { shadowOf(Looper.getMainLooper()).runOneTask() }
                    Thread.sleep(20)
                    if (harness.hardware.attachCount >= 1) {
                        attachedLate = true
                        break
                    }
                }
                error(
                    if (attachedLate) "CLASSIFIED: capture attaches LATE (starvation/timing, not a lost trigger)"
                    else "CLASSIFIED: SCO-up trigger LOST -- observer never attached (product bug candidate)"
                )
            }

            // Selection completes; the acquisition returns Acquired through the reuse path.
            harness.scoPlatform.bringScoUp()
            awaitState("acquisition settled", probe = { harness.wedgeState() }) { harness.monitor.route.value == AudioRoute.Headset(true) }
            awaitState("capture committed", probe = { harness.wedgeState() }) { harness.engineStarted() }
            val scoBeforeEnd = harness.scoPlatform.scoStarts
            val attaches = harness.hardware.attachCount

            // Self-end: the restart must wait for a FRESH acquisition (one more SCO start),
            // not spend on the just-released routing. The platform answers SCO normally now.
            harness.scoPlatform.holdScoDown = false
            endCaptureByFault(harness)
            awaitState("restarted after the reacquire", probe = { harness.wedgeState() }) { harness.hardware.attachCount >= attaches + 1 }
            assertEquals(
                "the restart followed a fresh acquisition",
                scoBeforeEnd + 1,
                harness.scoPlatform.scoStarts,
            )
        } finally {
            harness.stop()
        }
    }

    /**
     * The flag that says "this capture's routing came from an acquisition" must follow the
     * STREAM, not the session: an AirPods capture sets it, then the user switches to a wired
     * headset — the wired capture opens its own stream (no acquisition), so a self-end must
     * get its one restart at once, not wait on an acquisition that will never happen.
     */
    @Test
    fun `a wired capture after an AirPods session still gets its one restart`() = runBlocking {
        val withheldLogs = intArrayOf(0)
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (message.contains("mic_restart_withheld")) withheldLogs[0]++
            }
        }
        timber.log.Timber.plant(tree)
        val modelsReady = ModelsReadyCondition(isReady = false)
        val otherAppRule = FakeRule("other_app_playing", VoiceControlRuleGroup.Conflicts)
        val foregroundRule = FakeRule("app_in_foreground", VoiceControlRuleGroup.Context)
        setAirPodsConnected()
        val harness = startService(modelsReady, otherAppRule, foregroundRule)
        try {
            harness.awaitObserverInstalled()
            awaitState("AirPods capture up", probe = { harness.wedgeState() }) { harness.hardware.attachCount >= 1 }

            // The user switches headsets: AirPods go away, and another headset connects
            // already selected (the link is up before voice control re-evaluates). The device
            // change re-arms the budget and the new capture starts with NO acquisition.
            setOutputDevices(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            setInputDevices()
            harness.monitor.onDevicesChanged()
            awaitState("AirPods capture torn down", probe = { harness.wedgeState() }) { harness.hardware.vadStopCount >= 1 }
            // STAGE THE NEW HEADSET IN ONE STEP. Two separate list writes give the observer a
            // transient intermediate identity (speaker output with the SCO input already seeded);
            // seen after a capture death, such a flap would count as a fresh device trigger,
            // re-arm the budget and clear the owed restart -- exactly the B pattern this test
            // exists NOT to trigger.
            setOutputDevices(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            setInputDevices(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            harness.scoPlatform.seedAlreadySelectedInput()
            harness.monitor.onDevicesChanged()
            awaitState("wired capture up", probe = { harness.wedgeState() }) { harness.hardware.attachCount >= 2 }
            awaitState("wired capture committed", probe = { harness.wedgeState() }) { harness.engineStarted() }
            // The observer must have OBSERVED the final identity before the deaths begin: the
            // identity comparison runs inside the observer, so a staged change it has not yet
            // seen would fire as a trigger between a death and its restart, however it was
            // staged. (8|7|0 is A2DP output + SCO input pair, the settled signature here.)
            awaitState("final device identity observed", probe = { harness.wedgeState() }) {
                (getField(harness.service, "lastConnectedDeviceIdentity") as String) == "8|7|0"
            }


            // The wired capture ends by itself: one restart at once (its stream was its own,
            // not an acquisition's), then the fast second death withholds.
            withheldLogs[0] = 0
            endCaptureByFault(harness)
            awaitState("wired capture restarted", probe = { harness.wedgeState() }) { harness.hardware.attachCount >= 3 }
            awaitState("restart committed", probe = { harness.wedgeState() }) { harness.engineStarted() }
            endCaptureByFault(harness)
            awaitState(
                "withheld",
                probe = {
                    val b = getField(harness.service, "captureRestartBudget")!!
                    val avail = b.javaClass.getDeclaredField("restartAvailable").apply { isAccessible = true }.get(b)
                    val flight = b.javaClass.getDeclaredField("restartInFlight").apply { isAccessible = true }.get(b)
                    "available=$avail inFlight=$flight logs=${withheldLogs[0]} attach=${harness.hardware.attachCount} vadStop=${harness.hardware.vadStopCount} elapsed=${android.os.SystemClock.elapsedRealtime()} sched=${testScheduler.currentTime}"
                },
            ) {
                val budget = getField(harness.service, "captureRestartBudget")!!
                !(budget.javaClass.getDeclaredField("restartAvailable")
                    .apply { isAccessible = true }.get(budget) as Boolean) && withheldLogs[0] >= 1
            }
            Thread.sleep(300)
            testScheduler.advanceTimeBy(300.milliseconds)
            assertEquals("exactly one withheld log", 1, withheldLogs[0])
            assertEquals("no restart after withholding", 3, harness.hardware.attachCount)
        } finally {
            timber.log.Timber.uproot(tree)
            harness.stop()
        }
    }

    /**
     * The re-confirmation wedge: a self-ended acquisition capture whose restart decision lands
     * while the route is not yet startable keeps `pendingAutomaticRestart` true and waits for a
     * route/availability emission to bring the decision back. When the platform has already torn
     * the SCO link down before our release, teardown's `stopBluetoothSco`/`clearCommunicationDevice`
     * are no-ops, the device list is unchanged, and NO emission ever arrives — the restart waits
     * forever and the microphone stays dead in silence. The bound must expire, spend the credit,
     * and attempt the start anyway (what main does), or report the restart as withheld.
     */
    /**
     * The wedge, end to end: a self-ended acquisition capture whose stream was never confirmed
     * leaves its owed restart waiting, and nothing resolves it — the platform tore the SCO link
     * down, so the release's SCO/communication-device calls are no-ops and no emission follows.
     * Within the bound it must not sit silent past expiry: the fallback attempts the start, and
     * on a healthy surface that start succeeds.
     */
    @Test
    fun `a pending restart waiting on re-confirmation does not wait forever`() = runBlocking {
        val harness = heldWedge(ModelsReadyCondition(isReady = false), FakeRule("other_app_playing", VoiceControlRuleGroup.Conflicts), FakeRule("app_in_foreground", VoiceControlRuleGroup.Context))
        try {
            val attachBefore = harness.hardware.attachCount
            // The platform heals before the bound expires; the fallback must still run.
            harness.scoPlatform.bringScoUp()
            Thread.sleep(100)
            testScheduler.advanceTimeBy(2_000.milliseconds)
            settleUntil("the fallback start") { harness.hardware.attachCount > attachBefore }
            Thread.sleep(100)
            testScheduler.advanceTimeBy(1_500.milliseconds)
            settle()
            assertEquals("exactly one fallback start", attachBefore + 1, harness.hardware.attachCount)
        } finally {
            harness.stop()
        }
    }

    private fun Harness.wedgeState(): String {
        val b = getField(service, "captureRestartBudget")!!
        val avail = b.javaClass.getDeclaredField("restartAvailable").apply { isAccessible = true }.get(b)
        val flight = b.javaClass.getDeclaredField("restartInFlight").apply { isAccessible = true }.get(b)
        return "available=$avail inFlight=$flight pending=${getField(service, "pendingAutomaticRestart")} " +
            "expired=${getField(service, "restartReconfirmationExpired")} timer=${getField(service, "reconfirmationTimer")} " +
            "attach=${hardware.attachCount} vadStop=${hardware.vadStopCount} open=${hardware.openCount}"
    }

    /** Runs what is due WITHOUT moving the virtual clock, so time-bound state can be inspected. */
    private fun settle() {
        repeat(10) {
            testScheduler.runCurrent()
            runCatching { shadowOf(Looper.getMainLooper()).runOneTask() }
            Thread.sleep(10)
        }
    }

    /** Real-time-bounded wait on a virtual-clock-stable condition (no scheduler advancing). */
    private fun settleUntil(what: String, probe: () -> String = { "" }, predicate: () -> Boolean) {
        // WALL-CLOCK BUDGET sized for full-suite load: this pump loop runs alongside the rest of
        // the suite on the same host, so a 10 s deadline expired on a starved machine with the
        // subject strictly behind -- a host artifact, not a product race. 60 s still bounds a
        // genuinely wedged subject (the subject's own steps complete in milliseconds when run).
        val deadline = System.currentTimeMillis() + 60_000
        while (!predicate()) {
            if (System.currentTimeMillis() > deadline) error("settleUntil timed out waiting for $what; " + probe())
            testScheduler.runCurrent()
            runCatching { shadowOf(Looper.getMainLooper()).runOneTask() }
            Thread.sleep(10)
        }
    }

    /**
     * Deterministic wedge: hold the acquisition's SCO selection down, start capture through the
     * broadcast-only route, and self-end. The dying capture's stream is an acquisition's stream
     * that was NEVER confirmed, so its owed restart's decision lands on the un-confirmed gate and
     * waits — and nothing releases it until the test decides. No `awaitState` after the hold:
     * every await pumps virtual time, which would expire exactly the bound under test.
     */
    private suspend fun heldWedge(
        modelsReady: ModelsReadyCondition,
        otherAppRule: FakeRule,
        foregroundRule: FakeRule,
    ): Harness {
        setAirPodsConnected()
        val harness = startService(modelsReady, otherAppRule, foregroundRule)
        // SETUP RUNS UNDER THE SAME CLEANUP AS THE CALLER'S BODY: if any setup step wedges, the
        // service must not outlive this helper — @After only resets Dispatchers.Main, so a leaked
        // service from a failed setup would pollute the next test class.
        try {
            harness.awaitObserverInstalled()
            // The acquisition starts but selection never completes while the link is held down.
            harness.scoPlatform.holdScoDown = true
            awaitState("acquisition started", probe = { "scoStarts=${harness.scoPlatform.scoStarts} " + harness.wedgeState() }) {
                harness.scoPlatform.scoStarts >= 1
            }
            // The SCO state broadcast arrives first: the route reads Headset and the observer starts
            // the stream itself, before the acquisition has observed selection. Held here.
            harness.scoPlatform.deliverScoUpBroadcast()
            awaitState("capture up mid-acquisition", probe = { "attach=${harness.hardware.attachCount} " + harness.wedgeState() }) {
                harness.hardware.attachCount >= 1
            }
            // QUIESCE THE OBSERVER'S ASYNC ROUTE RUNS BEFORE STAGING THE PENDING RESTART: the
            // observer clears a pending restart whenever its run processes a route trigger it had
            // not yet recorded, and an in-flight acquisition failure spends it outright. On a
            // loaded host those runs lag the capture by whole seconds, so without this settle the
            // staged pending flag could be consumed before the bound test ever observes it (the
            // captured wedgeState: pending=false timer=null with the capture already attached).
            // CRITICAL: these waits use settleUntil, NOT awaitState -- awaitState advances the
            // shared scheduler, and inside the wedge the SCO link is held DOWN, so granted
            // virtual time can expire the acquisition's own timeout and change the very shape
            // the wedge stages (the heldWedge contract: no virtual time past the staging).
            // Wait until the observer has recorded every trigger the wedge produced so far and
            // no acquisition attempt is in flight, mirroring stagePendingRestart's hygiene.
            settleUntil("observer has seen gate allowed", probe = { harness.wedgeState() }) {
                (getField(harness.service, "lastGateAllowedSeenByObserver") as? Boolean) == true
            }
            settleUntil("observer has seen the final device identity", probe = { harness.wedgeState() }) {
                (getField(harness.service, "lastConnectedDeviceIdentity") as? String) == "8|7|0"
            }
            // PIN THE ACQUISITION STATE THE CALLERS RELY ON -- asserted, not waited: absence is
            // the invariant at this point (the observer's route run resolved the held attempt
            // when it committed the broadcast capture, before the clock freeze), so a wait here
            // would only stall if a future poll were pending. After healing (bringScoUp), the
            // restart/fallback start comes from the observer's route TRIGGERS, never from this
            // attempt completing -- a live attempt would be an uncontrolled variable that could
            // double-start on heal or fail and spend the staged restart.
            org.junit.Assert.assertTrue(
                "staged shape: no acquisition attempt may be in flight once the broadcast capture committed (state: " + harness.wedgeState() + ")",
                getField(harness.service, "acquisitionJob") == null,
            )
            // PIN THE ENGINE AS STARTED BEFORE THE CAPTURE DIES: onPipelineTerminated early-returns
            // when the engine generation is stale or the start was never committed, and then NO
            // restart is armed at all (pending=false, timer=null, credit untouched). attachCount>=1
            // alone does not mean the start committed; waiting for engineStarted pins the shape so
            // the termination takes the arming path the wedge exists to test.
            settleUntil("capture committed", probe = { "engineStarted=${harness.engineStarted()} " + harness.wedgeState() }) {
                harness.engineStarted()
            }
            // That capture self-ends; its owed restart waits on the never-confirmed acquisition.
            endCaptureByFault(harness)
            settleUntil("the restart waiting on re-confirmation", probe = { harness.wedgeState() }) {
                getField(harness.service, "pendingAutomaticRestart") == true &&
                    (getField(harness.service, "reconfirmationTimer") as? kotlinx.coroutines.Job)?.isActive == true
            }
            return harness
        } catch (t: Throwable) {
            // The stop's own failure must not REPLACE the setup error that wedged the helper.
            runCatching { harness.stop() }.onFailure { stopT -> t.addSuppressed(stopT) }
            throw t
        }
    }
    /**
     * Stage `pendingAutomaticRestart = true` for a bound test, safely. The observer clears a
     * pending restart whenever its run processes a route trigger it had not yet recorded (the
     * gate's first Blocked->Allowed, a device-identity change), and those runs are asynchronous
     * to the test. A route-eligible run can also SPEND a pending restart outright, which is why
     * the staging below puts the restart in the acquisition wait rather than leaving it open to
     * the observer's decision. An acquisition attempt already in flight can also spend the
     * restart when it fails, so the staging waits for that attempt to finish first, then stages
     * the acquisition-wait shape and fails if one settle pass clears the flag.
     */
    private suspend fun stagePendingRestart(harness: Harness) {
        awaitState("observer has seen gate allowed", probe = { harness.wedgeState() }) {
            (getField(harness.service, "lastGateAllowedSeenByObserver") as? Boolean) == true
        }
        awaitState("observer has seen the final device identity", probe = { harness.wedgeState() }) {
            (getField(harness.service, "lastConnectedDeviceIdentity") as? String) == "8|7|0"
        }
        // STAGE THE ACQUISITION-WAIT SHAPE THE BOUND EXISTS FOR: a restart owed by a capture
        // whose acquisition has not re-opened its stream. beginAutomaticRestart's early return
        // holds the pending restart there without spending it, so the observer's route runs
        // cannot consume the staged state and the bound's own expiry is the only outcome.
        // QUIESCE THE START JOB FIRST: ensureModelsReady runs on Dispatchers.IO and flips the
        // models condition to ready when it finishes, which re-runs the observer; a run landing
        // after the staging would spend the restart through beginAutomaticRestart's expired
        // bypass. Wait until the start job has completed before staging anything.
        awaitState("the service start job has finished", probe = { harness.wedgeState() }) {
            (getField(harness.service, "startInFlightJob") as? kotlinx.coroutines.Job)?.isCompleted == true
        }
        // QUIESCE ANY IN-FLIGHT ACQUISITION NEXT: an attempt started against the initial route
        // spends the pending restart when it fails (acquireCandidate's Failed branch), which
        // would wipe the staged state. Wait until no attempt is running before staging.
        awaitState("no acquisition attempt in flight", probe = { harness.wedgeState() }) {
            getField(harness.service, "acquisitionJob") == null
        }
        val acquisition = getField(harness.service, "routeAcquisition")!!
        setField(acquisition, "acquiredForCapture", true)
        setField(acquisition, "openedStream", false)
        setField(harness.service, "pendingAutomaticRestart", true)
        settle()
        check(
            getField(harness.service, "pendingAutomaticRestart") == true,
        ) { "staged restart flag cleared after the observer caught up" }
    }

    /**
     * The bound is tested one level down, driving [scheduleReconfirmationBoundTimer] directly:
     * the full staging's timing depends on SCO settle races the fake cannot hold reliably. Two
     * waits 750 ms apart must still expire exactly once, at 1500 ms from the FIRST wait — a
     * relaunch on the second wait would push expiry to 2250 ms and silence the bound.
     */
    @Test
    fun `re-scheduling the re-confirmation bound does not reset it`() = runBlocking {
        val expiryLogs = intArrayOf(0)
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (message.contains("re-confirmation timed out")) expiryLogs[0]++
            }
        }
        timber.log.Timber.plant(tree)
        val modelsReady = ModelsReadyCondition(isReady = false)
        val otherAppRule = FakeRule("other_app_playing", VoiceControlRuleGroup.Conflicts)
        val foregroundRule = FakeRule("app_in_foreground", VoiceControlRuleGroup.Context)
        setAirPodsConnected()
        val harness = startService(modelsReady, otherAppRule, foregroundRule)
        try {
            harness.awaitObserverInstalled()
            // Stage the wait state the observer's start branch reaches: a granted restart that
            // is waiting on re-confirmation, on a gate that is Allowed.
            awaitState("gate allowed", probe = { harness.wedgeState() }) { (getField(harness.service, "gate") as au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGate).state.value.allowed }
            stagePendingRestart(harness)
            val schedule = harness.service.javaClass.getDeclaredMethod("scheduleReconfirmationBoundTimer")
                .apply { isAccessible = true }
            val gateField = getField(harness.service, "gate")!!
            schedule.invoke(harness.service)
            val firstTimer = getField(harness.service, "reconfirmationTimer") as kotlinx.coroutines.Job
            assertTrue("the first wait schedules the bound", firstTimer.isActive)

            // Half the bound passes; a second waiting observer run re-schedules: the SAME timer
            // must still be running, and expiry must land at 1500 ms from the first wait.
            testScheduler.advanceTimeBy(750.milliseconds)
            settle()
            schedule.invoke(harness.service)
            assertEquals(
                "a second waiting run must not relaunch the bound",
                firstTimer,
                getField(harness.service, "reconfirmationTimer"),
            )
            testScheduler.advanceTimeBy(750.milliseconds)
            settle()
            assertEquals("the bound expired once, 1500 ms from the first wait", 1, expiryLogs[0])
            assertEquals("expiry raises the fallback flag", true, getField(harness.service, "restartReconfirmationExpired"))
            testScheduler.advanceTimeBy(1_500.milliseconds)
            settle()
            assertEquals("no second expiry", 1, expiryLogs[0])
        } finally {
            timber.log.Timber.uproot(tree)
            harness.stop()
        }
    }


    /**
     * The restart nudge must be strictly increasing: two nudges in the same real millisecond
     * wrote the same wall-clock value into the StateFlow, which conflated them and DROPPED the
     * second — the bound's expiry nudge could land in exactly the silence it exists to break.
     */
    @Test
    fun `two nudges in the same millisecond are both delivered to the observer flow`() = runBlocking {
        val modelsReady = ModelsReadyCondition(isReady = false)
        val otherAppRule = FakeRule("other_app_playing", VoiceControlRuleGroup.Conflicts)
        val foregroundRule = FakeRule("app_in_foreground", VoiceControlRuleGroup.Context)
        setAirPodsConnected()
        val harness = startService(modelsReady, otherAppRule, foregroundRule)
        try {
            harness.awaitObserverInstalled()
            @Suppress("UNCHECKED_CAST")
            val flow = getField(harness.service, "captureRestartSignal") as kotlinx.coroutines.flow.StateFlow<Long>
            val seen = java.util.Collections.synchronizedList(mutableListOf<Long>())
            val collector = launch(kotlinx.coroutines.Dispatchers.Main) {
                flow.collect { seen.add(it) }
            }
            testScheduler.runCurrent()
            val nudge = harness.service.javaClass.getDeclaredMethod("nudgeCaptureRestart")
                .apply { isAccessible = true }
            nudge.invoke(harness.service)
            testScheduler.runCurrent()
            nudge.invoke(harness.service)
            testScheduler.runCurrent()
            testScheduler.runCurrent()
            Thread.sleep(100)
            testScheduler.runCurrent()
assertEquals(
                "both nudges must reach a collector",
                listOf(0L, 1L, 2L),
                seen,
            )
            collector.cancel()
        } finally {
            harness.stop()
        }
    }

    @Test
    fun `the fallback start never opens a stream when the surface has no microphone`() = runBlocking {
        val withheldLogs = intArrayOf(0)
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (message.contains("mic_restart_withheld") ||
                    message.contains("capture_start_refused") ||
                    message.contains("engine not started: selected device is not an external microphone") ||
                    message.contains("acquisition skipped: automatic restart withheld")
                ) withheldLogs[0]++
            }
        }
        timber.log.Timber.plant(tree)
        val harness = heldWedge(ModelsReadyCondition(isReady = false), FakeRule("other_app_playing", VoiceControlRuleGroup.Conflicts), FakeRule("app_in_foreground", VoiceControlRuleGroup.Context))
        try {
            val opensBefore = harness.hardware.openCount
            val attachBefore = harness.hardware.attachCount
            // The platform never delivers selection: the surface has no microphone. The bound
            // expires and the fallback attempts the start, which must refuse without opening a
            // stream and must SAY so.
            testScheduler.advanceTimeBy(2_000.milliseconds)
            // Twice the bound, real and virtual time: if the attempt is going to report, it
            // reports within this window.
            val deadline = System.currentTimeMillis() + 10_000
            while (withheldLogs[0] < 1 && System.currentTimeMillis() < deadline) {
                testScheduler.runCurrent()
                testScheduler.advanceTimeBy(100.milliseconds)
                runCatching { shadowOf(Looper.getMainLooper()).runOneTask() }
                Thread.sleep(10)
            }
            assertTrue(
                "the failed fallback start must be reported, not silent (logs=${withheldLogs[0]} open=${harness.hardware.openCount} attach=${harness.hardware.attachCount} " + harness.wedgeState() + ")",
                withheldLogs[0] >= 1,
            )
            assertEquals("the fallback must not open a stream on a mic-less surface", opensBefore, harness.hardware.openCount)
            assertEquals("no attach without a stream", attachBefore, harness.hardware.attachCount)
        } finally {
            timber.log.Timber.uproot(tree)
            harness.stop()
        }
    }

    /**
     * The confirmation arriving INSIDE the bound must restart the mic the normal way. The wedge
     * stages NO live acquisition: the route already reads Headset (the staging broadcast fixed it
     * before the capture died), and the restart the test exercises is driven by the observer's
     * ROUTE TRIGGERS when the platform heals (bringScoUp) -- a same-set refresh alone, before the
     * heal, must start nothing and must not reset the bound. On heal: exactly one start, the
     * timer cancelled, no expiry WARN, and advancing past the bound changes nothing. (Related,
     * covered by neighbours: once the bound has expired and a trigger clears the restart, the
     * expired flag and timer are gone with it, so a LATER pending restart can only come back
     * through its own re-confirmation -- never through a stale bypass.)
     */
    @Test
    fun `a confirmation inside the bound re-confirms the restart the normal way`() = runBlocking {
        val expiryLogs = intArrayOf(0)
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (message.contains("re-confirmation timed out")) expiryLogs[0]++
            }
        }
        timber.log.Timber.plant(tree)
        val harness = heldWedge(ModelsReadyCondition(isReady = false), FakeRule("other_app_playing", VoiceControlRuleGroup.Conflicts), FakeRule("app_in_foreground", VoiceControlRuleGroup.Context))
        try {
            val attachBefore = harness.hardware.attachCount
            testScheduler.advanceTimeBy(750.milliseconds)
            settle()
            assertEquals("no start before the bound", attachBefore, harness.hardware.attachCount)
            // The platform heals inside the bound: link up, shadow input set, SCO broadcast --
            // the same three facts a real headset arriving produces.
            harness.scoPlatform.bringScoUp()
            testScheduler.advanceTimeBy(400.milliseconds)
            settleUntil("the normal restart") { harness.hardware.attachCount > attachBefore }
            assertEquals("exactly one start", attachBefore + 1, harness.hardware.attachCount)
            assertEquals(
                "the timer is cancelled with the spent restart",
                null,
                getField(harness.service, "reconfirmationTimer"),
            )
            // Past the bound: nothing more happens, the bound never fired.
            testScheduler.advanceTimeBy(1_000.milliseconds)
            settle()
            assertEquals("the bound must not have expired", 0, expiryLogs[0])
            assertEquals("no stacked start", attachBefore + 1, harness.hardware.attachCount)
        } finally {
            timber.log.Timber.uproot(tree)
            harness.stop()
        }
    }

    @Test
    fun `clearing the pending restart clears the expired flag and the bound timer`() = runBlocking {
        val expiryLogs = intArrayOf(0)
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (message.contains("re-confirmation timed out")) expiryLogs[0]++
            }
        }
        timber.log.Timber.plant(tree)
        val modelsReady = ModelsReadyCondition(isReady = false)
        val otherAppRule = FakeRule("other_app_playing", VoiceControlRuleGroup.Conflicts)
        val foregroundRule = FakeRule("app_in_foreground", VoiceControlRuleGroup.Context)
        setAirPodsConnected()
        val harness = startService(modelsReady, otherAppRule, foregroundRule)
        try {
            harness.awaitObserverInstalled()
            stagePendingRestart(harness)
            val schedule = harness.service.javaClass.getDeclaredMethod("scheduleReconfirmationBoundTimer")
                .apply { isAccessible = true }
            schedule.invoke(harness.service)
            val firstTimer = getField(harness.service, "reconfirmationTimer") as kotlinx.coroutines.Job
            assertTrue(firstTimer.isActive)

            // The bound expires; the fallback flag is raised. The log count sits next to the
            // flag so a missing expiry fails loudly even if the flag read ever races again.
            testScheduler.advanceTimeBy(1_500.milliseconds)
            settle()
            assertEquals("the bound expired once", 1, expiryLogs[0])
            assertEquals(true, getField(harness.service, "restartReconfirmationExpired"))

            // A trigger (device change, gate re-arm, spend — all go through the one helper)
            // clears the pending restart: the flag and the timer are gone with it.
            harness.service.javaClass.getDeclaredMethod("clearPendingRestart")
                .apply { isAccessible = true }.invoke(harness.service)
            assertEquals(false, getField(harness.service, "pendingAutomaticRestart"))
            assertEquals(false, getField(harness.service, "restartReconfirmationExpired"))
            assertEquals(null, getField(harness.service, "reconfirmationTimer"))

            // A fresh wait schedules a fresh bound that starts from zero: it must NOT behave
            // as already expired.
            stagePendingRestart(harness)
            schedule.invoke(harness.service)
            val secondTimer = getField(harness.service, "reconfirmationTimer") as kotlinx.coroutines.Job
            assertTrue("a fresh wait schedules a fresh bound", secondTimer.isActive && secondTimer !== firstTimer)
            assertEquals(false, getField(harness.service, "restartReconfirmationExpired"))
            testScheduler.advanceTimeBy(1_400.milliseconds)
            settle()
            assertEquals("before the fresh bound expires the flag stays down", false, getField(harness.service, "restartReconfirmationExpired"))
            testScheduler.advanceTimeBy(150.milliseconds)
            settle()
            assertEquals("the fresh bound expires on its own schedule", 2, expiryLogs[0])
            assertEquals("the fresh bound expires on its own schedule", true, getField(harness.service, "restartReconfirmationExpired"))
        } finally {
            timber.log.Timber.uproot(tree)
            harness.stop()
        }
    }

    /**
     * Table-driven over route kinds x restart outcomes: every route must give a self-ended
     * capture exactly one automatic restart, and a restart that dies within 60 s or fails to
     * start must leave restarts withheld with exactly one `mic_restart_withheld` log. Wired
     * and already-isolated rows run through the fake's pre-selected input
     * (seedAlreadySelectedInput): the route is observed before voice control starts, so the
     * capture needs no acquisition — the surface the a2dp rows cannot produce end to end.
     * Built-in (speaker) is Exposed: the gate never allows capture there, so no restart
     * decision is ever reachable.
     */
    @Test
    fun `the restart matrix holds on every route kind`() = runBlocking {
        data class Case(val kind: String, val outcome: String)

        val cases = listOf(
            Case("a2dp", "healthy"),
            Case("a2dp", "fastDeath"),
            Case("a2dp", "startFailureAcquisition"),
            Case("a2dp", "startFailureEngine"),
            Case("wired", "fastDeath"),
            Case("wired", "startFailureEngine"),
            Case("isolated", "fastDeath"),
            Case("isolated", "startFailureEngine"),
            Case("builtin", "noCapture"),
        )
        for (case in cases) {
            val withheldLogs = intArrayOf(0)
            val tree = object : timber.log.Timber.Tree() {
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    if (message.contains("mic_restart_withheld")) withheldLogs[0]++
                }
            }
            timber.log.Timber.plant(tree)
            try {
                val modelsReady = ModelsReadyCondition(isReady = false)
                val otherAppRule = FakeRule("other_app_playing", VoiceControlRuleGroup.Conflicts)
                val foregroundRule = FakeRule("app_in_foreground", VoiceControlRuleGroup.Context)
                val harness = when (case.kind) {
                    "a2dp" -> {
                        setAirPodsConnected()
                        startService(modelsReady, otherAppRule, foregroundRule)
                    }

                    // The fake presents the input as ALREADY selected (no acquisition needed);
                    // wiredInput makes the stream confirm a wired microphone. The capture
                    // hardware accepts the input without an SCO link, as a wired device would.
                    "wired" -> startService(
                        modelsReady,
                        otherAppRule,
                        foregroundRule,
                        alwaysCaptureReady = true,
                        wiredInput = true,
                        startIsolated = true,
                    )

                    "isolated" -> startService(
                        modelsReady,
                        otherAppRule,
                        foregroundRule,
                        alwaysCaptureReady = true,
                        startIsolated = true,
                    )

                    "builtin" -> {
                        setOutputDevices(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                        setInputDevices()
                        startService(modelsReady, otherAppRule, foregroundRule)
                    }

                    else -> error("unknown kind ${case.kind}")
                }
                try {
                    harness.awaitObserverInstalled()
                    if (case.kind == "builtin") {
                        // Exposed exposure: capture cannot start, so no restart decision exists.
                        Thread.sleep(500)
                        testScheduler.advanceTimeBy(500.milliseconds)
                        assertEquals(
                            "builtin: no capture, no restart machinery",
                            0,
                            harness.hardware.attachCount,
                        )
                        assertEquals("builtin: no withheld logs", 0, withheldLogs[0])
                        continue
                    }
                    awaitState("$case: capture up", probe = { harness.wedgeState() }) { harness.hardware.attachCount >= 1 }
                    // Let the initial start's route flips and debounces land, so the self-end
                    // below races nothing.
                    Thread.sleep(500)
                    testScheduler.advanceTimeBy(500.milliseconds)
                    val attaches = harness.hardware.attachCount

                    when (case.outcome) {
                        "healthy" -> {
                            // 60s+ of working capture keeps earning restarts: two healthy
                            // self-ends each get their automatic restart (contrast fastDeath).
                            repeat(2) {
                                // Let the previous restart's release churn settle before the
                                // next self-end, so the two do not interleave.
                                Thread.sleep(600)
                                testScheduler.advanceTimeBy(600.milliseconds)
                                setField(
                                    harness.service,
                                    "captureStartedAtMs",
                                    android.os.SystemClock.elapsedRealtime() - 61_000L,
                                )
                                endCaptureByFault(harness)
                                awaitState("$case: restarted", probe = { harness.wedgeState() }) {
                                    harness.hardware.attachCount >= attaches + 1 + it
                                }
                            }
                        }

                        "fastDeath" -> {
                            endCaptureByFault(harness)
                            awaitState("$case: restarted", probe = { harness.wedgeState() }) { harness.hardware.attachCount >= attaches + 1 }
                            // The restart's engine start commits after the VAD attach; wait
                            // for it, or the next self-end below would early-return on a not-
                            // yet-started engine.
                            awaitState("$case: restart committed", probe = { harness.wedgeState() }) { harness.engineStarted() }
                            // The restarted capture dies fast: withheld, exactly one log.
                            // Count only this explicit death: the harness's fake capture has
                            // its own lifecycle noise outside the test's actions.
                            withheldLogs[0] = 0
                            endCaptureByFault(harness)
                            awaitState("$case: withheld", probe = { harness.wedgeState() }) {
                                val budget = getField(harness.service, "captureRestartBudget")!!
                                !(budget.javaClass.getDeclaredField("restartAvailable")
                                    .apply { isAccessible = true }.get(budget) as Boolean)
                            }
                            Thread.sleep(400)
                            testScheduler.advanceTimeBy(400.milliseconds)
                            assertEquals(
                                "$case: exactly one withheld log",
                                1,
                                withheldLogs[0],
                            )
                            assertEquals(
                                "$case: no restart after withholding",
                                attaches + 1,
                                harness.hardware.attachCount,
                            )
                        }

                        "startFailureAcquisition" -> {
                            // Restart path A: the restart's acquisition fails.
                            harness.scoPlatform.establish = false
                            withheldLogs[0] = 0
                            endCaptureByFault(harness)
                            // The budget flips before the withheld log is written (the
                            // spend happens at the attempt); wait for BOTH.
                            awaitState("$case: withheld", probe = { harness.wedgeState() }) {
                                val budget = getField(harness.service, "captureRestartBudget")!!
                                !(budget.javaClass.getDeclaredField("restartAvailable")
                                    .apply { isAccessible = true }.get(budget) as Boolean) &&
                                    withheldLogs[0] >= 1
                            }
                            assertEquals("$case: exactly one withheld log", 1, withheldLogs[0])
                        }

                        "startFailureEngine" -> {
                            // Restart path B: the acquisition (when one is needed) succeeds but
                            // the engine start fails — the VAD attach never happens.
                            harness.hardware.failVadAttach = true
                            withheldLogs[0] = 0
                            endCaptureByFault(harness)
                            // The budget flips before the withheld log is written (the
                            // spend happens at the attempt); wait for BOTH.
                            awaitState("$case: withheld", probe = { harness.wedgeState() }) {
                                val budget = getField(harness.service, "captureRestartBudget")!!
                                !(budget.javaClass.getDeclaredField("restartAvailable")
                                    .apply { isAccessible = true }.get(budget) as Boolean) &&
                                    withheldLogs[0] >= 1
                            }
                            assertEquals("$case: exactly one withheld log", 1, withheldLogs[0])
                        }
                    }
                } finally {
                    harness.stop()
                }
            } finally {
                timber.log.Timber.uproot(tree)
            }
        }
    }

    /**
     * The single restart-decision function itself, over the input states the end-to-end rows
     * cannot isolate: the credit spends on a startable surface whose routing needs no waiting,
     * and NOT while a restart for an acquisition-ridden capture is still waiting for the
     * acquisition to re-open the stream (the recovery nudge arriving mid-release, or an
     * A2DP-only route whose stream is not confirmed yet), or on Unknown.
     */
    @Test
    fun `the restart decision spends only on a startable surface`() = runBlocking {
        fun newBudgetAndService(): VoiceControlService {
            val service = VoiceControlService().also { it.voiceSessionGate = VoiceSessionGate() }
            android.content.ContextWrapper::class.java.getDeclaredMethod(
                "attachBaseContext",
                android.content.Context::class.java,
            ).apply { isAccessible = true }.invoke(
                service,
                androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>(),
            )
            setField(
                service,
                "captureRestartBudget",
                CaptureRestartBudget(),
            )
            setField(
                service,
                "routeAcquisition",
                RouteAcquisition(
                    FakeScoPlatform(org.mockito.kotlin.mock()),
                    timeoutMs = 6_000L,
                ),
            )
            return service
        }

        fun decision(
            route: AudioRoute,
            acquiredForCapture: Boolean,
            confirmedStream: Boolean,
            pending: Boolean = true,
        ): Boolean {
            val service = newBudgetAndService()
            setField(service, "pendingAutomaticRestart", pending)
            val acquisition = getField(service, "routeAcquisition")!!
            acquisition.javaClass.getDeclaredField("acquiredForCapture").apply { isAccessible = true }
                .set(acquisition, acquiredForCapture)
            acquisition.javaClass.getDeclaredField("openedStream").apply { isAccessible = true }
                .set(acquisition, confirmedStream)
            val method = service.javaClass.getDeclaredMethod(
                "beginAutomaticRestart",
                AudioRoute::class.java,
            )
            method.isAccessible = true
            return method.invoke(service, route) as Boolean
        }

        // Wired / already-isolated input: the dying capture needed no acquisition, the headset
        // with a microphone is startable — the credit spends.
        assertTrue(decision(AudioRoute.Headset(hasMicrophone = true), acquiredForCapture = false, confirmedStream = false))
        // The same route right after an acquisition-ridden capture ended (recovery nudge
        // mid-release, the debounced route still observing the old headset): the restart is
        // waiting for the acquisition to re-open the stream — do not spend.
        assertFalse(decision(AudioRoute.Headset(hasMicrophone = true), acquiredForCapture = true, confirmedStream = false))
        // A2DP-only: spends only once the acquisition has re-opened the stream.
        assertFalse(decision(AudioRoute.BluetoothA2dpOnly, acquiredForCapture = true, confirmedStream = false))
        assertTrue(decision(AudioRoute.BluetoothA2dpOnly, acquiredForCapture = true, confirmedStream = true))
        // Unknown / built-in: nothing startable, no spend.
        assertFalse(decision(AudioRoute.Unknown, acquiredForCapture = true, confirmedStream = false))
        // No pending grant: never spends.
        assertFalse(
            decision(AudioRoute.Headset(hasMicrophone = true), acquiredForCapture = false, confirmedStream = false, pending = false),
        )
    }

    private suspend fun Harness.awaitObserverInstalled() {
        awaitState("observer install") { getField(service, "modeJob") != null }
    }

    /**
     * End the service the way Android does, so its real teardown runs and no work from this test
     * is still live when the next one installs its main dispatcher. `onDestroy` cancels the
     * service scope and joins it before releasing anything; the leaked children were what made a
     * following test fail with "Dispatchers.Main is used concurrently with setting it".
     */
    @Test
    fun `a stop closes the session's early connection and leaves nothing held`() = runBlocking {
        // The opener is a @Singleton: without a teardown close, an unspent pre-opened socket
        // survives the service stop and sits open in a dead session until the next session's
        // staleness check discards it.
        val opened = java.util.Collections.synchronizedList(mutableListOf<au.com.shiftyjelly.pocketcasts.repositories.cloud.SocketCloudTurnConnection>())
        val lateSubmitted = java.util.concurrent.CountDownLatch(1)
        // ONE dedicated opener thread: every task the opener queues runs on it in submission
        // order, so a probe submitted after the fact deterministically DRAINS all queued opener
        // work before the assertions -- no yield-and-hope.
        val openerExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val openerDispatcher = openerExecutor.asCoroutineDispatcher()
        val opener = au.com.shiftyjelly.pocketcasts.repositories.cloud.EarlyConnectionOpener(
            dispatcher = openerDispatcher,
            open = { tag, nowMs ->
                au.com.shiftyjelly.pocketcasts.repositories.cloud.SocketCloudTurnConnection(tag, nowMs).also { opened += it }
            },
            now = { android.os.SystemClock.elapsedRealtime() },
        )
        val harness = startService(
            ModelsReadyCondition(isReady = false),
            FakeRule("other_app_playing", VoiceControlRuleGroup.Conflicts),
            FakeRule("app_in_foreground", VoiceControlRuleGroup.Context),
        )
        setField(harness.service, "earlyConnectionOpener", opener)
        // A BARRIER-OWNED JOB THAT DIALS LATE: registered with the shutdown barrier before the
        // stop, so the teardown owes it a join. BOTH orders are legitimate shutdown outcomes: the
        // dial may take the opener before the stop advances the generation (the stop closes what
        // it opened), or the stop may win and drop the late request. The contract under test is
        // the OUTCOME: the initial open MUST have happened, and once the stop and all queued
        // opener work have settled, NO connection from either tag is left open.
        // HOLD the late dial on a test-side signal until SHUTDOWN HAS STARTED: the barrier's
        // closed flag flips the instant teardown snapshots the owned jobs. Waiting on the test
        // socket's CLOSE instead would deadlock by construction -- the opener's shutdown runs
        // AFTER the barrier joins (VoiceControlService teardown), so a barrier-owned job that
        // waits for that close waits for something that can only happen after its own join. The
        // closed flag pins the dial INSIDE the teardown window without that trap.
        val barrierObj = getField(harness.service, "shutdownBarrier")!!
        val barrierClosed = barrierObj.javaClass.getDeclaredMethod("isClosed").apply { isAccessible = true }
        val lateDial = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val shutdownStarted = kotlinx.coroutines.withTimeoutOrNull(30_000) {
                while (!(barrierClosed.invoke(barrierObj) as Boolean)) kotlinx.coroutines.yield()
                true
            } == true
            // A dial submitted before shutdown ever started proves nothing: fail rather than run.
            org.junit.Assert.assertTrue("fixture: shutdown never started", shutdownStarted)
            opener.openForLater("utt-stop-late")
            lateSubmitted.countDown()
        }
        try {
            val admitted = barrierObj.javaClass.getDeclaredMethod("beginStart", List::class.java).apply { isAccessible = true }
                .invoke(barrierObj, listOf(lateDial)) as Boolean
            org.junit.Assert.assertTrue("fixture: the barrier must have admitted the late job", admitted)
            opener.openForLater("utt-stop-test")
            // REQUIRE the initial open: generous wall-clock bound (a starved opener thread is a
            // host artifact), but expiry is a FAILURE, not a pass -- the test must not pass
            // without ever exercising the teardown against a live connection.
            // The TEST tag specifically: a stray open from any other path must not satisfy this.
            val firstOpened = kotlinx.coroutines.withTimeoutOrNull(30_000) {
                while (synchronized(opened) { opened.toList() }.none { it.ownerTag == "utt-stop-test" }) {
                    kotlinx.coroutines.yield()
                }
                true
            } == true
            org.junit.Assert.assertTrue("fixture: the initial open must have run", firstOpened)
            harness.stop()
            // PROVE the late request was submitted and joined, then DRAIN the opener's actual
            // queue: the probe task runs only after every opener job already queued on the single
            // executor thread has finished.
            assertTrue("fixture: the late dial must have been submitted", lateSubmitted.await(10, java.util.concurrent.TimeUnit.SECONDS))
            lateDial.join()
            runCatching {
                openerExecutor.submit { }.get(30, java.util.concurrent.TimeUnit.SECONDS)
            }.onFailure {
                org.junit.Assert.fail("fixture: the opener queue never drained: $it")
            }
            org.junit.Assert.assertTrue(
                "a stopped session must not keep any pre-opened socket open (state: " +
                    opened.joinToString { it.ownerTag + (if (it.isClosed) ":closed" else ":OPEN") } + ")",
                opened.isNotEmpty() && opened.all { it.isClosed },
            )
        } finally {
            // The late dial lives OUTSIDE the service scope: a refused barrier registration
            // leaves it with no join owner, so it is cancelled and joined here unconditionally.
            lateDial.cancel()
            lateDial.join()
            try {
                // Unwrapped, like every other test's finally: a stop failure is a real failure
                // and must surface.
                harness.stop()
            } finally {
                // The executor's worker is non-daemon: it is released even when the stop throws.
                openerDispatcher.close()
                openerExecutor.shutdownNow()
            }
        }
    }

    private suspend fun Harness.stop() {
        runCatching {
            VoiceControlService::class.java.getDeclaredMethod("onDestroy")
                .apply { isAccessible = true }.invoke(service)
        }
        // onDestroy discards the Deferred its teardown returns, and that teardown finishes on
        // Dispatchers.IO -- so the service scope being cancelled is not the same as the teardown
        // being over. The scope's root completing was the wrong thing to wait on: it says the
        // service's own children ended, not that the shared resources were released.
        //
        // THE FACT TO WAIT ON IS THE SESSION BEING AVAILABLE AGAIN. The gate is held until every
        // release has finished, so an admission that succeeds means the previous session is really
        // done -- which is exactly what must be true before the next test installs its main
        // dispatcher. ADMISSION IS THE PROBE, so the session it hands back is given up immediately.
        val gate = getField(service, "voiceSessionGate") as VoiceSessionGate
        // The bound has to cover the teardown's OWN worst case, not a guess: it joins the engine's
        // work in rounds of up to five seconds each, so on a loaded machine five seconds total can
        // expire while the release is still legitimately in progress.
        val budgetMs = 60_000L
        val deadline = System.currentTimeMillis() + budgetMs
        var settled = false
        var lastAdmission: VoiceSessionGate.Admission? = null
        while (System.currentTimeMillis() < deadline) {
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(25.milliseconds)
            runCatching { shadowOf(Looper.getMainLooper()).runOneTask() }
            kotlinx.coroutines.yield()
            val admission = gate.acquire(timeoutMs = 20)
            if (admission is VoiceSessionGate.Admission.Acquired) {
                gate.release(admission.session)
                settled = true
                break
            } else {
                lastAdmission = admission
            }
        }
        check(settled) {
            "the voice session was still held ${budgetMs}ms after onDestroy; " +
                "lastAdmission=$lastAdmission"
        }
    }

    // ---- tests ---------------------------------------------------------------

    @Test
    fun `a headset arriving after launch with capture off triggers acquisition and starts capture`() = runBlocking {
        // Launch with NO headset: speaker only. Capture must not start.
        setOutputDevices(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        setInputDevices()
        val modelsReady = ModelsReadyCondition(isReady = false)
        val otherAppRule = FakeRule("other_app_playing", VoiceControlRuleGroup.Conflicts)
        val foregroundRule = FakeRule("app_in_foreground", VoiceControlRuleGroup.Context)
        val harness = startService(modelsReady, otherAppRule, foregroundRule)
        try {
            harness.awaitObserverInstalled()
            awaitState("speaker-only steady state", probe = { harness.wedgeState() }) { harness.monitor.availability.value == AudioRoute.Speaker }
            assertEquals("no candidate, no acquisition", 0, harness.scoPlatform.scoStarts)
            assertEquals("no capture", 0, harness.hardware.attachCount)

            // The headset arrives mid-session (the AirPods reconnect). Availability flips to the
            // candidate; the observer must re-run and acquire.
            setAirPodsConnected()
            harness.monitor.onDevicesChanged()
            awaitState("VAD attach after headset arrival", probe = { harness.wedgeState() }) { harness.hardware.attachCount >= 1 }
            assertEquals(AudioRoute.Headset(true), harness.monitor.route.value)
            assertTrue("acquisition must have opened SCO", harness.scoPlatform.scoStarts >= 1)
        } finally {
            harness.stop()
        }
    }

    @Test
    fun `capture resumes when the headset reconnects mid-session`() = runBlocking {
        setAirPodsConnected()
        val modelsReady = ModelsReadyCondition(isReady = false)
        val otherAppRule = FakeRule("other_app_playing", VoiceControlRuleGroup.Conflicts)
        val foregroundRule = FakeRule("app_in_foreground", VoiceControlRuleGroup.Context)
        val harness = startService(modelsReady, otherAppRule, foregroundRule)
        try {
            harness.awaitObserverInstalled()
            awaitState("first capture", probe = { harness.wedgeState() }) { harness.hardware.attachCount >= 1 }
            val firstAttach = harness.hardware.attachCount

            // BT off: devices vanish; capture stops (EligibilityOff).
            setOutputDevices(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            setInputDevices()
            harness.monitor.onDevicesChanged()
            awaitState("capture torn down after headset loss", probe = { harness.wedgeState() }) { harness.hardware.vadStopCount >= 1 }
            awaitState("availability back to speaker", probe = { harness.wedgeState() }) { harness.monitor.availability.value == AudioRoute.Speaker }

            // BT on again: the AirPods reconnect mid-session. Capture MUST resume.
            setAirPodsConnected()
            harness.monitor.onDevicesChanged()
            awaitState("capture resumed after headset reconnect", probe = { harness.wedgeState() }) { harness.hardware.attachCount > firstAttach }
            assertTrue("the resumed capture acquired the headset again", harness.scoPlatform.scoStarts >= 2)
        } finally {
            harness.stop()
        }
    }

    @Test
    fun `an availability change with no microphone candidate acquires nothing`() = runBlocking {
        setAirPodsConnected()
        val modelsReady = ModelsReadyCondition(isReady = false)
        val otherAppRule = FakeRule("other_app_playing", VoiceControlRuleGroup.Conflicts)
        val foregroundRule = FakeRule("app_in_foreground", VoiceControlRuleGroup.Context)
        val harness = startService(modelsReady, otherAppRule, foregroundRule)
        try {
            harness.awaitObserverInstalled()
            awaitState("first capture", probe = { harness.wedgeState() }) { harness.hardware.attachCount >= 1 }
            val scoStartsBefore = harness.scoPlatform.scoStarts

            // Availability changes (wired headphones output, no mic input -> NoMic), but no
            // candidate with a microphone appears. The observer re-runs and must NOT acquire.
            setOutputDevices(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            setInputDevices(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            harness.monitor.onDevicesChanged()
            awaitState("availability moved off the candidate", probe = { harness.wedgeState() }) {
                harness.monitor.availability.value is AudioRoute.Headset &&
                    !(harness.monitor.availability.value as AudioRoute.Headset).hasMicrophone
            }
            // Let any wrongly-triggered acquisition surface.
            Thread.sleep(300)
            testScheduler.advanceTimeBy(300.milliseconds)
            assertEquals(
                "no acquisition without a mic candidate",
                scoStartsBefore,
                harness.scoPlatform.scoStarts,
            )
        } finally {
            harness.stop()
        }
    }

    @Test
    fun `a failed acquisition is not retried on an unrelated gate change until the trigger surface moves`() = runBlocking {
        setAirPodsConnected()
        val modelsReady = ModelsReadyCondition(isReady = false)
        val otherAppRule = FakeRule("other_app_playing", VoiceControlRuleGroup.Conflicts)
        val foregroundRule = FakeRule("app_in_foreground", VoiceControlRuleGroup.Context)
        val harness = startService(modelsReady, otherAppRule, foregroundRule, acquisitionTimeoutMs = 400L, scoEstablishes = false)
        try {
            harness.awaitObserverInstalled()
            // The platform never establishes SCO in this test, so acquisition FAILS (timeout).
            awaitState("first failed acquisition", probe = { harness.wedgeState() }) { harness.scoPlatform.scoStarts >= 1 }
            awaitState("failed attempt recorded", probe = { harness.wedgeState() }) {
                val failedAvailability = getField(harness.service, "failedAttemptAvailability")
                failedAvailability != null
            }
            val scoStartsAfterFailure = harness.scoPlatform.scoStarts

            // An UNRELATED re-evaluation: a combine source fires while the overall gate stays
            // Allowed throughout (the restart-signal bump — same family as a rule changing
            // without the gate ever leaving Allowed). No second attempt while the trigger
            // surface is unchanged.
            @Suppress("UNCHECKED_CAST")
            val restartSignal = getField(harness.service, "captureRestartSignal") as MutableStateFlow<Long>
            restartSignal.value = restartSignal.value + 1L
            awaitState("re-evaluation observed", probe = { harness.wedgeState() }) { true }
            Thread.sleep(400)
            testScheduler.advanceTimeBy(400.milliseconds)
            assertEquals(
                "no retry on an unrelated gate change after a failed attempt",
                scoStartsAfterFailure,
                harness.scoPlatform.scoStarts,
            )

            // The trigger surface moves (the device set changes): acquisition runs again.
            setOutputDevices(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            setInputDevices()
            harness.monitor.onDevicesChanged()
            awaitState("availability back to speaker", probe = { harness.wedgeState() }) { harness.monitor.availability.value == AudioRoute.Speaker }
            setAirPodsConnected()
            harness.monitor.onDevicesChanged()
            awaitState("retry after the trigger surface moved", probe = { harness.wedgeState() }) {
                harness.scoPlatform.scoStarts > scoStartsAfterFailure
            }
        } finally {
            harness.stop()
        }
    }

    @Test
    fun `user-driven bluetooth off-on cycles do not consume the restart budget`() = runBlocking {
        setAirPodsConnected()
        val modelsReady = ModelsReadyCondition(isReady = false)
        val otherAppRule = FakeRule("other_app_playing", VoiceControlRuleGroup.Conflicts)
        val foregroundRule = FakeRule("app_in_foreground", VoiceControlRuleGroup.Context)
        val harness = startService(modelsReady, otherAppRule, foregroundRule)
        try {
            harness.awaitObserverInstalled()
            awaitState("first capture", probe = { harness.wedgeState() }) { harness.hardware.attachCount >= 1 }

            // Merlin's sequence, twice: BT off (capture stops), BT on (capture resumes).
            repeat(2) {
                setOutputDevices(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                setInputDevices()
                harness.monitor.onDevicesChanged()
                awaitState("capture down (cycle)", probe = { harness.wedgeState() }) { harness.hardware.vadStopCount >= it + 1 }
                setAirPodsConnected()
                harness.monitor.onDevicesChanged()
                awaitState("capture back up (cycle)", probe = { harness.wedgeState() }) { harness.hardware.attachCount >= it + 2 }
            }

            val budget = getField(harness.service, "captureRestartBudget")!!
            val restartAvailable = budget.javaClass
                .getDeclaredField("restartAvailable").apply { isAccessible = true }.get(budget) as Boolean
            assertTrue(
                "user-caused reconnects are triggers: they must never leave restarts withheld",
                restartAvailable,
            )
        } finally {
            harness.stop()
        }
    }

    @Test
    fun `a blocker clearing after a failed acquisition is a new trigger and retries once`() = runBlocking {
        setAirPodsConnected()
        val modelsReady = ModelsReadyCondition(isReady = false)
        val otherAppRule = FakeRule("other_app_playing", VoiceControlRuleGroup.Conflicts)
        val foregroundRule = FakeRule("app_in_foreground", VoiceControlRuleGroup.Context)
        val harness = startService(modelsReady, otherAppRule, foregroundRule, acquisitionTimeoutMs = 400L, scoEstablishes = false)
        try {
            harness.awaitObserverInstalled()
            awaitState("first failed acquisition", probe = { harness.wedgeState() }) { harness.scoPlatform.scoStarts >= 1 }
            awaitState("failed attempt recorded", probe = { harness.wedgeState() }) {
                getField(harness.service, "failedAttemptAvailability") != null
            }
            val scoStartsAfterFailure = harness.scoPlatform.scoStarts

            // A BLOCKER CLEARS: the overall gate goes Blocked -> Allowed. That is a new trigger
            // for any blocker, so exactly one more attempt runs.
            otherAppRule.block("other_app_playing")
            awaitState("gate blocked observed", probe = { harness.wedgeState() }) { !gateAllowedNow(harness) }
            otherAppRule.allow()
            awaitState("gate allowed again", probe = { harness.wedgeState() }) { gateAllowedNow(harness) }
            awaitState("retry after the blocker cleared", probe = { harness.wedgeState() }) {
                harness.scoPlatform.scoStarts > scoStartsAfterFailure
            }
            val retryCount = harness.scoPlatform.scoStarts

            // And no further attempts: the retry's own failure re-records the surface.
            Thread.sleep(400)
            testScheduler.advanceTimeBy(400.milliseconds)
            assertEquals(
                "exactly one attempt per cleared-blocker trigger",
                retryCount,
                harness.scoPlatform.scoStarts,
            )
        } finally {
            harness.stop()
        }
    }

    @Test
    fun `an attempt aborted by a blocker does not swallow the blocker-clearing trigger`() = runBlocking {
        setAirPodsConnected()
        val modelsReady = ModelsReadyCondition(isReady = false)
        val otherAppRule = FakeRule("other_app_playing", VoiceControlRuleGroup.Conflicts)
        val foregroundRule = FakeRule("app_in_foreground", VoiceControlRuleGroup.Context)
        val harness = startService(modelsReady, otherAppRule, foregroundRule, acquisitionTimeoutMs = 400L, scoEstablishes = false)
        try {
            harness.awaitObserverInstalled()
            awaitState("first failed acquisition", probe = { harness.wedgeState() }) { harness.scoPlatform.scoStarts >= 1 }
            awaitState("failed attempt recorded", probe = { harness.wedgeState() }) {
                getField(harness.service, "failedAttemptAvailability") != null
            }

            // Attempt in flight (a new trigger started it), the gate blocks mid-attempt, the
            // attempt dies as abandoned, then the gate allows again: the cleared blocker is the
            // trigger and exactly ONE new attempt must run.
            otherAppRule.block("other_app_playing")
            harness.scoPlatform.scoStarts = 0
            awaitState("gate blocked observed", probe = { harness.wedgeState() }) { !gateAllowedNow(harness) }
            otherAppRule.allow()
            awaitState("gate allowed again", probe = { harness.wedgeState() }) { gateAllowedNow(harness) }
            awaitState("exactly one new attempt after the abandoned one", probe = { harness.wedgeState() }) {
                harness.scoPlatform.scoStarts >= 1
            }
            val attemptsAfter = harness.scoPlatform.scoStarts
            Thread.sleep(400)
            testScheduler.advanceTimeBy(400.milliseconds)
            assertEquals(
                "exactly one attempt for the cleared blocker",
                1,
                attemptsAfter,
            )
        } finally {
            harness.stop()
        }
    }

    @Test
    fun `a blocker clearing mid-attempt still gets its attempt after the failure with no later emission`() = runBlocking {
        setAirPodsConnected()
        val modelsReady = ModelsReadyCondition(isReady = false)
        val otherAppRule = FakeRule("other_app_playing", VoiceControlRuleGroup.Conflicts)
        val foregroundRule = FakeRule("app_in_foreground", VoiceControlRuleGroup.Context)
        val harness = startService(modelsReady, otherAppRule, foregroundRule, acquisitionTimeoutMs = 600L, scoEstablishes = false)
        try {
            harness.awaitObserverInstalled()
            awaitState("attempt in flight", probe = { harness.wedgeState() }) { harness.scoPlatform.scoStarts >= 1 }
            // Gate blocks and clears WHILE the attempt is in flight: both emissions are
            // consumed by the in-flight guard (rule 2's coalescing). Nothing changes after
            // the attempt ends, so the only path to a retry is the coalesced re-evaluation.
            otherAppRule.block("other_app_playing")
            otherAppRule.allow()
            val attemptsSoFar = harness.scoPlatform.scoStarts
            // The failure unwinds, and the coalesced trigger's re-evaluation fires a second
            // attempt WITHOUT any new emission after the block/allow pair.
            awaitState("coalesced attempt after the failure", probe = { harness.wedgeState() }) {
                harness.scoPlatform.scoStarts > attemptsSoFar
            }
            awaitState("attempt ended", probe = { harness.wedgeState() }) {
                getField(harness.service, "acquisitionJob") == null &&
                    getField(harness.service, "gateBlockedDuringAttempt") == false
            }
            val afterRetry = harness.scoPlatform.scoStarts
            Thread.sleep(300)
            testScheduler.advanceTimeBy(300.milliseconds)
            assertEquals(
                "exactly one coalesced attempt, no loop",
                afterRetry,
                harness.scoPlatform.scoStarts,
            )
        } finally {
            harness.stop()
        }
    }

    @Test
    fun `a route emission during a failing attempt with the gate Allowed throughout loops not`() = runBlocking {
        setAirPodsConnected()
        val modelsReady = ModelsReadyCondition(isReady = false)
        val otherAppRule = FakeRule("other_app_playing", VoiceControlRuleGroup.Conflicts)
        val foregroundRule = FakeRule("app_in_foreground", VoiceControlRuleGroup.Context)
        val harness = startService(modelsReady, otherAppRule, foregroundRule, acquisitionTimeoutMs = 600L, scoEstablishes = false)
        try {
            harness.awaitObserverInstalled()
            awaitState("attempt in flight", probe = { harness.wedgeState() }) { harness.scoPlatform.scoStarts >= 1 }
            // Setting SCO up perturbs the OBSERVED route while the attempt runs: simulate that
            // by re-announcing the same devices (route re-emits, availability unchanged, gate
            // Allowed throughout). One attempt in total: the failure records the unchanged
            // surface, and the finally re-run is skipped.
            setAirPodsConnected()
            harness.monitor.onDevicesChanged()
            awaitState("attempt ended", probe = { harness.wedgeState() }) { getField(harness.service, "acquisitionJob") == null }
            val attemptsSoFar = harness.scoPlatform.scoStarts
            Thread.sleep(300)
            testScheduler.advanceTimeBy(300.milliseconds)
            assertEquals(
                "exactly one attempt despite mid-attempt route emissions",
                1,
                attemptsSoFar,
            )
        } finally {
            harness.stop()
        }
    }

    @Test
    fun `availability churn without a user device change never re-arms the restart budget`() = runBlocking {
        setAirPodsConnected()
        val modelsReady = ModelsReadyCondition(isReady = false)
        val otherAppRule = FakeRule("other_app_playing", VoiceControlRuleGroup.Conflicts)
        val foregroundRule = FakeRule("app_in_foreground", VoiceControlRuleGroup.Context)
        val harness = startService(modelsReady, otherAppRule, foregroundRule)
        try {
            harness.awaitObserverInstalled()
            awaitState("capture up", probe = { harness.wedgeState() }) { harness.hardware.attachCount >= 1 }

            val budget = getField(harness.service, "captureRestartBudget")!!
            val restartAvailable = {
                budget.javaClass.getDeclaredField("restartAvailable").apply { isAccessible = true }
                    .get(budget) as Boolean
            }
            budget.javaClass.getDeclaredField("restartAvailable").apply { isAccessible = true }
                .set(budget, false)

            // The app's own surfaces churn (selection, release, SCO-link flips move
            // availability; restart signals bump) while the USER'S device set stays the same:
            // the observer re-runs, and nothing re-arms.
            @Suppress("UNCHECKED_CAST")
            val restartSignal = getField(harness.service, "captureRestartSignal") as MutableStateFlow<Long>
            restartSignal.value = restartSignal.value + 1L
            awaitState("churn observed", probe = { harness.wedgeState() }) { true }
            Thread.sleep(200)
            testScheduler.advanceTimeBy(200.milliseconds)
            assertFalse(
                "the app's own routing changes are not device triggers",
                restartAvailable(),
            )

            // The user's devices change: a real device trigger re-arms the budget.
            setOutputDevices(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            setInputDevices()
            harness.monitor.onDevicesChanged()
            awaitState("capture down", probe = { harness.wedgeState() }) { harness.hardware.vadStopCount >= 1 }
            setAirPodsConnected()
            harness.monitor.onDevicesChanged()
            awaitState("capture back up after the real trigger", probe = { harness.wedgeState() }) { harness.hardware.attachCount >= 2 }
            assertTrue("a real device trigger re-arms the budget", restartAvailable())
        } finally {
            harness.stop()
        }
    }

    private fun gateAllowedNow(harness: Harness): Boolean {
        val gate = getField(harness.service, "gate")!!
        val state = gate.javaClass.getMethod("getState").invoke(gate) as StateFlow<*>
        val allowed = state.value!!.javaClass.getMethod("getAllowed").invoke(state.value) as Boolean
        return allowed
    }
}
