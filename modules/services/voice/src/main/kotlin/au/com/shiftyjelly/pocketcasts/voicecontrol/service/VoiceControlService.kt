package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import au.com.shiftyjelly.pocketcasts.voicecontrol.BuildConfig
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.MoonshineVoiceEngine
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGateState
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognitionContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContextMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackIntentExecutor
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AndroidAudioRouteMonitor
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class VoiceControlService : Service() {
    @Inject lateinit var gate: VoiceControlGate

    @Inject lateinit var notificationManager: VoiceControlNotificationManager

    @Inject lateinit var voiceRecognizer: VoiceRecognizer

    @Inject lateinit var voicePlaybackIntentExecutor: VoicePlaybackIntentExecutor

    @Inject lateinit var playbackContextMonitor: PlaybackContextMonitor

    @Inject lateinit var audioRouteMonitor: AndroidAudioRouteMonitor

    @Inject lateinit var moonshineEngine: dagger.Lazy<MoonshineVoiceEngine>

    @Inject lateinit var playbackBufferRecorder: PlaybackBufferRecorder

    @Inject lateinit var modelManager: au.com.shiftyjelly.pocketcasts.voicecontrol.model.ModelManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastIntentType: String? = null
    private var lastCommandTime: Long = 0L
    private var engineStarted = false
    private var startInProgress = false
    private var stopping = false

    companion object {
        private const val COMMAND_DEBOUNCE_MS = 2000L
        private const val MOONSHINE_MODEL_ARCH = 2 // Small Streaming
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "au.com.shiftyjelly.pocketcasts.voicecontrol.action.STOP") {
            stopVoiceControl()
            return START_NOT_STICKY
        }
        startVoiceControl()
        return START_STICKY
    }

    private fun startVoiceControl() {
        if (startInProgress || engineStarted) {
            Timber.i("Voice control already started, skipping duplicate start")
            return
        }
        startInProgress = true
        Timber.i("Voice control service starting")

        if (!hasRequiredPermissions()) {
            Timber.w("Missing permissions, stopping")
            stopSelf()
            return
        }

        // On API 35+ FOREGROUND_SERVICE_MICROPHONE requires RECORD_AUDIO granted.
        // If the user hasn't granted it yet, startForeground will throw.
        try {
            val notification = notificationManager.createDownloadingNotification()
            startForeground(notificationManager.notificationId, notification)
        } catch (e: SecurityException) {
            Timber.e(e, "Cannot start foreground — mic permission not granted")
            stopSelf()
            return
        }

        gate.state.onEach { state ->
            if (state is VoiceControlGateState.Blocked) {
                Timber.w("Gate blocked: ${state.rules}")
                stopVoiceControl()
            }
        }.launchIn(serviceScope)

        serviceScope.launch(Dispatchers.IO) {
            voiceRecognizer.ensureReady().fold(
                onSuccess = {
                    Timber.i("Recognizer ready, ensuring Moonshine model")
                    modelManager.ensureMoonshineModel().fold(
                        onSuccess = {
                            Timber.i("Moonshine model ready, ensuring SmolLM model")
                            modelManager.ensureModel().fold(
                                onSuccess = {
                                    Timber.i("SmolLM model ready, starting engine")
                                    try {
                                        val notification = notificationManager.createListeningNotification()
                                        notificationManager.notify(notification)

                                        val modelPath = filesDir.resolve("moonshine-model").absolutePath
                                        moonshineEngine.get().start(
                                            modelPath = modelPath,
                                            modelArch = MOONSHINE_MODEL_ARCH,
                                            audioRoute = audioRouteMonitor.route.value,
                                            playbackBufferProvider = playbackBufferRecorder::snapshot,
                                            contextProvider = {
                                                VoiceRecognitionContext(
                                                    playbackContext = playbackContextMonitor.context.value,
                                                    audioRoute = audioRouteMonitor.route.value,
                                                )
                                            },
                                            onIntent = { intent -> handleIntent(intent) },
                                        )
                                        engineStarted = true
                                    } catch (e: Exception) {
                                        Timber.e(e, "Engine start failed")
                                        launch(Dispatchers.Main) { stopSelf() }
                                    }
                                },
                                onFailure = { e ->
                                    Timber.e(e, "SmolLM model not ready, stopping")
                                    launch(Dispatchers.Main) { stopSelf() }
                                },
                            )
                        },
                        onFailure = { e ->
                            Timber.e(e, "Moonshine model not ready, stopping")
                            launch(Dispatchers.Main) { stopSelf() }
                        },
                    )
                },
                onFailure = { e ->
                    Timber.e(e, "Recognizer not ready, stopping")
                    launch(Dispatchers.Main) { stopSelf() }
                },
            )
        }
    }

    private fun handleIntent(
        intent: au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoicePlaybackIntent,
    ) {
        val now = System.currentTimeMillis()
        val intentType = intent::class.simpleName ?: intent.toString()
        if (intentType == lastIntentType && (now - lastCommandTime) < COMMAND_DEBOUNCE_MS) {
            Timber.i("Debounce: $intentType")
            return
        }
        lastIntentType = intentType
        lastCommandTime = now

        Timber.i("Executing: $intent")
        serviceScope.launch(Dispatchers.IO) { voicePlaybackIntentExecutor.execute(intent) }
    }

    private fun stopVoiceControl() {
        if (stopping) return
        stopping = true
        Timber.i("Stopping voice control service")
        if (engineStarted) moonshineEngine.get().stop()
        engineStarted = false
        startInProgress = false
        notificationManager.cancelNotification()
        stopForeground(STOP_FOREGROUND_REMOVE)
        serviceScope.cancel()
        stopSelf()
    }

    private fun hasRequiredPermissions(): Boolean {
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVoiceControl()
    }
}
