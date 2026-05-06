package au.com.shiftyjelly.pocketcasts.voice.intent

class DeterministicVoiceIntentInterpreter @javax.inject.Inject constructor() : VoiceIntentInterpreter {
    override suspend fun interpret(result: VoiceRecognitionResult): VoicePlaybackIntent? {
        if (result.confidence < 0.7f) return null
        val text = result.transcript.lowercase()
        return when {
            text == "pause" || text == "stop" -> VoicePlaybackIntent.Pause
            text == "resume" || text == "play" -> VoicePlaybackIntent.Resume
            text.contains("next chapter") -> VoicePlaybackIntent.NextChapter
            text.contains("previous chapter") || text.contains("last chapter") -> VoicePlaybackIntent.PreviousChapter
            text.contains("skip") || text.contains("forward") -> VoicePlaybackIntent.SeekRelative(parseSeconds(text, 30) * 1000)
            text.contains("back") || text.contains("rewind") -> VoicePlaybackIntent.SeekRelative(-parseSeconds(text, 10) * 1000)
            else -> null
        }
    }

    private fun parseSeconds(text: String, defaultSeconds: Int): Int {
        return when {
            text.contains("one minute") -> 60
            text.contains("thirty") -> 30
            text.contains("ten") -> 10
            else -> defaultSeconds
        }
    }
}
