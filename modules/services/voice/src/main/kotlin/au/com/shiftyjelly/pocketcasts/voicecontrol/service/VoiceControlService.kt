package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackendSelector
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.CanaryFlashBackend
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.SenseVoiceBackend
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.WhisperCppBackend
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.VoiceAsrEngine
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconId
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.EnabledByUserCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRuleState
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.ModelsReadyCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningMode
import au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningModePolicy
import au.com.shiftyjelly.pocketcasts.voicecontrol.mode.shouldPlayListeningStartCue
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognitionContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContextMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoiceActionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackIntentExecutor
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.restoresAllowance
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.windowGenerationOf
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AcquisitionResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AndroidAudioRouteMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.MicExposure
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.RouteAcquisition
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.keepsPendingAcquisition
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.toMicExposure
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ExecutionOutcome
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@AndroidEntryPoint
class VoiceControlService : Service() {

    @Inject lateinit var gate: VoiceControlGate

    @Inject lateinit var notificationManager: VoiceControlNotificationManager

    @Inject lateinit var voiceRecognizer: VoiceRecognizer

    @Inject lateinit var voicePlaybackIntentExecutor: VoicePlaybackIntentExecutor

    @Inject lateinit var intentDispatcher: VoiceIntentDispatcher

    @Inject lateinit var playbackContextMonitor: PlaybackContextMonitor

    @Inject lateinit var audioRouteMonitor: AndroidAudioRouteMonitor

    @Inject lateinit var listeningModePolicy: ListeningModePolicy

    @Inject lateinit var voiceAsrEngine: dagger.Lazy<VoiceAsrEngine>

    @Inject lateinit var asrBackendSelector: AsrBackendSelector

    @Inject lateinit var playbackBufferRecorder: PlaybackBufferRecorder

    @Inject lateinit var modelManager: au.com.shiftyjelly.pocketcasts.voicecontrol.model.ModelManager

    @Inject lateinit var modelsReadyCondition: ModelsReadyCondition

    @Inject lateinit var voiceControlServiceController: VoiceControlServiceController

    @Inject lateinit var audioFeedbackRenderer: AudioFeedbackRenderer

    @Inject lateinit var gracePeriodSignal: GracePeriodSignal

    @Inject lateinit var routeAcquisition: RouteAcquisition

    @Inject lateinit var captureStateHolder: VoiceControlCaptureStateHolder

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var modeJob: Job? = null
    private var acquisitionJob: Job? = null

    /**
     * The start that has been ACCEPTED but may still be waiting for model readiness. `engineStart`
     * only flips true once capture is really up, so without this a second start would slip into the
     * readiness window (reviewer a85cf8fe). Cleared when the start coroutine finishes, so a failed
     * start can be retried.
     */
    private var startInFlightJob: Job? = null

    /** The teardown currently running, so a stop can return a job that really completes. */
    private var stopInFlightJob: Job? = null
    private var lastIntentType: String? = null
    private var lastCommandTime: Long = 0L
    private val engineStart = EngineStartState()
    private var wiredBackend: au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackend? = null
    private var captureWasEverActive = false
    private var acquisitionLogged = false
    private var lastObserverDecision: String? = null

    /** The automatic-restart cap, consulted by every start decision. */
    private val captureRestartBudget = CaptureRestartBudget()

    /**
     * Bumped to ask the mode observer to re-evaluate capture after it ended on its own. The
     * observer is the single owner of engine starts, so recovery signals it rather than starting
     * the engine from another coroutine, which could race an in-flight start.
     */
    private val captureRestartSignal = MutableStateFlow(0L)
    private var mediaSession: MediaSession? = null
    private var currentMode: ListeningMode = ListeningMode.Off

    companion object {
        private const val COMMAND_DEBOUNCE_MS = 2000L
        internal const val STOP_ACTION = "au.com.shiftyjelly.pocketcasts.voicecontrol.action.STOP"

        /** The spec's closed runtime-reason mapping for a pipeline that ended on its own. */
        private const val REASON_CAPTURE_FAILURE = "capture_failure"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP_ACTION) {
            // Stop came from the user, not from Android: the controller must not bring the service
            // back on the next foreground, or the button would silently do nothing.
            voiceControlServiceController.onServiceStoppedByUser()
            // ONE STOP AUTHORITY: the platform path stops through the seam, exactly like onDestroy.
            lifecycle.stop()
            return START_NOT_STICKY
        }
        // ONE START AUTHORITY: this platform path starts through the seam, not around it.
        lifecycle.start()
        return START_NOT_STICKY
    }

    /**
     * THE LIFECYCLE SEAM, reporting THIS service's own authority (reviewer a85cf8fe):
     * `startedOrStarting` covers the readiness window, and `stopPipeline` returns a job that
     * completes only after the pipeline has finished tearing down.
     */
    internal val lifecycle: VoiceServiceLifecycle by lazy {
        VoiceServiceLifecycle.FromServiceState(
            startedOrStarting = { engineStart.isStarted || startInFlightJob?.isActive == true },
            scope = object : VoiceServiceLifecycle.LifetimeScope {
                override val isActive get() = serviceScope.coroutineContext[Job]?.isActive == true
            },
            startPipeline = { startVoiceControl() },
            stopPipeline = { stopVoiceControl() },
        )
    }

    /** Returns true when a start was accepted; false when one is already running or in flight. */
    private fun startVoiceControl(): Boolean {
        if (engineStart.isStarted || startInFlightJob?.isActive == true) {
            Timber.i("Voice control already started")
            return false
        }
        // A user-driven service start clears the automatic-restart cap left by a previous session.
        captureRestartBudget.reset()

        if (!hasRequiredPermissions()) {
            Timber.w("Missing permissions, stopping")
            stopSelf()
            return false
        }

        try {
            val notification = notificationManager.createDownloadingNotification()
            startForeground(notificationManager.notificationId, notification)
            // Only now is the service really running: Android refuses a microphone foreground
            // service started from an ineligible app state, and that refusal throws below.
            voiceControlServiceController.onServiceStarted()
        } catch (e: SecurityException) {
            Timber.e(e, "Cannot start foreground — mic permission not granted")
            stopSelf()
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            mediaSession = MediaSession(this, "VoiceControl MediaSession")
            mediaSession?.isActive = true
        }

        // Handle models first: defer mode observation until readiness completes.
        // This avoids lazy init on first utterance — both whisper and the
        // LFM inference runtime are fully prepared before audio capture.
        // ALL-EXIT OWNERSHIP CLEANUP (reviewer dab802ec): invokeOnCompletion runs even when the
        // coroutine is cancelled BEFORE its body first runs (a launch finally does not), and the
        // try/catch below covers a launch that fails before the Job exists. Settlement is one-shot
        // in the engine, so every exit path converges on exactly one publication.
        startInFlightJob = serviceScope.launch(Dispatchers.IO) {
            ensureModelsReady()
            launch(Dispatchers.Main) {
                modeJob = kotlinx.coroutines.flow.combine(
                    listeningModePolicy.mode,
                    audioRouteMonitor.route,
                    // The gate is a third source so a gate transition that leaves the mode `Off` — a
                    // candidate route blocked only by the gate — still re-runs this observer. With
                    // `mode` alone, `Off` is unchanged across such a transition, no emission
                    // arrives, and acquisition is never attempted: the microphone stays shut after
                    // the user starts playback, with nothing to retry it.
                    gate.state,
                    // Recovery from a capture that ended on its own is a signal, not a second
                    // starter: this observer owns engine starts and re-runs on the bump.
                    captureRestartSignal,
                ) { mode, _, _, _ -> mode }.onEach { mode ->
                    val route = audioRouteMonitor.route.value
                    val micExposure = route.toMicExposure()
                    // Transitions, not repeated evaluations: the gate also emits for changes that
                    // leave this tuple alone (other rule state), and the composite decision — mode,
                    // route, exposure, gate — is what capture depends on. Debug level, and no audio
                    // or transcript content (voice-control-core.md > Privacy and UX).
                    val decision = "mode=$mode route=$route exposure=$micExposure " +
                        "gateAllowed=${gate.state.value.allowed} acquiring=${acquisitionJob != null}"
                    if (decision != lastObserverDecision) {
                        lastObserverDecision = decision
                        Timber.d("[VoicePipeline] observer: %s", decision)
                    }
                    // A pending acquisition stays valid while its candidate is present or has
                    // become the selected external microphone: the selection callbacks publish
                    // `Headset` while acquisition is still confirming, and cancelling on any
                    // non-candidate route would abort the acquisition that just succeeded and
                    // release the routing it acquired. Only actual device loss ends it; a gate
                    // blocker is handled by the gateAllows predicate inside acquisition.
                    if (!route.keepsPendingAcquisition()) {
                        acquisitionJob?.cancel()
                        acquisitionJob = null
                    }
                    when (mode) {
                        ListeningMode.Off -> {
                            // A gate loss, route change, or Off can arrive while a start is
                            // still suspended in ensureReady(); the acquisition caller clears
                            // its own job before that suspension, so nothing else can cancel
                            // it. Bumping the generation makes the pending start abandon
                            // itself instead of committing capture.
                            engineStart.invalidatePendingStart()
                            // The user turning voice control off is a fresh start, not a retry: clear
                            // the automatic-restart cap so re-enabling it can capture again.
                            if (gate.state.value.rules[EnabledByUserCondition.ID] is VoiceControlRuleState.Blocked) {
                                captureRestartBudget.reset()
                            }
                            if (engineStart.isStarted) {
                                // Active capture was ongoing, now stopping
                                val blockedRules = gate.state.value.rules
                                    .filter { it.value !is VoiceControlRuleState.Allowed }
                                val reasons = blockedRules.entries
                                    .joinToString(";") { "${it.key}=${it.value}" }
                                val isUserRequested = blockedRules.keys.any {
                                    it == EnabledByUserCondition.ID
                                }
                                logCaptureStopped(reasons, isUserRequested)
                                stopEngine()
                            } else if (route is AudioRoute.BluetoothA2dpOnly && gate.state.value.allowed) {
                                // The route is a candidate and every non-route gate question passes:
                                // acquire the external microphone, then capture. Without this the route
                                // stays Exposed and the mode never leaves Off — the deadlock.
                                acquireCandidate(route)
                            } else if (!captureWasEverActive && !acquisitionLogged) {
                                // Capture never started — log once, not on every recomputation
                                acquisitionLogged = true
                                val gateState = gate.state.value
                                val blockedRules = gateState.rules
                                    .filter { it.value !is VoiceControlRuleState.Allowed }
                                val reasons = blockedRules.entries
                                    .joinToString(";") { "${it.key}=${it.value}" }
                                Timber.i(
                                    "mic_acquisition_skipped: reasons=%s setup=%b conflicts=%b context=%b micExposure=%s route=%s mode=%s priorCaptureActive=false",
                                    reasons,
                                    gateState.setupPassed,
                                    gateState.conflictsPassed,
                                    gateState.contextPassed,
                                    micExposure,
                                    route,
                                    mode,
                                )
                            }
                            // else: was Off before, still Off — skip (reactive recomputation)
                            currentMode = ListeningMode.Off
                            updateListeningState()
                        }

                        ListeningMode.Continuous, ListeningMode.WakeWord -> {
                            captureWasEverActive = true
                            // Track the mode the engine is actually running in and drive the media
                            // session from it. Leaving the cache at the previous value skipped the
                            // switch back to wake-word when grace expired, so commands stayed
                            // accepted without a wake word.
                            if (engineStart.isStarted) {
                                if (mode != currentMode) {
                                    voiceAsrEngine.get().updateListeningMode(mode)
                                    // Keep the wake-word hint accurate as grace opens/expires
                                    val notification = notificationManager.createListeningNotification(
                                        wakeWordRequired = mode == ListeningMode.WakeWord,
                                    )
                                    notificationManager.notify(notification)
                                }
                                currentMode = mode
                                updateListeningState()
                            } else if (captureRestartBudget.allows()) {
                                @Suppress("MissingPermission") // permission checked in hasRequiredPermissions() above
                                if (startEngine(mode)) {
                                    currentMode = mode
                                    updateListeningState()
                                }
                            } else {
                                Timber.w("[VoicePipeline] start skipped: automatic restart withheld (cap reached)")
                            }
                        }
                    }
                }.launchIn(serviceScope)
            }
        }

        Timber.i("Voice control service started, observing listening mode")
        return true
    }

    private suspend fun ensureModelsReady() {
        Timber.i("Ensuring models")

        val backend = asrBackendSelector.select()
        if (backend == null) {
            Timber.w(
                "[VoicePipeline] ASR locale unsupported (lang=%s) — no Whisper product route; stopping",
                java.util.Locale.getDefault().language,
            )
            serviceScope.launch(Dispatchers.Main) { stopSelf() }
            return
        }
        Timber.i("Selected ASR backend: %s", backend::class.simpleName)

        // Digest verification runs on IO (reviewer ff30b294): hashing a cached model never
        // blocks the main/audio path.
        val needDownload = !modelManager.isModelReadyVerified(backend.requiredModel) ||
            !modelManager.isLfmModelReady()

        if (needDownload) {
            modelsReadyCondition.update(isReady = false)
        }

        if (modelManager.ensureModel(backend.requiredModel).isFailure) {
            Timber.e("ASR model download failed")
            serviceScope.launch(Dispatchers.Main) { stopSelf() }
            return
        }
        wireBackend(backend)
        // The backend outlives individual listening sessions: the engine keeps it warm across
        // stop/start (releasing it there would force a model reload on every resume), so the
        // service holds it and releases it once, in teardown.
        wiredBackend = backend

        if (backend.ensureReady().isFailure) {
            Timber.e("Backend not ready")
            serviceScope.launch(Dispatchers.Main) { stopSelf() }
            return
        }

        if (modelManager.ensureLfmModel().isFailure) {
            Timber.e("LFM model download failed")
            serviceScope.launch(Dispatchers.Main) { stopSelf() }
            return
        }

        Timber.i("LFM model ready, initializing intent router")
        val routerResult = voiceRecognizer.ensureReady()
        if (routerResult.isFailure) {
            Timber.e(routerResult.exceptionOrNull(), "Intent router initialization failed")
            serviceScope.launch(Dispatchers.Main) { stopSelf() }
            return
        }
        Timber.i("Intent router initialized")

        modelsReadyCondition.update(isReady = true)
        Timber.i("Voice control models ready")
    }

    private fun wireBackend(backend: au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackend) {
        val modelDir = java.io.File(filesDir, backend.requiredModel.targetDir)
        val modelFile = backend.requiredModel.files.firstOrNull()?.let {
            java.io.File(modelDir, it.filename)
        }
        if (modelFile != null && backend is WhisperCppBackend) {
            backend.setModelFile(modelFile)
        }
        if (backend is SenseVoiceBackend) {
            backend.setModelDir(modelDir)
        }
        if (backend is CanaryFlashBackend) {
            backend.setModelDir(modelDir)
        }
        Timber.i("ASR model ready")
    }

    /**
     * Starts the engine. The mode-flow collector is the only caller, and a `StateFlow` collector is
     * sequential, so one start cannot overlap another and no start lock is needed: serialization is
     * a consequence of there being one caller, not of [EngineStartState]. A second caller would not
     * be safe — two `VoiceAsrEngine.start` calls overwrite `processingJob` and attach two pipelines
     * to one stream. Returns true when the engine is running afterwards.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun startEngine(mode: ListeningMode): Boolean {
        // Captured before the attempt: validated after ensureReady() so a start that was
        // invalidated while suspended (gate loss, stop) does not commit.
        val generation = engineStart.generation()
        val started = engineStart.startIfNotRunning(
            start = {
                val backend = asrBackendSelector.select()
                if (backend == null) {
                    Timber.w(
                        "[VoicePipeline] ASR locale unsupported — refusing to start engine",
                    )
                    return@startIfNotRunning false
                }

                // Prepare the model before telling the user we are listening. The engine also checks
                // this (it refuses to run a pipeline that would only ever return empty results), but
                // by then the listening earcon and notification have already gone out, leaving the
                // user told "listening" with a microphone that never opens.
                if (backend.ensureReady().isFailure) {
                    Timber.e("[VoicePipeline] engine not started: ASR backend not ready")
                    return@startIfNotRunning false
                }

                // Eligibility is re-checked after the only suspension in this path. The mode-flow
                // collector can run while the acquisition start is suspended here, and a start
                // committed after the gate went Off would open a microphone the user is no
                // longer eligible to have open.
                if (!engineStart.isCurrent(generation)) {
                    Timber.i("[VoicePipeline] engine not started: start was invalidated")
                    return@startIfNotRunning false
                }
                if (!gate.state.value.allowed) {
                    Timber.i("[VoicePipeline] engine not started: gate no longer allows capture")
                    return@startIfNotRunning false
                }
                // Route eligibility. An Isolated route is directly eligible; a candidate route
                // is eligible once acquisition has opened and confirmed its stream, because the
                // (debounced) route monitor can still report BluetoothA2dpOnly at this point.
                // The opened stream is confirmed below, so anything else fails closed there.
                if (audioRouteMonitor.route.value.toMicExposure() != MicExposure.Isolated &&
                    !routeAcquisition.hasConfirmedStream
                ) {
                    Timber.i("[VoicePipeline] engine not started: route is not an isolated headset")
                    return@startIfNotRunning false
                }

                // Every capture start confirms the device the stream selected, not only
                // acquisition. The route classification can be wrong (an enumerated BLE input
                // is not a selected one), and native start must never open-and-start an
                // unconfirmed stream: the opened stream is the authority. Fails closed.
                if (!routeAcquisition.confirmCaptureStream()) {
                    Timber.e("[VoicePipeline] engine not started: selected device is not an external microphone")
                    return@startIfNotRunning false
                }

                val attachment = try {
                    voiceAsrEngine.get().start(
                        backend = backend,
                        audioRoute = audioRouteMonitor.route.value,
                        listeningMode = mode,
                        playbackBufferProvider = playbackBufferRecorder::snapshot,
                        micExposureProvider = { audioRouteMonitor.route.value.toMicExposure() },
                        onIntent = { intent, token -> handleIntent(intent, token) },
                        onTerminated = {
                            serviceScope.launch(Dispatchers.Main) { onPipelineTerminated(generation) }
                        },
                    )
                } catch (e: Exception) {
                    // The engine did not take the stream; it is still ours to close.
                    routeAcquisition.release()
                    Timber.e(e, "[VoicePipeline] engine start failed")
                    return@startIfNotRunning false
                }
                // Wait for the native capture pipeline to attach to the confirmed stream before
                // handing it over. Until then the stream is still ours: a start that ends before
                // attaching (backend failure, cancellation) must be closed here, or it stays live
                // with no consumer. Fail closed.
                val attached = try {
                    attachment.await()
                } catch (e: CancellationException) {
                    // The start caller was cancelled while the pipeline was attaching; the
                    // stream is still ours to stop and close.
                    voiceAsrEngine.get().stop()
                    routeAcquisition.release()
                    throw e
                }
                if (!attached) {
                    routeAcquisition.release()
                    Timber.e("[VoicePipeline] engine did not attach capture — confirmed stream closed")
                    return@startIfNotRunning false
                }
                // A gate loss/Off can arrive while the pipeline is attaching; the engine is now
                // running, so stop it rather than hand over a stream the user is no longer
                // eligible to have open.
                if (!engineStart.isCurrent(generation) || !gate.state.value.allowed) {
                    voiceAsrEngine.get().stop()
                    routeAcquisition.release()
                    Timber.i("[VoicePipeline] engine stopped: start was invalidated while attaching")
                    return@startIfNotRunning false
                }
                // The engine owns the confirmed stream from here; release() must not close it.
                routeAcquisition.handOffStream()
                true
            },
            // Post-start side effects run only after the coordinator has recorded the engine as
            // started. They must not be able to un-set it: acquireCandidate hands the acquired
            // stream to the engine based on this result, and a false would close a live stream.
            onStarted = {
                // Only when the microphone opens where the user cannot see it: the decision is
                // shouldPlayListeningStartCue in ListeningModePolicy. The predicate it calls is
                // covered in ListeningModePolicyTest.
                if (shouldPlayListeningStartCue(mode, gate.state.value.rules)) {
                    audioFeedbackRenderer.playEarcon(EarconId.LISTENING_START)
                }

                val notification = notificationManager.createListeningNotification(
                    wakeWordRequired = mode == ListeningMode.WakeWord,
                )
                notificationManager.notify(notification)
                Timber.i("[VoicePipeline] engine started mode=%s", mode)
            },
        )
        // Publish whether capture is actually running: the mode reads WakeWord/Continuous whenever
        // the route is eligible, including when a start fails, so settings must not equate the two.
        captureStateHolder.update(started)
        if (!started) {
            // A start that failed before taking the stream leaves acquisition's opened stream
            // unowned; close it rather than let it stay live with no consumer.
            routeAcquisition.release()
        }
        return started
    }

    /**
     * The processing pipeline can end on its own — a backend failure, or a stream that stops. Capture
     * state must not stay `Active`: settings would report listening and a later start would be
     * skipped as "already running". Scoped to the start generation, so an intentional stop (which
     * bumps it) is ignored.
     */
    private fun onPipelineTerminated(generation: Int) {
        if (!engineStart.isCurrent(generation) || !engineStart.isStarted) return
        // Budget the automatic restarts before anything releases routing: the release fires
        // selection callbacks that re-enter acquisition and the start path, and they consult the
        // same budget.
        captureRestartBudget.onTerminated()
        if (!captureRestartBudget.allows()) {
            Timber.w("[VoicePipeline] capture keeps ending — automatic restart withheld")
        }
        // A runtime stop, not a user request: the spec requires `mic_capture_stopped` at ERROR with
        // the runtime reason `capture_failure`, so lifecycle monitoring sees it.
        logCaptureStopped(REASON_CAPTURE_FAILURE, isUserRequested = false)
        clearCaptureState()
        recoverCapture()
    }

    /**
     * Capture ended without the gate asking it to — an AAudio disconnect, a backend failure. The
     * mode was not re-evaluated, so nothing else would re-run the observer: nudge it, if the rate
     * cap still allows a restart, and it starts capture again on its own sequential turn.
     *
     * The nudge goes to the observer rather than starting the engine here: it is the single owner
     * of engine starts, so a recovery cannot race an in-flight start that is still suspended.
     */
    private fun recoverCapture() {
        if (!captureRestartBudget.allows()) return
        captureRestartSignal.value = SystemClock.elapsedRealtime()
    }

    /**
     * Teardown shared by every stop: clear the started state, release acquisition routing, and list
     * the mode as Off. A caller that still holds a running engine stops it first.
     */
    private fun clearCaptureState() {
        engineStart.onStopped()
        captureStateHolder.update(false)
        currentMode = ListeningMode.Off
        routeAcquisition.release()
        updateListeningState()
    }

    /**
     * One `mic_capture_stopped` event for every way an active capture ends. The spec requires the
     * full gate posture, route/exposure, mode and prior capture state, at `ERROR` unless the user
     * directly asked for the stop.
     */
    private fun logCaptureStopped(reasons: String, isUserRequested: Boolean) {
        val gateState = gate.state.value
        val route = audioRouteMonitor.route.value
        val msg = "mic_capture_stopped: reasons=%s setup=%b conflicts=%b context=%b micExposure=%s route=%s mode=%s priorCaptureActive=true"
        if (isUserRequested) {
            Timber.i(msg, reasons, gateState.setupPassed, gateState.conflictsPassed, gateState.contextPassed, route.toMicExposure(), route, currentMode)
        } else {
            Timber.e(msg, reasons, gateState.setupPassed, gateState.conflictsPassed, gateState.contextPassed, route.toMicExposure(), route, currentMode)
        }
    }

    /**
     * Acquires an external Bluetooth microphone for a candidate route, then starts capture.
     * Acquisition is bounded and cancellable; when it fails or is cancelled no capture runs and
     * routing is released.
     */
    private fun acquireCandidate(route: AudioRoute) {
        if (acquisitionJob != null) return
        if (!captureRestartBudget.allows()) {
            Timber.w("[VoicePipeline] acquisition skipped: automatic restart withheld (cap reached)")
            return
        }
        Timber.i("[VoicePipeline] acquiring external microphone for route=%s", route)
        val gateAllows = {
            gate.state.value.allowed && audioRouteMonitor.route.value.keepsPendingAcquisition()
        }
        acquisitionJob = serviceScope.launch {
            try {
                val result = routeAcquisition.acquire(route, gateAllows)
                // Publish completion before starting the engine: the route monitor will flip to
                // Headset while startEngine() suspends, and the route-change handling must not
                // cancel the engine start as if it were still an acquisition.
                acquisitionJob = null
                when (result) {
                    is AcquisitionResult.Acquired -> {
                        // Publish the confirmed selection into the route state. The device list does
                        // not change when a microphone is selected, so the monitor may still report
                        // BluetoothA2dpOnly; without this the mode never leaves Off.
                        //
                        // Capture is started by the mode observer, the single owner of engine
                        // starts: the published route flips the mode to WakeWord/Continuous and the
                        // observer's startEngine reuses this attempt's already-opened stream.
                        audioRouteMonitor.onExternalMicrophoneConfirmed()
                        // The gate can block between acquisition's own check and this point. The
                        // stream is still ours until the engine takes it, so release it rather than
                        // leave it live with no consumer.
                        if (!gate.state.value.allowed) {
                            routeAcquisition.release()
                        }
                    }

                    is AcquisitionResult.Failed -> {
                        val gateState = gate.state.value
                        val blockedRules = gateState.rules
                            .filter { it.value !is VoiceControlRuleState.Allowed }
                        val reasons = blockedRules.entries
                            .joinToString(";") { "${it.key}=${it.value}" }
                        Timber.e(
                            "mic_acquisition_skipped: reasons=%s;%s setup=%b conflicts=%b context=%b micExposure=%s route=%s mode=%s priorCaptureActive=false",
                            result.reason,
                            reasons,
                            gateState.setupPassed,
                            gateState.conflictsPassed,
                            gateState.contextPassed,
                            route.toMicExposure(),
                            route,
                            currentMode,
                        )
                    }

                    AcquisitionResult.Cancelled, AcquisitionResult.NotNeeded -> Unit
                }
            } finally {
                if (acquisitionJob === currentCoroutineContext()[Job]) {
                    acquisitionJob = null
                }
            }
        }
    }

    private fun stopEngine() {
        acquisitionJob?.cancel()
        acquisitionJob = null
        // Stop the engine before releasing acquisition routing: the engine tears down the
        // stream it owns in its own flow `finally`, and release() must never delete a live
        // capture. After hand-off release() only undoes the acquired routing.
        if (engineStart.isStarted) {
            voiceAsrEngine.get().stop()
            clearCaptureState()
            Timber.i("[VoicePipeline] engine stopped")
        } else {
            routeAcquisition.release()
        }
    }

    private fun handleIntent(
        intent: au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent,
        invocationToken: au.com.shiftyjelly.pocketcasts.voicecontrol.engine.VoiceAsrEngine.InvocationToken?,
    ) {
        // THE OBSERVABLE SEAM: the dispatch lifecycle (debounce settle, invocation-local fact
        // channel, all-exit one-shot settlement) lives in VoiceIntentDispatcher so REAL controls
        // can drive it — overlap→persist, pre-body cancellation, handler throw (spec 250ddcc3).
        intentDispatcher.dispatch(
            scope = serviceScope,
            engine = voiceAsrEngine.get(),
            executor = voicePlaybackIntentExecutor,
            render = { _, response -> audioFeedbackRenderer.render(response) },
            onCommandRecognized = { recognized ->
                gracePeriodSignal.onCommandRecognized(
                    fromGeneration = recognized.windowGenerationOf(),
                    restoresAllowance = recognized.restoresAllowance(),
                )
            },
            intent = intent,
            invocationToken = invocationToken,
        )
    }

    private fun updateListeningState() {
        val session = mediaSession ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return

        when (currentMode) {
            ListeningMode.Continuous -> {
                session.isActive = true
                session.setPlaybackState(
                    PlaybackState.Builder()
                        .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                        .build(),
                )
            }

            ListeningMode.WakeWord -> {
                session.isActive = true
                session.setPlaybackState(
                    PlaybackState.Builder()
                        .setState(PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0.0f)
                        .build(),
                )
            }

            ListeningMode.Off -> {
                session.isActive = false
            }
        }
    }

    /**
     * Stops the pipeline and returns a job that completes when teardown has REALLY finished
     * (reviewer a85cf8fe). The previous version returned an already-completed job, which told an
     * awaiting caller that children had settled when they had not. This awaits the cancelled
     * children — `modeJob`, the acquisition job and the in-flight start — under `NonCancellable`, so
     * the returned job is a truthful completion signal. `serviceScope` is cancelled first so nothing
     * can be restarted behind the join.
     */
    private fun stopVoiceControl(): Job {
        // Idempotent for BOTH cases: a running teardown is joined, and a COMPLETED teardown keeps its
        // identity, so a later stop cannot release resources a second time.
        stopInFlightJob?.let { return it }
        Timber.i("Stopping voice control service")
        val rootJob = serviceScope.coroutineContext[Job]

        // CANCEL, STOP, JOIN, THEN RELEASE (reviewers 0bba7db5 + dbde4427).
        // A cancellation REQUEST is not "the users have finished": dispatcher children call
        // render()/onCommandRecognized and can still be inside them. Releasing the renderer,
        // recognizer, session or backend before those children have actually finished is
        // use-after-release, so the releases happen only AFTER the join below.
        modeJob?.cancel()
        // Engine + capture teardown runs after the cancellation request. It stops the engine's
        // capture synchronously, but the engine's PROCESSING job lives on the engine's own
        // SupervisorJob+IO scope and is only CANCELLED here, not joined (reviewers 267001c1). That
        // job is captured below and awaited alongside the service children before any release.
        stopEngine()
        val engineProcessingJobs = voiceAsrEngine.get().unfinishedProcessingJobs
        serviceScope.cancel()
        stopSelf()

        // The completion authority, dispatched OUTSIDE the cancelled scope. It joins the root (so
        // every owned child has really finished) and THEN releases the resources those children
        // were using. Its completion therefore means: users finished AND resources released.
        return CoroutineScope(Dispatchers.IO).launch {
            withContext(NonCancellable) {
                runCatching { rootJob?.join() }
                    .onFailure { Timber.w(it, "[VoicePipeline] teardown join failed") }
                // The engine's processing job is NOT a child of the service scope, so it needs its
                // own join: a noncooperative ASR/native call or its finally must finish before we
                // release the backend it may still be using.
                // EVERY unfinished engine job, not just the newest: a stop/start/stop sequence can
                // leave an older native call running (reviewer 26883305).
                engineProcessingJobs.forEach { job ->
                    runCatching { job.join() }
                        .onFailure { Timber.w(it, "[VoicePipeline] engine processing join failed") }
                }
                // The releases themselves run on MAIN: the media session and the feedback renderer
                // own Main-confined state (the renderer's scope is Dispatchers.Main.immediate), so
                // touching them from this IO job would be an off-thread release.
                withContext(Dispatchers.Main) {
                    wiredBackend?.release()
                    wiredBackend = null
                    mediaSession?.isActive = false
                    mediaSession?.release()
                    mediaSession = null
                    voiceRecognizer.release()
                    audioFeedbackRenderer.release()
                    notificationManager.cancelNotification()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
        }.also { stopInFlightJob = it }
    }

    private fun hasRequiredPermissions(): Boolean {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVoiceControl()
        // The controller keeps its own record of whether this service is running; without this it
        // would believe a service that the system killed is still alive and never start it again.
        voiceControlServiceController.onServiceStopped()
    }
}
