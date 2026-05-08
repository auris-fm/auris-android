package au.com.shiftyjelly.pocketcasts.voice.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import au.com.shiftyjelly.pocketcasts.voice.audio.EnergyVoiceAudioSegmenter
import au.com.shiftyjelly.pocketcasts.voice.audio.MicrophoneCapture
import au.com.shiftyjelly.pocketcasts.voice.audio.VoiceSegmenterResult
import au.com.shiftyjelly.pocketcasts.voice.gate.VoiceControlGate
import au.com.shiftyjelly.pocketcasts.voice.gate.VoiceControlGateState
import au.com.shiftyjelly.pocketcasts.voice.intent.VoiceIntentInterpreter
import au.com.shiftyjelly.pocketcasts.voice.model.VoiceModelManager
import au.com.shiftyjelly.pocketcasts.voice.model.VoiceRecognitionContext
import au.com.shiftyjelly.pocketcasts.voice.model.VoiceRecognizer
import au.com.shiftyjelly.pocketcasts.voice.model.VoiceUtteranceClip
import au.com.shiftyjelly.pocketcasts.voice.playback.PlaybackContextMonitor
import au.com.shiftyjelly.pocketcasts.voice.playback.VoicePlaybackIntentExecutor
import au.com.shiftyjelly.pocketcasts.voice.route.AndroidAudioRouteMonitor
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class VoiceControlService : Service() {
    @Inject lateinit var gate: VoiceControlGate
    @Inject lateinit var notificationManager: VoiceControlNotificationManager
    @Inject lateinit var microphoneCapture: MicrophoneCapture
    @Inject lateinit var segmenter: EnergyVoiceAudioSegmenter
    @Inject lateinit var voiceRecognizer: VoiceRecognizer
    @Inject lateinit var voiceIntentInterpreter: VoiceIntentInterpreter
    @Inject lateinit var voicePlaybackIntentExecutor: VoicePlaybackIntentExecutor
    @Inject lateinit var playbackContextMonitor: PlaybackContextMonitor
    @Inject lateinit var audioRouteMonitor: AndroidAudioRouteMonitor
    @Inject lateinit var voiceModelManager: VoiceModelManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var captureJob: Job? = null
    private var speechFrames = mutableListOf<au.com.shiftyjelly.pocketcasts.voice.audio.PcmAudioFrame>()
    private var lastCommand: String? = null
    private var lastCommandTime: Long = 0L

    companion object {
        private const val COMMAND_DEBOUNCE_MS = 2000L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "au.com.shiftyjelly.pocketcasts.voice.action.STOP") {
            stopVoiceControl()
            return START_NOT_STICKY
        }
        startVoiceControl()
        return START_STICKY
    }

    private fun startVoiceControl() {
        Timber.i("Voice control service starting")

        if (!hasRequiredPermissions()) {
            Timber.w("Missing permissions, stopping")
            stopSelf()
            return
        }

        val notification = notificationManager.createListeningNotification()
        startForeground(notificationManager.notificationId, notification)

        gate.state.onEach { state ->
            if (state is VoiceControlGateState.Blocked) {
                Timber.w("Gate blocked: ${state.rules}")
                stopVoiceControl()
            }
        }.launchIn(serviceScope)

        // Download model in background, then start capture
        serviceScope.launch(Dispatchers.IO) {
            voiceModelManager.ensureModel().fold(
                onSuccess = {
                    launch(Dispatchers.Main) { startAudioCapture() }
                },
                onFailure = { e ->
                    Timber.e(e, "Model download failed, stopping")
                    launch(Dispatchers.Main) { stopSelf() }
                },
            )
        }
    }

    private fun startAudioCapture() {
        captureJob?.cancel()
        captureJob = serviceScope.launch(Dispatchers.IO) {
            microphoneCapture.startCapture()
                .catch { e -> Timber.e(e, "Capture error") }
                .collect { frame -> processAudioFrame(frame) }
        }
    }

    private suspend fun processAudioFrame(frame: au.com.shiftyjelly.pocketcasts.voice.audio.PcmAudioFrame) {
        when (val result = segmenter.process(frame)) {
            is VoiceSegmenterResult.SpeechStarted -> {
                Timber.i("Speech started")
                speechFrames.clear()
                speechFrames.add(frame)
            }
            is VoiceSegmenterResult.SpeechContinuing -> {
                speechFrames.add(frame)
            }
            is VoiceSegmenterResult.SpeechEnded -> {
                speechFrames.addAll(result.frames)
                Timber.i("Speech ended: ${speechFrames.size} frames")
                val clip = VoiceUtteranceClip.fromFrames(speechFrames.toList())
                speechFrames.clear()
                processUtterance(clip)
            }
            is VoiceSegmenterResult.Rejected -> {
                Timber.w("Segment rejected: ${result.reason}")
                speechFrames.clear()
            }
            VoiceSegmenterResult.Silence -> { /* continue */ }
        }
    }

    private suspend fun processUtterance(clip: VoiceUtteranceClip) {
        val recognitionContext = VoiceRecognitionContext(
            playbackContext = playbackContextMonitor.context.value,
            audioRoute = audioRouteMonitor.route.value,
        )

        val result = voiceRecognizer.recognize(clip, recognitionContext)
        if (result != null) {
            handleCommand(result)
        }
    }

    private suspend fun handleCommand(result: au.com.shiftyjelly.pocketcasts.voice.intent.VoiceRecognitionResult) {
        val now = System.currentTimeMillis()
        if (result.transcript == lastCommand && (now - lastCommandTime) < COMMAND_DEBOUNCE_MS) {
            Timber.i("Debounce: '${result.transcript}'")
            return
        }
        lastCommand = result.transcript
        lastCommandTime = now

        Timber.i("Recognized: '${result.transcript}' (conf=${result.confidence})")

        val intent = voiceIntentInterpreter.interpret(result)
        if (intent != null) {
            Timber.i("Executing: $intent")
            voicePlaybackIntentExecutor.execute(intent)
        } else {
            Timber.w("No intent: '${result.transcript}'")
        }
    }

    private fun stopVoiceControl() {
        Timber.i("Stopping voice control service")
        captureJob?.cancel()
        microphoneCapture.stopCapture()
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
