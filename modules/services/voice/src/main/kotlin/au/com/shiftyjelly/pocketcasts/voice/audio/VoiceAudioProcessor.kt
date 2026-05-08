package au.com.shiftyjelly.pocketcasts.voice.audio

import au.com.shiftyjelly.pocketcasts.voice.audio.MicrophoneCapture
import au.com.shiftyjelly.pocketcasts.voice.audio.PcmAudioFrame
import au.com.shiftyjelly.pocketcasts.voice.audio.VoiceAudioSegmenter
import au.com.shiftyjelly.pocketcasts.voice.audio.VoiceSegmenterResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceAudioProcessor @Inject constructor(
    private val microphoneCapture: MicrophoneCapture,
    private val voiceSegmenter: VoiceAudioSegmenter,
) {
    /**
     * Start processing audio from the microphone and emit voice segmenter results.
     *
     * @return Flow of VoiceSegmenterResult objects indicating speech activity
     */
    fun startProcessing(): Flow<VoiceSegmenterResult> {
        return microphoneCapture.startCapture()
            .map { frame ->
                val result = voiceSegmenter.process(frame)
                when (result) {
                    is VoiceSegmenterResult.SpeechStarted -> {
                        Timber.i("Speech started detected")
                    }
                    is VoiceSegmenterResult.SpeechContinuing -> {
                        // Continue processing speech
                    }
                    is VoiceSegmenterResult.SpeechEnded -> {
                        Timber.i("Speech ended detected with ${result.frames.size} frames")
                    }
                    VoiceSegmenterResult.Silence -> {
                        // Silence detected
                    }
                }
                result
            }
    }

    /**
     * Stop audio processing and release microphone resources.
     */
    fun stopProcessing() {
        microphoneCapture.stopCapture()
        Timber.i("Voice audio processing stopped")
    }

    /**
     * Check if audio processing is currently active.
     */
    val isProcessing: Boolean
        get() = microphoneCapture.isRecording
}
