package au.com.shiftyjelly.pocketcasts.voice.intent

data class VoiceRecognitionResult(
    val transcript: String,
    val confidence: Float,
)
