package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteHint
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.PlaybackContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import javax.inject.Inject
import kotlin.math.abs

class VoicePlaybackIntentExecutor @Inject constructor(
    private val playbackSink: VoicePlaybackSink,
    private val effectsSink: VoiceEffectsSink,
    private val volumeSink: VoiceVolumeSink,
    private val sleepSink: VoiceSleepSink,
    private val chapterSink: VoiceChapterSink,
    private val bookmarkSink: VoiceBookmarkSink,
    private val cloudRouteSink: VoiceCloudRouteSink,
    private val playbackContextProvider: PlaybackContextProvider,
    private val queueSink: VoiceQueueSink,
    private val playbackQuerySink: VoicePlaybackQuerySink,
    private val statsQuerySink: VoiceStatsQuerySink,
    private val gracePeriodSignal: GracePeriodSignal,
    /**
     * The playback layer, used to report a playback command the *user* issued by
     * voice to its command revision. The cloud turn's own pause carries the same
     * source tag, so the playback layer cannot separate the two; the executor is
     * where the distinction is known.
     */
    private val playbackManager: PlaybackManager,
) {
    suspend fun execute(intent: VoiceIntent): VoiceResponse {
        // Recorded *before* the action runs: the revision is what a cloud turn
        // compares at restore time, and a user's pause that has already reached
        // the player but not this counter is exactly the window where the turn
        // would resume over them.
        if (intent.changesPlayPauseState()) playbackManager.noteUserPlaybackCommand()
        val response = when (intent) {
            is VoiceIntent.Playback -> executePlayback(intent)

            is VoiceIntent.Effects -> executeEffects(intent)

            is VoiceIntent.Volume -> executeVolume(intent)

            is VoiceIntent.Sleep -> executeSleep(intent)

            is VoiceIntent.Chapter -> executeChapter(intent)

            is VoiceIntent.Bookmark -> executeBookmark(intent)

            is VoiceIntent.CloudRoute -> {
                val context = playbackContextProvider.current()
                cloudRouteSink.routeToCloud(intent.request, intent.tier, context)
            }

            is VoiceIntent.Queue -> executeQueue(intent)

            is VoiceIntent.PlaybackQuery -> executePlaybackQuery(intent)

            is VoiceIntent.StatsQuery -> executeStatsQuery(intent)
        }
        gracePeriodSignal.onCommandRecognized(
            fromGeneration = intent.windowGenerationOf(),
            restoresAllowance = intent.restoresAllowance(),
        )
        return response
    }

    private suspend fun executePlayback(intent: VoiceIntent.Playback): VoiceResponse = when (intent) {
        VoiceIntent.Playback.Pause -> playbackSink.pause()

        VoiceIntent.Playback.Resume -> playbackSink.resume()

        is VoiceIntent.Playback.SeekRelative -> {
            // deltaSeconds in seconds; null means "no amount stated — sink
            // should apply its configured interval in direction".
            when (val delta = intent.deltaSeconds) {
                null -> {
                    // No amount stated; sink applies its interval in direction.
                    // Pass null so the sink uses the app's configured interval.
                    when (intent.direction) {
                        "backward" -> playbackSink.skipBackward(null)
                        else -> playbackSink.skipForward(null)
                    }
                }

                0 -> VoiceResponse.Silent

                // explicit "stay put"
                in Int.MIN_VALUE..-1 -> playbackSink.skipBackward(abs(delta))

                else -> playbackSink.skipForward(delta)
            }
        }

        is VoiceIntent.Playback.SeekAbsolute -> playbackSink.seekTo(intent.positionSeconds.coerceAtLeast(0))

        VoiceIntent.Playback.NextEpisode -> playbackSink.nextEpisode()
    }

    private fun executeEffects(intent: VoiceIntent.Effects): VoiceResponse = when (intent) {
        is VoiceIntent.Effects.SetSpeed -> effectsSink.setSpeed(intent.speed)
        is VoiceIntent.Effects.AdjustSpeed -> effectsSink.adjustSpeed(intent.delta)
        is VoiceIntent.Effects.SetTrimMode -> effectsSink.setTrimMode(intent.mode)
        is VoiceIntent.Effects.SetVolumeBoost -> effectsSink.setVolumeBoost(intent.enabled)
        VoiceIntent.Effects.QueryEffects -> effectsSink.queryEffects()
    }

    private fun executeVolume(intent: VoiceIntent.Volume): VoiceResponse = when (intent) {
        is VoiceIntent.Volume.SetVolume -> volumeSink.setVolume(intent.volume)
        is VoiceIntent.Volume.AdjustVolume -> volumeSink.adjustVolume(intent.delta)
        VoiceIntent.Volume.Query -> volumeSink.query()
    }

    private fun executeSleep(intent: VoiceIntent.Sleep): VoiceResponse = when (intent) {
        is VoiceIntent.Sleep.Set -> sleepSink.set(intent.minutes)
        VoiceIntent.Sleep.EndOfEpisode -> sleepSink.endOfEpisode()
        VoiceIntent.Sleep.EndOfChapter -> sleepSink.endOfChapter()
        is VoiceIntent.Sleep.AddTime -> sleepSink.addTime(intent.minutes)
        VoiceIntent.Sleep.Cancel -> sleepSink.cancel()
        VoiceIntent.Sleep.QuerySleep -> sleepSink.query()
    }

    private fun executeChapter(intent: VoiceIntent.Chapter): VoiceResponse = when (intent) {
        VoiceIntent.Chapter.NextChapter -> chapterSink.next()

        VoiceIntent.Chapter.PreviousChapter -> chapterSink.previous()

        is VoiceIntent.Chapter.ByIndex -> chapterSink.byIndex(intent.index)

        is VoiceIntent.Chapter.ByTitle -> VoiceResponse.Silent

        // no-op until chapter search is implemented
        is VoiceIntent.Chapter.OpenLink -> chapterSink.openLink(intent.index)

        VoiceIntent.Chapter.QueryList -> chapterSink.queryList()

        VoiceIntent.Chapter.QueryCurrent -> chapterSink.queryCurrent()

        VoiceIntent.Chapter.QueryCount -> chapterSink.queryCount()

        VoiceIntent.Chapter.QueryNext -> chapterSink.queryNext()
    }

    private suspend fun executeBookmark(intent: VoiceIntent.Bookmark): VoiceResponse = when (intent) {
        is VoiceIntent.Bookmark.Add -> bookmarkSink.add(intent.title)
        is VoiceIntent.Bookmark.Rename -> bookmarkSink.rename(intent.ref, intent.title)
        is VoiceIntent.Bookmark.Play -> bookmarkSink.play(intent.ref)
        is VoiceIntent.Bookmark.Delete -> bookmarkSink.delete(intent.ref)
        VoiceIntent.Bookmark.DeleteAll -> bookmarkSink.deleteAll()
        VoiceIntent.Bookmark.QueryBookmarkList -> bookmarkSink.queryList()
        VoiceIntent.Bookmark.QueryBookmarkCount -> bookmarkSink.queryCount()
        VoiceIntent.Bookmark.QueryNearby -> bookmarkSink.queryNearby()
    }

    private suspend fun executeQueue(intent: VoiceIntent.Queue): VoiceResponse = when (intent) {
        is VoiceIntent.Queue.AddTop -> queueSink.addTop(intent.episode)
        is VoiceIntent.Queue.AddBottom -> queueSink.addBottom(intent.episode)
        is VoiceIntent.Queue.Remove -> queueSink.remove(intent.episode)
        is VoiceIntent.Queue.MoveToTop -> queueSink.moveToTop(intent.episode)
        is VoiceIntent.Queue.MoveToBottom -> queueSink.moveToBottom(intent.episode)
        VoiceIntent.Queue.Clear -> queueSink.clear()
        is VoiceIntent.Queue.RemoveByPodcast -> queueSink.removeByPodcast(intent.podcast)
        is VoiceIntent.Queue.Sort -> queueSink.sort(intent.sortOrder)
        VoiceIntent.Queue.QueryContents -> queueSink.queryContents()
        VoiceIntent.Queue.QueryQueueNext -> queueSink.queryNext()
        VoiceIntent.Queue.QueryLength -> queueSink.queryLength()
        is VoiceIntent.Queue.QueryIsQueued -> queueSink.queryIsQueued(intent.episode)
    }

    private fun executePlaybackQuery(intent: VoiceIntent.PlaybackQuery): VoiceResponse = when (intent) {
        VoiceIntent.PlaybackQuery.WhatsPlaying -> playbackQuerySink.whatsPlaying()
        VoiceIntent.PlaybackQuery.Position -> playbackQuerySink.position()
        VoiceIntent.PlaybackQuery.TimeRemaining -> playbackQuerySink.timeRemaining()
        VoiceIntent.PlaybackQuery.CurrentPodcast -> playbackQuerySink.currentPodcast()
        VoiceIntent.PlaybackQuery.EpisodeDuration -> playbackQuerySink.episodeDuration()
        VoiceIntent.PlaybackQuery.PublishDate -> playbackQuerySink.publishDate()
        VoiceIntent.PlaybackQuery.EpisodeDescription -> playbackQuerySink.episodeDescription()
        VoiceIntent.PlaybackQuery.DownloadStatus -> playbackQuerySink.downloadStatus()
        VoiceIntent.PlaybackQuery.EpisodeTitle -> playbackQuerySink.episodeTitle()
    }

    private fun executeStatsQuery(intent: VoiceIntent.StatsQuery): VoiceResponse = when (intent) {
        is VoiceIntent.StatsQuery.ListeningTime -> statsQuerySink.listeningTime(intent.period)
        is VoiceIntent.StatsQuery.TopPodcasts -> statsQuerySink.topPodcasts(intent.period)
        is VoiceIntent.StatsQuery.EpisodesFinished -> statsQuerySink.episodesFinished(intent.period)
        VoiceIntent.StatsQuery.ListeningStreak -> statsQuerySink.listeningStreak()
        VoiceIntent.StatsQuery.SubscriptionCount -> statsQuerySink.subscriptionCount()
        VoiceIntent.StatsQuery.UnplayedTotal -> statsQuerySink.unplayedTotal()
        VoiceIntent.StatsQuery.DownloadStats -> statsQuerySink.downloadStats()
        VoiceIntent.StatsQuery.QueueTotal -> statsQuerySink.queueTotal()
        is VoiceIntent.StatsQuery.NewEpisodes -> statsQuerySink.newEpisodes(intent.timeframe)
        VoiceIntent.StatsQuery.TimeSinceLastListen -> statsQuerySink.timeSinceLastListen()
    }
}

interface VoicePlaybackSink {
    /** Lower the host player's volume (duck) while cloud audio plays through the shared output. */
    suspend fun duck(): VoiceResponse

    /** Restore the host player to full volume after cloud audio finishes. */
    suspend fun restore(): VoiceResponse

    suspend fun pause(): VoiceResponse
    suspend fun resume(): VoiceResponse
    suspend fun skipForward(seconds: Int?): VoiceResponse
    suspend fun skipBackward(seconds: Int?): VoiceResponse
    suspend fun seekTo(positionSeconds: Int): VoiceResponse
    fun nextEpisode(): VoiceResponse
}

interface VoiceEffectsSink {
    fun setSpeed(speed: Double): VoiceResponse
    fun adjustSpeed(delta: Double): VoiceResponse
    fun setTrimMode(mode: String): VoiceResponse
    fun setVolumeBoost(enabled: Boolean): VoiceResponse
    fun queryEffects(): VoiceResponse.Spoken
}

interface VoiceVolumeSink {
    fun setVolume(volume: Int): VoiceResponse
    fun adjustVolume(delta: Int): VoiceResponse
    fun query(): VoiceResponse.Spoken
}

interface VoiceSleepSink {
    fun set(minutes: Int): VoiceResponse
    fun endOfEpisode(): VoiceResponse
    fun endOfChapter(): VoiceResponse
    fun addTime(minutes: Int): VoiceResponse
    fun cancel(): VoiceResponse
    fun query(): VoiceResponse.Spoken
}

interface VoiceChapterSink {
    fun next(): VoiceResponse
    fun previous(): VoiceResponse
    fun byIndex(index: Int): VoiceResponse
    fun openLink(index: Int): VoiceResponse
    fun queryList(): VoiceResponse.Spoken
    fun queryCurrent(): VoiceResponse.Spoken
    fun queryCount(): VoiceResponse.Spoken
    fun queryNext(): VoiceResponse.Spoken
}

interface VoiceBookmarkSink {
    suspend fun add(title: String?): VoiceResponse
    fun rename(ref: String, title: String): VoiceResponse
    fun play(ref: String): VoiceResponse
    fun delete(ref: String): VoiceResponse
    fun deleteAll(): VoiceResponse
    fun queryList(): VoiceResponse.Spoken
    fun queryCount(): VoiceResponse.Spoken
    fun queryNearby(): VoiceResponse.Spoken
}

interface VoiceCloudRouteSink {
    suspend fun routeToCloud(
        request: String,
        tier: VoiceIntent.CloudTier,
        context: PlaybackContext,
    ): VoiceResponse

    /**
     * Typed-flow entry point (cloud-assistant.md `route_hint`): only a typed UI
     * flow or a validated structured parse may supply [hint]. Free-text
     * utterances must use [routeToCloud] and never derive a hint.
     */
    suspend fun routeToCloudWithHint(
        request: String,
        tier: VoiceIntent.CloudTier,
        context: PlaybackContext,
        hint: CloudRouteHint,
    ): VoiceResponse = routeToCloud(request, tier, context)
}

interface VoiceQueueSink {
    suspend fun addTop(episode: String?): VoiceResponse
    suspend fun addBottom(episode: String?): VoiceResponse
    suspend fun remove(episode: String): VoiceResponse
    suspend fun moveToTop(episode: String): VoiceResponse
    suspend fun moveToBottom(episode: String): VoiceResponse
    fun clear(): VoiceResponse
    suspend fun removeByPodcast(podcast: String): VoiceResponse
    fun sort(sortOrder: String): VoiceResponse
    fun queryContents(): VoiceResponse.Spoken
    fun queryNext(): VoiceResponse.Spoken
    fun queryLength(): VoiceResponse.Spoken
    suspend fun queryIsQueued(episode: String): VoiceResponse.Spoken
}

interface VoicePlaybackQuerySink {
    fun whatsPlaying(): VoiceResponse.Spoken
    fun position(): VoiceResponse.Spoken
    fun timeRemaining(): VoiceResponse.Spoken
    fun currentPodcast(): VoiceResponse.Spoken
    fun episodeDuration(): VoiceResponse.Spoken
    fun publishDate(): VoiceResponse.Spoken
    fun episodeDescription(): VoiceResponse.Spoken
    fun downloadStatus(): VoiceResponse.Spoken
    fun episodeTitle(): VoiceResponse.Spoken
}

interface VoiceStatsQuerySink {
    fun listeningTime(period: String?): VoiceResponse.Spoken
    fun topPodcasts(period: String?): VoiceResponse.Spoken
    fun episodesFinished(period: String?): VoiceResponse.Spoken
    fun listeningStreak(): VoiceResponse.Spoken
    fun subscriptionCount(): VoiceResponse.Spoken
    fun unplayedTotal(): VoiceResponse.Spoken
    fun downloadStats(): VoiceResponse.Spoken
    fun queueTotal(): VoiceResponse.Spoken
    fun newEpisodes(timeframe: String?): VoiceResponse.Spoken
    fun timeSinceLastListen(): VoiceResponse.Spoken
}

/** The grace window a dispatched cloud turn belongs to, or null for a local command. */
internal fun VoiceIntent.windowGenerationOf(): Long? = (this as? VoiceIntent.CloudRoute)?.windowGeneration

/**
 * Whether handling this command earns the window another dispatch. A route the
 * router chose is a deliberate act; the routing-failure fallback is not, since
 * the allowance it is spending exists for exactly that case.
 */
internal fun VoiceIntent.restoresAllowance(): Boolean = (this as? VoiceIntent.CloudRoute)?.origin != VoiceIntent.CloudRouteOrigin.RoutingFailure

/**
 * True for the commands the user can issue by voice that change play/pause
 * state — the ones that contest a cloud turn's claim on the player. A seek or a
 * scrub is not one of them.
 */
internal fun VoiceIntent.changesPlayPauseState(): Boolean = when (this) {
    VoiceIntent.Playback.Pause, VoiceIntent.Playback.Resume -> true
    else -> false
}
