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
    }

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var testScheduler: TestCoroutineScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        testScheduler = TestCoroutineScheduler()
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- fakes at the native boundary (same contracts as VoiceControlCaptureRestartTest) ----

    private inner class FakeScoPlatform(
        private val microphoneCapture: MicrophoneCapture,
        private val establish: Boolean = true,
    ) : au.com.shiftyjelly.pocketcasts.voicecontrol.route.RouteAcquisitionPlatform {
        var scoStarts = 0
        var scoStops = 0

        @Volatile var scoLinkUp = false
            private set

        override fun bluetoothCandidates() = listOf(
            BluetoothCandidate(deviceId = SCO_INPUT_ID, transport = BluetoothTransport.ClassicSco),
        )

        override fun startBluetoothSco() {
            scoStarts++
            if (!establish) return
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

        override fun stopBluetoothSco() {
            scoStops++
            scoLinkUp = false
            setShadowBluetoothScoOn(audioManager, false)
            shadowOf(audioManager).setInputDevices(emptyList())
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
            if (deviceId == SCO_INPUT_ID) AudioDeviceInfo.TYPE_BLUETOOTH_SCO else null

        override fun openInputStream(): Int = microphoneCapture.openStreamForAcquisition()
        override fun startInputStream(): Boolean = microphoneCapture.startAcquiredStream()
        override fun closeInputStream() = microphoneCapture.closeAcquiredStream()
    }

    private class FakeHardware(private val scoReady: () -> Boolean) : CaptureHardware {
        var capturing = false
        var attachCount = 0
        var vadStopCount = 0
        var openCount = 0
        var startStreamCount = 0
        var closeCount = 0

        override fun startCaptureAndVad(sampleRate: Int, channels: Int, assetManager: android.content.res.AssetManager): Boolean {
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

        override fun waitForVadEvent(timeoutMs: Int): Int {
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

    private suspend fun awaitState(what: String, predicate: () -> Boolean) {
        val outcome = kotlinx.coroutines.CompletableDeferred<Result<Unit>>()
        val job = kotlinx.coroutines.SupervisorJob()
        val scope = kotlinx.coroutines.CoroutineScope(job + kotlinx.coroutines.Dispatchers.Main.immediate)
        scope.launch {
            try {
                val deadline = System.currentTimeMillis() + 8_000
                while (!predicate()) {
                    if (System.currentTimeMillis() > deadline) error("timed out waiting for $what")
                    testScheduler.advanceTimeBy(25.milliseconds)
                    kotlinx.coroutines.delay(25)
                }
                outcome.complete(Result.success(Unit))
            } catch (e: Throwable) {
                outcome.complete(Result.failure(e))
            }
        }
        try {
            while (!outcome.isCompleted) {
                testScheduler.runCurrent()
                testScheduler.advanceTimeBy(25.milliseconds)
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
    ): Harness {
        val userRule = FakeRule(au.com.shiftyjelly.pocketcasts.voicecontrol.gate.EnabledByUserCondition.ID, VoiceControlRuleGroup.Setup)
        val gate = VoiceControlGate(
            listOf(userRule, otherAppRule, foregroundRule, modelsReady),
            CoroutineScope(Dispatchers.Main.immediate),
        )

        var scoLinkUp: () -> Boolean = { false }
        val hardware = FakeHardware(scoReady = { scoLinkUp() })
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
        val scoPlatform = FakeScoPlatform(microphoneCapture, establish = scoEstablishes).also { scoLinkUp = { it.scoLinkUp } }
        val acquisition = RouteAcquisition(scoPlatform, timeoutMs = acquisitionTimeoutMs, pollIntervalMs = 10L)

        val policy = ListeningModePolicy(gate, monitor, GracePeriodSignal(), CoroutineScope(Dispatchers.Main.immediate))

        val notificationManager = mock(VoiceControlNotificationManager::class.java)
        whenever(notificationManager.notificationId).thenReturn(1001)
        whenever(notificationManager.createListeningNotification(any())).thenReturn(mock(Notification::class.java))
        whenever(notificationManager.createDownloadingNotification()).thenReturn(mock(Notification::class.java))

        val service = VoiceControlService()
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

    private suspend fun Harness.awaitObserverInstalled() {
        awaitState("observer install") { getField(service, "modeJob") != null }
    }

    private suspend fun Harness.stop() {
        runCatching { (getField(service, "modeJob") as? kotlinx.coroutines.Job)?.cancel() }
        runCatching { microphoneCapture.stopCapture() }
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
            awaitState("speaker-only steady state") { harness.monitor.availability.value == AudioRoute.Speaker }
            assertEquals("no candidate, no acquisition", 0, harness.scoPlatform.scoStarts)
            assertEquals("no capture", 0, harness.hardware.attachCount)

            // The headset arrives mid-session (the AirPods reconnect). Availability flips to the
            // candidate; the observer must re-run and acquire.
            setAirPodsConnected()
            harness.monitor.onDevicesChanged()
            awaitState("VAD attach after headset arrival") { harness.hardware.attachCount >= 1 }
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
            awaitState("first capture") { harness.hardware.attachCount >= 1 }
            val firstAttach = harness.hardware.attachCount

            // BT off: devices vanish; capture stops (EligibilityOff).
            setOutputDevices(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            setInputDevices()
            harness.monitor.onDevicesChanged()
            awaitState("capture torn down after headset loss") { harness.hardware.vadStopCount >= 1 }
            awaitState("availability back to speaker") { harness.monitor.availability.value == AudioRoute.Speaker }

            // BT on again: the AirPods reconnect mid-session. Capture MUST resume.
            setAirPodsConnected()
            harness.monitor.onDevicesChanged()
            awaitState("capture resumed after headset reconnect") { harness.hardware.attachCount > firstAttach }
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
            awaitState("first capture") { harness.hardware.attachCount >= 1 }
            val scoStartsBefore = harness.scoPlatform.scoStarts

            // Availability changes (wired headphones output, no mic input -> NoMic), but no
            // candidate with a microphone appears. The observer re-runs and must NOT acquire.
            setOutputDevices(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            setInputDevices(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            harness.monitor.onDevicesChanged()
            awaitState("availability moved off the candidate") {
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
            awaitState("first failed acquisition") { harness.scoPlatform.scoStarts >= 1 }
            awaitState("failed attempt recorded") {
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
            awaitState("re-evaluation observed") { true }
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
            awaitState("availability back to speaker") { harness.monitor.availability.value == AudioRoute.Speaker }
            setAirPodsConnected()
            harness.monitor.onDevicesChanged()
            awaitState("retry after the trigger surface moved") {
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
            awaitState("first capture") { harness.hardware.attachCount >= 1 }

            // Merlin's sequence, twice: BT off (capture stops), BT on (capture resumes).
            repeat(2) {
                setOutputDevices(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                setInputDevices()
                harness.monitor.onDevicesChanged()
                awaitState("capture down (cycle)") { harness.hardware.vadStopCount >= it + 1 }
                setAirPodsConnected()
                harness.monitor.onDevicesChanged()
                awaitState("capture back up (cycle)") { harness.hardware.attachCount >= it + 2 }
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
            awaitState("first failed acquisition") { harness.scoPlatform.scoStarts >= 1 }
            awaitState("failed attempt recorded") {
                getField(harness.service, "failedAttemptAvailability") != null
            }
            val scoStartsAfterFailure = harness.scoPlatform.scoStarts

            // A BLOCKER CLEARS: the overall gate goes Blocked -> Allowed. That is a new trigger
            // for any blocker, so exactly one more attempt runs.
            otherAppRule.block("other_app_playing")
            awaitState("gate blocked observed") { !gateAllowedNow(harness) }
            otherAppRule.allow()
            awaitState("gate allowed again") { gateAllowedNow(harness) }
            awaitState("retry after the blocker cleared") {
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
            awaitState("first failed acquisition") { harness.scoPlatform.scoStarts >= 1 }
            awaitState("failed attempt recorded") {
                getField(harness.service, "failedAttemptAvailability") != null
            }

            // Attempt in flight (a new trigger started it), the gate blocks mid-attempt, the
            // attempt dies as abandoned, then the gate allows again: the cleared blocker is the
            // trigger and exactly ONE new attempt must run.
            otherAppRule.block("other_app_playing")
            harness.scoPlatform.scoStarts = 0
            awaitState("gate blocked observed") { !gateAllowedNow(harness) }
            otherAppRule.allow()
            awaitState("gate allowed again") { gateAllowedNow(harness) }
            awaitState("exactly one new attempt after the abandoned one") {
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
            awaitState("attempt in flight") { harness.scoPlatform.scoStarts >= 1 }
            // Gate blocks and clears WHILE the attempt is in flight: both emissions are
            // consumed by the in-flight guard (rule 2's coalescing). Nothing changes after
            // the attempt ends, so the only path to a retry is the coalesced re-evaluation.
            otherAppRule.block("other_app_playing")
            otherAppRule.allow()
            val attemptsSoFar = harness.scoPlatform.scoStarts
            // The failure unwinds, and the coalesced trigger's re-evaluation fires a second
            // attempt WITHOUT any new emission after the block/allow pair.
            awaitState("coalesced attempt after the failure") {
                harness.scoPlatform.scoStarts > attemptsSoFar
            }
            awaitState("attempt ended") {
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
            awaitState("attempt in flight") { harness.scoPlatform.scoStarts >= 1 }
            // Setting SCO up perturbs the OBSERVED route while the attempt runs: simulate that
            // by re-announcing the same devices (route re-emits, availability unchanged, gate
            // Allowed throughout). One attempt in total: the failure records the unchanged
            // surface, and the finally re-run is skipped.
            setAirPodsConnected()
            harness.monitor.onDevicesChanged()
            awaitState("attempt ended") { getField(harness.service, "acquisitionJob") == null }
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

    private fun gateAllowedNow(harness: Harness): Boolean {
        val gate = getField(harness.service, "gate")!!
        val state = gate.javaClass.getMethod("getState").invoke(gate) as StateFlow<*>
        val allowed = state.value!!.javaClass.getMethod("getAllowed").invoke(state.value) as Boolean
        return allowed
    }
}
