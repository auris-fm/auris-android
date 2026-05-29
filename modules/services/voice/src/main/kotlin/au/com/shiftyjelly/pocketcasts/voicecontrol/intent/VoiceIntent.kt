package au.com.shiftyjelly.pocketcasts.voicecontrol.intent

sealed interface VoiceIntent {
    data object Pause : VoiceIntent
    data object Resume : VoiceIntent
    data class SeekRelative(val deltaMs: Int) : VoiceIntent
    data class SeekAbsolute(val positionMs: Int) : VoiceIntent
    data object NextChapter : VoiceIntent
    data object PreviousChapter : VoiceIntent
    data class ChapterByIndex(val index: Int) : VoiceIntent
    data class ChapterByTitle(val query: String) : VoiceIntent {
        val normalizedQuery: String = query.trim()
    }
    data object NextEpisode : VoiceIntent
    data class SetSpeed(val speed: Double) : VoiceIntent
    data class AdjustSpeed(val delta: Double) : VoiceIntent
    data class SetVolume(val volume: Int) : VoiceIntent
    data class AdjustVolume(val delta: Int) : VoiceIntent
    data class SleepTimer(val minutes: Int) : VoiceIntent
    data class SetTrimMode(val mode: String) : VoiceIntent
    data class SetVolumeBoost(val enabled: Boolean) : VoiceIntent
    data class AddBookmark(val title: String) : VoiceIntent
}
