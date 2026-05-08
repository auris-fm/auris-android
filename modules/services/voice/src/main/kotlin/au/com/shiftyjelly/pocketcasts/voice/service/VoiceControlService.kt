package au.com.shiftyjelly.pocketcasts.voice.service

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import au.com.shiftyjelly.pocketcasts.voice.gate.VoiceControlGate
import au.com.shiftyjelly.pocketcasts.voice.gate.VoiceControlGateState
import au.com.shiftyjelly.pocketcasts.voice.intent.VoiceIntentInterpreter
import au.com.shiftyjelly.pocketcasts.voice.intent.VoiceRecognitionResult
import au.com.shiftyjelly.pocketcasts.voice.playback.VoicePlaybackIntentExecutor
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import timber.log.Timber

@AndroidEntryPoint
class VoiceControlService : Service() {
    @Inject lateinit var gate: VoiceControlGate

    @Inject lateinit var notificationManager: VoiceControlNotificationManager

    @Inject lateinit var voiceIntentInterpreter: VoiceIntentInterpreter

    @Inject lateinit var voicePlaybackIntentExecutor: VoicePlaybackIntentExecutor

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var speechRecognizer: SpeechRecognizer? = null
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
            Timber.w("Missing required permissions, stopping voice control")
            stopSelf()
            return
        }

        val notification = notificationManager.createListeningNotification()
        startForeground(notificationManager.notificationId, notification)

        gate.state.onEach { state ->
            if (state is VoiceControlGateState.Blocked) {
                Timber.w("Voice control gate blocked: ${state.rules}")
                stopVoiceControl()
            }
        }.launchIn(serviceScope)

        startSpeechRecognition()
    }

    private fun startSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Timber.w("Speech recognition not available on this device")
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Timber.i("Speech recognition ready - listening for commands")
                }

                override fun onBeginningOfSpeech() {
                    Timber.i("Speech detected")
                }

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    Timber.i("Speech ended - processing command")
                }

                override fun onError(error: Int) {
                    Timber.w("Speech error: $error")
                    restartRecognition()
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                    val transcript = matches?.firstOrNull() ?: ""
                    val confidence = scores?.firstOrNull() ?: 0f
                    handleRecognition(VoiceRecognitionResult(transcript, confidence))
                    restartRecognition()
                }

                override fun onPartialResults(partialResults: Bundle?) {}

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            recognizer.startListening(intent)
        }
    }

    private fun restartRecognition() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(500) }
        startSpeechRecognition()
    }

    private fun handleRecognition(result: VoiceRecognitionResult) {
        val now = System.currentTimeMillis()
        if (result.transcript == lastCommand && (now - lastCommandTime) < COMMAND_DEBOUNCE_MS) {
            Timber.i("Debouncing duplicate: '${result.transcript}'")
            return
        }
        lastCommand = result.transcript
        lastCommandTime = now

        Timber.i("Recognized: '${result.transcript}' (conf=${result.confidence})")

        val intent = kotlinx.coroutines.runBlocking { voiceIntentInterpreter.interpret(result) }
        if (intent != null) {
            Timber.i("Executing: $intent")
            kotlinx.coroutines.runBlocking { voicePlaybackIntentExecutor.execute(intent) }
        } else {
            Timber.w("No intent for: '${result.transcript}'")
        }
    }

    private fun stopVoiceControl() {
        Timber.i("Stopping voice control service")
        speechRecognizer?.destroy()
        speechRecognizer = null
        notificationManager.cancelNotification()
        stopForeground(STOP_FOREGROUND_REMOVE)
        serviceScope.cancel()
        stopSelf()
    }

    private fun hasRequiredPermissions(): Boolean {
        return hasPermission(android.Manifest.permission.RECORD_AUDIO) &&
            hasPermission(android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
    }

    private fun hasPermission(permission: String): Boolean {
        return checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVoiceControl()
    }
}
