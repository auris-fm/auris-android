package au.com.shiftyjelly.pocketcasts.voice.model

import au.com.shiftyjelly.pocketcasts.voice.intent.VoicePlaybackIntent

interface VoiceRecognizer {
    suspend fun ensureReady(): Result<Unit>

    /**
     * Process an audio utterance and return a validated [VoicePlaybackIntent] or null.
     *
     * Gemma 4 E2B performs ASR and intent interpretation in a single model pass,
     * returning structured JSON that is parsed into a [VoicePlaybackIntent].
     */
    suspend fun recognize(
        clip: VoiceUtteranceClip,
        context: VoiceRecognitionContext,
    ): VoicePlaybackIntent?
}
