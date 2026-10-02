package au.com.shiftyjelly.pocketcasts.voicecontrol.intent

sealed interface VoiceIntent {
    sealed interface Playback : VoiceIntent {
        data object Pause : Playback
        data object Resume : Playback
        data class SeekRelative(val deltaSeconds: Int?) : Playback  // null when the request stated no amount; the sink applies its own interval in direction
        data class SeekAbsolute(val positionSeconds: Int) : Playback
        data object NextEpisode : Playback
    }

    sealed interface Effects : VoiceIntent {
        data class SetSpeed(val speed: Double) : Effects
        data class AdjustSpeed(val delta: Double) : Effects
        data class SetTrimMode(val mode: String) : Effects
        data class SetVolumeBoost(val enabled: Boolean) : Effects
        data object QueryEffects : Effects
    }

    sealed interface Volume : VoiceIntent {
        data class SetVolume(val volume: Int) : Volume
        data class AdjustVolume(val delta: Int) : Volume
        data object Query : Volume
    }

    sealed interface Sleep : VoiceIntent {
        data class Set(val minutes: Int) : Sleep
        data object EndOfEpisode : Sleep
        data object EndOfChapter : Sleep
        data class AddTime(val minutes: Int) : Sleep
        data object Cancel : Sleep
        data object QuerySleep : Sleep
    }

    sealed interface Chapter : VoiceIntent {
        data object NextChapter : Chapter
        data object PreviousChapter : Chapter
        data class ByIndex(val index: Int) : Chapter
        data class ByTitle(val query: String) : Chapter {
            val normalizedQuery: String = query.trim()
        }
        data class OpenLink(val index: Int) : Chapter
        data object QueryList : Chapter
        data object QueryCurrent : Chapter
        data object QueryCount : Chapter
        data object QueryNext : Chapter
    }

    sealed interface Bookmark : VoiceIntent {
        data class Add(val title: String? = null) : Bookmark
        data class Rename(val ref: String, val title: String) : Bookmark
        data class Play(val ref: String) : Bookmark
        data class Delete(val ref: String) : Bookmark
        data object DeleteAll : Bookmark
        data object QueryBookmarkList : Bookmark
        data object QueryBookmarkCount : Bookmark
        data object QueryNearby : Bookmark
    }

    sealed interface Queue : VoiceIntent {
        data class AddTop(val episode: String? = null) : Queue
        data class AddBottom(val episode: String? = null) : Queue
        data class Remove(val episode: String) : Queue
        data class MoveToTop(val episode: String) : Queue
        data class MoveToBottom(val episode: String) : Queue
        data object Clear : Queue
        data class RemoveByPodcast(val podcast: String) : Queue
        data class Sort(val sortOrder: String) : Queue
        data object QueryContents : Queue
        data object QueryQueueNext : Queue
        data object QueryLength : Queue
        data class QueryIsQueued(val episode: String) : Queue
    }

    sealed interface PlaybackQuery : VoiceIntent {
        data object WhatsPlaying : PlaybackQuery
        data object Position : PlaybackQuery
        data object TimeRemaining : PlaybackQuery
        data object CurrentPodcast : PlaybackQuery
        data object EpisodeDuration : PlaybackQuery
        data object PublishDate : PlaybackQuery
        data object EpisodeDescription : PlaybackQuery
        data object DownloadStatus : PlaybackQuery
        data object EpisodeTitle : PlaybackQuery
    }

    sealed interface StatsQuery : VoiceIntent {
        data class ListeningTime(val period: String? = null) : StatsQuery
        data class TopPodcasts(val period: String? = null) : StatsQuery
        data class EpisodesFinished(val period: String? = null) : StatsQuery
        data object ListeningStreak : StatsQuery
        data object SubscriptionCount : StatsQuery
        data object UnplayedTotal : StatsQuery
        data object DownloadStats : StatsQuery
        data object QueueTotal : StatsQuery
        data class NewEpisodes(val timeframe: String? = null) : StatsQuery
        data object TimeSinceLastListen : StatsQuery
    }

    data class CloudRoute(
        val request: String,
        val tier: CloudTier,
        val context: PlaybackContext = PlaybackContext(),
        /**
         * The grace window this dispatch was issued under, stamped by whoever
         * dispatched it. Handling the command may extend that window only while
         * it is still current and still open: the turn outlives the window, and
         * without the identity an in-flight turn could hold a later session open
         * or spend its allowance.
         */
        val windowGeneration: Long? = null,
        /**
         * Who issued this dispatch. Separate from [windowGeneration] on purpose:
         * the generation decides whether the window is touched at all, the
         * origin decides whether the window's allowance is restored. A
         * model-chosen route is a deliberate act and restores it; a
         * routing-failure escalation is what the allowance exists for and does
         * not, or the fallback would fund its own next attempt.
         */
        val origin: CloudRouteOrigin = CloudRouteOrigin.ModelCall,
    ) : VoiceIntent

    enum class CloudRouteOrigin { ModelCall, RoutingFailure }

    enum class CloudTier { Free, Premium, Unknown }
}

data class PlaybackContext(
    val episodeId: String = "",
    val podcastId: String = "",
    val referencePositionMs: Long? = null,
    val clientPositionMs: Long = 0L,
    val recentReferencePositions: List<Long> = emptyList(),
    val previousReferencePositionMs: Long? = null,
)
