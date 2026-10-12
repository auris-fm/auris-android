package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteHint
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.PlaybackContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import javax.inject.Inject
import kotlin.math.abs
import timber.log.Timber

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
    /**
     * The turn last prepared for an utterance, so a RETRY of that utterance reuses it.
     *
     * Retention is what makes the retry path production-reachable: without it, preparation would be a
     * branch only a test could exercise, and a real retry would prepare a SECOND turn -- minting a new
     * request ID, and if its window had moved, a new conversation for a request that was already sent.
     */
    private val retainedLock = Any()

    /**
     * Prepared turns by LOGICAL request, so a retry finds its own.
     *
     * A MAP, not a single slot: one slot holds only the most recent turn, so preparing a second
     * logical request OVERWROTE the first and a later retry of it found nothing -- creating a third
     * wire request for a logical request that already had one. Each logical request keeps its turn
     * until it is no longer retryable.
     */
    private val retained =
        mutableMapOf<String, au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn>()

    private fun retainTurn(
        logicalRequestId: String?,
        turn: au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn,
    ) {
        // Retained under the LOGICAL identity the caller supplied, so a later retry naming the same
        // request finds it -- and an utterance that names none never does. Keying on the request TEXT
        // would let two separate user acts that happen to say the same words share one turn.
        val logical = logicalRequestId ?: return
        synchronized(retainedLock) { retained[logical] = turn }
    }

    /**
     * The retained turn for [intent]'s LOGICAL request, when one was prepared.
     *
     * Keyed by logical identity, NOT by the request text: identical words do not identify a retry, and
     * two separate user acts can say the same thing. A retry names the logical request it repeats; an
     * utterance that names none is a NEW act, so it prepares its own turn with its own request ID and
     * its own CREATE-or-RESUME decision under its own origin.
     */
    private fun retainedTurn(intent: VoiceIntent.CloudRoute): au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn? {
        val logical = intent.logicalRequestId ?: return null
        return synchronized(retainedLock) { retained[logical] }
    }

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
                // THE CONTROLLER RETRY PATH: a dispatch carrying a PREPARED turn sends that turn, not a
                // newly prepared one, so a retry reuses its request ID, handle and mode. Its captured
                // origin decides which window it is validated against, so a retry whose window has since
                // been superseded or closed is refused rather than adopting the current one.
                val alreadyPrepared = intent.preparedTurn
                    // NOT CARRIED BY THE INTENT: this dispatch is a RETRY of the request this executor
                    // last prepared for the same utterance. The turn is retained here, which is what
                    // makes the retry path reachable WITHOUT the caller having to populate the intent --
                    // otherwise preparation and retention would be a branch only a test ever runs.
                    ?: retainedTurn(intent)
                if (alreadyPrepared != null) {
                    val captureForRetry = alreadyPrepared.capturedOrigin
                    // The privacy half is the executor's, as for a first attempt: the sink cannot
                    // observe the grace signal.
                    if (captureForRetry != null && !gracePeriodSignal.isCurrent(captureForRetry)) {
                        Timber.i("[Voice] superseded retry discarded (stamp=$captureForRetry)")
                        return VoiceResponse.Silent
                    }
                    return cloudRouteSink.dispatchPrepared(alreadyPrepared)
                }
                val context = playbackContextProvider.current()
                // The window identity is the one STAMPED ON THE INTENT when it was dispatched --
                // not the current one read here. The executor runs later than the dispatch, so reading
                // the live window would let a fresh wake look like a continuation of the previous
                // conversation, and would let a request captured before a successor wake act under the
                // successor's. The stamp is the captured origin; when a dispatch carries none, there is
                // no window to claim and the sink falls back to the declared-live origin.
                val captured = intent.windowGeneration
                // A dispatch stamped with a window that is no longer current is SUPERSEDED and does
                // not run at all. This belongs here rather than only in the sink: the sink's admission
                // governs which CONVERSATION a turn joins, while this governs whether a superseded
                // request may act on the successor's playback at all -- and a stale dispatch reaching
                // the sink would still be a mutation made on behalf of a replaced user act.
                if (captured != null && !gracePeriodSignal.isCurrent(captured)) {
                    Timber.i("[Voice] superseded dispatch discarded (stamp=$captured)")
                    return VoiceResponse.Silent
                }
                // READ BEFORE NOTING: whether this is the window's first request decides whether it
                // begins a conversation, and noting first made every dispatch see itself as a
                // continuation -- the flag was computed against a state this dispatch had just set.
                val isFirstRequestOfWindow = captured != null &&
                    !gracePeriodSignal.hasPreparedRequest(captured)
                // PREPARED ONCE, RETAINED FOR A RETRY. Preparing here rather than at the call site is
                // what makes the retry path a production path: the identity fixed at this moment is the
                // identity a later retry must reuse.
                val preparedTurn = cloudRouteSink.prepareTurn(
                    request = intent.request,
                    tier = intent.tier,
                    context = context,
                    isLiveGraceTurn = captured != null &&
                        gracePeriodSignal.isCurrent(captured) &&
                        gracePeriodSignal.isActive.value,
                    capturedOrigin = captured,
                    isWakeOriginated = captured != null &&
                        gracePeriodSignal.isWakeOriginated(
                            captured,
                            isFirstRequestOfWindow = isFirstRequestOfWindow,
                        ),
                ) ?: return VoiceResponse.Silent
                retainTurn(intent.logicalRequestId, preparedTurn)
                // DISPATCHED THROUGH THE ALREADY-PREPARED TURN, so the first attempt and a retry take
                // the SAME path and the identity is the one fixed above rather than a second one minted
                // here.
                val dispatched = cloudRouteSink.dispatchPrepared(preparedTurn)
                // Noted after the dispatch: this window now has a request, so a LATER one continues
                // rather than beginning.
                captured?.let { gracePeriodSignal.notePreparedRequest(it) }
                dispatched
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

        is VoiceIntent.Playback.SeekAbsolute -> playbackSink.seekTo(
            intent.positionSeconds,
            episodeDurationSeconds = currentEpisodeDurationSeconds(),
        )

        VoiceIntent.Playback.NextEpisode -> playbackSink.nextEpisode()
    }

    /** The current episode's duration in seconds, for resolving a negative absolute position. */
    private fun currentEpisodeDurationSeconds(): Int = playbackManager.getCurrentEpisode()?.duration?.toInt() ?: 0

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

    /**
     * Pause on the turn's own behalf, taking a HOLD the turn will release.
     *
     * Deliberately distinct from [pause]: both reach the player through `VOICE_COMMANDS`, so a
     * listener cannot tell them apart by interface. The user's own pause must supersede the answer
     * hold, and the turn's hold must not be exempted from that rule merely because it shares the tag.
     */
    suspend fun pauseAsHold(): VoiceResponse
    suspend fun resume(): VoiceResponse
    suspend fun skipForward(seconds: Int?): VoiceResponse
    suspend fun skipBackward(seconds: Int?): VoiceResponse

    /**
     * Seek to a signed absolute episode position.
     *
     * The schema's semantics belong to this call rather than to its callers: non-negative values are
     * offsets from the beginning, 0 is the beginning, and a NEGATIVE value is an offset back from the
     * episode end. [episodeDurationSeconds] is what makes the negative case resolvable -- clamping it
     * to zero would turn "seek back 30 s from the end" into "seek to the beginning".
     */
    suspend fun seekTo(positionSeconds: Int, episodeDurationSeconds: Int): VoiceResponse
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
    /**
     * @param isLiveGraceTurn true when this turn continues an answer still inside its grace window, so
     * it RESUMES the conversation in progress; false when it begins a new one and CREATES.
     */
    suspend fun routeToCloud(
        request: String,
        tier: VoiceIntent.CloudTier,
        context: PlaybackContext,
        // Defaults to a NEW conversation: a caller that does not say it continues a grace window is
        // starting one, and CREATE is the safe reading of silence.
        isLiveGraceTurn: Boolean = false,
        /** The window this request was captured under; null when the caller captured none. */
        capturedOrigin: Long? = null,
        /** True when that window was opened by a wake, so the turn begins a conversation. */
        isWakeOriginated: Boolean = false,
    ): VoiceResponse

    /**
     * Prepares a turn ONCE and returns it, for a caller that will retain it and may retry it.
     *
     * Returns null when the origin has been superseded, so nothing is sent on behalf of a turn that has
     * already been replaced.
     */
    suspend fun prepareTurn(
        request: String,
        tier: VoiceIntent.CloudTier,
        context: PlaybackContext,
        isLiveGraceTurn: Boolean = false,
        capturedOrigin: Long? = null,
        isWakeOriginated: Boolean = false,
        hint: au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteHint? = null,
    ): au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn?

    /**
     * Dispatches an ALREADY PREPARED turn -- the controller retry path.
     *
     * Through the SAME lifecycle as a first dispatch, so a retry registers, holds its place and cleans
     * up like any other turn, and is re-validated against its own captured window.
     */
    suspend fun dispatchPrepared(
        prepared: au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn,
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
        isLiveGraceTurn: Boolean = false,
        capturedOrigin: Long? = null,
        isWakeOriginated: Boolean = false,
    ): VoiceResponse = routeToCloud(
        request,
        tier,
        context,
        isLiveGraceTurn,
        capturedOrigin,
        isWakeOriginated,
    )
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
