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
        Timber.i("Ensuring voice recognition models are ready")
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
                Timber.w("Models downloaded but still missing: %s", missing)
                return Result.failure(Exception("Models not ready: $missing"))
            },
            onFailure = { e ->
                Timber.e(e, "Model download failed")
                return Result.failure(e)
            },
        )
    }

    override suspend fun recognize(
        clip: VoiceUtteranceClip,
        context: VoiceRecognitionContext,
    ): VoicePlaybackIntent? {
        val t0 = System.currentTimeMillis()
        val transcript = whisperRecognizer.transcribe(clip)
        val t1 = System.currentTimeMillis()
        Timber.i("Whisper transcribe: %dms, text='%s'", t1 - t0, transcript)
        if (transcript.isBlank()) {
            Timber.w("Whisper: empty transcript, skipping intent parsing")
            return null
        }
        val intent = intentParser.parseIntent(transcript, context)
        val t2 = System.currentTimeMillis()
        Timber.i("SmolLM parseIntent: %dms, intent=%s", t2 - t1, intent)
        return intent
    }
}
