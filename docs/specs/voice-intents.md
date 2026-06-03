# Voice Intents

## Summary

Define the complete set of voice intents and wire them through to actual actions. The voice control system supports hands-free interaction across every app feature — everything a user can do by touch can also be done by voice.

The [dialog docs](../dialogs/) define the full interaction patterns (phrasing, confirmations, edge cases) for each action category. This spec owns the intent schema, executor wiring, and sink interfaces. How an utterance becomes a matched intent + extracted slots is owned by the [Recognition Pipeline spec](recognition-pipeline.md).

## Architecture

```text
VoiceIntent (sealed hierarchy, domain-grouped)
       │
       ▼
VoiceIntentExecutor ─── dispatches by domain ───► VoiceSink (per domain)
       │
       ▼
VoiceResponse? (silent | earcon | spoken)
```

Each domain has its own `VoiceSink` interface because domains touch different infrastructure (PlaybackManager, QueueManager, PodcastManager, etc.). The executor dispatches by intent domain to the correct sink and returns a `VoiceResponse` for the confirmation strategy (earcon, spoken, or silent — as defined in the dialogs).

```kotlin
sealed interface VoiceResponse {
    data object Silent : VoiceResponse
    data class Earcon(val id: String) : VoiceResponse
    data class Spoken(val text: String) : VoiceResponse
}
```

## Intents by Domain

### Playback — Transport

| Intent | Slot | Response |
|---|---|---|
| `Pause` | — | earcon |
| `Resume` | — | silent |
| `SeekRelative(deltaMs: Int)` | `deltaMs` (+ forward, − backward) | silent |
| `SeekAbsolute(positionMs: Int)` | `positionMs` | silent |
| `NextEpisode` | — | earcon + announce title |

### Playback — Effects

| Intent | Slot | Response |
|---|---|---|
| `SetSpeed(speed: Double)` | `speed` (0.5–5.0) | earcon + spoken value |
| `AdjustSpeed(delta: Double)` | `delta` | earcon + spoken value |
| `SetTrimMode(mode: String)` | `mode` (off/low/medium/high) | earcon + spoken mode |
| `SetVolumeBoost(enabled: Boolean)` | `enabled` | earcon + spoken state |
| `SetVolume(volume: Int)` | `volume` (0–100) | earcon |
| `AdjustVolume(delta: Int)` | `delta` | earcon |

### Playback — Sleep Timer

| Intent | Slot | Response |
|---|---|---|
| `SleepSet(minutes: Int)` | `minutes` | earcon + spoken duration |
| `SleepEndOfEpisode` | — | earcon + spoken |
| `SleepEndOfChapter` | — | earcon + spoken |
| `SleepAddTime(minutes: Int)` | `minutes` | earcon + spoken remaining |
| `SleepCancel` | — | earcon + spoken |

### Playback — Queries

| Intent | Slot | Response |
|---|---|---|
| `QueryEffectsState` | — | spoken |
| `QuerySleepTimer` | — | spoken |

### Chapters

| Intent | Slot | Response |
|---|---|---|
| `NextChapter` | — | silent |
| `PreviousChapter` | — | silent |
| `ChapterByIndex(index: Int)` | `index` (1-based) | silent |
| `ChapterByTitle(query: String)` | `query` | silent |
| `OpenChapterLink(index: Int)` | `index` | earcon |
| `QueryChapterList` | — | spoken |
| `QueryCurrentChapter` | — | spoken |
| `QueryChapterCount` | — | spoken |
| `QueryNextChapter` | — | spoken |

### Bookmarks

| Intent | Slot | Response |
|---|---|---|
| `AddBookmark(title: String?)` | `title` | earcon + spoken position |
| `RenameBookmark(ref: BookmarkRef, title: String)` | `ref`, `title` | earcon |
| `PlayBookmark(ref: BookmarkRef)` | `ref` | silent |
| `DeleteBookmark(ref: BookmarkRef)` | `ref` | earcon |
| `DeleteAllBookmarks` | — | earcon (explicit confirm) |
| `QueryBookmarkList` | — | spoken |
| `QueryBookmarkCount` | — | spoken |
| `QueryNearbyBookmarks` | — | spoken |

`BookmarkRef` is a discriminated reference — by title, by index, or "just made":
```kotlin
sealed interface BookmarkRef {
    data class ByTitle(val query: String) : BookmarkRef
    data class ByIndex(val index: Int) : BookmarkRef
    data object Latest : BookmarkRef
}
```

### Queue

| Intent | Slot | Response |
|---|---|---|
| `QueueAddTop(episode: EpisodeRef)` | `episode` | earcon |
| `QueueAddBottom(episode: EpisodeRef)` | `episode` | earcon |
| `QueueRemove(episode: EpisodeRef)` | `episode` | earcon |
| `QueueMoveToTop(episode: EpisodeRef)` | `episode` | earcon |
| `QueueMoveToBottom(episode: EpisodeRef)` | `episode` | earcon |
| `QueueClear` | — | earcon (explicit confirm) |
| `QueueRemoveByPodcast(podcast: PodcastRef)` | `podcast` | earcon |
| `QueueSort(order: SortOrder)` | `order` | earcon |
| `QueryQueueContents` | — | spoken |
| `QueryQueueNext` | — | spoken |
| `QueryQueueLength` | — | spoken |
| `QueryIsQueued(episode: EpisodeRef)` | `episode` | spoken |

### Podcasts

| Intent | Slot | Response |
|---|---|---|
| `Subscribe(podcast: PodcastRef)` | `podcast` | earcon |
| `Unsubscribe(podcast: PodcastRef)` | `podcast` | earcon |
| `DownloadEpisode(episode: EpisodeRef)` | `episode` | earcon |
| `DeleteDownload(episode: EpisodeRef)` | `episode` | earcon (explicit confirm) |
| `StarEpisode(episode: EpisodeRef)` | `episode` | earcon |
| `UnstarEpisode(episode: EpisodeRef)` | `episode` | earcon |
| `ArchiveEpisode(episode: EpisodeRef)` | `episode` | earcon |
| `UnarchiveEpisode(episode: EpisodeRef)` | `episode` | earcon |
| `MarkPlayed(episode: EpisodeRef)` | `episode` | earcon |
| `MarkUnplayed(episode: EpisodeRef)` | `episode` | earcon |
| `RemoveFromHistory(episode: EpisodeRef)` | `episode` | earcon (explicit confirm) |
| `BulkDownload(filter: EpisodeFilter)` | `filter` | earcon (explicit confirm) |
| `BulkArchive(filter: EpisodeFilter)` | `filter` | earcon (explicit confirm) |
| `BulkMarkPlayed(filter: EpisodeFilter)` | `filter` | earcon |
| `AddToPlaylist(episode: EpisodeRef, playlist: PlaylistRef)` | `episode`, `playlist` | earcon |
| `CreateFolder(name: String)` | `name` | earcon |
| `RenameFolder(folder: FolderRef, name: String)` | `folder`, `name` | earcon |
| `AssignToFolder(podcast: PodcastRef, folder: FolderRef)` | `podcast`, `folder` | earcon |
| `RemoveFromFolder(podcast: PodcastRef)` | `podcast` | earcon |
| `DeleteFolder(folder: FolderRef)` | `folder` | earcon (explicit confirm) |
| `RatePodcast(podcast: PodcastRef, rating: Int)` | `podcast`, `rating` (1–5) | earcon |
| `ToggleNotifications(podcast: PodcastRef, enabled: Boolean)` | `podcast`, `enabled` | earcon |
| `AutoAddToQueue(podcast: PodcastRef, position: QueuePosition)` | `podcast`, `position` | earcon |
| `AutoDownloadPodcast(podcast: PodcastRef, enabled: Boolean)` | `podcast`, `enabled` | earcon |

### Playlists

| Intent | Slot | Response |
|---|---|---|
| `CreatePlaylist(name: String?)` | `name` | earcon + spoken name |
| `CreateSmartPlaylist(rules: SmartRules, name: String?)` | `rules`, `name` | earcon + spoken name |
| `DeletePlaylist(playlist: PlaylistRef)` | `playlist` | earcon (explicit confirm) |
| `RenamePlaylist(playlist: PlaylistRef, name: String)` | `playlist`, `name` | earcon |
| `PlayAll(playlist: PlaylistRef, shuffle: Boolean)` | `playlist`, `shuffle` | silent |
| `DownloadAll(playlist: PlaylistRef)` | `playlist` | earcon (explicit confirm) |
| `PlaylistAddEpisode(episode: EpisodeRef, playlist: PlaylistRef)` | `episode`, `playlist` | earcon |
| `PlaylistRemoveEpisode(episode: EpisodeRef, playlist: PlaylistRef)` | `episode`, `playlist` | earcon |
| `ArchiveAll(playlist: PlaylistRef)` | `playlist` | earcon (explicit confirm) |
| `UnarchiveAll(playlist: PlaylistRef)` | `playlist` | earcon |
| `AutoDownloadPlaylist(playlist: PlaylistRef, enabled: Boolean)` | `playlist`, `enabled` | earcon |

### Search & Discover

| Intent | Slot | Response |
|---|---|---|
| `Search(query: String)` | `query` | spoken results |
| `FilterResults(type: ContentType?)` | `type` (podcasts/episodes) | spoken results |
| `SubscribeFromResult(ref: ResultRef)` | `ref` | earcon |
| `PlayFromResult(ref: ResultRef)` | `ref` | silent |
| `DescribeResult(ref: ResultRef)` | `ref` | spoken |
| `SearchHistoryReRun(query: String?)` | `query` | spoken results |
| `ClearSearchHistory` | — | earcon (explicit confirm) |
| `DiscoverTrending` | — | spoken |
| `DiscoverRecommendations` | — | spoken |
| `DiscoverCategory(category: String)` | `category` | spoken |
| `DiscoverNewReleases(timeframe: String?)` | `timeframe` | spoken |
| `ChangeRegion(region: String)` | `region` | earcon |

### Content Queries

| Intent | Slot | Response |
|---|---|---|
| `QueryWhatsPlaying` | — | spoken |
| `QueryPosition` | — | spoken |
| `QueryTimeRemaining` | — | spoken |
| `QueryCurrentPodcast` | — | spoken |
| `QueryEpisodeDuration` | — | spoken |
| `QueryPublishDate` | — | spoken |
| `QueryEpisodeDescription` | — | spoken (summarize if long) |
| `QueryDownloadStatus` | — | spoken |
| `QueryEpisodeTitle` | — | spoken |
| `QueryListeningTime(period: String?)` | `period` | spoken |
| `QueryTopPodcasts` | — | spoken |
| `QueryEpisodesFinished(period: String?)` | `period` | spoken |
| `QueryListeningStreak` | — | spoken |
| `QuerySubscriptionCount` | — | spoken |
| `QueryUnplayedTotal` | — | spoken |
| `QueryDownloadStats` | — | spoken |
| `QueryQueueTotal` | — | spoken |
| `QueryNewEpisodes(timeframe: String?)` | `timeframe` | spoken |
| `QueryTimeSinceLastListen` | — | spoken |

### Transcripts

| Intent | Slot | Response |
|---|---|---|
| `OpenTranscript` | — | earcon |
| `SearchTranscript(term: String)` | `term` | spoken match count |
| `NavigateTranscript(direction: NavigationDirection)` | `direction` | spoken context |
| `SeekToTopic(topic: String)` | `topic` | silent |
| `ReadTranscriptLine` | — | spoken |
| `QueryTranscriptTopic(topic: String)` | `topic` | spoken |
| `SeekToQuote(quote: String)` | `quote` | silent |
| `ReadTranscriptSection(start: String?, end: String?)` | `start`, `end` | spoken |

### Assistant

| Intent | Slot | Response |
|---|---|---|
| `AskEpisode(question: String)` | `question` | spoken (AI) |
| `SummarizeEpisode` | — | spoken (AI) |
| `QueryEpisodeContent(question: String)` | `question` | spoken (AI) |
| `JumpToTopic(topic: String)` | `topic` | silent |
| `PlayQuote(ref: String?)` | `ref` | silent |
| `StopQuote` | — | silent |
| `RetryFailedMessage` | — | earcon |
| `ClearChat` | — | earcon (explicit confirm) |
| `CastToDevice(device: String?)` | `device` | spoken |
| `StopCasting` | — | earcon |
| `SendGuestPass` | — | earcon |
| `ClaimGuestPass` | — | earcon |
| `ViewStories` | — | earcon |
| `NextStory` | — | silent |
| `PreviousStory` | — | silent |
| `ShareStory` | — | earcon |
| `ReplayStories` | — | earcon |

### Settings

| Intent | Slot | Response |
|---|---|---|
| `SetTheme(theme: String)` | `theme` (light/dark/classic dark/ink/system) | earcon |
| `SetAutoDownloadUpNext(enabled: Boolean)` | `enabled` | earcon |
| `SetAutoDownloadNew(enabled: Boolean)` | `enabled` | earcon |
| `SetAutoDownloadOnFollow(enabled: Boolean)` | `enabled` | earcon |
| `SetWifiOnly(enabled: Boolean)` | `enabled` | earcon |
| `SetChargingOnly(enabled: Boolean)` | `enabled` | earcon |
| `SetPodcastAutoDownload(podcast: PodcastRef, enabled: Boolean)` | `podcast`, `enabled` | earcon |
| `StopAllDownloads` | — | earcon (explicit confirm) |
| `ClearDownloadErrors` | — | earcon (explicit confirm) |
| `SetDownloadLimit(count: Int)` | `count` | earcon |
| `SetNextTrackAction(action: TrackAction)` | `action` | earcon |
| `SetPreviousTrackAction(action: TrackAction)` | `action` | earcon |
| `SetConfirmationSound(enabled: Boolean)` | `enabled` | earcon |
| `SetAutoAdd(podcast: PodcastRef, enabled: Boolean)` | `podcast`, `enabled` | earcon |
| `SetAutoAddPosition(position: QueuePosition)` | `position` | earcon |
| `SetAutoAddLimit(count: Int)` | `count` | earcon |
| `SetArchiveAfterPlaying(delay: String)` | `delay` | earcon |
| `SetArchiveInactive(period: String)` | `period` | earcon |
| `SetIncludeStarredAutoArchive(enabled: Boolean)` | `enabled` | earcon |
| `SetNotifications(enabled: Boolean)` | `enabled` | earcon |
| `SetPodcastNotifications(podcast: PodcastRef, enabled: Boolean)` | `podcast`, `enabled` | earcon |
| `ManualCleanup` | — | earcon (explicit confirm) |
| `ExportOpml` | — | earcon |

### Account

| Intent | Slot | Response |
|---|---|---|
| `SignInEmail(email: String?, password: String?)` | `email`, `password` | spoken (multi-turn slot fill) |
| `SignInGoogle` | — | spoken (requires screen tap) |
| `CreateAccount(email: String, password: String, newsletter: Boolean?)` | `email`, `password`, `newsletter` | spoken (multi-turn slot fill) |
| `ChangeEmail(newEmail: String, password: String)` | `newEmail`, `password` | spoken (multi-turn slot fill) |
| `ChangePassword(current: String, new: String)` | `current`, `new` | spoken (multi-turn slot fill) |
| `ResetPassword(email: String?)` | `email` | spoken |
| `RedeemPromoCode(code: String?)` | `code` | spoken |
| `SignOut` | — | earcon (explicit confirm) |
| `ChangePlan(plan: String)` | `plan` (monthly/yearly) | earcon (explicit confirm) |
| `ClaimOffer(offer: String?)` | `offer` | spoken |
| `CancelSubscription` | — | earcon (explicit confirm) |
| `KeepSubscription` | — | spoken |

### Sharing

| Intent | Slot | Response |
|---|---|---|
| `ShareEpisode(episode: EpisodeRef?)` | `episode` | earcon |
| `ShareAtCurrentTime` | — | earcon |
| `ShareAtTime(time: String)` | `time` | earcon |
| `SharePodcast(podcast: PodcastRef?)` | `podcast` | earcon |
| `ShareClip(start: String?, end: String?)` | `start`, `end` | earcon |
| `ShareBookmark(ref: BookmarkRef)` | `ref` | earcon |
| `ShareTranscript(section: String?)` | `section` | earcon |
| `CreateSharedList(name: String, podcasts: List<PodcastRef>)` | `name`, `podcasts` | earcon |
| `ShareViaApp(app: String)` | `app` | earcon |
| `AcceptSharedList(mode: AcceptMode)` | `mode` (all/select) | spoken |

## Shared Reference Types

Several domains reference the same entity types. These are shared across intents:

```kotlin
sealed interface EpisodeRef {
    data class ByTitle(val query: String) : EpisodeRef
    data class ByIndex(val index: Int) : EpisodeRef
    data object Current : EpisodeRef
}

sealed interface PodcastRef {
    data class ByTitle(val query: String) : PodcastRef
    data object Current : PodcastRef
}

sealed interface PlaylistRef {
    data class ByName(val query: String) : PlaylistRef
    data class ByIndex(val index: Int) : PlaylistRef
}

sealed interface FolderRef {
    data class ByName(val query: String) : FolderRef
}

sealed interface ResultRef {
    data class ByTitle(val query: String) : ResultRef
    data class ByIndex(val index: Int) : ResultRef
}

enum class QueuePosition { Top, Bottom }
enum class NavigationDirection { Next, Previous }
enum class ContentType { Podcasts, Episodes }
enum class TrackAction { SkipForward, SkipBackward, AddBookmark }
enum class SortOrder { NewestFirst, OldestFirst }
enum class AcceptMode { All, Select }

data class EpisodeFilter(
    val podcast: PodcastRef? = null,
    val status: String? = null,  // "unplayed", "played", "downloaded"
    val limit: Int? = null,
)

data class SmartRules(
    val podcast: PodcastRef? = null,
    val duration: String? = null,
    val downloaded: Boolean? = null,
    val played: Boolean? = null,
    val mediaType: String? = null,
)
```

## Intent Sealed Interface

```kotlin
sealed interface VoiceIntent {
    // Playback
    sealed interface Playback : VoiceIntent
    sealed interface Chapter : VoiceIntent
    sealed interface Bookmark : VoiceIntent
    sealed interface Queue : VoiceIntent
    sealed interface Podcast : VoiceIntent
    sealed interface Playlist : VoiceIntent
    sealed interface Search : VoiceIntent
    sealed interface ContentQuery : VoiceIntent
    sealed interface Transcript : VoiceIntent
    sealed interface Assistant : VoiceIntent
    sealed interface Settings : VoiceIntent
    sealed interface Account : VoiceIntent
    sealed interface Sharing : VoiceIntent
}

// Playback
sealed interface PlaybackIntent : VoiceIntent.Playback {
    data object Pause : PlaybackIntent
    data object Resume : PlaybackIntent
    data class SeekRelative(val deltaMs: Int) : PlaybackIntent
    data class SeekAbsolute(val positionMs: Int) : PlaybackIntent
    data object NextEpisode : PlaybackIntent
    data class SetSpeed(val speed: Double) : PlaybackIntent
    data class AdjustSpeed(val delta: Double) : PlaybackIntent
    data class SetTrimMode(val mode: String) : PlaybackIntent
    data class SetVolumeBoost(val enabled: Boolean) : PlaybackIntent
    data class SetVolume(val volume: Int) : PlaybackIntent
    data class AdjustVolume(val delta: Int) : PlaybackIntent
    data class SleepSet(val minutes: Int) : PlaybackIntent
    data object SleepEndOfEpisode : PlaybackIntent
    data object SleepEndOfChapter : PlaybackIntent
    data class SleepAddTime(val minutes: Int) : PlaybackIntent
    data object SleepCancel : PlaybackIntent
    data object QueryEffectsState : PlaybackIntent
    data object QuerySleepTimer : PlaybackIntent
}

// Chapters
sealed interface ChapterIntent : VoiceIntent.Chapter {
    data object NextChapter : ChapterIntent
    data object PreviousChapter : ChapterIntent
    data class ChapterByIndex(val index: Int) : ChapterIntent
    data class ChapterByTitle(val query: String) : ChapterIntent {
        val normalizedQuery: String = query.trim()
    }
    data class OpenChapterLink(val index: Int) : ChapterIntent
    data object QueryChapterList : ChapterIntent
    data object QueryCurrentChapter : ChapterIntent
    data object QueryChapterCount : ChapterIntent
    data object QueryNextChapter : ChapterIntent
}

// Bookmarks
sealed interface BookmarkIntent : VoiceIntent.Bookmark {
    data class AddBookmark(val title: String?) : BookmarkIntent
    data class RenameBookmark(val ref: BookmarkRef, val title: String) : BookmarkIntent
    data class PlayBookmark(val ref: BookmarkRef) : BookmarkIntent
    data class DeleteBookmark(val ref: BookmarkRef) : BookmarkIntent
    data object DeleteAllBookmarks : BookmarkIntent
    data object QueryBookmarkList : BookmarkIntent
    data object QueryBookmarkCount : BookmarkIntent
    data object QueryNearbyBookmarks : BookmarkIntent
}

// Queue
sealed interface QueueIntent : VoiceIntent.Queue {
    data class QueueAddTop(val episode: EpisodeRef) : QueueIntent
    data class QueueAddBottom(val episode: EpisodeRef) : QueueIntent
    data class QueueRemove(val episode: EpisodeRef) : QueueIntent
    data class QueueMoveToTop(val episode: EpisodeRef) : QueueIntent
    data class QueueMoveToBottom(val episode: EpisodeRef) : QueueIntent
    data object QueueClear : QueueIntent
    data class QueueRemoveByPodcast(val podcast: PodcastRef) : QueueIntent
    data class QueueSort(val order: SortOrder) : QueueIntent
    data object QueryQueueContents : QueueIntent
    data object QueryQueueNext : QueueIntent
    data object QueryQueueLength : QueueIntent
    data class QueryIsQueued(val episode: EpisodeRef) : QueueIntent
}

// Podcasts
sealed interface PodcastIntent : VoiceIntent.Podcast {
    data class Subscribe(val podcast: PodcastRef) : PodcastIntent
    data class Unsubscribe(val podcast: PodcastRef) : PodcastIntent
    data class DownloadEpisode(val episode: EpisodeRef) : PodcastIntent
    data class DeleteDownload(val episode: EpisodeRef) : PodcastIntent
    data class StarEpisode(val episode: EpisodeRef) : PodcastIntent
    data class UnstarEpisode(val episode: EpisodeRef) : PodcastIntent
    data class ArchiveEpisode(val episode: EpisodeRef) : PodcastIntent
    data class UnarchiveEpisode(val episode: EpisodeRef) : PodcastIntent
    data class MarkPlayed(val episode: EpisodeRef) : PodcastIntent
    data class MarkUnplayed(val episode: EpisodeRef) : PodcastIntent
    data class RemoveFromHistory(val episode: EpisodeRef) : PodcastIntent
    data class BulkDownload(val filter: EpisodeFilter) : PodcastIntent
    data class BulkArchive(val filter: EpisodeFilter) : PodcastIntent
    data class BulkMarkPlayed(val filter: EpisodeFilter) : PodcastIntent
    data class AddToPlaylist(val episode: EpisodeRef, val playlist: PlaylistRef) : PodcastIntent
    data class CreateFolder(val name: String) : PodcastIntent
    data class RenameFolder(val folder: FolderRef, val name: String) : PodcastIntent
    data class AssignToFolder(val podcast: PodcastRef, val folder: FolderRef) : PodcastIntent
    data class RemoveFromFolder(val podcast: PodcastRef) : PodcastIntent
    data class DeleteFolder(val folder: FolderRef) : PodcastIntent
    data class RatePodcast(val podcast: PodcastRef, val rating: Int) : PodcastIntent
    data class ToggleNotifications(val podcast: PodcastRef, val enabled: Boolean) : PodcastIntent
    data class AutoAddToQueue(val podcast: PodcastRef, val position: QueuePosition) : PodcastIntent
    data class AutoDownloadPodcast(val podcast: PodcastRef, val enabled: Boolean) : PodcastIntent
}

// Playlists
sealed interface PlaylistIntent : VoiceIntent.Playlist {
    data class CreatePlaylist(val name: String?) : PlaylistIntent
    data class CreateSmartPlaylist(val rules: SmartRules, val name: String?) : PlaylistIntent
    data class DeletePlaylist(val playlist: PlaylistRef) : PlaylistIntent
    data class RenamePlaylist(val playlist: PlaylistRef, val name: String) : PlaylistIntent
    data class PlayAll(val playlist: PlaylistRef, val shuffle: Boolean) : PlaylistIntent
    data class DownloadAll(val playlist: PlaylistRef) : PlaylistIntent
    data class PlaylistAddEpisode(val episode: EpisodeRef, val playlist: PlaylistRef) : PlaylistIntent
    data class PlaylistRemoveEpisode(val episode: EpisodeRef, val playlist: PlaylistRef) : PlaylistIntent
    data class ArchiveAll(val playlist: PlaylistRef) : PlaylistIntent
    data class UnarchiveAll(val playlist: PlaylistRef) : PlaylistIntent
    data class AutoDownloadPlaylist(val playlist: PlaylistRef, val enabled: Boolean) : PlaylistIntent
}

// Search & Discover
sealed interface SearchIntent : VoiceIntent.Search {
    data class Search(val query: String) : SearchIntent
    data class FilterResults(val type: ContentType?) : SearchIntent
    data class SubscribeFromResult(val ref: ResultRef) : SearchIntent
    data class PlayFromResult(val ref: ResultRef) : SearchIntent
    data class DescribeResult(val ref: ResultRef) : SearchIntent
    data class SearchHistoryReRun(val query: String?) : SearchIntent
    data object ClearSearchHistory : SearchIntent
    data object DiscoverTrending : SearchIntent
    data object DiscoverRecommendations : SearchIntent
    data class DiscoverCategory(val category: String) : SearchIntent
    data class DiscoverNewReleases(val timeframe: String?) : SearchIntent
    data class ChangeRegion(val region: String) : SearchIntent
}

// Content Queries
sealed interface ContentQueryIntent : VoiceIntent.ContentQuery {
    data object QueryWhatsPlaying : ContentQueryIntent
    data object QueryPosition : ContentQueryIntent
    data object QueryTimeRemaining : ContentQueryIntent
    data object QueryCurrentPodcast : ContentQueryIntent
    data object QueryEpisodeDuration : ContentQueryIntent
    data object QueryPublishDate : ContentQueryIntent
    data object QueryEpisodeDescription : ContentQueryIntent
    data object QueryDownloadStatus : ContentQueryIntent
    data object QueryEpisodeTitle : ContentQueryIntent
    data class QueryListeningTime(val period: String?) : ContentQueryIntent
    data object QueryTopPodcasts : ContentQueryIntent
    data class QueryEpisodesFinished(val period: String?) : ContentQueryIntent
    data object QueryListeningStreak : ContentQueryIntent
    data object QuerySubscriptionCount : ContentQueryIntent
    data object QueryUnplayedTotal : ContentQueryIntent
    data object QueryDownloadStats : ContentQueryIntent
    data object QueryQueueTotal : ContentQueryIntent
    data class QueryNewEpisodes(val timeframe: String?) : ContentQueryIntent
    data object QueryTimeSinceLastListen : ContentQueryIntent
}

// Transcripts
sealed interface TranscriptIntent : VoiceIntent.Transcript {
    data object OpenTranscript : TranscriptIntent
    data class SearchTranscript(val term: String) : TranscriptIntent
    data class NavigateTranscript(val direction: NavigationDirection) : TranscriptIntent
    data class SeekToTopic(val topic: String) : TranscriptIntent
    data object ReadTranscriptLine : TranscriptIntent
    data class QueryTranscriptTopic(val topic: String) : TranscriptIntent
    data class SeekToQuote(val quote: String) : TranscriptIntent
    data class ReadTranscriptSection(val start: String?, val end: String?) : TranscriptIntent
}

// Assistant
sealed interface AssistantIntent : VoiceIntent.Assistant {
    data class AskEpisode(val question: String) : AssistantIntent
    data object SummarizeEpisode : AssistantIntent
    data class QueryEpisodeContent(val question: String) : AssistantIntent
    data class JumpToTopic(val topic: String) : AssistantIntent
    data class PlayQuote(val ref: String?) : AssistantIntent
    data object StopQuote : AssistantIntent
    data object RetryFailedMessage : AssistantIntent
    data object ClearChat : AssistantIntent
    data class CastToDevice(val device: String?) : AssistantIntent
    data object StopCasting : AssistantIntent
    data object SendGuestPass : AssistantIntent
    data object ClaimGuestPass : AssistantIntent
    data object ViewStories : AssistantIntent
    data object NextStory : AssistantIntent
    data object PreviousStory : AssistantIntent
    data object ShareStory : AssistantIntent
    data object ReplayStories : AssistantIntent
}

// Settings
sealed interface SettingsIntent : VoiceIntent.Settings {
    data class SetTheme(val theme: String) : SettingsIntent
    data class SetAutoDownloadUpNext(val enabled: Boolean) : SettingsIntent
    data class SetAutoDownloadNew(val enabled: Boolean) : SettingsIntent
    data class SetAutoDownloadOnFollow(val enabled: Boolean) : SettingsIntent
    data class SetWifiOnly(val enabled: Boolean) : SettingsIntent
    data class SetChargingOnly(val enabled: Boolean) : SettingsIntent
    data class SetPodcastAutoDownload(val podcast: PodcastRef, val enabled: Boolean) : SettingsIntent
    data object StopAllDownloads : SettingsIntent
    data object ClearDownloadErrors : SettingsIntent
    data class SetDownloadLimit(val count: Int) : SettingsIntent
    data class SetNextTrackAction(val action: TrackAction) : SettingsIntent
    data class SetPreviousTrackAction(val action: TrackAction) : SettingsIntent
    data class SetConfirmationSound(val enabled: Boolean) : SettingsIntent
    data class SetAutoAdd(val podcast: PodcastRef, val enabled: Boolean) : SettingsIntent
    data class SetAutoAddPosition(val position: QueuePosition) : SettingsIntent
    data class SetAutoAddLimit(val count: Int) : SettingsIntent
    data class SetArchiveAfterPlaying(val delay: String) : SettingsIntent
    data class SetArchiveInactive(val period: String) : SettingsIntent
    data class SetIncludeStarredAutoArchive(val enabled: Boolean) : SettingsIntent
    data class SetNotifications(val enabled: Boolean) : SettingsIntent
    data class SetPodcastNotifications(val podcast: PodcastRef, val enabled: Boolean) : SettingsIntent
    data object ManualCleanup : SettingsIntent
    data object ExportOpml : SettingsIntent
}

// Account
sealed interface AccountIntent : VoiceIntent.Account {
    data class SignInEmail(val email: String?, val password: String?) : AccountIntent
    data object SignInGoogle : AccountIntent
    data class CreateAccount(val email: String, val password: String, val newsletter: Boolean?) : AccountIntent
    data class ChangeEmail(val newEmail: String, val password: String) : AccountIntent
    data class ChangePassword(val current: String, val new: String) : AccountIntent
    data class ResetPassword(val email: String?) : AccountIntent
    data class RedeemPromoCode(val code: String?) : AccountIntent
    data object SignOut : AccountIntent
    data class ChangePlan(val plan: String) : AccountIntent
    data class ClaimOffer(val offer: String?) : AccountIntent
    data object CancelSubscription : AccountIntent
    data object KeepSubscription : AccountIntent
}

// Sharing
sealed interface SharingIntent : VoiceIntent.Sharing {
    data class ShareEpisode(val episode: EpisodeRef?) : SharingIntent
    data object ShareAtCurrentTime : SharingIntent
    data class ShareAtTime(val time: String) : SharingIntent
    data class SharePodcast(val podcast: PodcastRef?) : SharingIntent
    data class ShareClip(val start: String?, val end: String?) : SharingIntent
    data class ShareBookmark(val ref: BookmarkRef) : SharingIntent
    data class ShareTranscript(val section: String?) : SharingIntent
    data class CreateSharedList(val name: String, val podcasts: List<PodcastRef>) : SharingIntent
    data class ShareViaApp(val app: String) : SharingIntent
    data class AcceptSharedList(val mode: AcceptMode) : SharingIntent
}
```

## Sink Interfaces

Each domain has its own sink. Sinks for mutating actions return `VoiceResponse`. Query sinks return `VoiceResponse.Spoken`.

```kotlin
interface VoicePlaybackSink {
    suspend fun pause(): VoiceResponse
    suspend fun resume(): VoiceResponse
    suspend fun skipForward(seconds: Int): VoiceResponse
    suspend fun skipBackward(seconds: Int): VoiceResponse
    suspend fun seekTo(positionMs: Int): VoiceResponse
    fun nextEpisode(): VoiceResponse
    fun setSpeed(speed: Double): VoiceResponse
    fun adjustSpeed(delta: Double): VoiceResponse
    fun setTrimMode(mode: String): VoiceResponse
    fun setVolumeBoost(enabled: Boolean): VoiceResponse
    fun setVolume(volume: Int): VoiceResponse
    fun adjustVolume(delta: Int): VoiceResponse
    fun sleepSet(minutes: Int): VoiceResponse
    fun sleepEndOfEpisode(): VoiceResponse
    fun sleepEndOfChapter(): VoiceResponse
    fun sleepAddTime(minutes: Int): VoiceResponse
    fun sleepCancel(): VoiceResponse
    fun queryEffectsState(): VoiceResponse.Spoken
    fun querySleepTimer(): VoiceResponse.Spoken
}

interface VoiceChapterSink {
    fun next(): VoiceResponse
    fun previous(): VoiceResponse
    fun byIndex(index: Int): VoiceResponse
    fun byTitle(query: String): VoiceResponse
    fun openLink(index: Int): VoiceResponse
    fun queryList(): VoiceResponse.Spoken
    fun queryCurrent(): VoiceResponse.Spoken
    fun queryCount(): VoiceResponse.Spoken
    fun queryNext(): VoiceResponse.Spoken
}

interface VoiceBookmarkSink {
    fun add(title: String?): VoiceResponse
    fun rename(ref: BookmarkRef, title: String): VoiceResponse
    fun play(ref: BookmarkRef): VoiceResponse
    fun delete(ref: BookmarkRef): VoiceResponse
    fun deleteAll(): VoiceResponse
    fun queryList(): VoiceResponse.Spoken
    fun queryCount(): VoiceResponse.Spoken
    fun queryNearby(): VoiceResponse.Spoken
}

interface VoiceQueueSink {
    fun addTop(episode: EpisodeRef): VoiceResponse
    fun addBottom(episode: EpisodeRef): VoiceResponse
    fun remove(episode: EpisodeRef): VoiceResponse
    fun moveToTop(episode: EpisodeRef): VoiceResponse
    fun moveToBottom(episode: EpisodeRef): VoiceResponse
    fun clear(): VoiceResponse
    fun removeByPodcast(podcast: PodcastRef): VoiceResponse
    fun sort(order: SortOrder): VoiceResponse
    fun queryContents(): VoiceResponse.Spoken
    fun queryNext(): VoiceResponse.Spoken
    fun queryLength(): VoiceResponse.Spoken
    fun queryIsQueued(episode: EpisodeRef): VoiceResponse.Spoken
}

interface VoicePodcastSink {
    fun subscribe(podcast: PodcastRef): VoiceResponse
    fun unsubscribe(podcast: PodcastRef): VoiceResponse
    fun downloadEpisode(episode: EpisodeRef): VoiceResponse
    fun deleteDownload(episode: EpisodeRef): VoiceResponse
    fun starEpisode(episode: EpisodeRef): VoiceResponse
    fun unstarEpisode(episode: EpisodeRef): VoiceResponse
    fun archiveEpisode(episode: EpisodeRef): VoiceResponse
    fun unarchiveEpisode(episode: EpisodeRef): VoiceResponse
    fun markPlayed(episode: EpisodeRef): VoiceResponse
    fun markUnplayed(episode: EpisodeRef): VoiceResponse
    fun removeFromHistory(episode: EpisodeRef): VoiceResponse
    fun bulkDownload(filter: EpisodeFilter): VoiceResponse
    fun bulkArchive(filter: EpisodeFilter): VoiceResponse
    fun bulkMarkPlayed(filter: EpisodeFilter): VoiceResponse
    fun addToPlaylist(episode: EpisodeRef, playlist: PlaylistRef): VoiceResponse
    fun createFolder(name: String): VoiceResponse
    fun renameFolder(folder: FolderRef, name: String): VoiceResponse
    fun assignToFolder(podcast: PodcastRef, folder: FolderRef): VoiceResponse
    fun removeFromFolder(podcast: PodcastRef): VoiceResponse
    fun deleteFolder(folder: FolderRef): VoiceResponse
    fun ratePodcast(podcast: PodcastRef, rating: Int): VoiceResponse
    fun toggleNotifications(podcast: PodcastRef, enabled: Boolean): VoiceResponse
    fun autoAddToQueue(podcast: PodcastRef, position: QueuePosition): VoiceResponse
    fun autoDownloadPodcast(podcast: PodcastRef, enabled: Boolean): VoiceResponse
}

interface VoicePlaylistSink {
    fun create(name: String?): VoiceResponse
    fun createSmart(rules: SmartRules, name: String?): VoiceResponse
    fun delete(playlist: PlaylistRef): VoiceResponse
    fun rename(playlist: PlaylistRef, name: String): VoiceResponse
    fun playAll(playlist: PlaylistRef, shuffle: Boolean): VoiceResponse
    fun downloadAll(playlist: PlaylistRef): VoiceResponse
    fun addEpisode(episode: EpisodeRef, playlist: PlaylistRef): VoiceResponse
    fun removeEpisode(episode: EpisodeRef, playlist: PlaylistRef): VoiceResponse
    fun archiveAll(playlist: PlaylistRef): VoiceResponse
    fun unarchiveAll(playlist: PlaylistRef): VoiceResponse
    fun autoDownload(playlist: PlaylistRef, enabled: Boolean): VoiceResponse
}

interface VoiceSearchSink {
    suspend fun search(query: String): VoiceResponse.Spoken
    suspend fun filterResults(type: ContentType?): VoiceResponse.Spoken
    suspend fun subscribeFromResult(ref: ResultRef): VoiceResponse
    suspend fun playFromResult(ref: ResultRef): VoiceResponse
    suspend fun describeResult(ref: ResultRef): VoiceResponse.Spoken
    suspend fun searchHistoryReRun(query: String?): VoiceResponse.Spoken
    suspend fun clearSearchHistory(): VoiceResponse
    suspend fun discoverTrending(): VoiceResponse.Spoken
    suspend fun discoverRecommendations(): VoiceResponse.Spoken
    suspend fun discoverCategory(category: String): VoiceResponse.Spoken
    suspend fun discoverNewReleases(timeframe: String?): VoiceResponse.Spoken
    suspend fun changeRegion(region: String): VoiceResponse
}

interface VoiceContentQuerySink {
    fun whatsPlaying(): VoiceResponse.Spoken
    fun position(): VoiceResponse.Spoken
    fun timeRemaining(): VoiceResponse.Spoken
    fun currentPodcast(): VoiceResponse.Spoken
    fun episodeDuration(): VoiceResponse.Spoken
    fun publishDate(): VoiceResponse.Spoken
    fun episodeDescription(): VoiceResponse.Spoken
    fun downloadStatus(): VoiceResponse.Spoken
    fun episodeTitle(): VoiceResponse.Spoken
    fun listeningTime(period: String?): VoiceResponse.Spoken
    fun topPodcasts(): VoiceResponse.Spoken
    fun episodesFinished(period: String?): VoiceResponse.Spoken
    fun listeningStreak(): VoiceResponse.Spoken
    fun subscriptionCount(): VoiceResponse.Spoken
    fun unplayedTotal(): VoiceResponse.Spoken
    fun downloadStats(): VoiceResponse.Spoken
    fun queueTotal(): VoiceResponse.Spoken
    fun newEpisodes(timeframe: String?): VoiceResponse.Spoken
    fun timeSinceLastListen(): VoiceResponse.Spoken
}

interface VoiceTranscriptSink {
    fun open(): VoiceResponse
    fun search(term: String): VoiceResponse.Spoken
    fun navigate(direction: NavigationDirection): VoiceResponse.Spoken
    fun seekToTopic(topic: String): VoiceResponse
    fun readLine(): VoiceResponse.Spoken
    fun queryTopic(topic: String): VoiceResponse.Spoken
    fun seekToQuote(quote: String): VoiceResponse
    fun readSection(start: String?, end: String?): VoiceResponse.Spoken
}

interface VoiceAssistantSink {
    suspend fun askEpisode(question: String): VoiceResponse.Spoken
    suspend fun summarizeEpisode(): VoiceResponse.Spoken
    suspend fun queryEpisodeContent(question: String): VoiceResponse.Spoken
    fun jumpToTopic(topic: String): VoiceResponse
    fun playQuote(ref: String?): VoiceResponse
    fun stopQuote(): VoiceResponse
    fun retryFailedMessage(): VoiceResponse
    fun clearChat(): VoiceResponse
    fun castToDevice(device: String?): VoiceResponse.Spoken
    fun stopCasting(): VoiceResponse
    fun sendGuestPass(): VoiceResponse
    fun claimGuestPass(): VoiceResponse
    fun viewStories(): VoiceResponse
    fun nextStory(): VoiceResponse
    fun previousStory(): VoiceResponse
    fun shareStory(): VoiceResponse
    fun replayStories(): VoiceResponse
}

interface VoiceSettingsSink {
    fun setTheme(theme: String): VoiceResponse
    fun setAutoDownloadUpNext(enabled: Boolean): VoiceResponse
    fun setAutoDownloadNew(enabled: Boolean): VoiceResponse
    fun setAutoDownloadOnFollow(enabled: Boolean): VoiceResponse
    fun setWifiOnly(enabled: Boolean): VoiceResponse
    fun setChargingOnly(enabled: Boolean): VoiceResponse
    fun setPodcastAutoDownload(podcast: PodcastRef, enabled: Boolean): VoiceResponse
    fun stopAllDownloads(): VoiceResponse
    fun clearDownloadErrors(): VoiceResponse
    fun setDownloadLimit(count: Int): VoiceResponse
    fun setNextTrackAction(action: TrackAction): VoiceResponse
    fun setPreviousTrackAction(action: TrackAction): VoiceResponse
    fun setConfirmationSound(enabled: Boolean): VoiceResponse
    fun setAutoAdd(podcast: PodcastRef, enabled: Boolean): VoiceResponse
    fun setAutoAddPosition(position: QueuePosition): VoiceResponse
    fun setAutoAddLimit(count: Int): VoiceResponse
    fun setArchiveAfterPlaying(delay: String): VoiceResponse
    fun setArchiveInactive(period: String): VoiceResponse
    fun setIncludeStarredAutoArchive(enabled: Boolean): VoiceResponse
    fun setNotifications(enabled: Boolean): VoiceResponse
    fun setPodcastNotifications(podcast: PodcastRef, enabled: Boolean): VoiceResponse
    fun manualCleanup(): VoiceResponse
    fun exportOpml(): VoiceResponse
}

interface VoiceAccountSink {
    suspend fun signInEmail(email: String?, password: String?): VoiceResponse.Spoken
    suspend fun signInGoogle(): VoiceResponse.Spoken
    suspend fun createAccount(email: String, password: String, newsletter: Boolean?): VoiceResponse.Spoken
    suspend fun changeEmail(newEmail: String, password: String): VoiceResponse.Spoken
    suspend fun changePassword(current: String, new: String): VoiceResponse.Spoken
    suspend fun resetPassword(email: String?): VoiceResponse.Spoken
    suspend fun redeemPromoCode(code: String?): VoiceResponse.Spoken
    fun signOut(): VoiceResponse
    fun changePlan(plan: String): VoiceResponse
    suspend fun claimOffer(offer: String?): VoiceResponse.Spoken
    fun cancelSubscription(): VoiceResponse
    fun keepSubscription(): VoiceResponse.Spoken
}

interface VoiceSharingSink {
    fun shareEpisode(episode: EpisodeRef?): VoiceResponse
    fun shareAtCurrentTime(): VoiceResponse
    fun shareAtTime(time: String): VoiceResponse
    fun sharePodcast(podcast: PodcastRef?): VoiceResponse
    fun shareClip(start: String?, end: String?): VoiceResponse
    fun shareBookmark(ref: BookmarkRef): VoiceResponse
    fun shareTranscript(section: String?): VoiceResponse
    fun createSharedList(name: String, podcasts: List<PodcastRef>): VoiceResponse
    fun shareViaApp(app: String): VoiceResponse
    fun acceptSharedList(mode: AcceptMode): VoiceResponse.Spoken
}
```

## Executor — `VoiceIntentExecutor`

The executor dispatches by intent domain to the correct sink. Each branch is exhaustive within its domain.

```kotlin
class VoiceIntentExecutor @Inject constructor(
    private val playbackSink: VoicePlaybackSink,
    private val chapterSink: VoiceChapterSink,
    private val bookmarkSink: VoiceBookmarkSink,
    private val queueSink: VoiceQueueSink,
    private val podcastSink: VoicePodcastSink,
    private val playlistSink: VoicePlaylistSink,
    private val searchSink: VoiceSearchSink,
    private val contentQuerySink: VoiceContentQuerySink,
    private val transcriptSink: VoiceTranscriptSink,
    private val assistantSink: VoiceAssistantSink,
    private val settingsSink: VoiceSettingsSink,
    private val accountSink: VoiceAccountSink,
    private val sharingSink: VoiceSharingSink,
) {
    suspend fun execute(intent: VoiceIntent): VoiceResponse? = when (intent) {
        is PlaybackIntent -> executePlayback(intent)
        is ChapterIntent -> executeChapter(intent)
        is BookmarkIntent -> executeBookmark(intent)
        is QueueIntent -> executeQueue(intent)
        is PodcastIntent -> executePodcast(intent)
        is PlaylistIntent -> executePlaylist(intent)
        is SearchIntent -> executeSearch(intent)
        is ContentQueryIntent -> executeContentQuery(intent)
        is TranscriptIntent -> executeTranscript(intent)
        is AssistantIntent -> executeAssistant(intent)
        is SettingsIntent -> executeSettings(intent)
        is AccountIntent -> executeAccount(intent)
        is SharingIntent -> executeSharing(intent)
    }

    private suspend fun executePlayback(intent: PlaybackIntent): VoiceResponse = when (intent) {
        is PlaybackIntent.Pause -> playbackSink.pause()
        is PlaybackIntent.Resume -> playbackSink.resume()
        is PlaybackIntent.SeekRelative -> if (intent.deltaMs >= 0)
            playbackSink.skipForward(intent.deltaMs / 1000)
        else
            playbackSink.skipBackward(-intent.deltaMs / 1000)
        is PlaybackIntent.SeekAbsolute -> playbackSink.seekTo(intent.positionMs.coerceAtLeast(0))
        is PlaybackIntent.NextEpisode -> playbackSink.nextEpisode()
        is PlaybackIntent.SetSpeed -> playbackSink.setSpeed(intent.speed)
        is PlaybackIntent.AdjustSpeed -> playbackSink.adjustSpeed(intent.delta)
        is PlaybackIntent.SetTrimMode -> playbackSink.setTrimMode(intent.mode)
        is PlaybackIntent.SetVolumeBoost -> playbackSink.setVolumeBoost(intent.enabled)
        is PlaybackIntent.SetVolume -> playbackSink.setVolume(intent.volume)
        is PlaybackIntent.AdjustVolume -> playbackSink.adjustVolume(intent.delta)
        is PlaybackIntent.SleepSet -> playbackSink.sleepSet(intent.minutes)
        is PlaybackIntent.SleepEndOfEpisode -> playbackSink.sleepEndOfEpisode()
        is PlaybackIntent.SleepEndOfChapter -> playbackSink.sleepEndOfChapter()
        is PlaybackIntent.SleepAddTime -> playbackSink.sleepAddTime(intent.minutes)
        is PlaybackIntent.SleepCancel -> playbackSink.sleepCancel()
        is PlaybackIntent.QueryEffectsState -> playbackSink.queryEffectsState()
        is PlaybackIntent.QuerySleepTimer -> playbackSink.querySleepTimer()
    }

    // ... equivalent private dispatch methods per domain
}
```

## Analytics

Tag all voice-initiated actions with `SourceView.VOICE_COMMANDS`. Each domain sink implementation records domain-specific analytics (e.g. `PLAYBACK_SPEED_CHANGED`, `BOOKMARK_CREATED`, `QUEUE_EPISODE_ADDED`) using the voice source view.

## Multi-Turn and Confirmation

Some intents require multi-turn slot filling (account sign-in) or explicit confirmation before execution (queue clear, bulk actions, sign out). These flows are owned by the dialog layer, not this spec. The intent is only dispatched once all slots are filled and confirmations are obtained.

The confirmation flow produces a `VoiceIntent` only when confirmed — a denied confirmation produces no intent. The executor never sees cancelled flows.
