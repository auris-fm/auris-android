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
    private val modelManager: ModelManager,
) : VoiceRecognizer {

    override suspend fun ensureReady(): Result<Unit> {
        modelManager.ensureModels().fold(
            onSuccess = {
                val whisperReady = whisperRecognizer.isModelReady()
                val lmReady = intentParser.isModelReady()
                if (whisperReady && lmReady) {
                    return Result.success(Unit)
                }
                val missing = buildString {
                    if (!whisperReady) append("whisper ")
                    if (!lmReady) append("smol-lm")
                }
                return Result.failure(Exception("Models not ready: $missing"))
            },
            onFailure = { e ->
                return Result.failure(e)
            },
        )
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
