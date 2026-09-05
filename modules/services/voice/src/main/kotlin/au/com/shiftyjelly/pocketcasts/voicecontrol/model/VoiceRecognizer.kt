package au.com.shiftyjelly.pocketcasts.voicecontrol.model

import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm.RouterStageDiagnostic

data class VoiceRecognizeResult(
    val intent: VoiceIntent?,
    val diagnostic: RouterStageDiagnostic? = null,
)

interface VoiceRecognizer {
    suspend fun ensureReady(): Result<Unit>

    suspend fun recognize(
        input: IntentRoutingInput,
        context: VoiceRecognitionContext,
    ): VoiceRecognizeResult

    fun release()
}

class NoOpVoiceRecognizer @javax.inject.Inject constructor() : VoiceRecognizer {
    override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)
    override suspend fun recognize(
        input: IntentRoutingInput,
        context: VoiceRecognitionContext,
    ): VoiceRecognizeResult = VoiceRecognizeResult(intent = null)
    override fun release() = Unit
}
