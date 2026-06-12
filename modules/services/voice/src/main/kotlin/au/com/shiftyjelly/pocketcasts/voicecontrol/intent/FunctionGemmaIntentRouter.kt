package au.com.shiftyjelly.pocketcasts.voicecontrol.intent

import au.com.shiftyjelly.pocketcasts.voicecontrol.dialog.VoiceDialogManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognitionContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.withContext
import timber.log.Timber

@Singleton
class FunctionGemmaIntentRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mapper: ToolCallMapper,
    private val dialogManager: VoiceDialogManager,
) : VoiceRecognizer {

    @Volatile
    private var engine: Engine? = null

    override suspend fun ensureReady(): Result<Unit> {
        if (engine != null) return Result.success(Unit)
        return withContext(Dispatchers.IO) {
            try {
                val modelFile = File(context.filesDir, "functiongemma-model/model.litertlm")
                if (!modelFile.exists()) {
                    return@withContext Result.failure(
                        IllegalStateException("FunctionGemma model not found at ${modelFile.absolutePath}"),
                    )
                }
                val config = EngineConfig(modelPath = modelFile.absolutePath)
                engine = Engine(config).also { it.initialize() }
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
        val engine = engine ?: return@withContext null
        if (transcript.isBlank()) return@withContext null

        try {
            val prompt = buildPrompt(transcript, ToolSchema.json)
            val response = engine.createConversation().use { conversation ->
                conversation.sendMessageAsync(prompt).last()
            }
            val toolCall = ToolCall.parse(response.toString()) ?: return@withContext null
            dialogManager.resolve(toolCall)
        } catch (e: Exception) {
            Timber.e(e, "FunctionGemma inference failed")
            null
        }
    }

    private fun buildPrompt(transcript: String, declarations: String): String {
        return "<bos>\n" +
            "<start_of_turn>developer\n" +
            "You are a model that can do function calling with the following functions\n" +
            declarations +
            "<end_of_turn>\n" +
            "<start_of_turn>user\n" +
            transcript +
            "<end_of_turn>\n" +
            "<start_of_turn>model\n"
    }
}

private typealias Context = android.content.Context
