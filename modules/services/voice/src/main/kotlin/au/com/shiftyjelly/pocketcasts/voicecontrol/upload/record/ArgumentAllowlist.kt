package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record

import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent

/**
 * Deny-by-default argument allowlist (spec: "each intent's argument fields require an allowlist
 * before inclusion"). Only TRAINING-RELEVANT, non-private enumerated fields pass; anything not
 * listed here is NEVER serialized. Missing authorization still records category + disposition —
 * the caller includes `intent` and `disposition` regardless of what the allowlist drops.
 *
 * Excluded by construction everywhere: free-text queries/titles/episode names (private content),
 * CloudRoute.request/context/windowGeneration (conversation payload + internal authority), raw
 * exception strings, tokens/URLs/cookies (never sourced into this layer at all).
 */
object ArgumentAllowlist {

    /** Field name -> canonical value, or empty when NOTHING for this intent may be included. */
    fun allowedArgs(intent: VoiceIntent): Map<String, RecordNode> = when (intent) {
        // Parameterless categories: category alone is the training signal.
        VoiceIntent.Playback.Pause, VoiceIntent.Playback.Resume, VoiceIntent.Playback.NextEpisode,
        VoiceIntent.Effects.QueryEffects,
        VoiceIntent.Volume.Query,
        VoiceIntent.Sleep.EndOfEpisode, VoiceIntent.Sleep.EndOfChapter, VoiceIntent.Sleep.Cancel,
        VoiceIntent.Sleep.QuerySleep,
        VoiceIntent.Chapter.NextChapter, VoiceIntent.Chapter.PreviousChapter,
        VoiceIntent.Chapter.QueryList, VoiceIntent.Chapter.QueryCurrent,
        VoiceIntent.Chapter.QueryCount, VoiceIntent.Chapter.QueryNext,
        VoiceIntent.Bookmark.DeleteAll, VoiceIntent.Bookmark.QueryBookmarkList,
        VoiceIntent.Bookmark.QueryBookmarkCount, VoiceIntent.Bookmark.QueryNearby,
        VoiceIntent.Queue.Clear, VoiceIntent.Queue.QueryContents, VoiceIntent.Queue.QueryQueueNext,
        VoiceIntent.Queue.QueryLength,
        VoiceIntent.PlaybackQuery.WhatsPlaying, VoiceIntent.PlaybackQuery.Position,
        VoiceIntent.PlaybackQuery.TimeRemaining, VoiceIntent.PlaybackQuery.CurrentPodcast,
        VoiceIntent.PlaybackQuery.EpisodeDuration, VoiceIntent.PlaybackQuery.PublishDate,
        VoiceIntent.PlaybackQuery.EpisodeDescription, VoiceIntent.PlaybackQuery.DownloadStatus,
        VoiceIntent.PlaybackQuery.EpisodeTitle,
        VoiceIntent.StatsQuery.ListeningStreak, VoiceIntent.StatsQuery.SubscriptionCount,
        VoiceIntent.StatsQuery.UnplayedTotal, VoiceIntent.StatsQuery.DownloadStats,
        VoiceIntent.StatsQuery.QueueTotal, VoiceIntent.StatsQuery.TimeSinceLastListen,
        -> emptyMap()

        // Numeric-only parameters are training-relevant and non-private.
        is VoiceIntent.Playback.SeekRelative -> buildMap {
            intent.deltaSeconds?.let { put("delta_seconds", RecordNode.Num(it.toDouble())) }
            // direction is a closed enum-like token, not free text; include only known values.
            when (intent.direction) {
                "forward", "backward" -> put("direction", RecordNode.Str(intent.direction))
                else -> Unit // unknown strings are NOT allowlisted vocabulary: drop
            }
        }

        is VoiceIntent.Playback.SeekAbsolute -> mapOf("position_seconds" to RecordNode.Num(intent.positionSeconds.toDouble()))

        is VoiceIntent.Effects.SetSpeed -> mapOf("speed" to RecordNode.Num(intent.speed))

        is VoiceIntent.Effects.AdjustSpeed -> mapOf("delta" to RecordNode.Num(intent.delta))

        is VoiceIntent.Effects.SetTrimMode ->
            if (intent.mode in TRIM_MODES) mapOf("mode" to RecordNode.Str(intent.mode)) else emptyMap()

        is VoiceIntent.Effects.SetVolumeBoost -> mapOf("enabled" to RecordNode.Bool(intent.enabled))

        is VoiceIntent.Volume.SetVolume -> mapOf("volume" to RecordNode.Num(intent.volume.toDouble()))

        is VoiceIntent.Volume.AdjustVolume -> mapOf("delta" to RecordNode.Num(intent.delta.toDouble()))

        is VoiceIntent.Sleep.Set -> mapOf("minutes" to RecordNode.Num(intent.minutes.toDouble()))

        is VoiceIntent.Sleep.AddTime -> mapOf("minutes" to RecordNode.Num(intent.minutes.toDouble()))

        is VoiceIntent.Chapter.ByIndex -> mapOf("index" to RecordNode.Num(intent.index.toDouble()))

        is VoiceIntent.Chapter.OpenLink -> mapOf("index" to RecordNode.Num(intent.index.toDouble()))

        // Chapter.ByTitle(query): free text — private; category only.
        is VoiceIntent.Chapter.ByTitle -> emptyMap()

        // Bookmark titles/refs and Queue episode/podcast names: private references — excluded.
        is VoiceIntent.Bookmark.Add -> emptyMap()

        is VoiceIntent.Bookmark.Rename, is VoiceIntent.Bookmark.Play, is VoiceIntent.Bookmark.Delete -> emptyMap()

        is VoiceIntent.Queue.AddTop, is VoiceIntent.Queue.AddBottom, is VoiceIntent.Queue.Remove,
        is VoiceIntent.Queue.MoveToTop, is VoiceIntent.Queue.MoveToBottom,
        is VoiceIntent.Queue.RemoveByPodcast, is VoiceIntent.Queue.QueryIsQueued,
        -> emptyMap()

        is VoiceIntent.Queue.Sort -> if (intent.sortOrder in SORT_ORDERS) mapOf("sort_order" to RecordNode.Str(intent.sortOrder)) else emptyMap()

        // Stats period tokens are closed vocabularies.
        is VoiceIntent.StatsQuery.ListeningTime -> periodArg(intent.period)

        is VoiceIntent.StatsQuery.TopPodcasts -> periodArg(intent.period)

        is VoiceIntent.StatsQuery.EpisodesFinished -> periodArg(intent.period)

        is VoiceIntent.StatsQuery.NewEpisodes -> periodArg(intent.timeframe)

        // Cloud routes carry conversation payloads and internal authority: never include args.
        is VoiceIntent.CloudRoute -> emptyMap()
    }

    private fun periodArg(period: String?): Map<String, RecordNode> = if (period != null && period in PERIODS) mapOf("period" to RecordNode.Str(period)) else emptyMap()

    private val PERIODS = setOf("today", "week", "month", "year", "all_time")
    private val TRIM_MODES = setOf("off", "mild", "strong")
    private val SORT_ORDERS = setOf("newest", "oldest", "longest", "shortest")
}
