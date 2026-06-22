package au.com.shiftyjelly.pocketcasts.voicecontrol.intent

import au.com.shiftyjelly.pocketcasts.voicecontrol.dialog.VoiceDialogManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.ModelManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognitionContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Session
import com.google.ai.edge.litertlm.SessionConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

@Singleton
class FunctionGemmaIntentRouter @Inject constructor(
    private val dialogManager: VoiceDialogManager,
    private val modelManager: ModelManager,
) : VoiceRecognizer {

    @Volatile
    private var engine: Engine? = null

    override suspend fun ensureReady(): Result<Unit> {
        if (engine != null) return Result.success(Unit)
        return withContext(Dispatchers.IO) {
            try {
                val modelFile = modelManager.functionGemmaModelFile
                if (!modelFile.exists()) {
                    return@withContext Result.failure(
                        IllegalStateException("FunctionGemma model not found at ${modelFile.absolutePath}"),
                    )
                }
                val config = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.CPU(),
                    maxNumTokens = 2048,
                    cacheDir = modelManager.functionGemmaDir.absolutePath,
                )
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
            val startMs = System.currentTimeMillis()
            val prompt = buildPrompt(transcript)

            Timber.i(
                "FunctionGemma inference start (transcript='%s', promptLen=%d)",
                transcript.take(200),
                prompt.length,
            )

            @Suppress("DEPRECATION")
            val generated: String
            val session = engine.createSession(SessionConfig())
            val prefillMs = System.currentTimeMillis() - startMs
            session.use {
                session.runPrefill(listOf(InputData.Text(prompt)))
                generated = session.runDecode().trim { it <= ' ' }
            }
            val decodeMs = System.currentTimeMillis() - startMs - prefillMs
            val totalMs = System.currentTimeMillis() - startMs

            Timber.i(
                "FunctionGemma inference done (prefillMs=%d, decodeMs=%d, totalMs=%d, generated='%s')",
                prefillMs,
                decodeMs,
                totalMs,
                generated.take(300),
            )

            val toolCall = ToolCall.parse(generated) ?: run {
                Timber.w("FunctionGemma parse failed (generated='%s')", generated.take(300))
                return@withContext null
            }
            Timber.i(
                "FunctionGemma tool call parsed (name=%s, action=%s, params=%s)",
                toolCall.name,
                toolCall.action,
                toolCall.params,
            )
            dialogManager.resolve(toolCall)
        } catch (e: Exception) {
            Timber.e(e, "FunctionGemma inference failed")
            null
        }
    }

    // Matches the eval prompt format exactly:
    //   <start_of_turn>developer
    //   You are a model that can do function calling with the following functions{DECLARATIONS}<end_of_turn>
    //   <start_of_turn>user
    //   {transcript}<end_of_turn>
    //   <start_of_turn>model
    private fun buildPrompt(transcript: String): String {
        return FunctionGemmaPrompt.staticPrefix +
            FunctionGemmaPrompt.requestSuffix(transcript, emptyList())
    }
}
