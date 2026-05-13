package au.com.shiftyjelly.pocketcasts.voice.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import au.com.shiftyjelly.pocketcasts.voice.BuildConfig
import au.com.shiftyjelly.pocketcasts.voice.audio.MicrophoneCapture
import au.com.shiftyjelly.pocketcasts.voice.audio.PcmAudioFrame
import au.com.shiftyjelly.pocketcasts.voice.audio.VoiceAudioSegmenter
import au.com.shiftyjelly.pocketcasts.voice.audio.VoiceClipSaver
import au.com.shiftyjelly.pocketcasts.voice.audio.VoiceSegmenterResult
import au.com.shiftyjelly.pocketcasts.voice.gate.VoiceControlGate
import au.com.shiftyjelly.pocketcasts.voice.gate.VoiceControlGateState
import au.com.shiftyjelly.pocketcasts.voice.model.SpeakerEmbedder
import au.com.shiftyjelly.pocketcasts.voice.model.VoiceEnrollmentManager
import au.com.shiftyjelly.pocketcasts.voice.model.VoiceEnrollmentState
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
import kotlinx.coroutines.isActive
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

    @Inject lateinit var segmenter: VoiceAudioSegmenter

    @Inject lateinit var voiceRecognizer: VoiceRecognizer

    @Inject lateinit var voicePlaybackIntentExecutor: VoicePlaybackIntentExecutor

    @Inject lateinit var playbackContextMonitor: PlaybackContextMonitor

    @Inject lateinit var audioRouteMonitor: AndroidAudioRouteMonitor

    @Inject lateinit var enrollmentManager: VoiceEnrollmentManager

    @Inject lateinit var embedder: SpeakerEmbedder

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var captureJob: Job? = null
    private var speechFrames = mutableListOf<PcmAudioFrame>()
    private var lastIntentType: String? = null
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

        // Mandatory enrollment check
        if (enrollmentManager.state.value !is VoiceEnrollmentState.Enrolled) {
            Timber.w("Speaker not enrolled, showing enrollment notification")
            val notification = notificationManager.createEnrollmentRequiredNotification()
            startForeground(notificationManager.notificationId, notification)
            stopSelf()
            return
        }

        // Load speaker embedding model
        if (!embedder.load()) {
            Timber.e("Failed to load speaker embedding model, stopping")
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

        serviceScope.launch(Dispatchers.IO) {
            voiceRecognizer.ensureReady().fold(
                onSuccess = {
                    launch(Dispatchers.Main) { startAudioCapture() }
                },
                onFailure = { e ->
                    Timber.e(e, "Recognizer not ready, stopping")
                    launch(Dispatchers.Main) { stopSelf() }
                },
            )
        }
    }

    private fun startAudioCapture() {
        captureJob?.cancel()
        captureJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                microphoneCapture.startCapture()
                    .catch { e -> Timber.e(e, "Capture stream ended, restarting") }
                    .collect { frame -> processAudioFrame(frame) }
                Timber.i("Capture stream completed, restarting")
                kotlinx.coroutines.delay(100)
            }
        }
    }

    private suspend fun processAudioFrame(frame: PcmAudioFrame) {
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
                speechFrames.clear()
                Timber.i("Speech ended: ${result.frames.size} frames")
                processUtterance(VoiceUtteranceClip.fromFrames(result.frames))
            }

            is VoiceSegmenterResult.Rejected -> {
                Timber.w("Segment rejected: ${result.reason}")
                speechFrames.clear()
            }

            VoiceSegmenterResult.Silence -> { /* continue */ }
        }
    }

    private suspend fun processUtterance(clip: VoiceUtteranceClip) {
        // Speaker verification gate
        if (!enrollmentManager.verify(clip)) {
            Timber.d("Speaker verification failed, discarding utterance")
            return
        }

        val recognitionContext = VoiceRecognitionContext(
            playbackContext = playbackContextMonitor.context.value,
            audioRoute = audioRouteMonitor.route.value,
        )

        val intent = voiceRecognizer.recognize(clip, recognitionContext)
        if (intent != null) {
            handleIntent(clip, intent)
        }
    }

    private suspend fun handleIntent(
        clip: VoiceUtteranceClip,
        intent: au.com.shiftyjelly.pocketcasts.voice.intent.VoicePlaybackIntent,
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

        if (BuildConfig.DEBUG) {
            VoiceClipSaver.save(clip, intent.toString())
        }

        voicePlaybackIntentExecutor.execute(intent)
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
