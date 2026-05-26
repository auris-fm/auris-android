package au.com.shiftyjelly.pocketcasts.voicecontrol.model

import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoicePlaybackIntent

interface VoiceRecognizer {
    suspend fun ensureReady(): Result<Unit>

    suspend fun recognize(
        clip: VoiceUtteranceClip,
        context: VoiceRecognitionContext,
    ): VoicePlaybackIntent?
}

class NoOpVoiceRecognizer @javax.inject.Inject constructor() : VoiceRecognizer {
    override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)
    override suspend fun recognize(clip: VoiceUtteranceClip, context: VoiceRecognitionContext): VoicePlaybackIntent? = null
}
