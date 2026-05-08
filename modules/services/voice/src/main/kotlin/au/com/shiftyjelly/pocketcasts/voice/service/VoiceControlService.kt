package au.com.shiftyjelly.pocketcasts.voice.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.IBinder
import au.com.shiftyjelly.pocketcasts.voice.audio.RejectionReason
import au.com.shiftyjelly.pocketcasts.voice.audio.VoiceAudioProcessor
import au.com.shiftyjelly.pocketcasts.voice.audio.VoiceSegmenterResult
import au.com.shiftyjelly.pocketcasts.voice.gate.VoiceControlGate
import au.com.shiftyjelly.pocketcasts.voice.gate.VoiceControlGateState
import au.com.shiftyjelly.pocketcasts.voice.intent.VoiceIntentInterpreter
import au.com.shiftyjelly.pocketcasts.voice.intent.VoicePlaybackIntent
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeout
import timber.log.Timber

@AndroidEntryPoint
class VoiceControlService : Service() {
    @Inject lateinit var gate: VoiceControlGate

    @Inject lateinit var voiceAudioProcessor: VoiceAudioProcessor

    @Inject lateinit var notificationManager: VoiceControlNotificationManager

    @Inject lateinit var voiceRecognizer: VoiceRecognizer

    @Inject lateinit var voiceIntentInterpreter: VoiceIntentInterpreter

    @Inject lateinit var voicePlaybackIntentExecutor: VoicePlaybackIntentExecutor

    @Inject lateinit var playbackContextMonitor: PlaybackContextMonitor

    @Inject lateinit var audioRouteMonitor: AndroidAudioRouteMonitor

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    // Command deduplication
    private var lastProcessedCommand: String? = null
    private var lastCommandTimeMs: Long = 0L

    companion object {
        const val ACTION_STOP = "au.com.shiftyjelly.pocketcasts.voice.action.STOP"
        private const val COMMAND_DEBOUNCE_MS = 2000L // 2 seconds between identical commands
        private const val RECOGNITION_TIMEOUT_MS = 5000L // 5 seconds timeout for voice recognition
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopVoiceControl()
            else -> startVoiceControl()
        }
        return START_STICKY
    }

    private fun startVoiceControl() {
        Timber.i("Voice control service started")

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val notification = notificationManager.createListeningNotification()
        startForeground(notificationManager.notificationId, notification)

        // Observe gate state and stop if blocked
        gate.state.onEach { state ->
            if (state is VoiceControlGateState.Blocked) {
                Timber.w("Voice control gate blocked: ${state.rules}")
                stopVoiceControl()
            }
        }.launchIn(serviceScope)

        // Observe audio route changes and stop if route becomes invalid
        audioRouteMonitor.route.onEach { route ->
            Timber.i("Audio route changed to: $route")
            if (!isValidAudioRoute(route)) {
                Timber.w("Audio route became invalid: $route")
                stopVoiceControl()
            }
        }.launchIn(serviceScope)

        voiceAudioProcessor.startProcessing()
            .onEach { result ->
                handleSegmenterResult(result)
            }
            .catch { error ->
                Timber.e(error, "Error processing voice audio")
                stopVoiceControl()
            }
            .launchIn(serviceScope)
    }

    private suspend fun handleSegmenterResult(result: VoiceSegmenterResult) {
        when (result) {
            is VoiceSegmenterResult.SpeechStarted -> {
                Timber.i("Speech started - voice command detected")
                requestAudioFocus()
            }

            is VoiceSegmenterResult.SpeechEnded -> {
                Timber.i("Speech ended - processing ${result.frames.size} audio frames")
                processVoiceSegment(result.frames)
            }

            is VoiceSegmenterResult.SpeechContinuing -> {
                // Continue processing speech
            }

            is VoiceSegmenterResult.Rejected -> {
                Timber.w("Speech segment rejected: ${result.reason}")
                when (result.reason) {
                    RejectionReason.TooShort -> {
                        Timber.i("Speech was too short - ignoring")
                    }

                    RejectionReason.LowConfidence -> {
                        Timber.i("Speech confidence too low - ignoring")
                    }

                    RejectionReason.Timeout -> {
                        Timber.w("Speech timeout - segmenter stuck in speech, resetting")
                    }

                    RejectionReason.InvalidRoute -> {
                        Timber.w("Invalid audio route during capture")
                        stopVoiceControl()
                    }
                }
            }

            VoiceSegmenterResult.Silence -> {
                // Silence detected
            }
        }
    }

    private fun requestAudioFocus() {
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .build()

        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(audioAttributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        Timber.i("Audio focus gained")
                    }

                    AudioManager.AUDIOFOCUS_LOSS -> {
                        Timber.i("Audio focus lost")
                        stopVoiceControl()
                    }

                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        Timber.i("Audio focus lost transient")
                    }
                }
            }
            .build()

        val result = audioManager?.requestAudioFocus(audioFocusRequest!!)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Timber.w("Audio focus request failed")
        }
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let {
            val result = audioManager?.abandonAudioFocusRequest(it)
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Timber.i("Audio focus abandoned")
            }
            audioFocusRequest = null
        }
    }

    private suspend fun processVoiceSegment(frames: List<au.com.shiftyjelly.pocketcasts.voice.audio.PcmAudioFrame>) {
        Timber.i("Processing voice segment with ${frames.size} frames")

        // Create utterance clip
        val clip = VoiceUtteranceClip.fromFrames(frames)
        Timber.i("Created utterance clip: duration=${clip.durationMs}ms, confidence=${clip.confidenceScore}")

        // Create recognition context
        val context = VoiceRecognitionContext(
            playbackContext = playbackContextMonitor.context.value,
            audioRoute = audioRouteMonitor.route.value,
        )
        Timber.i("Recognition context: playback=$context, route=${context.audioRoute}")

        // Perform voice recognition with timeout
        try {
            val recognitionResult = withTimeout(RECOGNITION_TIMEOUT_MS) {
                voiceRecognizer.recognize(clip, context)
            }

            if (recognitionResult != null) {
                Timber.i("Voice recognition result: transcript='${recognitionResult.transcript}', confidence=${recognitionResult.confidence}")
                handleRecognitionResult(recognitionResult)
            } else {
                Timber.w("Voice recognition returned null result")
            }
        } catch (e: Exception) {
            when (e) {
                is kotlinx.coroutines.TimeoutCancellationException -> {
                    Timber.w("Voice recognition timeout after ${RECOGNITION_TIMEOUT_MS}ms")
                }

                else -> {
                    Timber.e(e, "Error during voice recognition")
                }
            }
        }
    }

    private suspend fun handleRecognitionResult(result: au.com.shiftyjelly.pocketcasts.voice.intent.VoiceRecognitionResult) {
        // Check for command deduplication
        val currentTime = System.currentTimeMillis()
        if (result.transcript == lastProcessedCommand &&
            (currentTime - lastCommandTimeMs) < COMMAND_DEBOUNCE_MS
        ) {
            Timber.i("Ignoring duplicate command: '${result.transcript}'")
            return
        }

        lastProcessedCommand = result.transcript
        lastCommandTimeMs = currentTime

        // Interpret voice intent
        val intent = voiceIntentInterpreter.interpret(result)
        if (intent != null) {
            Timber.i("Interpreted voice intent: $intent")
            executeVoiceIntent(intent)
        } else {
            Timber.w("Could not interpret voice intent from transcript: '${result.transcript}'")
        }
    }

    private suspend fun executeVoiceIntent(intent: VoicePlaybackIntent) {
        try {
            voicePlaybackIntentExecutor.execute(intent)
            Timber.i("Executed voice intent: $intent")
        } catch (e: Exception) {
            Timber.e(e, "Error executing voice intent: $intent")
        }
    }

    private fun isValidAudioRoute(route: au.com.shiftyjelly.pocketcasts.voice.route.AudioRoute): Boolean {
        return when (route) {
            is au.com.shiftyjelly.pocketcasts.voice.route.AudioRoute.Headset -> true

            is au.com.shiftyjelly.pocketcasts.voice.route.AudioRoute.Speaker -> true

            is au.com.shiftyjelly.pocketcasts.voice.route.AudioRoute.BluetoothA2dpOnly -> false

            // No microphone
            is au.com.shiftyjelly.pocketcasts.voice.route.AudioRoute.Unknown -> false
        }
    }

    private fun stopVoiceControl() {
        Timber.i("Stopping voice control service")
        voiceAudioProcessor.stopProcessing()
        abandonAudioFocus()
        @Suppress("DEPRECATION")
        stopForeground(true)
        notificationManager.cancelNotification()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.i("Voice control service destroyed")
        voiceAudioProcessor.stopProcessing()
        abandonAudioFocus()
        serviceScope.cancel()
    }
}
