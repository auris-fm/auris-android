package au.com.shiftyjelly.pocketcasts.voicecontrol.intent

import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognitionContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

@Singleton
class FunctionGemmaIntentRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mapper: ToolCallMapper,
) : VoiceRecognizer {

    @Volatile
    private var initialized = false

    override suspend fun ensureReady(): Result<Unit> {
        if (initialized) return Result.success(Unit)
        return withContext(Dispatchers.IO) {
            try {
                val modelFile = File(context.filesDir, "functiongemma-model/model.litertlm")
                if (!modelFile.exists()) {
                    return@withContext Result.failure(IllegalStateException("FunctionGemma model not found at ${modelFile.absolutePath}"))
                }
                // LiteRT-LM model loading will be wired in when the dependency is added
                initialized = true
                Timber.i("FunctionGemmaIntentRouter initialized")
                Result.success(Unit)
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize FunctionGemmaIntentRouter")
                Result.failure(e)
            }
        }
    }

    override suspend fun recognize(
        transcript: String,
        context: VoiceRecognitionContext,
    ): VoiceIntent? = withContext(Dispatchers.IO) {
        if (!initialized || transcript.isBlank()) return@withContext null

        // TODO: Replace with actual LiteRT-LM inference when dependency is wired
        // val prompt = buildPrompt(transcript, ToolSchema.json)
        // val response = model.generate(prompt, maxTokens = 128)
        // val toolCall = ToolCall.parse(response) ?: return@withContext null
        // mapper.map(toolCall)
        null
    }
}

private typealias Context = android.content.Context
