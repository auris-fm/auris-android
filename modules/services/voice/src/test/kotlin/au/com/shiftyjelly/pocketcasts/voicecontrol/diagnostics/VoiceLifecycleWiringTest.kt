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
@Config(sdk = [35])
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
                    // A real utterance identity is a UUID (spec f47e972): the diagnostic records a
                    // non-UUID as `request_id=invalid`, so a placeholder here would assert the
                    // opposite of the contract.
                    hint = null, logicalRequestId = "b7ce1a5e-1c1b-4a4f-9d1e-3f2c8a6d5e70", isRetry = false, sourceLanguage = "zh",
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
        val scope = CoroutineScope(Dispatchers.Unconfined + kotlinx.coroutines.SupervisorJob())
        val service = VoiceControlService()
        var pendingCloud: kotlinx.coroutines.Job? = null
        try {
            LifecycleLogCapture().use { log ->
                val context = ApplicationProvider.getApplicationContext<Context>()
                android.content.ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                    .apply { isAccessible = true }.invoke(service, context)
                shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
                val signal = GracePeriodSignal()
                val ready = ModelsReadyCondition(isReady = false)
                val visibility = MutableStateFlow(true)
                val lifecycle = object : au.com.shiftyjelly.pocketcasts.repositories.playback.AppLifecycleProvider {
                    override val isInForeground = visibility
                }
                val foreground = au.com.shiftyjelly.pocketcasts.voicecontrol.foreground.ForegroundStateMonitor(lifecycle, scope, signal)
                val playbackState = MutableStateFlow(
                    au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackState(
                        state = au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackState.State.PAUSED,
                        episodeUuid = "episode-id",
                    ),
                )
                val playbackManager = mock<au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager>()
                whenever(playbackManager.playbackStateFlow).thenReturn(playbackState)
                val playbackMonitor = PlaybackContextMonitor(playbackManager, signal, backgroundScope)
                val settings = mock<au.com.shiftyjelly.pocketcasts.preferences.Settings>()
                val disabled = mock<au.com.shiftyjelly.pocketcasts.preferences.UserSetting<Boolean>>()
                whenever(disabled.flow).thenReturn(MutableStateFlow(false))
                whenever(settings.voiceControlUserDisabled).thenReturn(disabled)
                val otherApp = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.OtherAppPlayingCondition().apply { update(false) }
                val gate = au.com.shiftyjelly.pocketcasts.voicecontrol.di.VoiceControlModule.provideVoiceControlGate(
                    playbackContextMonitor = playbackMonitor,
                    settings = settings,
                    deviceSupported = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.DeviceSupportedCondition(),
                    modelsReady = ready,
                    notOnCall = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.NotOnCallCondition(),
                    notCasting = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.NotCastingCondition(),
                    batteryOk = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.BatteryOkCondition(),
                    appInForeground = au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.AppInForegroundCondition(foreground, scope),
                    otherAppPlaying = otherApp,
                    scope = scope,
                )
                val requestContext = object : PlaybackContextProvider {
                    override fun current() = PlaybackContext(episodeId = playbackState.value.episodeUuid.takeIf { it.isNotBlank() })
                }
                val entered = CompletableDeferred<Unit>()
                val finish = CompletableDeferred<Unit>()
                val config = mock<CloudConfig>()
                whenever(config.baseUrl()).thenReturn("https://example.invalid")
                val cloudSink = CloudRouteSink(
                    cloudConfig = config,
                    tokenProvider = object : CloudTokenProviding {
                        override suspend fun currentToken(): String? {
                            entered.complete(Unit)
                            finish.await()
                            return null
                        }
                    },
                    playbackSink = mock(), fingerprintTimingManager = mock(), playbackContextProvider = requestContext,
                    cloudPlaybackContextState = CloudPlaybackContextState(), analytics = mock(),
                    templateResolver = au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.SpokenTemplateResolver(emptyMap()),
                    currentLocale = { java.util.Locale.ENGLISH }, playbackContextMonitor = playbackMonitor,
                    audioPlayer = null, gracePeriodSignal = signal,
                )
                val executor = au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackIntentExecutor(
                    playbackSink = mock(), effectsSink = mock(), volumeSink = mock(), sleepSink = mock(),
                    chapterSink = mock(), bookmarkSink = mock(), cloudRouteSink = cloudSink,
                    playbackContextProvider = requestContext, queueSink = mock(), playbackQuerySink = mock(),
                    statsQuerySink = mock(), gracePeriodSignal = signal, playbackManager = playbackManager,
                )
                val route = MutableStateFlow<AudioRoute>(AudioRoute.Headset(true))
                val monitor = mock<AndroidAudioRouteMonitor>()
                var reopenBeforeQueuedOff = false
                whenever(monitor.route).thenAnswer {
                    if (reopenBeforeQueuedOff) {
                        reopenBeforeQueuedOff = false
                        // Eligibility recovers after Off was emitted but before its observer handles it.
                        visibility.value = true
                        signal.onWakeWordDetected()
                    }
                    route
                }
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
                service.voicePlaybackIntentExecutor = executor
                service.voiceDialogManager = mock()
                service.playbackContextMonitor = playbackMonitor
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
                pumpUntil { service.captureStateHolder.capturing.value }
                signal.onWakeWordDetected()
                runCurrent()
                assertTrue("a fresh wake cannot be canceled by a stale derived Off", signal.isActive.value)
                terminate()
                runCurrent()
                pumpUntil { service.captureStateHolder.capturing.value }
                val origin = signal.currentGeneration
                val prepared = cloudSink.prepareTurn(
                    "controlled question",
                    VoiceIntent.CloudTier.Premium,
                    requestContext.current(),
                    capturedOrigin = origin,
                    logicalRequestId = "held-question",
                )!!
                val cloudReply = async { cloudSink.dispatchPrepared(prepared) }
                pendingCloud = cloudReply
                entered.await()
                visibility.value = false
                runCurrent()
                assertTrue("background with a paused playable episode remains eligible", gate.state.value.allowed)
                assertTrue(service.captureStateHolder.capturing.value)
                assertTrue(signal.isCurrent(origin))
                org.junit.Assert.assertSame(prepared, cloudSink.retainedTurnFor("held-question"))
                playbackState.value = au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackState()
                runCurrent()
                assertFalse("grace must not substitute for the last real listening context", gate.state.value.contextPassed)
                pumpUntil { !service.captureStateHolder.capturing.value }
                assertFalse(signal.isActive.value)
                assertTrue(signal.isClosedByPrivacy())
                org.junit.Assert.assertNull("the actual service binding retires invalidated work", cloudSink.retainedTurnFor("held-question"))
                finish.complete(Unit)
                cloudReply.await()
                signal.onCommandRecognized(fromGeneration = origin)
                assertFalse("late cloud completion cannot restore context-invalidated grace", signal.isActive.value)
                visibility.value = true
                pumpUntil { service.captureStateHolder.capturing.value }
                assertFalse("context recovery alone does not reopen grace", signal.isActive.value)
                signal.onWakeWordDetected()
                runCurrent()
                assertTrue("a fresh eligible wake survives derived mode updates", signal.isActive.value)
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
                visibility.value = false
                pumpUntil { !service.captureStateHolder.capturing.value }
                verify(engine, org.mockito.kotlin.times(3)).stop()
                visibility.value = true
                pumpUntil { service.captureStateHolder.capturing.value }
                signal.onWakeWordDetected()
                val oldHold = signal.holdCloudTurn()!!
                reopenBeforeQueuedOff = true
                visibility.value = false
                pumpUntil { !reopenBeforeQueuedOff && service.captureStateHolder.capturing.value }
                assertTrue("an observed stale Off cannot invalidate the new eligible wake", signal.isActive.value)
                assertTrue(gate.state.value.allowed)
                signal.finishCloudTurn(oldHold)
                assertTrue(signal.isActive.value)
                assertTrue("the service actually observed Off after eligibility recovered", log.lines().any { it.contains("event=Eligibility") && it.contains("mode=Off") && it.contains("gate=15 ") })
                service.onDestroy()
                val lines = log.lines()
                assertTrue(lines.any { it.contains("event=Capture reason=PipelineTerminated source=CapturePipeline") })
                assertTrue(lines.any { it.contains("event=Capture reason=ServiceStopped source=ServiceStop") })
                assertTrue(lines.any { it.contains("event=Capture reason=CaptureStarted") && it.contains("prior_capture=false capture=true") })
                assertTrue(lines.any { it.contains("event=Eligibility") && it.contains("mode=Off") })
                assertTrue(lines.any { it.contains("event=Gate") && it.contains("gate=3 ") && it.contains("changed_rules=256") })
                assertTrue(lines.any { it.contains("event=Capture reason=EligibilityOff source=ServiceObserver") && it.contains("prior_capture=true capture=false") })
            }
        } finally {
            pendingCloud?.cancelAndJoin()
            runCatching { service.onDestroy() }
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            Dispatchers.resetMain()
        }
    }
}
