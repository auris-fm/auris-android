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

    /**
     * P1 (reviewer 7033f4d9): the verified loaded model identity — established by THIS
     * implementation's own successful loader — or null when routing has not run with an
     * established artifact. Loading alone is not routing; callers attach the ref only at the
     * actual invocation.
     */
    fun loadedModelRef(): au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef? = null
}

class NoOpVoiceRecognizer @javax.inject.Inject constructor() : VoiceRecognizer {
    override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)
    override suspend fun recognize(
        input: IntentRoutingInput,
        context: VoiceRecognitionContext,
    ): VoiceRecognizeResult = VoiceRecognizeResult(intent = null)
    override fun release() = Unit
}
