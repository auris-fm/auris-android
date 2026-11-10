package au.com.shiftyjelly.pocketcasts.voicecontrol.intent

/**
 * The name a routed intent publishes as `routing.predicted_intent`.
 *
 * The upload spec fixes this value as `<tool>.<action>`, using the tool and action names exactly as
 * the Voice Intents tool schema spells them, with `cloud_route` alone for a cloud route and no value
 * at all for an unrouted (`no_match`) utterance. The server expands it into the object name the
 * recording is filed under, so this is what the record says about what the app decided.
 *
 * Derived from the intent's type and action only. Never a field of it: a cloud route's `request`
 * carries the utterance, and the spec forbids putting argument values or request text in the name.
 *
 * The reverse direction of [ToolCallMapper]: that one turns a schema call into an intent, this one
 * turns the intent back into the schema's own name, so the two cannot drift apart in vocabulary.
 */
object PredictedIntentName {

    private val TOOL_OF_FAMILY: Map<String, String> = mapOf(
        "Playback" to "playback",
        "Effects" to "effects",
        "Volume" to "volume",
        "Sleep" to "sleep",
        "Chapter" to "chapter",
        "Bookmark" to "bookmark",
        "Queue" to "queue",
        "PlaybackQuery" to "playback_query",
        "StatsQuery" to "stats_query",
    )

    /**
     * The exact schema action names, per intent leaf. An `when` over the sealed hierarchy rather
     * than a lookup by class name, so a new leaf fails the build instead of silently publishing a
     * name the schema does not define.
     */
    fun of(intent: VoiceIntent): String = when (intent) {
        // playback
        is VoiceIntent.Playback.Pause -> "playback.pause"

        is VoiceIntent.Playback.Resume -> "playback.resume"

        is VoiceIntent.Playback.SeekRelative -> "playback.seek_relative"

        is VoiceIntent.Playback.SeekAbsolute -> "playback.seek_to"

        is VoiceIntent.Playback.NextEpisode -> "playback.next_episode"

        // effects
        is VoiceIntent.Effects.SetSpeed -> "effects.set_speed"

        is VoiceIntent.Effects.AdjustSpeed -> "effects.adjust_speed"

        is VoiceIntent.Effects.SetTrimMode -> "effects.set_trim_mode"

        is VoiceIntent.Effects.SetVolumeBoost -> "effects.set_volume_boost"

        is VoiceIntent.Effects.QueryEffects -> "effects.query_effects"

        // volume
        is VoiceIntent.Volume.SetVolume -> "volume.set_volume"

        is VoiceIntent.Volume.AdjustVolume -> "volume.adjust_volume"

        is VoiceIntent.Volume.Query -> "volume.query"

        // sleep
        is VoiceIntent.Sleep.Set -> "sleep.set"

        is VoiceIntent.Sleep.EndOfEpisode -> "sleep.end_of_episode"

        is VoiceIntent.Sleep.EndOfChapter -> "sleep.end_of_chapter"

        is VoiceIntent.Sleep.AddTime -> "sleep.add_time"

        is VoiceIntent.Sleep.Cancel -> "sleep.cancel"

        is VoiceIntent.Sleep.QuerySleep -> "sleep.query"

        // chapter
        is VoiceIntent.Chapter.NextChapter -> "chapter.next"

        is VoiceIntent.Chapter.PreviousChapter -> "chapter.previous"

        is VoiceIntent.Chapter.ByIndex -> "chapter.by_index"

        is VoiceIntent.Chapter.ByTitle -> "chapter.by_title"

        is VoiceIntent.Chapter.OpenLink -> "chapter.open_link"

        is VoiceIntent.Chapter.QueryList -> "chapter.query_list"

        is VoiceIntent.Chapter.QueryCurrent -> "chapter.query_current"

        is VoiceIntent.Chapter.QueryCount -> "chapter.query_count"

        is VoiceIntent.Chapter.QueryNext -> "chapter.query_next"

        // bookmark
        is VoiceIntent.Bookmark.Add -> "bookmark.add"

        is VoiceIntent.Bookmark.Rename -> "bookmark.rename"

        is VoiceIntent.Bookmark.Play -> "bookmark.play"

        is VoiceIntent.Bookmark.Delete -> "bookmark.delete"

        is VoiceIntent.Bookmark.DeleteAll -> "bookmark.delete_all"

        is VoiceIntent.Bookmark.QueryBookmarkList -> "bookmark.query_list"

        is VoiceIntent.Bookmark.QueryBookmarkCount -> "bookmark.query_count"

        is VoiceIntent.Bookmark.QueryNearby -> "bookmark.query_nearby"

        // queue
        is VoiceIntent.Queue.AddTop -> "queue.add_top"

        is VoiceIntent.Queue.AddBottom -> "queue.add_bottom"

        is VoiceIntent.Queue.Remove -> "queue.remove"

        is VoiceIntent.Queue.MoveToTop -> "queue.move_to_top"

        is VoiceIntent.Queue.MoveToBottom -> "queue.move_to_bottom"

        is VoiceIntent.Queue.Clear -> "queue.clear"

        is VoiceIntent.Queue.RemoveByPodcast -> "queue.remove_by_podcast"

        is VoiceIntent.Queue.Sort -> "queue.sort"

        is VoiceIntent.Queue.QueryContents -> "queue.query_contents"

        is VoiceIntent.Queue.QueryQueueNext -> "queue.query_next"

        is VoiceIntent.Queue.QueryLength -> "queue.query_length"

        is VoiceIntent.Queue.QueryIsQueued -> "queue.query_is_queued"

        // playback_query
        is VoiceIntent.PlaybackQuery.WhatsPlaying -> "playback_query.whats_playing"

        is VoiceIntent.PlaybackQuery.Position -> "playback_query.position"

        is VoiceIntent.PlaybackQuery.TimeRemaining -> "playback_query.time_remaining"

        is VoiceIntent.PlaybackQuery.CurrentPodcast -> "playback_query.current_podcast"

        is VoiceIntent.PlaybackQuery.EpisodeDuration -> "playback_query.episode_duration"

        is VoiceIntent.PlaybackQuery.PublishDate -> "playback_query.publish_date"

        is VoiceIntent.PlaybackQuery.EpisodeDescription -> "playback_query.episode_description"

        is VoiceIntent.PlaybackQuery.DownloadStatus -> "playback_query.download_status"

        is VoiceIntent.PlaybackQuery.EpisodeTitle -> "playback_query.episode_title"

        // stats_query
        is VoiceIntent.StatsQuery.ListeningTime -> "stats_query.listening_time"

        is VoiceIntent.StatsQuery.TopPodcasts -> "stats_query.top_podcasts"

        is VoiceIntent.StatsQuery.EpisodesFinished -> "stats_query.episodes_finished"

        is VoiceIntent.StatsQuery.ListeningStreak -> "stats_query.listening_streak"

        is VoiceIntent.StatsQuery.SubscriptionCount -> "stats_query.subscription_count"

        is VoiceIntent.StatsQuery.UnplayedTotal -> "stats_query.unplayed_total"

        is VoiceIntent.StatsQuery.DownloadStats -> "stats_query.download_stats"

        is VoiceIntent.StatsQuery.QueueTotal -> "stats_query.queue_total"

        is VoiceIntent.StatsQuery.NewEpisodes -> "stats_query.new_episodes"

        is VoiceIntent.StatsQuery.TimeSinceLastListen -> "stats_query.time_since_last_listen"

        // The spec's exception: a cloud route is the tool alone, never an action or the request.
        is VoiceIntent.CloudRoute -> "cloud_route"
    }

    /** The tool half of the name, used to group by feature where only the family is needed. */
    fun toolOf(intent: VoiceIntent): String = of(intent).substringBefore('.')

    /** Every name the schema defines, so a control can assert the mapping stays inside it. */
    fun allFamilyTools(): Set<String> = TOOL_OF_FAMILY.values.toSet()
}
