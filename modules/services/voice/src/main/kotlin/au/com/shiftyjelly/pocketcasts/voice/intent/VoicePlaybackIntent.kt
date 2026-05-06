package au.com.shiftyjelly.pocketcasts.voice.intent

sealed interface VoicePlaybackIntent {
    data object Pause : VoicePlaybackIntent
    data object Resume : VoicePlaybackIntent
    data class SeekRelative(val deltaMs: Int) : VoicePlaybackIntent
    data class SeekAbsolute(val positionMs: Int) : VoicePlaybackIntent
    data object NextChapter : VoicePlaybackIntent
    data object PreviousChapter : VoicePlaybackIntent
    data class ChapterByIndex(val index: Int) : VoicePlaybackIntent
    data class ChapterByTitle(val query: String) : VoicePlaybackIntent {
        val normalizedQuery: String = query.trim()
    }
    data class SetPlaybackSpeed(val speed: Double) : VoicePlaybackIntent
}
