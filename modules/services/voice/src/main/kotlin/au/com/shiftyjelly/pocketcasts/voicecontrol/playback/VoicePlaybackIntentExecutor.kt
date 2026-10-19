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
    /** The turn retained for [logicalRequestId], if any. Delegates to the sink's store. */
    private fun retainedForLogical(
        logicalRequestId: String,
    ): au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn? = cloudRouteSink.retainedTurnFor(logicalRequestId)

    /**
     * Reported when releasing a retained turn FAILS, so a hidden failure becomes a visible one.
     *
     * Set by the owner that manages the session; unset means the release failure is logged and no more,
     * which is still not silent.
     */
    var onRetirementFailure: ((logicalRequestId: String, cause: Throwable) -> Unit)? = null

    /**
     * Retires every prepared turn belonging to [endedGeneration], reporting what it retired.
     *
     * The window-ended lifecycle, reachable by the owner that observes windows ending. A request is
     * retryable while ITS window lives, so a window ending for ANY reason retires what it holds.
     */
    // THROUGH THE INTERFACE, NOT A CAST. The safe-cast fell back to `emptyList()`, so a sink that did
    // not implement the method would LOOK like it retired nothing -- a silent no-op reported as a
    // successful retirement. A mandatory member cannot be omitted: the compiler refuses.
    fun endWindow(endedGeneration: Long): List<String> = cloudRouteSink.endWindow(endedGeneration)

    /** The manager this executor installed its release on, so teardown can remove it. */
    private var boundDialogManager:
        au.com.shiftyjelly.pocketcasts.voicecontrol.dialog.VoiceDialogManager? = null

    /**
     * Connects a dialog manager's abandonment reports to this sink's retention release.
     *
     * CALLED AT COMPOSITION, not injected: making the manager a constructor dependency would close a
     * cycle through the router, which already reaches the manager. One call, one direction.
     */
    fun bindDialogAbandonment(
        dialogManager: au.com.shiftyjelly.pocketcasts.voicecontrol.dialog.VoiceDialogManager,
    ) {
        // ONE DIRECTION: the manager reports an abandoned or replaced request and the SINK forgets the
        // turn retained for it. The sink is not made a dependency of the manager -- the router already
        // reaches the manager, so that would close a cycle -- and the binding is installed here, at
        // composition, where both are in hand.
        // Through the INTERFACE, not a cast: the release is part of the contract, so a caller cannot
        // reach a sink that silently ignores it.
        dialogManager.onRequestAbandoned = { logical ->
            // NOT SWALLOWED. A release that fails leaves the turn retained for the life of the process,
            // and a silent catch makes that indistinguishable from a release that worked -- the
            // hidden-failure shape this whole mechanism exists to avoid. A failure is reported through
            // the path the owner watches, so it is visible where the session is managed.
            try {
                cloudRouteSink.forgetRetained(logical)
            } catch (t: Throwable) {
                onRetirementFailure?.invoke(logical, t)
                Timber.w(t, "[Voice] releasing the turn retained for %s failed", logical)
            }
        }
        boundDialogManager = dialogManager
    }

    /**
     * Removes the abandonment binding installed by [bindDialogAbandonment].
     *
     * TEARDOWN MATTERS BOTH WAYS. A binding left in place after the sink it points at is gone keeps an
     * obsolete consumer: the manager would report abandoned requests into a released object. And a
     * binding cleared while the manager is still live would LOSE retirement notifications, leaving
     * prepared turns retained for the life of the process. Called wherever the session that installed
     * it ends.
     */
    fun unbindDialogAbandonment() {
        boundDialogManager?.onRequestAbandoned = null
        boundDialogManager = null
    }

    // Owns the outcome of ONE dispatch, for the caller that dispatched it.
    //
    // NOT A SHARED SLOT. A field on the executor belongs to whoever writes it last: two turns' outcomes
    // would race for one place, and a caller reading it afterwards cannot tell whose it is. The caller
    // mints this and keeps it, so the value belongs to the REQUEST rather than to the object that
    // happened to perform it.
    class TurnOutcome {
        @Volatile
        var action: CloudRouteActionOutcome = CloudRouteActionOutcome.None
            internal set
    }

    suspend fun execute(intent: VoiceIntent, outcome: TurnOutcome = TurnOutcome()): VoiceResponse {
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
                // The PREPARED TURN when the caller carries one; otherwise preparation itself
                // resolves a retry, because the SINK retains turns by logical request and returns the
                // existing one unchanged. Retention therefore lives in one place, and a direct sink
                // caller and this executor cannot disagree about what a retry is.
                //
                // THE RETURNED TURN'S OWN ORIGIN IS WHAT MATTERS. A retained turn carries the origin it
                // was prepared under, which may be OLDER than this dispatch's stamp -- so validating the
                // INCOMING stamp would pass while the turn being sent belongs to a superseded window.
                // The live-authority check therefore runs on the origin of the turn that will actually
                // be dispatched.
                //
                // AN UNKNOWN RETRY IS REFUSED. A dispatch naming a logical request that was never
                // prepared is not a retry of anything -- treating it as new input would silently create
                // a conversation for a request whose own identity is unresolvable, which is the
                // substitution this whole mechanism exists to prevent.
                // The refusal applies to a RETRY, not to a first attempt: a first attempt naming a
                // logical request is exactly how that request comes to exist.
                if (intent.isRetry) {
                    val logical = intent.logicalRequestId
                    val known = logical != null &&
                        (intent.preparedTurn != null || retainedForLogical(logical) != null)
                    if (!known) {
                        Timber.i("[Voice] refused: retry of unknown logical request ${intent.logicalRequestId}")
                        return VoiceResponse.Silent
                    }
                }
                // A RETRY THAT THE SINK WILL RESOLVE is validated on the turn the sink HOLDS, before it
                // is asked to dispatch it: the retained turn's origin is the one that decides, and it
                // may be older than this dispatch's stamp.
                val resolvedBySink = intent.logicalRequestId
                    ?.takeIf { intent.isRetry }
                    ?.let { retainedForLogical(it) }
                val sinkResolvedOrigin = resolvedBySink?.capturedOrigin
                if (sinkResolvedOrigin != null && !gracePeriodSignal.isCurrent(sinkResolvedOrigin)) {
                    Timber.i("[Voice] superseded retry discarded (stamp=$sinkResolvedOrigin)")
                    return VoiceResponse.Silent
                }
                val alreadyPrepared = intent.preparedTurn
                if (alreadyPrepared != null) {
                    // The TURN'S origin, not the dispatch's: after a successor window opens, a retry of
                    // an older logical request still carries the older origin, and it is that turn
                    // which must be refused. Checking the incoming stamp let the old turn through.
                    val captureForRetry = alreadyPrepared.capturedOrigin
                    if (captureForRetry != null && !gracePeriodSignal.isCurrent(captureForRetry)) {
                        Timber.i("[Voice] superseded retry discarded (stamp=$captureForRetry)")
                        return VoiceResponse.Silent
                    }
                    // THE SAME CONSUMPTION AS THE OTHER DISPATCH SITE: feedback out, action outcome
                    // recorded. A retry that returns only its feedback would hide an unknowable action
                    // on exactly the path where the caller most needs to know whether it ran.
                    val retried = cloudRouteSink.dispatchPrepared(alreadyPrepared)
                    outcome.action = retried.action
                    return retried.feedback
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
                    logicalRequestId = intent.logicalRequestId,
                    // THE RETRY FLAG IS FORWARDED. Without it the sink cannot tell a retry from new
                    // input, so a retry whose identity was retired between the executor's entry guards
                    // and this preparation was silently MINTED AS A NEW REQUEST and sent -- the exact
                    // defect the refusal exists to prevent.
                    isRetry = intent.isRetry,
                    sourceLanguage = intent.sourceLanguage,
                ) ?: return VoiceResponse.Silent
                // DISPATCHED THROUGH THE ALREADY-PREPARED TURN, so the first attempt and a retry take
                // the SAME path and the identity is the one fixed above rather than a second one minted
                // here.
                val dispatched = cloudRouteSink.dispatchPrepared(preparedTurn)
                // THE REQUEST-OWNED RESULT IS CONSUMED HERE, at the production dispatch site. Its
                // FEEDBACK is what the user hears; its ACTION OUTCOME is recorded so a caller can tell a
                // confirmed action from one that was dispatched without a knowable outcome. Reading only
                // the feedback would treat an unknowable execution as if nothing had happened.
                outcome.action = dispatched.action
                // Noted after the dispatch: this window now has a request, so a LATER one continues
                // rather than beginning.
                captured?.let { gracePeriodSignal.notePreparedRequest(it) }
                dispatched.feedback
            }

            is VoiceIntent.Queue -> executeQueue(intent)

            is VoiceIntent.PlaybackQuery -> executePlaybackQuery(intent)

            is VoiceIntent.StatsQuery -> executeStatsQuery(intent)
        }
        // THE OUTCOME GUARD BELONGS HERE, at the site that recognizes -- not only at a later caller.
        // Recognizing unconditionally here meant a turn whose action outcome could not be established
        // still earned the recognized-command privileges before any caller could check, so a guard added
        // downstream was defeated by this call. ONE RULE, AT THE SITE THAT ACTS.
        if (capabilityOf(intent, outcome).allowsRecognition) {
            gracePeriodSignal.onCommandRecognized(
                fromGeneration = intent.windowGenerationOf(),
                restoresAllowance = intent.restoresAllowance(),
            )
        }
        return response
    }

    /**
     * What this turn's outcome permits, decided in ONE place for every caller.
     *
     * AN UNKNOWN CLOUD ACTION IS NOT A RECOGNIZED COMMAND. The turn dispatched an action whose outcome
     * could not be established, so it has not been shown to have done anything and must not earn the
     * window extension or the escalation reset that recognition grants. A local intent carries no cloud
     * action at all, so it is unaffected.
     */
    internal fun capabilityOf(
        intent: VoiceIntent,
        outcome: TurnOutcome,
    ): TurnCapability = when {
        intent !is VoiceIntent.CloudRoute -> TurnCapability.Recognized
        outcome.action == CloudRouteActionOutcome.Unknown -> TurnCapability.Unverified
        else -> TurnCapability.Recognized
    }

    /** What a completed turn is allowed to claim. */
    enum class TurnCapability(val allowsRecognition: Boolean) {
        /** The turn may be treated as a recognized command. */
        Recognized(allowsRecognition = true),

        /** The action's outcome could not be established, so the turn claims nothing. */
        Unverified(allowsRecognition = false),
    }

    private suspend fun executePlayback(intent: VoiceIntent.Playback): VoiceResponse = when (intent) {
        VoiceIntent.Playback.Pause -> playbackSink.pause()

        VoiceIntent.Playback.Resume -> playbackSink.resume().asLocalResponse()

        is VoiceIntent.Playback.SeekRelative -> {
            // deltaSeconds in seconds; null means "no amount stated — sink
            // should apply its configured interval in direction".
            when (val delta = intent.deltaSeconds) {
                null -> {
                    // No amount stated; sink applies its interval in direction.
                    // Pass null so the sink uses the app's configured interval.
                    when (intent.direction) {
                        "backward" -> playbackSink.skipBackward(null).asLocalResponse()
                        else -> playbackSink.skipForward(null).asLocalResponse()
                    }
                }

                0 -> VoiceResponse.Silent

                // explicit "stay put"
                in Int.MIN_VALUE..-1 -> playbackSink.skipBackward(abs(delta)).asLocalResponse()

                else -> playbackSink.skipForward(delta).asLocalResponse()
            }
        }

        is VoiceIntent.Playback.SeekAbsolute -> playbackSink.seekTo(
            intent.positionSeconds,
            episodeDurationSeconds = currentEpisodeDurationSeconds(),
        ).asLocalResponse()

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

/**
 * The outcome of an episode-scoped playback operation.
 *
 * IT IS NOT [VoiceResponse]. The sink answers `Silent` for an ACCEPTED operation and for a REFUSED one
 * alike, so a caller reading the response cannot tell them apart -- and a caller that commits history
 * from a refusal records an action that never happened. This says plainly which occurred.
 *
 * [Accepted] carries no position: what to record is the caller's own pre-action value, and this type
 * must not imply the sink knows it.
 */
sealed interface EpisodeOperationResult {
    /**
     * The operation reached the player, on the episode it was bound to.
     *
     * [episodeId] and [preActionPositionMs] ARE PART OF THE RESULT, not something a caller re-reads
     * afterwards. A caller that reads the live state once the operation was accepted reads whatever is
     * selected NOW, which a later change may have made a different recording -- and history recorded
     * against it would attribute the position to an episode the action never touched. The owner decides
     * the episode when it accepts the operation, so the owner's answer is what history must use.
     */
    data class Accepted(
        val episodeId: String?,
        val preActionPositionMs: Long?,
    ) : EpisodeOperationResult

    /** The operation was refused -- a different episode is selected, or the binding was absent. */
    data object Refused : EpisodeOperationResult

    /**
     * The operation was ISSUED and no outcome came back, so whether it ran is UNKNOWN.
     *
     * DISTINCT FROM [Refused], and the distinction is the whole point. A refusal is a DECISION a caller
     * may act on; unknown means we do not know, so RETRYING COULD REPEAT an operation the other side
     * already performed. Collapsing it into Refused would make an unknowable outcome look safely
     * retryable, and collapsing it into [Accepted] would claim a success nobody observed.
     */
    data class Unknown(
        val episodeId: String?,
        val preActionPositionMs: Long?,
    ) : EpisodeOperationResult
}

/**
 * The response a LOCAL playback intent reports for an episode-scoped operation.
 *
 * Local intents carry no cloud episode binding -- their episode-independence is the existing contract --
 * so they are not refused for one and this maps the outcome back to the `Silent` they always returned.
 * The EPISODE-SCOPED caller does not use this: it reads the outcome directly, because a refusal there
 * must not be reported as a performed action.
 */
fun EpisodeOperationResult.asLocalResponse(): VoiceResponse = VoiceResponse.Silent

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

    /**
     * Resume, only while [expectedEpisodeId] is still the selected episode.
     *
     * Null means the caller has no episode-specific subject -- the standalone resume action is
     * episode-INDEPENDENT -- while a quote's resume carries the recording it was played from, because
     * resuming a replacement would start audio the user never asked for under a request about a
     * different episode.
     */
    suspend fun resume(expectedEpisodeId: String? = null): EpisodeOperationResult
    suspend fun skipForward(seconds: Int?, expectedEpisodeId: String? = null): EpisodeOperationResult
    suspend fun skipBackward(seconds: Int?, expectedEpisodeId: String? = null): EpisodeOperationResult

    /**
     * Seek to a signed absolute episode position, ON [expectedEpisodeId].
     *
     * [expectedEpisodeId] MAKES VALIDATION AND MUTATION ONE DECISION. A separate check cannot work: the
     * selection can change between the check and the mutation, and `turnMutex` cannot exclude a change
     * made elsewhere in the app -- it only serializes this turn's own operations. Passing the episode
     * the position was computed for lets the owner that reads the current selection refuse, atomically,
     * a mutation addressed to a different recording.
     *
     * Null means the caller has no episode-specific subject: the operation is episode-INDEPENDENT (a
     * pause, a resume) and is not refused on this ground.
     *
     * The schema's semantics belong to this call rather than to its callers: non-negative values are
     * offsets from the beginning, 0 is the beginning, and a NEGATIVE value is an offset back from the
     * episode end. [episodeDurationSeconds] is what makes the negative case resolvable -- clamping it
     * to zero would turn "seek back 30 s from the end" into "seek to the beginning".
     */
    suspend fun seekTo(
        positionSeconds: Int,
        episodeDurationSeconds: Int,
        expectedEpisodeId: String? = null,
    ): EpisodeOperationResult
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

/**
 * What a cloud turn produced, OWNED BY THE REQUEST THAT DISPATCHED IT.
 *
 * [feedback] is what the user should hear; [action] is what the turn's playback action ACHIEVED, which is
 * a separate fact: a turn can produce feedback and still have dispatched an action whose outcome is
 * unknown. A caller that only sees the feedback cannot tell those apart, and would treat an unknowable
 * execution as if nothing had happened.
 */
data class CloudTurnResult(
    val feedback: VoiceResponse,
    val action: CloudRouteActionOutcome,
)

/**
 * What the turn's playback action achieved, as far as the client could OBSERVE.
 *
 * Mirrors the sink's own three-valued outcome so it can travel OUT of the sink without the caller
 * depending on a nested type.
 */
enum class CloudRouteActionOutcome {
    /** No action was performed or attempted. */
    None,

    /** The action was ISSUED and its outcome could not be established. */
    Unknown,

    /** The action was CONFIRMED performed. */
    Confirmed,
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
    ): CloudTurnResult

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
        /** The logical request this turn belongs to, for the retry path. */
        logicalRequestId: String? = null,
        /** True when the caller is retrying, so a lookup miss refuses rather than prepares. */
        isRetry: Boolean = false,
        /** ASR source language retained with the prepared request, including retries. */
        sourceLanguage: String? = null,
    ): au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn?

    /**
     * Installs [consumer] as the release target for a logical request that is no longer retryable.
     *
     * INVERTED ON PURPOSE. Binding this as a constructor dependency would make the sink and the dialog
     * manager depend on each other -- the router already reaches the manager -- and Dagger rejects the
     * cycle. The owner registers its release here, so the connection is one-way and the graph stays
     * acyclic.
     */
    fun onRetentionReleased(consumer: (logicalRequestId: String) -> Unit)

    /**
     * Forgets the turn retained for [logicalRequestId]: no retry of it is coming.
     *
     * Part of the CONTRACT rather than a detail of one implementation: the release has to work through
     * the interface, or a caller could reach a sink that silently ignores it.
     */
    fun forgetRetained(logicalRequestId: String)

    /**
     * Ends the window [endedGeneration], retiring what it held, and reports the retired identities.
     *
     * PART OF THE CONTRACT rather than a detail of one implementation -- the same reason as
     * [forgetRetained]. [endedGeneration] is the window that ENDED, which is not the one now current.
     */
    fun endWindow(endedGeneration: Long): List<String>

    /** The turn retained for [logicalRequestId], so a caller can tell a retry from new input. */
    fun retainedTurnFor(
        logicalRequestId: String,
    ): au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn?

    /**
     * Dispatches an ALREADY PREPARED turn -- the controller retry path.
     *
     * Through the SAME lifecycle as a first dispatch, so a retry registers, holds its place and cleans
     * up like any other turn, and is re-validated against its own captured window.
     */
    suspend fun dispatchPrepared(
        prepared: au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn,
    ): CloudTurnResult

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
    ): CloudTurnResult = routeToCloud(
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
