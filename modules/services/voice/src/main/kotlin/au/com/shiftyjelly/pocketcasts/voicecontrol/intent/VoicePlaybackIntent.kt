package au.com.shiftyjelly.pocketcasts.voicecontrol.intent

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
    data object NextEpisode : VoicePlaybackIntent
    data class SetSpeed(val speed: Double) : VoicePlaybackIntent
    data class AdjustSpeed(val delta: Double) : VoicePlaybackIntent
    data class SetVolume(val volume: Int) : VoicePlaybackIntent
    data class AdjustVolume(val delta: Int) : VoicePlaybackIntent
    data class SleepTimer(val minutes: Int) : VoicePlaybackIntent
    data class SetTrimMode(val mode: String) : VoicePlaybackIntent
    data class SetVolumeBoost(val enabled: Boolean) : VoicePlaybackIntent
    data class AddBookmark(val title: String) : VoicePlaybackIntent
}
