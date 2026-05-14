package au.com.shiftyjelly.pocketcasts.voicecontrol.model

import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.WhisperRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.SmolLmIntentParser
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoicePlaybackIntent
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class CascadedVoiceRecognizer @Inject constructor(
    private val whisperRecognizer: WhisperRecognizer,
    private val intentParser: SmolLmIntentParser,
) : VoiceRecognizer {

    override suspend fun ensureReady(): Result<Unit> {
        val whisperReady = whisperRecognizer.isModelReady()
        val lmReady = intentParser.isModelReady()
        return if (whisperReady && lmReady) {
            Result.success(Unit)
        } else {
            val missing = buildString {
                if (!whisperReady) append("whisper ")
                if (!lmReady) append("smol-lm")
            }
            Result.failure(Exception("Models not ready: $missing"))
        }
    }

    override suspend fun recognize(
        clip: VoiceUtteranceClip,
        context: VoiceRecognitionContext,
    ): VoicePlaybackIntent? {
        val transcript = whisperRecognizer.transcribe(clip)
        if (transcript.isBlank()) {
            Timber.w("Whisper: empty transcript, skipping intent parsing")
            return null
        }
        return intentParser.parseIntent(transcript, context)
    }
}
