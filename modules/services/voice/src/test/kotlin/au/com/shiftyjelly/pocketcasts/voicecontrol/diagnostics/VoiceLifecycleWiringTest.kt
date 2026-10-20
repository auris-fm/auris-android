@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics

import android.app.Notification
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTokenProviding
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.CloudConfig
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackend
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackendSelector
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.ModelSpec
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.CaptureAttachment
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.VoiceAsrEngine
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRuleState
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.GracePeriodActiveCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.ModelsReadyCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.PlaybackContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningModePolicy
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.CloudPlaybackContextState
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.CloudRouteSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContextMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContextProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AndroidAudioRouteMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.RouteAcquisition
import au.com.shiftyjelly.pocketcasts.voicecontrol.service.VoiceControlCaptureStateHolder
import au.com.shiftyjelly.pocketcasts.voicecontrol.service.VoiceControlService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceLifecycleWiringTest {
    @Test
    fun `the injected cloud sink correlates its real hold and cancellation without request text`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            LifecycleLogCapture().use { log ->
                val signal = GracePeriodSignal()
                signal.onWakeWordDetected()
                val entered = CompletableDeferred<Unit>()
                var lookupMode = 0
                val config = mock<CloudConfig>()
                whenever(config.baseUrl()).thenReturn("https://example.invalid")
                val monitor = mock<PlaybackContextMonitor>()
                whenever(monitor.isHostAudioActive).thenReturn(MutableStateFlow(false))
                val sink = CloudRouteSink(
                    cloudConfig = config,
                    tokenProvider = object : CloudTokenProviding {
                        override suspend fun currentToken(): String? {
                            if (lookupMode == 1) throw kotlinx.coroutines.CancellationException("PRIVATE CANCELLATION")
                            if (lookupMode == 2) error("PRIVATE FAILURE")
                            entered.complete(Unit)
                            kotlinx.coroutines.awaitCancellation()
                        }
                    },
                    playbackSink = mock(), fingerprintTimingManager = mock(),
                    playbackContextProvider = object : PlaybackContextProvider {
                        override fun current() = PlaybackContext()
                    },
                    cloudPlaybackContextState = CloudPlaybackContextState(), analytics = mock(),
                    templateResolver = au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.SpokenTemplateResolver(emptyMap()),
                    currentLocale = { java.util.Locale.ENGLISH }, playbackContextMonitor = monitor,
                    audioPlayer = null, gracePeriodSignal = signal,
                )
                val prepared = sink.prepareTurn(
                    request = "PRIVATE UTTERANCE", tier = VoiceIntent.CloudTier.Premium, context = PlaybackContext(),
                    isLiveGraceTurn = true, capturedOrigin = signal.currentGeneration, isWakeOriginated = true,
                    hint = null, logicalRequestId = "test-logical-request", isRetry = false, sourceLanguage = "zh",
                )!!
                val turn = async { sink.dispatchPrepared(prepared) }
                entered.await()
                turn.cancelAndJoin()
                val lines = log.lines()
                val started = lines.first { it.contains("event=Turn reason=TurnStarted") }
                val owner = Regex("owner=(\\d+)").find(started)!!.groupValues[1]
                assertTrue(started.contains("request_id=${prepared.requestId}"))
                assertFalse(started.contains("request_id=-"))
                assertTrue(lines.any { it.contains("reason=HoldAcquired") && it.contains("owner=$owner ") })
                assertTrue(lines.any { it.contains("reason=TurnCancelled") && it.contains("owner=$owner ") })
                assertTrue(lines.any { it.contains("reason=HoldCancelled") && it.contains("owner=$owner ") })
                assertFalse(lines.any { it.contains("PRIVATE") || it.contains("example.invalid") })
                // Cancellation thrown by a child/provider occurs while the outer job is still active.
                lookupMode = 1
                val childCancelled = async { sink.dispatchPrepared(prepared, isRetry = true) }
                childCancelled.join()
                assertTrue(childCancelled.isCancelled)
                lookupMode = 2
                kotlinx.coroutines.supervisorScope {
                    val failed = async { sink.dispatchPrepared(prepared, isRetry = true) }
                    assertTrue(runCatching { failed.await() }.isFailure)
                }
                val all = log.lines()
                val owners = all.filter { it.contains("event=Turn reason=TurnStarted") }
                    .map { Regex("owner=(\\d+)").find(it)!!.groupValues[1] }
                assertTrue(all.any { it.contains("reason=TurnCancelled") && it.contains("owner=${owners[1]} ") })
                assertTrue(all.any { it.contains("reason=HoldCancelled") && it.contains("owner=${owners[1]} ") })
                assertTrue(all.any { it.contains("reason=TurnFailed") && it.contains("owner=${owners[2]} ") })
                assertFalse(all.any { it.contains("PRIVATE") })
                signal.onAppBackgrounded()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `real route callbacks retain their different sources`() = runTest {
        LifecycleLogCapture().use { log ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val signal = GracePeriodSignal()
            val monitor = AndroidAudioRouteMonitor(context, signal)
            signal.onWakeWordDetected()
            monitor.onDevicesChanged()
            signal.onWakeWordDetected()
            val headset = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLE_HEADSET).build()
            shadowOf(manager).callOnCommunicationDeviceChangedListeners(headset)
            shadowOf(Looper.getMainLooper()).idle()
            signal.onWakeWordDetected()
            @Suppress("DEPRECATION")
            val intent = android.content.Intent(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            context.sendBroadcast(intent)
            shadowOf(Looper.getMainLooper()).idle()
            signal.onWakeWordDetected()
            monitor.onExternalMicrophoneConfirmed()
            val lines = log.lines()
            for (source in listOf("Devices", "CommunicationDevice", "ScoState", "Acquisition")) {
                assertTrue(source, lines.any { it.contains("reason=RouteChanged source=$source ") })
            }
        }
    }

    @Test
    fun `the foreground monitor records its actual background transition`() = runTest {
        LifecycleLogCapture().use { log ->
            val visibility = MutableStateFlow(true)
            val lifecycle = mock<au.com.shiftyjelly.pocketcasts.repositories.playback.AppLifecycleProvider>()
            whenever(lifecycle.isInForeground).thenReturn(visibility)
            val signal = GracePeriodSignal()
            au.com.shiftyjelly.pocketcasts.voicecontrol.foreground.ForegroundStateMonitor(lifecycle, backgroundScope, signal)
            runCurrent()
            signal.onWakeWordDetected()
            visibility.value = false
            runCurrent()
            assertTrue(log.lines().any { it.contains("reason=Backgrounded source=Foreground") && it.contains("prior_active=true active=false") })
            visibility.value = true
            runCurrent()
            signal.onWakeWordDetected()
            val hold = signal.holdCloudTurn()!!
            visibility.value = false
            runCurrent()
            assertTrue(signal.isActive.value)
            assertTrue(log.lines().any { it.contains("reason=BackgroundHoldPreserved source=Foreground") && it.contains("prior_active=true active=true holds_before=1 holds_after=1") })
            signal.finishCloudTurn(hold)
        }
    }

    @Test
    fun `the actual service observer exports context loss and capture stop`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val scope = CoroutineScope(Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
        val service = VoiceControlService()
        try {
            LifecycleLogCapture().use { log ->
                val context = ApplicationProvider.getApplicationContext<Context>()
                android.content.ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                    .apply { isAccessible = true }.invoke(service, context)
                shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
                val signal = GracePeriodSignal()
                val ready = ModelsReadyCondition(isReady = false)
                val gate = VoiceControlGate(listOf(GracePeriodActiveCondition(signal, scope), ready), scope)
                val route = MutableStateFlow<AudioRoute>(AudioRoute.Headset(true))
                val monitor = mock<AndroidAudioRouteMonitor>()
                whenever(monitor.route).thenReturn(route)
                whenever(monitor.availability).thenReturn(route)
                val engine = mock<VoiceAsrEngine>()
                var terminate: () -> Unit = {}
                whenever(engine.start(any(), any(), any(), any(), any(), any(), any()))
                    .thenAnswer { invocation ->
                        terminate = invocation.getArgument(6)
                        CaptureAttachment(CompletableDeferred(true))
                    }
                val backend = mock<AsrBackend>()
                whenever(backend.requiredModel).thenReturn(ModelSpec(files = emptyList(), targetDir = "test"))
                whenever(backend.ensureReady()).thenReturn(Result.success(Unit))
                val selector = mock<AsrBackendSelector>()
                whenever(selector.select()).thenReturn(backend)
                val acquisition = mock<RouteAcquisition>()
                whenever(acquisition.confirmCaptureStream()).thenReturn(true)
                service.gate = gate
                service.notificationManager = mock {
                    on { createDownloadingNotification() }.thenReturn(mock<Notification>())
                    on { createListeningNotification(any()) }.thenReturn(mock<Notification>())
                }
                service.voiceRecognizer = mock()
                whenever(service.voiceRecognizer.ensureReady()).thenReturn(Result.success(Unit))
                service.voicePlaybackIntentExecutor = mock()
                service.voiceDialogManager = mock()
                service.playbackContextMonitor = mock()
                service.audioRouteMonitor = monitor
                service.listeningModePolicy = ListeningModePolicy(gate, monitor, signal, scope)
                service.voiceAsrEngine = dagger.Lazy { engine }
                service.asrBackendSelector = selector
                service.playbackBufferRecorder = mock()
                service.modelManager = mock()
                service.modelsReadyCondition = ready
                service.voiceControlServiceController = mock()
                service.audioFeedbackRenderer = mock()
                service.gracePeriodSignal = signal
                service.routeAcquisition = acquisition
                service.captureStateHolder = VoiceControlCaptureStateHolder()
                service.onStartCommand(null, 0, 1)
                fun pumpUntil(predicate: () -> Boolean) {
                    repeat(500) {
                        runCurrent()
                        if (predicate()) return
                        Thread.sleep(10)
                    }
                    error("bounded service observer did not reach expected capture state")
                }
                pumpUntil { ready.state.value is VoiceControlRuleState.Allowed }
                signal.onWakeWordDetected()
                pumpUntil { service.captureStateHolder.capturing.value }
                terminate()
                runCurrent()
                pumpUntil { service.captureStateHolder.capturing.value }
                val blockedHold = signal.holdCloudTurn()!!
                ready.update(isReady = false)
                pumpUntil { !service.captureStateHolder.capturing.value }
                assertFalse("a capture blocker invalidates even a live cloud hold", signal.isActive.value)
                assertTrue(signal.isClosedByPrivacy())
                signal.finishCloudTurn(blockedHold)
                ready.update(isReady = true)
                runCurrent()
                assertFalse("clearing the blocker and late completion cannot reopen grace", signal.isActive.value)
                signal.onWakeWordDetected()
                pumpUntil { service.captureStateHolder.capturing.value }
                signal.onAppBackgrounded()
                pumpUntil { !service.captureStateHolder.capturing.value }
                verify(engine, org.mockito.kotlin.times(2)).stop()
                signal.onWakeWordDetected()
                pumpUntil { service.captureStateHolder.capturing.value }
                service.onDestroy()
                val lines = log.lines()
                assertTrue(lines.any { it.contains("event=Capture reason=PipelineTerminated source=CapturePipeline") })
                assertTrue(lines.any { it.contains("event=Capture reason=ServiceStopped source=ServiceStop") })
                assertTrue(lines.any { it.contains("event=Capture reason=CaptureStarted") && it.contains("prior_capture=false capture=true") })
                assertTrue(lines.any { it.contains("event=Eligibility") && it.contains("mode=Off") })
                assertTrue(lines.any { it.contains("event=Gate") && it.contains("gate=3 ") && it.contains("changed_rules=512") })
                assertTrue(lines.any { it.contains("event=Capture reason=EligibilityOff source=ServiceObserver") && it.contains("prior_capture=true capture=false") })
            }
        } finally {
            runCatching { service.onDestroy() }
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            Dispatchers.resetMain()
        }
    }
}
