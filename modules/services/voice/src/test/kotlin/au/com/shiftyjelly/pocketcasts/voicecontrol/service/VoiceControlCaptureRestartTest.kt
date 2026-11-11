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
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceControlCaptureRestartTest {
    companion object {
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
        Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))
    }
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }
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
            Thread {
                try {
                    Thread.sleep(150)
                    scoLinkUp = true
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
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
                }
            }.apply { isDaemon = true }.start()
        }
        override fun stopBluetoothSco() {
            scoStops++
            scoLinkUp = false
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
        override fun audioMode(): Int = audioManager.mode
        override fun setAudioMode(mode: Int) {
            modeSets++
            lastMode = mode
            audioManager.mode = mode
        }
        override fun deviceType(deviceId: Int): Int? = if (deviceId == SCO_INPUT_ID) AudioDeviceInfo.TYPE_BLUETOOTH_SCO else null
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
    private fun replaceField(target: Any, name: String, value: Any?) {
        val clazz = generateSequence<Class<*>>(target.javaClass) { it.superclass }
            .first { c -> c.declaredFields.any { it.name == name } }
        val f = clazz.getDeclaredField(name)
        f.isAccessible = true
        f.set(target, value)
    }
    private lateinit var testScheduler: kotlinx.coroutines.test.TestCoroutineScheduler
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
    @Test
    fun `the observed stop-restart cycle reproduces and is pinned`() = runBlocking {
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
        val gate = VoiceControlGate(
            listOf(userRule, otherAppRule, foregroundRule, modelsReady),
            CoroutineScope(Dispatchers.Main.immediate),
        )
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
        val monitor = AndroidAudioRouteMonitor(context, GracePeriodSignal())
        val scoPlatform = FakeScoPlatform(microphoneCapture).also { scoLinkUp = { it.scoLinkUp } }
        val acquisition = RouteAcquisition(scoPlatform, timeoutMs = 6_000L, pollIntervalMs = 10L)
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
        val engineStartProbe = getField(service, "engineStart")!!
        val generationField = engineStartProbe.javaClass.getDeclaredField("startGeneration").apply { isAccessible = true }
        try {
            VoiceControlService::class.java.getDeclaredMethod(
                "onStartCommand",
                Intent::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }.invoke(service, null, 0, 1)
            awaitState("observer install") { getField(service, "modeJob") != null }
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
            otherAppRule.block("other_app_playing")
            awaitState("first VAD teardown") { hardware.vadStopCount >= 1 }
            otherAppRule.allow()
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
            assertEquals("vadStopCount", 1, finalVadStop)
            assertEquals("openCount", 2, finalOpen)
            assertEquals("closeCount (explicit close, not engine teardown)", 0, finalClose)
        } finally {
            runCatching {
                (getField(service, "modeJob") as? kotlinx.coroutines.Job)?.cancel()
            }
            runCatching { microphoneCapture.stopCapture() }
        }
    }
}
