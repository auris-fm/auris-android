package au.com.shiftyjelly.pocketcasts.voice.intent

class DeterministicVoiceIntentInterpreter @javax.inject.Inject constructor() : VoiceIntentInterpreter {
    override suspend fun interpret(result: VoiceRecognitionResult): VoicePlaybackIntent? {
        if (result.confidence < 0.75f) return null
        val text = result.transcript.trim()
        if (text.length < 2) return null

        val parts = text.split(Regex("\\s+"), limit = 2)
        val command = parts[0].lowercase()
        val arg = parts.getOrNull(1)

        return when (command) {
            "pause", "stop" -> VoicePlaybackIntent.Pause
            "resume", "play" -> VoicePlaybackIntent.Resume
            "forward" -> VoicePlaybackIntent.SeekRelative((parseSeconds(arg)?.coerceAtMost(600) ?: 30) * 1000)
            "back", "rewind" -> VoicePlaybackIntent.SeekRelative(-(parseSeconds(arg)?.coerceAtMost(600) ?: 10) * 1000)
            "seek", "goto" -> {
                val seconds = parseSeconds(arg)
                if (seconds != null) VoicePlaybackIntent.SeekAbsolute(seconds * 1000)
                else null
            }
            "next" -> VoicePlaybackIntent.NextChapter
            "previous", "prev" -> VoicePlaybackIntent.PreviousChapter
            "chapter" -> {
                if (arg == null) null
                else {
                    val index = arg.toIntOrNull()
                    if (index != null) VoicePlaybackIntent.ChapterByIndex(index)
                    else VoicePlaybackIntent.ChapterByTitle(arg)
                }
            }
            "speed" -> {
                val multiplier = arg?.trimEnd('x')?.toDoubleOrNull()
                if (multiplier != null && multiplier > 0 && multiplier <= 5) {
                    VoicePlaybackIntent.SetPlaybackSpeed(multiplier)
                } else null
            }
            "none" -> null
            else -> null
        }
    }

    private fun parseSeconds(arg: String?): Int? {
        if (arg == null) return null
        val raw = arg.lowercase().trim()
        // Try direct number (seconds)
        raw.toIntOrNull()?.let { return it }
        raw.toDoubleOrNull()?.let { return it.toInt() }
        // Try "X minutes" or "X min"
        val minMatch = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:min(?:ute)?s?)").find(raw)
        if (minMatch != null) return (minMatch.groupValues[1].toDouble() * 60).toInt()
        // Try "X seconds" or "X sec"
        val secMatch = Regex("(\\d+)\\s*sec(?:onds?)?").find(raw)
        if (secMatch != null) return secMatch.groupValues[1].toInt()
        // Textual numbers
        return when {
            raw.contains("thirty") || raw.contains("half") -> 30
            raw.contains("twenty") -> 20
            raw.contains("fifteen") -> 15
            raw.contains("ten") -> 10
            raw.contains("five") -> 5
            raw.contains("one") || raw.contains("a minute") -> 60
            else -> null
        }
    }
}
