package au.com.shiftyjelly.pocketcasts.voicecontrol.intent

sealed interface VoiceIntent {
    sealed interface Playback : VoiceIntent {
        data object Pause : Playback
        data object Resume : Playback
        data class SeekRelative(val deltaMs: Int) : Playback
        data class SeekAbsolute(val positionMs: Int) : Playback
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

    sealed interface Episode : VoiceIntent {
        data class Download(val episode: String) : Episode
        data class DeleteDownload(val episode: String) : Episode
        data class Star(val episode: String) : Episode
        data class Unstar(val episode: String) : Episode
        data class Archive(val episode: String) : Episode
        data class Unarchive(val episode: String) : Episode
        data class MarkPlayed(val episode: String) : Episode
        data class MarkUnplayed(val episode: String) : Episode
        data class RemoveFromHistory(val episode: String) : Episode
        data class AddToPlaylist(val episode: String, val playlist: String) : Episode
    }

    sealed interface Podcast : VoiceIntent {
        data class Subscribe(val podcast: String) : Podcast
        data class Unsubscribe(val podcast: String) : Podcast
        data class Rate(val podcast: String, val rating: Int) : Podcast
        data class ToggleNotifications(val podcast: String, val enabled: Boolean? = null) : Podcast
        data class AutoAdd(val podcast: String, val position: String? = null) : Podcast
        data class AutoDownload(val podcast: String, val enabled: Boolean) : Podcast
    }

    sealed interface Bulk : VoiceIntent {
        data class BulkDownload(val podcast: String? = null, val filter: String? = null) : Bulk
        data class BulkArchive(val podcast: String? = null, val filter: String? = null) : Bulk
        data class BulkMarkPlayed(val podcast: String? = null, val filter: String? = null) : Bulk
    }

    sealed interface Folder : VoiceIntent {
        data class Create(val name: String) : Folder
        data class Rename(val folder: String, val name: String) : Folder
        data class Assign(val podcast: String, val folder: String) : Folder
        data class RemoveFrom(val podcast: String) : Folder
        data class Delete(val folder: String) : Folder
    }

    sealed interface Playlist : VoiceIntent {
        data class Create(val name: String? = null) : Playlist
        data class CreateSmart(val rules: String, val name: String? = null) : Playlist
        data class Delete(val playlist: String) : Playlist
        data class Rename(val playlist: String, val name: String) : Playlist
        data class PlayAll(val playlist: String, val shuffle: Boolean = false) : Playlist
        data class DownloadAll(val playlist: String) : Playlist
        data class AddEpisode(val episode: String, val playlist: String) : Playlist
        data class RemoveEpisode(val episode: String, val playlist: String) : Playlist
        data class ArchiveAll(val playlist: String) : Playlist
        data class UnarchiveAll(val playlist: String) : Playlist
        data class AutoDownload(val playlist: String, val enabled: Boolean) : Playlist
    }

    sealed interface Search : VoiceIntent {
        data class PerformSearch(val query: String) : Search
        data class Filter(val type: String? = null) : Search
        data class SubscribeResult(val ref: String) : Search
        data class PlayResult(val ref: String) : Search
        data class DescribeResult(val ref: String) : Search
        data class Rerun(val query: String? = null) : Search
        data object ClearHistory : Search
        data object Trending : Search
        data object Recommendations : Search
        data class Category(val category: String) : Search
        data class NewReleases(val timeframe: String? = null) : Search
        data class ChangeRegion(val region: String) : Search
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

    sealed interface Transcript : VoiceIntent {
        data object Open : Transcript
        data class Search(val term: String) : Transcript
        data class Navigate(val direction: String) : Transcript
        data class SeekToTopic(val topic: String) : Transcript
        data object ReadLine : Transcript
        data class QueryTopic(val topic: String) : Transcript
        data class SeekToQuote(val quote: String) : Transcript
        data class ReadSection(val start: String? = null, val end: String? = null) : Transcript
    }

    sealed interface Assistant : VoiceIntent {
        data class Ask(val question: String) : Assistant
        data object Summarize : Assistant
        data class QueryContent(val question: String) : Assistant
        data class JumpToTopic(val topic: String) : Assistant
        data class PlayQuote(val ref: String? = null) : Assistant
        data object StopQuote : Assistant
        data object Retry : Assistant
        data object ClearChat : Assistant
    }

    sealed interface Cast : VoiceIntent {
        data class Start(val device: String? = null) : Cast
        data object Stop : Cast
    }

    sealed interface Stories : VoiceIntent {
        data object View : Stories
        data object NextStory : Stories
        data object PreviousStory : Stories
        data object Share : Stories
        data object Replay : Stories
    }

    sealed interface GuestPass : VoiceIntent {
        data object Send : GuestPass
        data object Claim : GuestPass
    }

    sealed interface DownloadSettings : VoiceIntent {
        data class AutoDownloadUpNext(val enabled: Boolean) : DownloadSettings
        data class AutoDownloadNew(val enabled: Boolean) : DownloadSettings
        data class AutoDownloadOnFollow(val enabled: Boolean) : DownloadSettings
        data class WifiOnly(val enabled: Boolean) : DownloadSettings
        data class ChargingOnly(val enabled: Boolean) : DownloadSettings
        data class PodcastAutoDownload(val podcast: String, val enabled: Boolean) : DownloadSettings
        data object StopAllDownloads : DownloadSettings
        data object ClearErrors : DownloadSettings
        data class DownloadLimit(val count: Int) : DownloadSettings
    }

    sealed interface PlaybackSettings : VoiceIntent {
        data class NextTrackAction(val trackAction: String) : PlaybackSettings
        data class PreviousTrackAction(val trackAction: String) : PlaybackSettings
        data class ConfirmationSound(val enabled: Boolean) : PlaybackSettings
        data class AutoAdd(val podcast: String? = null) : PlaybackSettings
        data class AutoAddPosition(val position: String) : PlaybackSettings
        data class AutoAddLimit(val count: Int) : PlaybackSettings
        data class ArchiveAfterPlaying(val delay: String) : PlaybackSettings
        data class ArchiveInactive(val period: String) : PlaybackSettings
        data class IncludeStarredAutoArchive(val enabled: Boolean) : PlaybackSettings
    }

    sealed interface AppSettings : VoiceIntent {
        data class SetTheme(val theme: String) : AppSettings
        data class Notifications(val enabled: Boolean) : AppSettings
        data class PodcastNotifications(val podcast: String, val enabled: Boolean) : AppSettings
        data object ManualCleanup : AppSettings
        data object ExportOpml : AppSettings
    }

    sealed interface Account : VoiceIntent {
        data class SignInEmail(val email: String, val password: String) : Account
        data object SignInGoogle : Account
        data class CreateAccount(val email: String, val password: String, val newsletter: Boolean = false) : Account
        data class ChangeEmail(val newEmail: String, val password: String) : Account
        data class ChangePassword(val currentPassword: String, val newPassword: String) : Account
        data class ResetPassword(val email: String? = null) : Account
        data class RedeemPromo(val code: String? = null) : Account
        data object SignOut : Account
        data class ChangePlan(val plan: String) : Account
        data class ClaimOffer(val offer: String? = null) : Account
        data object CancelSubscription : Account
        data object KeepSubscription : Account
    }

    sealed interface Sharing : VoiceIntent {
        data class ShareEpisode(val episode: String? = null) : Sharing
        data object ShareAtCurrentTime : Sharing
        data class ShareAtTime(val time: String) : Sharing
        data class SharePodcast(val podcast: String? = null) : Sharing
        data class ShareClip(val start: String, val end: String) : Sharing
        data class ShareBookmark(val bookmark: String) : Sharing
        data class ShareTranscript(val section: String? = null) : Sharing
        data class CreateSharedList(val listName: String, val podcasts: String? = null) : Sharing
        data class ShareViaApp(val episode: String? = null, val app: String? = null) : Sharing
        data class AcceptSharedList(val acceptMode: String? = null) : Sharing
    }

    data class CloudRoute(
        val request: String,
        val tier: CloudTier,
        val category: CloudCategory,
        val context: PlaybackContext = PlaybackContext(),
    ) : VoiceIntent

    enum class CloudTier { Free, Premium, Unknown }
    enum class CloudCategory {
        Understanding,
        Discovery,
        Learning,
        Assistant,
        Research,
        Engagement,
        Synthesis,
        Unknown,
    }
}

data class PlaybackContext(
    val episodeId: String = "",
    val positionMs: Long = 0L,
    val recentTimestamps: List<Long> = emptyList(),
)
