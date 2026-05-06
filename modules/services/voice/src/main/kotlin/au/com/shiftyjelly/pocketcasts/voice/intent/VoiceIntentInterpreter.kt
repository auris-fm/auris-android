package au.com.shiftyjelly.pocketcasts.voice.intent

interface VoiceIntentInterpreter {
    suspend fun interpret(result: VoiceRecognitionResult): VoicePlaybackIntent?
}
