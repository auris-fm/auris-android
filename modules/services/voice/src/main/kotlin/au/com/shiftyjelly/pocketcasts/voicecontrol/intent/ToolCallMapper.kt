package au.com.shiftyjelly.pocketcasts.voicecontrol.intent

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolCallMapper @Inject constructor() {

    fun map(call: ToolCall): VoiceIntent? {
        if (call.name == "no_match") return null
        return when (call.name) {
            "playback" -> mapPlayback(call.action, call)
            "effects" -> mapEffects(call.action, call)
            "volume" -> mapVolume(call.action, call)
            "sleep" -> mapSleep(call.action, call)
            "chapter" -> mapChapter(call.action, call)
            "bookmark" -> mapBookmark(call.action, call)
            "queue" -> mapQueue(call.action, call)
            "episode" -> mapEpisode(call.action, call)
            "podcast" -> mapPodcast(call.action, call)
            "bulk" -> mapBulk(call.action, call)
            "folder" -> mapFolder(call.action, call)
            "playlist" -> mapPlaylist(call.action, call)
            "search" -> mapSearch(call.action, call)
            "playback_query" -> mapPlaybackQuery(call.action)
            "stats_query" -> mapStatsQuery(call.action, call)
            "transcript" -> mapTranscript(call.action, call)
            "assistant" -> mapAssistant(call.action, call)
            "cast" -> mapCast(call.action, call)
            "stories" -> mapStories(call.action)
            "guest_pass" -> mapGuestPass(call.action)
            "download_settings" -> mapDownloadSettings(call.action, call)
            "playback_settings" -> mapPlaybackSettings(call.action, call)
            "app_settings" -> mapAppSettings(call.action, call)
            "account" -> mapAccount(call.action, call)
            "sharing" -> mapSharing(call.action, call)
            else -> null
        }
    }

    private fun mapPlayback(action: String, call: ToolCall): VoiceIntent.Playback? = when (action) {
        "pause" -> VoiceIntent.Playback.Pause

        "resume" -> VoiceIntent.Playback.Resume

        "seek_relative" -> {
            val seconds = call.intParam("seconds") ?: return null
            VoiceIntent.Playback.SeekRelative(seconds * 1000)
        }

        "seek_to" -> {
            val seconds = call.intParam("seconds") ?: return null
            VoiceIntent.Playback.SeekAbsolute(seconds * 1000)
        }

        "next_episode" -> VoiceIntent.Playback.NextEpisode

        else -> null
    }

    private fun mapEffects(action: String, call: ToolCall): VoiceIntent.Effects? = when (action) {
        "set_speed" -> {
            val speed = call.doubleParam("speed") ?: return null
            VoiceIntent.Effects.SetSpeed(speed)
        }

        "adjust_speed" -> {
            val delta = call.doubleParam("delta") ?: return null
            VoiceIntent.Effects.AdjustSpeed(delta)
        }

        "set_trim_mode" -> {
            val mode = call.stringParam("mode") ?: return null
            VoiceIntent.Effects.SetTrimMode(mode)
        }

        "set_volume_boost" -> {
            val enabled = call.boolParam("enabled") ?: return null
            VoiceIntent.Effects.SetVolumeBoost(enabled)
        }

        "query_effects" -> VoiceIntent.Effects.QueryEffects

        else -> null
    }

    private fun mapVolume(action: String, call: ToolCall): VoiceIntent.Volume? = when (action) {
        "set_volume" -> {
            val volume = call.intParam("volume") ?: return null
            VoiceIntent.Volume.SetVolume(volume)
        }

        "adjust_volume" -> {
            val delta = call.intParam("delta") ?: return null
            VoiceIntent.Volume.AdjustVolume(delta)
        }

        "query" -> VoiceIntent.Volume.Query

        else -> null
    }

    private fun mapSleep(action: String, call: ToolCall): VoiceIntent.Sleep? = when (action) {
        "set" -> {
            val minutes = call.intParam("minutes") ?: return null
            VoiceIntent.Sleep.Set(minutes)
        }

        "end_of_episode" -> VoiceIntent.Sleep.EndOfEpisode

        "end_of_chapter" -> VoiceIntent.Sleep.EndOfChapter

        "add_time" -> {
            val minutes = call.intParam("minutes") ?: return null
            VoiceIntent.Sleep.AddTime(minutes)
        }

        "cancel" -> VoiceIntent.Sleep.Cancel

        "query" -> VoiceIntent.Sleep.QuerySleep

        else -> null
    }

    private fun mapChapter(action: String, call: ToolCall): VoiceIntent.Chapter? = when (action) {
        "next" -> VoiceIntent.Chapter.NextChapter

        "previous" -> VoiceIntent.Chapter.PreviousChapter

        "by_index" -> {
            val index = call.intParam("index") ?: return null
            VoiceIntent.Chapter.ByIndex(index)
        }

        "by_title" -> {
            val query = call.stringParam("query") ?: return null
            VoiceIntent.Chapter.ByTitle(query)
        }

        "open_link" -> {
            val index = call.intParam("index") ?: return null
            VoiceIntent.Chapter.OpenLink(index)
        }

        "query_list" -> VoiceIntent.Chapter.QueryList

        "query_current" -> VoiceIntent.Chapter.QueryCurrent

        "query_count" -> VoiceIntent.Chapter.QueryCount

        "query_next" -> VoiceIntent.Chapter.QueryNext

        else -> null
    }

    private fun mapBookmark(action: String, call: ToolCall): VoiceIntent.Bookmark? = when (action) {
        "add" -> VoiceIntent.Bookmark.Add(call.stringParam("title"))

        "rename" -> {
            val ref = call.stringParam("ref") ?: return null
            val title = call.stringParam("title") ?: return null
            VoiceIntent.Bookmark.Rename(ref, title)
        }

        "play" -> {
            val ref = call.stringParam("ref") ?: return null
            VoiceIntent.Bookmark.Play(ref)
        }

        "delete" -> {
            val ref = call.stringParam("ref") ?: return null
            VoiceIntent.Bookmark.Delete(ref)
        }

        "delete_all" -> VoiceIntent.Bookmark.DeleteAll

        "query_list" -> VoiceIntent.Bookmark.QueryBookmarkList

        "query_count" -> VoiceIntent.Bookmark.QueryBookmarkCount

        "query_nearby" -> VoiceIntent.Bookmark.QueryNearby

        else -> null
    }

    private fun mapQueue(action: String, call: ToolCall): VoiceIntent.Queue? = when (action) {
        "add_top" -> VoiceIntent.Queue.AddTop(call.stringParam("episode"))

        "add_bottom" -> VoiceIntent.Queue.AddBottom(call.stringParam("episode"))

        "remove" -> {
            val episode = call.stringParam("episode") ?: return null
            VoiceIntent.Queue.Remove(episode)
        }

        "move_to_top" -> {
            val episode = call.stringParam("episode") ?: return null
            VoiceIntent.Queue.MoveToTop(episode)
        }

        "move_to_bottom" -> {
            val episode = call.stringParam("episode") ?: return null
            VoiceIntent.Queue.MoveToBottom(episode)
        }

        "clear" -> VoiceIntent.Queue.Clear

        "remove_by_podcast" -> {
            val podcast = call.stringParam("podcast") ?: return null
            VoiceIntent.Queue.RemoveByPodcast(podcast)
        }

        "sort" -> {
            val order = call.stringParam("sort_order") ?: return null
            VoiceIntent.Queue.Sort(order)
        }

        "query_contents" -> VoiceIntent.Queue.QueryContents

        "query_next" -> VoiceIntent.Queue.QueryQueueNext

        "query_length" -> VoiceIntent.Queue.QueryLength

        "query_is_queued" -> {
            val episode = call.stringParam("episode") ?: return null
            VoiceIntent.Queue.QueryIsQueued(episode)
        }

        else -> null
    }

    private fun mapEpisode(action: String, call: ToolCall): VoiceIntent.Episode? {
        val episode = call.stringParam("episode")
        return when (action) {
            "download" -> VoiceIntent.Episode.Download(episode ?: return null)

            "delete_download" -> VoiceIntent.Episode.DeleteDownload(episode ?: return null)

            "star" -> VoiceIntent.Episode.Star(episode ?: return null)

            "unstar" -> VoiceIntent.Episode.Unstar(episode ?: return null)

            "archive" -> VoiceIntent.Episode.Archive(episode ?: return null)

            "unarchive" -> VoiceIntent.Episode.Unarchive(episode ?: return null)

            "mark_played" -> VoiceIntent.Episode.MarkPlayed(episode ?: return null)

            "mark_unplayed" -> VoiceIntent.Episode.MarkUnplayed(episode ?: return null)

            "remove_from_history" -> VoiceIntent.Episode.RemoveFromHistory(episode ?: return null)

            "add_to_playlist" -> {
                val playlist = call.stringParam("playlist") ?: return null
                VoiceIntent.Episode.AddToPlaylist(episode ?: return null, playlist)
            }

            else -> null
        }
    }

    private fun mapPodcast(action: String, call: ToolCall): VoiceIntent.Podcast? {
        val podcast = call.stringParam("podcast")
        return when (action) {
            "subscribe" -> VoiceIntent.Podcast.Subscribe(podcast ?: return null)

            "unsubscribe" -> VoiceIntent.Podcast.Unsubscribe(podcast ?: return null)

            "rate" -> {
                val rating = call.intParam("rating") ?: return null
                VoiceIntent.Podcast.Rate(podcast ?: return null, rating)
            }

            "toggle_notifications" -> VoiceIntent.Podcast.ToggleNotifications(
                podcast ?: return null,
                call.boolParam("enabled"),
            )

            "auto_add" -> VoiceIntent.Podcast.AutoAdd(podcast ?: return null, call.stringParam("position"))

            "auto_download" -> VoiceIntent.Podcast.AutoDownload(
                podcast ?: return null,
                call.boolParam("enabled") ?: return null,
            )

            else -> null
        }
    }

    private fun mapBulk(action: String, call: ToolCall): VoiceIntent.Bulk? = when (action) {
        "download" -> VoiceIntent.Bulk.BulkDownload(call.stringParam("podcast"), call.stringParam("filter"))
        "archive" -> VoiceIntent.Bulk.BulkArchive(call.stringParam("podcast"), call.stringParam("filter"))
        "mark_played" -> VoiceIntent.Bulk.BulkMarkPlayed(call.stringParam("podcast"), call.stringParam("filter"))
        else -> null
    }

    private fun mapFolder(action: String, call: ToolCall): VoiceIntent.Folder? = when (action) {
        "create" -> VoiceIntent.Folder.Create(call.stringParam("name") ?: return null)

        "rename" -> VoiceIntent.Folder.Rename(
            call.stringParam("folder") ?: return null,
            call.stringParam("name") ?: return null,
        )

        "assign" -> VoiceIntent.Folder.Assign(
            call.stringParam("podcast") ?: return null,
            call.stringParam("folder") ?: return null,
        )

        "remove_from" -> VoiceIntent.Folder.RemoveFrom(call.stringParam("podcast") ?: return null)

        "delete" -> VoiceIntent.Folder.Delete(call.stringParam("folder") ?: return null)

        else -> null
    }

    private fun mapPlaylist(action: String, call: ToolCall): VoiceIntent.Playlist? = when (action) {
        "create" -> VoiceIntent.Playlist.Create(call.stringParam("name"))

        "create_smart" -> VoiceIntent.Playlist.CreateSmart(
            call.stringParam("rules") ?: return null,
            call.stringParam("name"),
        )

        "delete" -> VoiceIntent.Playlist.Delete(call.stringParam("playlist") ?: return null)

        "rename" -> VoiceIntent.Playlist.Rename(
            call.stringParam("playlist") ?: return null,
            call.stringParam("name") ?: return null,
        )

        "play_all" -> VoiceIntent.Playlist.PlayAll(
            call.stringParam("playlist") ?: return null,
            call.boolParam("shuffle") ?: false,
        )

        "download_all" -> VoiceIntent.Playlist.DownloadAll(call.stringParam("playlist") ?: return null)

        "add_episode" -> VoiceIntent.Playlist.AddEpisode(
            call.stringParam("episode") ?: return null,
            call.stringParam("playlist") ?: return null,
        )

        "remove_episode" -> VoiceIntent.Playlist.RemoveEpisode(
            call.stringParam("episode") ?: return null,
            call.stringParam("playlist") ?: return null,
        )

        "archive_all" -> VoiceIntent.Playlist.ArchiveAll(call.stringParam("playlist") ?: return null)

        "unarchive_all" -> VoiceIntent.Playlist.UnarchiveAll(call.stringParam("playlist") ?: return null)

        "auto_download" -> VoiceIntent.Playlist.AutoDownload(
            call.stringParam("playlist") ?: return null,
            call.boolParam("enabled") ?: return null,
        )

        else -> null
    }

    private fun mapSearch(action: String, call: ToolCall): VoiceIntent.Search? = when (action) {
        "search" -> VoiceIntent.Search.PerformSearch(call.stringParam("query") ?: return null)
        "filter" -> VoiceIntent.Search.Filter(call.stringParam("type"))
        "subscribe_result" -> VoiceIntent.Search.SubscribeResult(call.stringParam("ref") ?: return null)
        "play_result" -> VoiceIntent.Search.PlayResult(call.stringParam("ref") ?: return null)
        "describe_result" -> VoiceIntent.Search.DescribeResult(call.stringParam("ref") ?: return null)
        "rerun" -> VoiceIntent.Search.Rerun(call.stringParam("query"))
        "clear_history" -> VoiceIntent.Search.ClearHistory
        "trending" -> VoiceIntent.Search.Trending
        "recommendations" -> VoiceIntent.Search.Recommendations
        "category" -> VoiceIntent.Search.Category(call.stringParam("category") ?: return null)
        "new_releases" -> VoiceIntent.Search.NewReleases(call.stringParam("timeframe"))
        "change_region" -> VoiceIntent.Search.ChangeRegion(call.stringParam("region") ?: return null)
        else -> null
    }

    private fun mapPlaybackQuery(action: String): VoiceIntent.PlaybackQuery? = when (action) {
        "whats_playing" -> VoiceIntent.PlaybackQuery.WhatsPlaying
        "position" -> VoiceIntent.PlaybackQuery.Position
        "time_remaining" -> VoiceIntent.PlaybackQuery.TimeRemaining
        "current_podcast" -> VoiceIntent.PlaybackQuery.CurrentPodcast
        "episode_duration" -> VoiceIntent.PlaybackQuery.EpisodeDuration
        "publish_date" -> VoiceIntent.PlaybackQuery.PublishDate
        "episode_description" -> VoiceIntent.PlaybackQuery.EpisodeDescription
        "download_status" -> VoiceIntent.PlaybackQuery.DownloadStatus
        "episode_title" -> VoiceIntent.PlaybackQuery.EpisodeTitle
        else -> null
    }

    private fun mapStatsQuery(action: String, call: ToolCall): VoiceIntent.StatsQuery? = when (action) {
        "listening_time" -> VoiceIntent.StatsQuery.ListeningTime(call.stringParam("period"))
        "top_podcasts" -> VoiceIntent.StatsQuery.TopPodcasts(call.stringParam("period"))
        "episodes_finished" -> VoiceIntent.StatsQuery.EpisodesFinished(call.stringParam("period"))
        "listening_streak" -> VoiceIntent.StatsQuery.ListeningStreak
        "subscription_count" -> VoiceIntent.StatsQuery.SubscriptionCount
        "unplayed_total" -> VoiceIntent.StatsQuery.UnplayedTotal
        "download_stats" -> VoiceIntent.StatsQuery.DownloadStats
        "queue_total" -> VoiceIntent.StatsQuery.QueueTotal
        "new_episodes" -> VoiceIntent.StatsQuery.NewEpisodes(call.stringParam("timeframe"))
        "time_since_last_listen" -> VoiceIntent.StatsQuery.TimeSinceLastListen
        else -> null
    }

    private fun mapTranscript(action: String, call: ToolCall): VoiceIntent.Transcript? = when (action) {
        "open" -> VoiceIntent.Transcript.Open
        "search" -> VoiceIntent.Transcript.Search(call.stringParam("term") ?: return null)
        "navigate" -> VoiceIntent.Transcript.Navigate(call.stringParam("direction") ?: return null)
        "seek_to_topic" -> VoiceIntent.Transcript.SeekToTopic(call.stringParam("topic") ?: return null)
        "read_line" -> VoiceIntent.Transcript.ReadLine
        "query_topic" -> VoiceIntent.Transcript.QueryTopic(call.stringParam("topic") ?: return null)
        "seek_to_quote" -> VoiceIntent.Transcript.SeekToQuote(call.stringParam("quote") ?: return null)
        "read_section" -> VoiceIntent.Transcript.ReadSection(call.stringParam("start"), call.stringParam("end"))
        else -> null
    }

    private fun mapAssistant(action: String, call: ToolCall): VoiceIntent.Assistant? = when (action) {
        "ask" -> VoiceIntent.Assistant.Ask(call.stringParam("question") ?: return null)
        "summarize" -> VoiceIntent.Assistant.Summarize
        "query_content" -> VoiceIntent.Assistant.QueryContent(call.stringParam("question") ?: return null)
        "jump_to_topic" -> VoiceIntent.Assistant.JumpToTopic(call.stringParam("topic") ?: return null)
        "play_quote" -> VoiceIntent.Assistant.PlayQuote(call.stringParam("ref"))
        "stop_quote" -> VoiceIntent.Assistant.StopQuote
        "retry" -> VoiceIntent.Assistant.Retry
        "clear_chat" -> VoiceIntent.Assistant.ClearChat
        else -> null
    }

    private fun mapCast(action: String, call: ToolCall): VoiceIntent.Cast? = when (action) {
        "start" -> VoiceIntent.Cast.Start(call.stringParam("device"))
        "stop" -> VoiceIntent.Cast.Stop
        else -> null
    }

    private fun mapStories(action: String): VoiceIntent.Stories? = when (action) {
        "view" -> VoiceIntent.Stories.View
        "next" -> VoiceIntent.Stories.NextStory
        "previous" -> VoiceIntent.Stories.PreviousStory
        "share" -> VoiceIntent.Stories.Share
        "replay" -> VoiceIntent.Stories.Replay
        else -> null
    }

    private fun mapGuestPass(action: String): VoiceIntent.GuestPass? = when (action) {
        "send" -> VoiceIntent.GuestPass.Send
        "claim" -> VoiceIntent.GuestPass.Claim
        else -> null
    }

    private fun mapDownloadSettings(action: String, call: ToolCall): VoiceIntent.DownloadSettings? = when (action) {
        "auto_download_up_next" -> VoiceIntent.DownloadSettings.AutoDownloadUpNext(call.boolParam("enabled") ?: return null)

        "auto_download_new" -> VoiceIntent.DownloadSettings.AutoDownloadNew(call.boolParam("enabled") ?: return null)

        "auto_download_on_follow" -> VoiceIntent.DownloadSettings.AutoDownloadOnFollow(call.boolParam("enabled") ?: return null)

        "wifi_only" -> VoiceIntent.DownloadSettings.WifiOnly(call.boolParam("enabled") ?: return null)

        "charging_only" -> VoiceIntent.DownloadSettings.ChargingOnly(call.boolParam("enabled") ?: return null)

        "podcast_auto_download" -> VoiceIntent.DownloadSettings.PodcastAutoDownload(
            call.stringParam("podcast") ?: return null,
            call.boolParam("enabled") ?: return null,
        )

        "stop_all_downloads" -> VoiceIntent.DownloadSettings.StopAllDownloads

        "clear_errors" -> VoiceIntent.DownloadSettings.ClearErrors

        "download_limit" -> VoiceIntent.DownloadSettings.DownloadLimit(call.intParam("count") ?: return null)

        else -> null
    }

    private fun mapPlaybackSettings(action: String, call: ToolCall): VoiceIntent.PlaybackSettings? = when (action) {
        "next_track_action" -> VoiceIntent.PlaybackSettings.NextTrackAction(call.stringParam("track_action") ?: return null)
        "previous_track_action" -> VoiceIntent.PlaybackSettings.PreviousTrackAction(call.stringParam("track_action") ?: return null)
        "confirmation_sound" -> VoiceIntent.PlaybackSettings.ConfirmationSound(call.boolParam("enabled") ?: return null)
        "auto_add" -> VoiceIntent.PlaybackSettings.AutoAdd(call.stringParam("podcast"))
        "auto_add_position" -> VoiceIntent.PlaybackSettings.AutoAddPosition(call.stringParam("position") ?: return null)
        "auto_add_limit" -> VoiceIntent.PlaybackSettings.AutoAddLimit(call.intParam("count") ?: return null)
        "archive_after_playing" -> VoiceIntent.PlaybackSettings.ArchiveAfterPlaying(call.stringParam("delay") ?: return null)
        "archive_inactive" -> VoiceIntent.PlaybackSettings.ArchiveInactive(call.stringParam("period") ?: return null)
        "include_starred_auto_archive" -> VoiceIntent.PlaybackSettings.IncludeStarredAutoArchive(call.boolParam("enabled") ?: return null)
        else -> null
    }

    private fun mapAppSettings(action: String, call: ToolCall): VoiceIntent.AppSettings? = when (action) {
        "set_theme" -> VoiceIntent.AppSettings.SetTheme(call.stringParam("theme") ?: return null)

        "notifications" -> VoiceIntent.AppSettings.Notifications(call.boolParam("enabled") ?: return null)

        "podcast_notifications" -> VoiceIntent.AppSettings.PodcastNotifications(
            call.stringParam("podcast") ?: return null,
            call.boolParam("enabled") ?: return null,
        )

        "manual_cleanup" -> VoiceIntent.AppSettings.ManualCleanup

        "export_opml" -> VoiceIntent.AppSettings.ExportOpml

        else -> null
    }

    private fun mapAccount(action: String, call: ToolCall): VoiceIntent.Account? = when (action) {
        "sign_in_email" -> VoiceIntent.Account.SignInEmail(
            call.stringParam("email") ?: return null,
            call.stringParam("password") ?: return null,
        )

        "sign_in_google" -> VoiceIntent.Account.SignInGoogle

        "create_account" -> VoiceIntent.Account.CreateAccount(
            call.stringParam("email") ?: return null,
            call.stringParam("password") ?: return null,
            call.boolParam("newsletter") ?: false,
        )

        "change_email" -> VoiceIntent.Account.ChangeEmail(
            call.stringParam("new_email") ?: return null,
            call.stringParam("password") ?: return null,
        )

        "change_password" -> VoiceIntent.Account.ChangePassword(
            call.stringParam("current_password") ?: return null,
            call.stringParam("new_password") ?: return null,
        )

        "reset_password" -> VoiceIntent.Account.ResetPassword(call.stringParam("email"))

        "redeem_promo" -> VoiceIntent.Account.RedeemPromo(call.stringParam("code"))

        "sign_out" -> VoiceIntent.Account.SignOut

        "change_plan" -> VoiceIntent.Account.ChangePlan(call.stringParam("plan") ?: return null)

        "claim_offer" -> VoiceIntent.Account.ClaimOffer(call.stringParam("offer"))

        "cancel_subscription" -> VoiceIntent.Account.CancelSubscription

        "keep_subscription" -> VoiceIntent.Account.KeepSubscription

        else -> null
    }

    private fun mapSharing(action: String, call: ToolCall): VoiceIntent.Sharing? = when (action) {
        "share_episode" -> VoiceIntent.Sharing.ShareEpisode(call.stringParam("episode"))

        "share_at_current_time" -> VoiceIntent.Sharing.ShareAtCurrentTime

        "share_at_time" -> VoiceIntent.Sharing.ShareAtTime(call.stringParam("time") ?: return null)

        "share_podcast" -> VoiceIntent.Sharing.SharePodcast(call.stringParam("podcast"))

        "share_clip" -> VoiceIntent.Sharing.ShareClip(
            call.stringParam("start") ?: return null,
            call.stringParam("end") ?: return null,
        )

        "share_bookmark" -> VoiceIntent.Sharing.ShareBookmark(call.stringParam("bookmark") ?: return null)

        "share_transcript" -> VoiceIntent.Sharing.ShareTranscript(call.stringParam("section"))

        "create_shared_list" -> VoiceIntent.Sharing.CreateSharedList(
            call.stringParam("list_name") ?: return null,
            call.stringParam("podcasts"),
        )

        "share_via_app" -> VoiceIntent.Sharing.ShareViaApp(call.stringParam("episode"), call.stringParam("app"))

        "accept_shared_list" -> VoiceIntent.Sharing.AcceptSharedList(call.stringParam("accept_mode"))

        else -> null
    }
}
