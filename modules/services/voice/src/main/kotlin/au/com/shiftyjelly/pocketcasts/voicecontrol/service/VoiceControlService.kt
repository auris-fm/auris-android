package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import au.com.shiftyjelly.pocketcasts.voicecontrol.BuildConfig
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.MicrophoneCapture
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.PcmAudioFrame
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceAudioSegmenter
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceClipSaver
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceSegmenterResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGateState
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.SpeakerEmbedder
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceEnrollmentManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceEnrollmentState
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognitionContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceUtteranceClip
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContextMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackIntentExecutor
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AndroidAudioRouteMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.ui.EnrollmentActivity
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
import kotlinx.coroutines.isActive
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
        if (intent?.action == "au.com.shiftyjelly.pocketcasts.voicecontrol.action.STOP") {
            stopVoiceControl()
            return START_NOT_STICKY
        }
        startVoiceControl()
        return START_NOT_STICKY
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
            Timber.w("Speaker not enrolled, launching enrollment activity")
            val notification = notificationManager.createEnrollmentRequiredNotification()
            startForeground(notificationManager.notificationId, notification)
            val enrollIntent = Intent(this, EnrollmentActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(enrollIntent)
            stopSelf()
            return
        }

        // Load speaker embedding model
        if (!embedder.load()) {
            Timber.e("Failed to load speaker embedding model, stopping")
            stopSelf()
            return
        }

        // Show a "downloading" notification until models are ready
        val downloadingNotification = notificationManager.createDownloadingNotification()
        startForeground(notificationManager.notificationId, downloadingNotification)

        gate.state.onEach { state ->
            if (state is VoiceControlGateState.Blocked) {
                Timber.w("Gate blocked: ${state.rules}")
                stopVoiceControl()
            }
        }.launchIn(serviceScope)

        serviceScope.launch(Dispatchers.IO) {
            Timber.i("Voice recognizer ensureReady starting")
            voiceRecognizer.ensureReady().fold(
                onSuccess = {
                    Timber.i("Voice recognizer ready, starting audio capture")
                    launch(Dispatchers.Main) {
                        val notification = notificationManager.createListeningNotification()
                        notificationManager.notify(notification)
                        startAudioCapture()
                    }
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
        val tStart = System.currentTimeMillis()

        // Speaker verification gate
        val tv0 = System.currentTimeMillis()
        val verified = enrollmentManager.verify(clip)
        val tv1 = System.currentTimeMillis()
        if (!verified) {
            Timber.i("Speaker verification failed (%dms)", tv1 - tv0)
            return
        }
        Timber.i("Speaker verification passed (%dms)", tv1 - tv0)

        val recognitionContext = VoiceRecognitionContext(
            playbackContext = playbackContextMonitor.context.value,
            audioRoute = audioRouteMonitor.route.value,
        )

        val intent = voiceRecognizer.recognize(clip, recognitionContext)
        val tEnd = System.currentTimeMillis()
        Timber.i("Utterance total: %dms, intent=%s", tEnd - tStart, intent)
        if (intent != null) {
            handleIntent(clip, intent)
        }
    }

    private suspend fun handleIntent(
        clip: VoiceUtteranceClip,
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
