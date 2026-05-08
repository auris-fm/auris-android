package au.com.shiftyjelly.pocketcasts.voice.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.IBinder
import au.com.shiftyjelly.pocketcasts.voice.audio.VoiceAudioProcessor
import au.com.shiftyjelly.pocketcasts.voice.audio.VoiceSegmenterResult
import au.com.shiftyjelly.pocketcasts.voice.gate.VoiceControlGate
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class VoiceControlService : Service() {
    @Inject lateinit var gate: VoiceControlGate
    @Inject lateinit var voiceAudioProcessor: VoiceAudioProcessor
    @Inject lateinit var notificationManager: VoiceControlNotificationManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var audioManager: AudioManager? = null

    companion object {
        const val ACTION_STOP = "au.com.shiftyjelly.pocketcasts.voice.action.STOP"
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

    private fun handleSegmenterResult(result: VoiceSegmenterResult) {
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
            VoiceSegmenterResult.Silence -> {
                // Silence detected
            }
        }
    }

    private fun requestAudioFocus() {
        @Suppress("DEPRECATION")
        val result = audioManager?.requestAudioFocus(
            { focusChange ->
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
            },
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
        )

        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Timber.w("Audio focus request failed")
        }
    }

    private fun abandonAudioFocus() {
        @Suppress("DEPRECATION")
        val result = audioManager?.abandonAudioFocus { }
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Timber.i("Audio focus abandoned")
        }
    }

    private fun processVoiceSegment(frames: List<au.com.shiftyjelly.pocketcasts.voice.audio.PcmAudioFrame>) {
        Timber.i("Processing voice segment with ${frames.size} frames")
        // TODO: Integrate with voice recognition and intent interpretation
        // This is where the actual voice command processing will happen
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
