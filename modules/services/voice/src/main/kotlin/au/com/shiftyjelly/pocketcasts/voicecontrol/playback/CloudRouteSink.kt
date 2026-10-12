package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteCapabilities
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteContext
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteConversationEntry
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteErrorCodes
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteEvent
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteHint
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteLimits
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudSearchResults
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTokenProviding
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnRoute
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.CloudConfig
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.CloudIdentity
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintTimingManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.CloudAudioPlayer
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconId
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.SpokenLine
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.SpokenTemplateResolver
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.PlaybackContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

@Singleton
class CloudRouteSink internal constructor(
    private val resolveBaseUrl: () -> String,
    private val tokenProvider: CloudTokenProviding,
    private val playbackSink: VoicePlaybackSink,
    private val fingerprintTimingManager: FingerprintTimingManager,
    private val playbackContextProvider: PlaybackContextProvider,
    private val conversationAdmission: CloudConversationAdmission = CloudConversationAdmission(),
    private val cloudPlaybackContextState: CloudPlaybackContextState,
    private val analytics: CloudRouteAnalytics,
    private val routeInvoker: ((CloudRouteTurn) -> Flow<CloudRouteEvent>)?,
    /** Non-null only when this client can render structured discovery results. */
    private val searchResultsRenderer: CloudSearchResultsRenderer? = null,
    private val conversationMemory: CloudConversationMemory = CloudConversationMemory(),
    private val templateResolver: SpokenTemplateResolver = SpokenTemplateResolver(emptyMap()),
    private val currentLocale: () -> Locale = { Locale.getDefault() },
    /**
     * Whether the app is playing audio right now. The turn pauses the player to
     * hold the user's place, and restoring that pause is only legitimate if
     * there was playback to pause: claiming a pause that never happened resumes
     * a player the user had deliberately stopped.
     */
    private val isHostPlaying: () -> Boolean,
    /**
     * How many play/pause/stop commands the playback layer has seen. Sampled
     * *after* the turn takes its own pause, so the value expected at restore
     * accounts for that pause; any further command — a user's tap or their voice
     * — means someone else now owns the player's state and the turn must not
     * restore over them.
     */
    private val playbackCommandRevision: () -> Long = { 0L },
    /**
     * The current episode's duration in seconds, for resolving a NEGATIVE absolute position as an
     * offset back from the episode end. Zero when no episode is loaded, which makes such a seek
     * resolve to the beginning rather than to a position the episode does not have.
     */
    private val episodeDurationSeconds: () -> Int = { 0 },
    /**
     * Whether a privacy event has closed the window, so restoring the turn's pause would resume
     * playback the user's own privacy action ended. A privacy closure does not go through the
     * playback layer, so it does not advance the command revision and must be consulted separately.
     */
    private val isPrivacyClosed: () -> Boolean = { false },
    /** Monotonic privacy-closure count; a change across the turn means privacy ended it. */
    private val privacyClosureCount: () -> Long = { 0L },
    /** Audio player for cloud-delivered binary audio frames. */
    private val audioPlayer: CloudAudioPlayer? = null,
) : VoiceCloudRouteSink {

    @Inject constructor(
        cloudConfig: CloudConfig,
        tokenProvider: CloudTokenProviding,
        playbackSink: VoicePlaybackSink,
        fingerprintTimingManager: FingerprintTimingManager,
        playbackContextProvider: PlaybackContextProvider,
        cloudPlaybackContextState: CloudPlaybackContextState,
        analytics: CloudRouteAnalytics,
        templateResolver: SpokenTemplateResolver,
        currentLocale: () -> Locale,
        playbackContextMonitor: PlaybackContextMonitor,
        audioPlayer: CloudAudioPlayer?,
    ) : this(
        resolveBaseUrl = cloudConfig::baseUrl,
        tokenProvider = tokenProvider,
        playbackSink = playbackSink,
        fingerprintTimingManager = fingerprintTimingManager,
        playbackContextProvider = playbackContextProvider,
        cloudPlaybackContextState = cloudPlaybackContextState,
        analytics = analytics,
        audioPlayer = audioPlayer,
        routeInvoker = null,
        searchResultsRenderer = null,
        conversationMemory = CloudConversationMemory(),
        templateResolver = templateResolver,
        currentLocale = currentLocale,
        isHostPlaying = { playbackContextMonitor.isHostAudioActive.value },
        playbackCommandRevision = playbackContextMonitor::playbackCommandRevision,
        episodeDurationSeconds = playbackContextMonitor::episodeDurationSeconds,
        isPrivacyClosed = playbackContextMonitor::isPrivacyClosed,
        // Only closures that coincided with the host's audio stopping carry a playback fact. A
        // visibility-only closure must not strand a turn-owned pause: the hold policy releases a
        // turn's own pause when the answer completes, unless playback actually stopped or a user
        // command superseded it.
        privacyClosureCount = playbackContextMonitor::playbackStopClosureCount,
    )

    /**
     * Per-turn audio bookkeeping. Deliberately NOT instance state: this sink
     * is a singleton and a superseded turn unwinds on another thread, so
     * shared flags would let turn A's restore resume playback for turn B
     * (A resumes and clears the flag, then B streams over playing audio).
     */
    private class TurnState {
        var preQuotePositionMs: Long? = null
    }

    /**
     * Turn ownership and player-state transitions, serialized by one mutex.
     *
     * A monitor lock is not enough: the restore *suspends* (resume loads the
     * play queue), so checking ownership and then restoring outside the lock
     * lets a newer turn register and pause during that suspension — the older
     * turn then resumes the shared player under the newer turn.
     */
    private val turnMutex = Mutex()
    private var activeTurnId: Long = 0
    private var activeTurn: Job? = null

    /**
     * True while this turn still *owes* the player the restore of a pause it
     * applied — an obligation, not a description of the player's state. The
     * two part company on purpose: after a `pause` action the player is paused
     * while this is `false` (the user asked for that pause, so it is not ours
     * to undo), and while a turn plays it is `false` too.
     *
     * Ownership-scoped rather than per-turn: when a turn is superseded the
     * successor inherits the paused player, so the obligation has to transfer
     * with ownership — otherwise a successor that fails between registration
     * and its own pause leaves audio paused with no owner (a handoff gap, not
     * just a race).
     */
    private var playerAutoPaused = false

    /**
     * This turn reserved the pause at turn start but the server has not confirmed it.
     *
     * Under the hold contract the turn-start block RESERVES the hold -- the auto-resume decision needs
     * "was it playing before the stream", and that information exists only at turn start -- and the
     * server's deterministic hold frame CONFIRMS it. A reservation that is never confirmed (a short
     * answer that never commits to audible output) must be released without a pause ever having been
     * activated, which requires these two facts to be distinct: [playerAutoPaused] records an ACTUAL
     * hold, this records a claim on one.
     */
    private var pauseReserved = false

    /** The command revision in force when this turn chain took the pause. */
    private var pauseCommandRevision: Long = 0L
    private val turnCounter = AtomicLong(0)

    override suspend fun routeToCloud(
        request: String,
        tier: VoiceIntent.CloudTier,
        context: PlaybackContext,
        isLiveGraceTurn: Boolean,
        capturedOrigin: Long?,
        isWakeOriginated: Boolean,
    ): VoiceResponse = routeTurn(
        request = request,
        tier = tier,
        context = context,
        isLiveGraceTurn = isLiveGraceTurn,
        capturedOrigin = capturedOrigin,
        isWakeOriginated = isWakeOriginated,
        hint = null,
    )

    override suspend fun routeToCloudWithHint(
        request: String,
        tier: VoiceIntent.CloudTier,
        context: PlaybackContext,
        hint: CloudRouteHint,
        isLiveGraceTurn: Boolean,
        capturedOrigin: Long?,
        isWakeOriginated: Boolean,
    ): VoiceResponse = routeTurn(
        request = request,
        tier = tier,
        context = context,
        isLiveGraceTurn = isLiveGraceTurn,
        capturedOrigin = capturedOrigin,
        isWakeOriginated = isWakeOriginated,
        hint = hint,
    )

    /**
     * Prepares a turn ONCE, for a controller that may dispatch it more than once.
     *
     * The returned turn is immutable and already carries its identity: request ID, conversation handle
     * and mode were fixed when it was prepared. A RETRY reuses it unchanged -- it must not re-prepare,
     * because re-preparing would mint a second conversation and consume the wake selection a second
     * time, so a retried request would look like a new user act.
     *
     * Returns null when the origin has been superseded: nothing is sent, so no conversation is created
     * on behalf of a turn that has already been replaced.
     */
    override suspend fun prepareTurn(
        request: String,
        tier: VoiceIntent.CloudTier,
        context: PlaybackContext,
        isLiveGraceTurn: Boolean,
        capturedOrigin: Long?,
        isWakeOriginated: Boolean,
        hint: CloudRouteHint?,
        /** The logical request this turn belongs to, when the caller is preparing a retry. */
        logicalRequestId: String?,
        /** True when the caller is RETRYING, so a lookup miss is a refusal rather than a first attempt. */
        isRetry: Boolean,
    ): CloudRouteTurn? {
        // A RETRY REUSES ITS TURN rather than minting a new one. The caller names the logical request;
        // if a turn already exists for it, this returns THAT turn unchanged, so its request ID, handle
        // and mode are the ones the first attempt fixed. This is the sink's half of the retention the
        // executor keeps, so a direct caller and the executor resolve a retry identically.
        if (logicalRequestId != null) {
            conversationAdmission.retained(logicalRequestId)?.let { return it }
            // A lookup MISS IS NOT A REASON TO PREPARE. The comment above said so and the code did not:
            // it fell through and built a fresh turn, which resurrected a request that is not retryable.
            // Only a FIRST ATTEMPT may prepare under a new identity, and the caller says which it is
            // (`isRetry`), so a miss under a RETRY is refused here rather than filled in.
            if (isRetry) return null
            // NOT RETAINED. Either this logical request was never prepared, or it has been RETIRED --
            // its window closed, or its dialog was abandoned. Preparing a fresh turn under a retired
            // identity would resurrect a request that is no longer retryable, so absence is reported
            // rather than filled in.
            //
            // A first attempt is distinguished by the caller (`isRetry`), which is the only place that
            // can tell "prepare this" from "reuse what I prepared".
        }
        val origin = capturedOrigin ?: conversationAdmission.currentOriginForTest()
        val admission = conversationAdmission.prepare(
            origin = origin,
            isLiveGraceTurn = isLiveGraceTurn,
            isWakeOriginated = isWakeOriginated,
        ) ?: return null
        val built = CloudRouteTurn(
            request = request,
            context = buildRouteContext(context),
            requestId = UUID.randomUUID().toString(),
            capabilities = advertisedCapabilities(),
            routeHint = hint,
            conversationId = admission.conversationId,
            conversationMode = admission.mode,
            capturedOrigin = origin,
        )
        if (logicalRequestId != null) {
            conversationAdmission.retain(logicalRequestId, built)
        }
        return built
    }

    /**
     * Dispatches an ALREADY PREPARED turn, through the SAME lifecycle a first dispatch takes.
     *
     * NOT a shortcut to the transport. A retry must re-validate its origin before touching transport or
     * shared state, and must still register, hold the turn's place and clean up like any other turn --
     * a raw transport call would send an invalidated turn and skip the ownership bookkeeping the rest
     * of the engine depends on.
     *
     * Returns the response the normal path would, including the refusal when the retry's origin has
     * been invalidated in the meantime.
     */
    override fun retainedTurnFor(logicalRequestId: String): CloudRouteTurn? = conversationAdmission.retained(logicalRequestId)

    /** Retires the turns belonging to [endedGeneration], reporting the identities retired. */
    override fun endWindow(endedGeneration: Long): List<String> = conversationAdmission.endWindow(endedGeneration)

    /**
     * Forgets [logicalRequestId]'s retained turn: no retry of it is coming.
     *
     * The production binding for the dialog's abandonment callback. Without a binding, a prepared turn
     * is held for the life of the process, and a later dispatch naming a finished request would be
     * admitted as a retry of work that is done.
     */
    override fun forgetRetained(logicalRequestId: String) {
        conversationAdmission.forgetRetained(logicalRequestId)
        retentionReleased?.invoke(logicalRequestId)
    }

    /** The consumer notified when a logical request stops being retryable. */
    private var retentionReleased: ((logicalRequestId: String) -> Unit)? = null

    override fun onRetentionReleased(consumer: (logicalRequestId: String) -> Unit) {
        retentionReleased = consumer
    }

    override suspend fun dispatchPrepared(prepared: CloudRouteTurn): VoiceResponse = routeTurn(
        request = prepared.request,
        tier = VoiceIntent.CloudTier.Premium,
        context = PlaybackContext(),
        hint = prepared.routeHint,
        // The origin travels WITH the prepared turn, so a retry is validated against the window it was
        // captured under rather than against whatever is current when it is finally retried.
        capturedOrigin = prepared.capturedOrigin,
        isWakeOriginated = false,
        prepared = prepared,
    )

    private suspend fun routeTurn(
        request: String,
        tier: VoiceIntent.CloudTier,
        context: PlaybackContext,
        // True when this turn continues an answer still inside its grace window: it RESUMES rather
        // than creating a new conversation.
        isLiveGraceTurn: Boolean = false,
        /** The window this request was CAPTURED under, or null when it was not captured. */
        capturedOrigin: Long? = null,
        /** True when the captured window was opened by a wake: the turn begins a conversation. */
        isWakeOriginated: Boolean = false,
        hint: CloudRouteHint?,
        /**
         * An ALREADY PREPARED turn to dispatch instead of building one -- the retry path.
         *
         * Reusing it is the point: its request ID, handle and mode were fixed when it was prepared, so
         * a retry sends the SAME logical request rather than minting a second conversation.
         */
        prepared: CloudRouteTurn? = null,
    ): VoiceResponse {
        if (resolveBaseUrl().isBlank()) {
            val soon = localizedTemplate(KEY_CLOUD_COMING_SOON)
            return if (soon.isBlank()) {
                VoiceResponse.Earcon(EarconId.ERROR)
            } else {
                VoiceResponse.Spoken(soon)
            }
        }

        val myJob = currentCoroutineContext()[Job]
        val myId = turnCounter.incrementAndGet()
        // AUTHORITY IS RESOLVED BEFORE ANY SUCCESSOR-OWNED STATE CHANGES. Registration replaces the
        // active turn and cancels its job, so validating afterwards would let a REJECTED retry cancel
        // the successor it was rejected in favour of -- a refusal that still did damage. The refusal
        // returns here, before the try block, so nothing has been registered, cancelled or held.
        //
        // A REJECTED RETRY TERMINATES and does not fall through to fresh preparation: preparing would
        // mint a second conversation for a request that was already invalidated.
        val originForAdmission = prepared?.capturedOrigin
            ?: capturedOrigin
            ?: conversationAdmission.currentOriginForTest()
        val admitted = if (prepared != null) {
            conversationAdmission.validateForRetry(originForAdmission) ?: run {
                Timber.i("[CloudRoute] retry discarded: its origin is no longer valid")
                return VoiceResponse.Silent
            }
        } else {
            conversationAdmission.prepare(
                origin = originForAdmission,
                isLiveGraceTurn = isLiveGraceTurn,
                isWakeOriginated = isWakeOriginated,
            ) ?: run {
                Timber.i("[CloudRoute] turn discarded: its origin was superseded before admission")
                return VoiceResponse.Silent
            }
        }
        val turnState = TurnState()
        var tokenBuffer = ""
        var executedAction = false
        var handledResult = false
        var audioPlayed = false
        var ducked = false
        // Latched: once a privacy event ends this turn, a later wake must not re-authorise it. Kept
        // per-turn so an unrelated turn's closure cannot block this one either. Compared as a count
        // taken when the turn commits to suspending, because a closure during the suspension is not
        // observable afterwards: a wake clears the flag before this turn's cleanup runs.
        var privacyClosuresAtPause: Long? = null
        var outcome: VoiceResponse? = null
        // A local playback failure is a DIFFERENT fact from the turn's server terminal outcome, so it
        // is tracked separately: the server result is preserved and reported as its own outcome, and
        // the local failure is surfaced alongside it rather than replacing or replaying anything.
        var localPlaybackFailed = false
        audioPlayer?.onWriteFailure = { frames ->
            localPlaybackFailed = true
            Timber.w("[CloudRoute] answer audio was cut short by a sink refusal at %d frames", frames)
        }

        // Everything from registration onward sits inside the cleanup path: a
        // throw or cancellation during setup (context build, route opening, the
        // initial pause) must still release ownership and restore audio, or the
        // successor could inherit a paused player with no owner.
        try {
            // A newer turn supersedes the previous one. Registration and the
            // initial pause share the turn mutex, so a predecessor's suspending
            // restore cannot land between them.
            val previous = turnMutex.withLock {
                val previousTurn = activeTurn
                activeTurn = myJob
                activeTurnId = myId
                previousTurn
            }
            // The live origin is declared here ONLY when the caller supplied no captured window. With
            // a captured origin, the window belongs to the DIALOG REQUEST's registration -- declaring
            // the sink's own counter would overwrite it and make every captured request look stale.
            if (capturedOrigin == null) {
                conversationAdmission.beginOrigin(myId)
            }
            if (previous != null && previous !== myJob) previous.cancel()

            val routeContext = buildRouteContext(context)
            // The turn's WIRE identity: the frame's `turn_id` is this value, and a deterministic hold
            // must be attributed against it. It is a different identity from the local `myId` counter,
            // and it is read straight off the turn that was built so the two cannot drift.
            var myRequestId: String? = null
            // Prepared HERE, from this turn's captured origin -- not resolved at send time, where a
            // retry or a superseded origin would borrow whichever handle was then current. A turn whose
            // origin is no longer live is discarded rather than admitted against someone else's handle.
            // The origin is the one the REQUEST was captured under, supplied by the caller that knows
            // the dialog window -- NOT the sink's own turn counter, which increments per execution and
            // therefore cannot express "this request belongs to the window it was registered in". A
            // request with no captured window falls back to the declared-live origin.
            // The admission was resolved BEFORE registration (see above), so a refusal has already
            // returned without touching the active turn.
            val origin = prepared?.capturedOrigin
                ?: capturedOrigin
                ?: conversationAdmission.currentOriginForTest()
            val admission = admitted
            val turn = prepared ?: CloudRouteTurn(
                request = request,
                context = routeContext,
                conversationId = admission.conversationId,
                conversationMode = admission.mode,
                capturedOrigin = origin,
                // Stable per logical turn; a transport retry reuses it, and the
                // server rejects a repeat rather than re-executing (no auto-retry
                // is attempted by this client).
                requestId = UUID.randomUUID().toString().also { myRequestId = it },
                capabilities = advertisedCapabilities(),
                routeHint = hint,
            )
            val events = openRoute(turn)

            // Hold the user's place: pause the host for the turn, and remember the obligation
            // to restore it. Taken under the same mutex as registration so a predecessor's
            // suspending restore cannot land between them — and only once the route is open,
            // so a turn that fails before it can run does not pause the user's playback only
            // to resume it.
            //
            // Ownership-scoped rather than per-turn: a successor inherits the already-paused
            // player instead of pausing twice, so the obligation transfers with ownership.
            // Gated on the host actually playing, because claiming a pause that never happened
            // would resume a player the user had deliberately stopped. The obligation is set
            // before the suspending call so a cancellation that lands mid-pause still restores;
            // the revision is sampled before it so a command that arrives during the suspension
            // is not swallowed into the value expected at restore.
            turnMutex.withLock {
                // RECORD the pre-stream state, and DO NOT PAUSE.
                //
                // The resume decision needs what playback was doing BEFORE the stream, which is only
                // knowable here -- so the metadata is captured at turn start. The PAUSE does not belong
                // here: the owning policy distinguishes short-answer ducking from
                // server-selected long-answer holding, and pausing unconditionally at turn start
                // prevents the ducking a short answer is entitled to. No pause happens until the
                // matching hold frame arrives, and a reservation the server never activates is not
                // auto-resumed.
                //
                // The closure count is sampled under the same mutex that serializes registration and
                // cleanup: a closure that lands while this turn is suspended is invisible to a live
                // flag once a wake clears it, but stays visible as a count difference. A turn that
                // starts after a closure samples the new count, so the closure neither blocks it nor
                // excuses a resume it did not take.
                if (activeTurnId == myId && !playerAutoPaused && !pauseReserved && isHostPlaying() && !isPrivacyClosed()) {
                    privacyClosuresAtPause = privacyClosureCount()
                    pauseCommandRevision = playbackCommandRevision()
                    // Reserved, NOT activated: `playerAutoPaused` stays false so nothing restores a
                    // pause that never happened, and the frame is what turns this into a real hold.
                    pauseReserved = true
                }
            }

            // Flow.collect's action is crossinline — cannot return@routeTurn from it.
            events.collect { event ->
                if (outcome != null) return@collect
                when (event) {
                    is CloudRouteEvent.Connected -> {
                        // Handshake acknowledgement, and the frame that carries the codec the
                        // server negotiated. Propagate it so the player builds the right decoder
                        // and sample rate before any binary frame arrives.
                        audioPlayer?.setCodec(event.codec)
                    }

                    is CloudRouteEvent.Action -> {
                        // A deterministic HOLD carries `turn_id` and is the turn's own: the engine
                        // sends it at its delivery commitment, meaning "this turn is about to speak".
                        // It is attributed by that identity rather than by arrival order, because the
                        // terminal rule is "release nothing unless THIS turn took the hold, and never
                        // release a successor's" -- which arrival order cannot answer once two turns
                        // overlap. A hold for another turn is ignored outright: it belongs to a turn
                        // that is not this one, so acting on it would take or drop a hold we do not own.
                        //
                        // A frame WITHOUT `turn_id` is an app operation, answered with a typed result,
                        // and it keeps the existing path unchanged.
                        if (event.turnId != null && event.turnId != myRequestId) {
                            return@collect
                        }
                        // The delivery commitment ACTIVATES this turn's hold: it is the moment the
                        // server commits to a long answer, so the user's place is now taken. This is
                        // the ONLY place the turn pauses -- turn start records the metadata and does
                        // not touch playback, so a short answer keeps the ducking it is entitled to.
                        //
                        // The reservation is spent here, which is what keeps an unactivated one
                        // distinguishable from a taken hold: an unactivated reservation is dropped at
                        // the turn's end without a pause ever having happened, and nothing auto-resumes
                        // it.
                        if (event.turnId != null && event.action == "pause") {
                            turnMutex.withLock {
                                if (activeTurnId == myId && pauseReserved && !playerAutoPaused && isHostPlaying() && !isPrivacyClosed()) {
                                    pauseReserved = false
                                    playerAutoPaused = true
                                    // Declared as an ownership fact, not inferred from the tag: the
                                    // user's own pause reaches the player through the same
                                    // VOICE_COMMANDS interface.
                                    playbackSink.pauseAsHold()
                                }
                            }
                            return@collect
                        }
                        // Only a *performed* action counts: executeAction ignores an unknown tool,
                        // an unknown action, missing parameters and superseded turns, and an ignored
                        // action followed by a blank Done must not pass for a turn that delivered
                        // something.
                        executedAction = executeAction(
                            event.tool,
                            event.action,
                            event.params,
                            turnState,
                            myId,
                            plannedEpisodeId = context.episodeId,
                        ) || executedAction
                    }

                    is CloudRouteEvent.AudioFrame -> {
                        // Binary audio on the turn socket: submit to the player for buffered
                        // playback (the assistant's reply is delivered as audio, not tokens).
                        // Duck the host player on the first frame; restore on Done.
                        if (!audioPlayed) {
                            audioPlayed = true
                            playbackSink.duck()
                            ducked = true
                        }
                        audioPlayer?.submitFrame(event.bytes)
                        audioPlayer?.play()
                    }

                    // No frame this route receives produces a Token — the Worker does emit `token`
                    // frames, but not over this path — so the branch is inert here; it is kept because
                    // the event type is part of the contract model and the route tests drive it
                    // directly.
                    is CloudRouteEvent.Token -> tokenBuffer += event.text

                    is CloudRouteEvent.Done -> {
                        analytics.recordTurn(
                            outcome = "done",
                            inputTokens = event.inputTokens,
                            outputTokens = event.outputTokens,
                        )
                        // Restore the host player to full volume.
                        if (ducked) {
                            playbackSink.restore()
                            ducked = false
                        }
                        // Wait for the output to be OBSERVED to have played the submitted frames.
                        // drainAndStop submitted the frames and stopped immediately -- submission is
                        // not playback, and the tail still in the device buffer was cut off. Per the
                        // completion ruling, the turn ends normally only when the playback head
                        // reaches what was written; an unconsumed or unmeasurable result is recorded
                        // on the local axis, like a refused write, without rewriting the server's
                        // terminal outcome.
                        if (audioPlayed) {
                            val sink = audioPlayer
                            val completion = sink?.let {
                                try {
                                    it.drainAndAwaitCompletion()
                                } catch (c: kotlinx.coroutines.CancellationException) {
                                    throw c
                                } catch (e: Exception) {
                                    Timber.w(e, "[CloudRoute] awaiting playback completion failed")
                                    CloudAudioPlayer.Completion.Unknown
                                }
                            } ?: CloudAudioPlayer.Completion.Unknown
                            if (completion != CloudAudioPlayer.Completion.Consumed) {
                                Timber.w(
                                    "[CloudRoute] playback ended %s (head unobserved or lagging)",
                                    completion,
                                )
                                localPlaybackFailed = localPlaybackFailed ||
                                    completion == CloudAudioPlayer.Completion.Unconsumed
                            }
                        }
                        // Not redundant with the memory's own blank check: a
                        // blank answer is not an exchange, and record() would
                        // still add the user half of one.
                        if (tokenBuffer.isNotBlank()) {
                            conversationMemory.record(request, tokenBuffer)
                        }
                        // The local playback failure is reported on its own axis, so the server's
                        // terminal outcome is preserved rather than being rewritten by a local
                        // fault. It is not a replay trigger either: the answer is simply reported
                        // as not fully delivered.
                        if (localPlaybackFailed) {
                            Timber.w("[CloudRoute] turn completed on the server but its audio was cut short locally")
                        }
                        outcome = when {
                            localPlaybackFailed -> VoiceResponse.Earcon(EarconId.ERROR)

                            audioPlayer?.audioWritten == true -> VoiceResponse.Silent

                            audioPlayer != null -> {
                                // WebSocket path expected audio but none arrived —
                                // explicit failure rather than speaking leftover tokens.
                                VoiceResponse.Earcon(EarconId.ERROR)
                            }

                            tokenBuffer.isNotBlank() -> VoiceResponse.Spoken(tokenBuffer)

                            // The turn delivered something else — an action it actually performed,
                            // or a result it rendered. Silence is right: that was the answer.
                            executedAction || handledResult -> VoiceResponse.Silent

                            else -> {
                                // No tokens and nothing done: an unexpected empty turn, not a search
                                // that found nothing. The server's guard should make this
                                // unreachable, so this covers old servers and regressions. Same
                                // localized-or-earcon rule as the other spoken fallbacks: never
                                // speak base-language prose on a device set to another language.
                                val line = localizedTemplate(KEY_CLOUD_INCOMPLETE_TURN)
                                if (line.isBlank()) VoiceResponse.Earcon(EarconId.ERROR) else VoiceResponse.Spoken(line)
                            }
                        }
                    }

                    is CloudRouteEvent.Result -> {
                        // Renderer is present iff we advertised the capability;
                        // results never auto-play. An unknown result kind is
                        // ignored (forward compatibility) rather than rendered.
                        val renderer = searchResultsRenderer
                        if (renderer != null &&
                            event.results.kind == CloudSearchResults.KIND_EPISODE_RESULTS
                        ) {
                            if (event.results.items.isEmpty()) {
                                renderer.renderEmpty(event.results.scope)
                            } else {
                                renderer.renderResults(event.results)
                            }
                            // Rendered is delivered: the no-match state is the answer, so a blank
                            // Done after it must not also speak the incomplete-turn line.
                            handledResult = true
                        }
                    }

                    is CloudRouteEvent.Error -> {
                        tokenBuffer = ""
                        // Genuinely unavailable evidence is a state the results
                        // renderer owns whenever it is present AND this turn had a
                        // results surface (three states, not two).
                        // Capability advertisement is client-scoped (a client
                        // advertises only what it can render), so the renderer
                        // check is the whole gate — see advertisedCapabilities().
                        if (searchResultsRenderer != null &&
                            event.code == CloudRouteErrorCodes.RETRIEVAL_UNAVAILABLE
                        ) {
                            searchResultsRenderer.renderUnavailable()
                            // Rendered, so the user has been told; a spoken line on top of it would
                            // say the same thing twice in different words.
                            handledResult = true
                        }
                        // Code only: the server's message can carry upstream
                        // detail derived from the user's request or account,
                        // and it is already spoken/shown where it belongs.
                        // A server-signalled result state, not a client defect:
                        // keep it visible without putting a normal turn outcome
                        // at error level.
                        Timber.w(
                            "CloudRouteSink: turn error code=%s",
                            CloudRouteErrorCodes.normalizeForLog(event.code),
                        )
                        analytics.recordTurn(outcome = "error")
                        // The client speaks its own words for every failure and never the server's
                        // `message`. That field is user-facing on the edge and upstream detail on the
                        // assistant ("catalog provider: status 401 for
                        // /api/catalog/podcasts/<uuid>/episodes"), and no client can tell those two
                        // apart without knowing which service it reached. So: a template for the
                        // code, then a generic local line, then the earcon — untrusted text is never
                        // spoken, and a code nobody has seen yet still says something.
                        val spoken = if (handledResult) {
                            ""
                        } else {
                            localizedTemplate(KEY_CLOUD_ERROR_PREFIX + event.code)
                                .ifBlank { localizedTemplate(KEY_CLOUD_ERROR_GENERIC) }
                        }
                        // Rendered already means the user was told; adding a sound would say it again.
                        outcome = when {
                            handledResult -> VoiceResponse.Silent
                            spoken.isBlank() -> VoiceResponse.Earcon(EarconId.ERROR)
                            else -> VoiceResponse.Spoken(spoken)
                        }
                    }
                }
            }
        } finally {
            // Any exit path — normal return, upstream cancellation, timeouts,
            // unexpected throws — must not leave playback paused silently.
            // NonCancellable: the resume path itself suspends (play-queue loads),
            // and on a cancelled turn it must still run to completion.
            //
            // Ownership check and restore happen under one mutex, so no newer
            // turn can register and pause while this restore is suspended in
            // resume(); only the current turn touches audio state, and it clears
            // its own registration as it goes.
            withContext(NonCancellable) {
                turnMutex.withLock {
                    if (activeTurnId == myId) {
                        // Stop cloud audio if it was playing but the turn ended
                        // abnormally (cancellation, timeout, error).
                        if (audioPlayed) {
                            audioPlayer?.stop()
                        }
                        // Abandon duck focus if we requested it but didn't complete
                        // normally (Done already restores).
                        if (ducked) {
                            playbackSink.restore()
                            ducked = false
                        }
                        restoreTransientAudioState(privacyInvalidatedSince(privacyClosuresAtPause))
                        // The reservation is per turn-CHAIN, like the pause it claims: a turn that
                        // never received the server's confirmation leaves it set, and clearing it only
                        // when THIS turn still owns the audio state keeps a successor's reservation
                        // intact -- the successor inherits the paused player, so it inherits the claim.
                        pauseReserved = false
                        activeTurnId = 0
                        activeTurn = null
                    }
                }
            }
        }
        return outcome ?: VoiceResponse.Silent
    }

    private fun openRoute(turn: CloudRouteTurn): Flow<CloudRouteEvent> {
        routeInvoker?.let { return it(turn) }
        // Production runs over the WebSocket only (cloud-assistant.md, owner's ruling): a refused
        // upgrade fails the turn visibly rather than falling back, so a completed turn is evidence
        // the negotiated path served it. The choice lives in one place rather than beside a second
        // path — there is no second path to be beside.
        return CloudTurnRoute(resolveBaseUrl(), tokenProvider).route(turn)
    }

    /** Advertise a capability only when its renderer is actually available. */
    private fun advertisedCapabilities(): List<String> = if (searchResultsRenderer != null) {
        listOf(CloudRouteCapabilities.SEARCH_RESULTS_V1)
    } else {
        emptyList()
    }

    private fun buildRouteContext(context: PlaybackContext): CloudRouteContext {
        // The saved positions are requested FOR THIS EPISODE: a position recorded while another
        // episode played describes that episode, and sending it here would name a reference in a
        // recording the turn is not about.
        val state = cloudPlaybackContextState.snapshot(context.episodeId)
        val episodeId = context.episodeId?.takeIf { it.isNotEmpty() }
        // THE NO-EPISODE RULE IS APPLIED HERE, LAST, after every source of enrichment. The saved state
        // holds reference positions accumulated while an episode WAS playing; merging them into a turn
        // that has no episode sends positions belonging to a recording the turn is not about, and the
        // server rejects playback positions without an episode. Applying the rule at the provider alone
        // would not hold, because the enrichment below reintroduces them.
        val hasEpisode = episodeId != null
        return CloudRouteContext(
            // NO CLIENT CONVERSATION HISTORY ON A CLOUD FRAME. The owning spec forbids
            // `context.recent_conversation` here and requires a wire omission assertion: the Worker
            // owns cloud conversation history, so sending our own would duplicate it and let a
            // client-side copy diverge from the conversation the server is actually maintaining.
            // Conversation IDENTITY replaces it -- the turn says WHICH conversation it belongs to
            // rather than re-sending what it thinks was said.
            //
            // Local conversation memory stays LOCAL: the local intent path still uses it.
            recentConversation = null,
            // An absent episode stays absent: no substitution, and no empty string that would claim
            // a subject this turn does not have.
            episodeId = episodeId,
            podcastId = context.podcastId?.takeIf { it.isNotEmpty() },
            // Every playback position is omitted without an episode -- reference, client, recent and
            // previous alike. They are positions IN an episode; without one there is nothing they can
            // be positions in, and a saved value describes the episode it was saved from.
            referencePositionMs = context.referencePositionMs.takeIf { hasEpisode },
            clientPositionMs = context.clientPositionMs.takeIf { hasEpisode },
            recentReferencePositions = if (hasEpisode) {
                context.recentReferencePositions.ifEmpty { state.recentReferencePositions }
            } else {
                emptyList()
            },
            previousReferencePositionMs = if (hasEpisode) {
                context.previousReferencePositionMs ?: state.previousReferencePositionMs
            } else {
                null
            },
        )
    }

    /** A client-owned line for [key], or empty when this locale must not be spoken to: [SpokenLine]. */
    private fun localizedTemplate(key: String): String = SpokenLine.forKey(key, templateResolver, currentLocale())

    /**
     * Whether a privacy closure happened after the turn took its pause obligation. A live flag cannot
     * answer this: a later wake clears it while this turn is still suspended.
     */
    private fun privacyInvalidatedSince(atPause: Long?): Boolean = atPause != null && privacyClosureCount() != atPause

    /** Owner-only: resume the player if this turn chain auto-paused it. */
    private suspend fun restoreTransientAudioState(privacyInvalidated: Boolean) {
        if (!playerAutoPaused) return
        playerAutoPaused = false
        // The reservation goes with the pause it claims: releasing the hold ends the claim on it, so a
        // later turn can reserve again. Both are the same obligation seen from two sides -- one says a
        // pause HAPPENED, the other that this chain CLAIMS it -- and clearing only the first would
        // leave every later turn unable to reserve.
        pauseReserved = false
        // The turn is a long suspension, and playback can move underneath it.
        // Ownership is a question about commands, not state: a user who pressed
        // pause leaves the player in the same state the turn left it, so only a
        // command revision can tell their action from nobody acting.
        //
        // `privacyInvalidated` is this turn's own latch, not the live flag: a later wake clears the
        // flag, and in the interval before its turn registers ownership is still this turn's, so the
        // live flag would re-authorise a restore the user's privacy action ended.
        if (privacyInvalidated) {
            Timber.i("[VoicePipeline] cloud turn left playback alone (privacy closed)")
            return
        }
        if (playbackCommandRevision() != pauseCommandRevision) {
            Timber.i("[VoicePipeline] cloud turn left playback alone (someone else acted)")
            return
        }
        if (isHostPlaying()) {
            Timber.i("[VoicePipeline] cloud turn left playback alone (host is playing again)")
            return
        }
        playbackSink.resume()
        Timber.i("[VoicePipeline] cloud turn restored playback (resumed what it paused)")
    }

    /** Returns true only when an action was actually performed: an ignored action is not a delivery. */
    private suspend fun executeAction(
        tool: String,
        action: String,
        params: Map<String, Any?>,
        turnState: TurnState,
        myId: Long,
        plannedEpisodeId: String?,
    ): Boolean {
        if (tool != "playback") return false

        // AN ACTION BELONGS TO THE EPISODE IT WAS PLANNED AGAINST. Turn ownership is not enough:
        // selecting another episode opens NO new cloud turn, so `activeTurnId` still names this one and
        // its ownership guard does not fire -- while every playback verb addresses "whatever is playing",
        // which is now the other episode. A seek computed for A then lands in B at a position that means
        // nothing there, and an equivalent skip appends history to the wrong recording.
        //
        // THE EPISODE THE ACTION WAS PLANNED FOR travels WITH the mutation rather than being compared
        // here. A check at this point cannot hold: the selection can change between it and the seek, and
        // [turnMutex] only serializes THIS turn's operations -- it cannot exclude a change made anywhere
        // else in the app. So the binding is passed down and validated by the owner of what is playing,
        // as part of the same operation. Episode-independent operations pass null and are unaffected.
        val turnEpisodeId = plannedEpisodeId?.takeIf { it.isNotEmpty() }

        // Player-state changes are serialized with ownership: a superseded
        // turn must not seek/resume/pause the player its successor now owns.
        return turnMutex.withLock {
            if (activeTurnId != myId) return@withLock false
            when (action) {
                "seek_to" -> {
                    // The two forms are MUTUALLY EXCLUSIVE, not ordered: `position_seconds` is sink
                    // time ("Ordinary seek_to.position_seconds already uses sink seconds") while
                    // `reference_position_ms` is reference time needing the fingerprint mapping.
                    // Trying one and then the other expresses precedence where the contract requires
                    // exactly one, so a call carrying both would execute instead of being refused.
                    // Presence, not parseability: a field that is present but not a number still
                    // CLAIMS the form, so a call carrying it plus a valid other form supplies two
                    // forms and must be refused. Testing the parsed values instead would read an
                    // unparseable field as absent and execute the other one.
                    val hasPositionField = params.containsKey("position_seconds")
                    val hasReferenceField = params.containsKey("reference_position_ms")
                    if (hasPositionField == hasReferenceField) return@withLock false
                    val positionSeconds = params.positionSeconds()
                    val referenceMs = params.referencePositionMs()
                    if (hasPositionField && positionSeconds != null) {
                        // The signed semantics belong to the sink: a negative is an offset back from
                        // the episode end. Clamping it here would turn "seek back 30 s from the end"
                        // into "seek to the beginning", a different instruction.
                        val outcome = playbackSink.seekTo(positionSeconds, episodeDurationSeconds(), turnEpisodeId)
                        // HISTORY IS COMMITTED FROM THE ACCEPTED RESULT'S OWN PROVENANCE. Recording first
                        // meant a refused seek still appended its pre-action position; re-reading the live
                        // state afterwards would subject it to a later selection change. The owner decided
                        // the episode when it accepted, so its answer is what history uses.
                        if (outcome !is EpisodeOperationResult.Accepted) return@withLock false
                        capturePreActionPosition(positionSeconds * 1000L, turnState)
                        return@withLock true
                    }
                    // The reference branch is reached only when that form was supplied, so a null
                    // here means the value did not parse and the call is refused.
                    if (referenceMs == null) return@withLock false
                    // A reference target with no usable mapping, or no episode to be a position IN, is
                    // refused rather than approximated -- and history is committed ONLY when the seek
                    // was ACCEPTED, against the episode it was bound to.
                    if (!seekToReference(referenceMs, turnEpisodeId)) return@withLock false
                    capturePreActionPosition(referenceMs, turnState)
                    return@withLock true
                }

                "play_quote" -> {
                    val referenceMs = params.referencePositionMs() ?: return@withLock false
                    // Same rule: the seek is attempted FIRST and history follows only an accepted one.
                    if (!seekToReference(referenceMs, turnEpisodeId)) return@withLock false
                    capturePreActionPosition(referenceMs, turnState)
                    // THE RESUME IS BOUND TO THE EPISODE THE QUOTE WAS PLAYED FROM -- this is the call
                    // the quote's own resume makes, not the standalone "resume" action. A quote means
                    // "hear this, then carry on with the recording we were in", so resuming a
                    // replacement episode would start audio the user never asked for, under a turn
                    // about a different recording. Refused when the selection has moved.
                    if (turnEpisodeId != null &&
                        playbackContextProvider.current().episodeId != turnEpisodeId
                    ) {
                        return@withLock false
                    }
                    // Cleared before the suspending call so the flag never
                    // outlives the player state it refers to. (Here a throw
                    // would make the finally resume, which is what this action
                    // wanted anyway — the reorder is for uniformity, not safety.)
                    playerAutoPaused = false
                    pauseReserved = false
                    playbackSink.resume(turnEpisodeId)
                    true
                }

                "stop_quote" -> {
                    // THE RESTORE CARRIES ITS ORIGINAL EXPECTATION. The position to return to belongs to
                    // the episode the quote was played FROM, so a selection change during the quote must
                    // refuse the restore rather than seek the replacement episode back into a recording
                    // the user was not listening to. The outcome is honoured, not assumed.
                    val restoreMs = turnState.preQuotePositionMs ?: return@withLock false
                    val restored = playbackSink.seekTo(
                        restoreMs.toInt() / 1000,
                        episodeDurationSeconds(),
                        turnEpisodeId,
                    )
                    restored is EpisodeOperationResult.Accepted
                }

                "pause" -> {
                    // Explicit pause is not ours to undo later — clear the
                    // obligation before the suspending call so a throw cannot
                    // leave us thinking we still own a pause the user asked for.
                    playerAutoPaused = false
                    pauseReserved = false
                    playbackSink.pause()
                    true
                }

                "resume" -> {
                    // THE EPISODE BINDING APPLIES TO RESUME TOO. A resume is a player-state mutation, and
                    // a quote's "resume" means "carry on with the recording we were playing" -- resuming
                    // a replacement episode would start audio the user never asked for, under a turn
                    // about a different recording. Refused when the selection has moved.
                    if (turnEpisodeId != null &&
                        playbackContextProvider.current().episodeId != turnEpisodeId
                    ) {
                        return@withLock false
                    }
                    // As at play_quote: cleared first for uniformity, so a throw
                    // cannot leave the flag describing a state we failed to reach.
                    playerAutoPaused = false
                    pauseReserved = false
                    playbackSink.resume(turnEpisodeId)
                    true
                }

                "seek_relative" -> {
                    // delta_seconds is nullable for a reason: null means "no amount was stated,"
                    // which is the only case that applies the sink's own interval. A stated zero
                    // is a real delta meaning "stay put" — treated here as a no-op, matching
                    // the local executor's `0 -> Silent`. Falling through would move a user who
                    // named no movement by the interval, which is the defaulted-seek failure this
                    // dispatch exists to avoid.
                    //
                    // The match order matters and is load-bearing: a stated nonzero delta is
                    // matched FIRST, so it executes by its SIGN and its direction field is not
                    // consulted. Only a null delta consults `direction`, and only an unstated
                    // amount with an unrecognised direction reaches `else`. A stated delta whose
                    // direction string is unrecognised is NOT refused — it seeks by sign.
                    val delta = (params["delta_seconds"] as? Number)?.toInt()
                    val direction = params["direction"] as? String
                    when {
                        delta != null && delta != 0 -> {
                            if (delta > 0) {
                                playbackSink.skipForward(delta, turnEpisodeId) is EpisodeOperationResult.Accepted
                            } else {
                                playbackSink.skipBackward(-delta, turnEpisodeId) is EpisodeOperationResult.Accepted
                            }
                            true
                        }

                        delta == 0 -> true

                        // Neither amount nor direction stated: the spec explicitly permits the
                        // configured skip interval here, and the sink's own comment says null means
                        // exactly that.
                        delta == null && direction == null -> {
                            playbackSink.skipForward(null, turnEpisodeId) is EpisodeOperationResult.Accepted
                        }

                        delta == null && direction == "backward" -> {
                            playbackSink.skipBackward(null, turnEpisodeId) is EpisodeOperationResult.Accepted
                        }

                        delta == null && direction == "forward" -> {
                            playbackSink.skipForward(null, turnEpisodeId) is EpisodeOperationResult.Accepted
                        }

                        // An unstated amount with a stated unrecognised direction: refused rather
                        // than defaulted. Falling through to forward would move a user who asked
                        // for nothing recognisable. The `when` value IS the return value — it must
                        // not be discarded, or the sink reports the turn as delivered while
                        // nothing moved.
                        else -> false
                    }
                }

                // An action this build does not know: nothing happened, and the turn must not be
                // counted as delivered on the strength of it.
                else -> false
            }
        }
    }

    private fun capturePreActionPosition(referenceMs: Long, turnState: TurnState) {
        // AFTER ACCEPTANCE, SO THE SUBJECT IS THE EPISODE THE OPERATION WAS BOUND TO. This runs only
        // once the sink accepted the action, and the expected-episode operation is what establishes
        // that the player is still on [turnEpisodeId] -- so recording against it is recording against
        // the episode the position actually belongs to. Re-reading the live provider here would record
        // against whatever a later selection change made current, which is a different recording.
        val previousPlaybackMs = playbackContextProvider.current().clientPositionMs
        // preQuotePositionMs stays on the playback timeline: stop_quote seeks the local player back to
        // it. When NO episode is playing there is no position to return to, so this records null and
        // the restore path refuses -- substituting zero would send the user to the start of a
        // recording they were not listening to.
        turnState.preQuotePositionMs = previousPlaybackMs
        // previous_reference_position_ms is a reference-timeline value on the
        // wire — convert from the playback timeline (ad/intro offset). When no
        // mapping exists yet, omit the field rather than send a wrong-timeline
        // value.
        // With no episode playing there is no playback instant to map onto the reference timeline,
        // so the previous reference is absent rather than derived from a substituted zero.
        val previousReferenceMs = previousPlaybackMs?.let { playbackMs ->
            fingerprintTimingManager
                .referenceTime(playbackMs.toInt())
                ?.let { referenceSeconds -> (referenceSeconds * 1000).toLong() }
        }
        cloudPlaybackContextState.record(
            referencePositionMs = referenceMs,
            previousReferencePositionMs = previousReferenceMs,
            // Recorded AGAINST the episode it describes, so a later turn about a different episode
            // cannot inherit it.
            episodeId = playbackContextProvider.current().episodeId,
        )
    }

    /**
     * Seeks to a position expressed on the REFERENCE (ad-free) timeline.
     *
     * Requires a valid mapping; there is no fallback. Treating the reference milliseconds as sink
     * time assumes the two timelines coincide, which is exactly what dynamic ad insertion breaks --
     * the seek would land on the wrong audio while reporting success. `timeline-alignment` states the
     * fallback explicitly: `reference_position_ms` is null when alignment is unavailable, and the
     * server falls back to `client_position_ms` for transcript lookup. Nothing authorizes substituting
     * one timeline for the other, so an unavailable mapping refuses the action.
     *
     * @return true when the seek was performed, false when the mapping is unavailable.
     */
    private suspend fun seekToReference(referenceMs: Long, expectedEpisodeId: String?): Boolean {
        // AN EPISODE-SPECIFIC REFERENCE POSITION MUST BE BOUND TO AN EPISODE. A reference position is a
        // position IN a recording, so a null expectation here would turn it into an unrestricted seek:
        // the position would be applied to whatever happens to be playing, which is the substitution the
        // episode guard exists to prevent. Refused rather than approximated.
        if (expectedEpisodeId == null) return false
        val referenceSeconds = referenceMs / 1000.0
        val playbackMs = fingerprintTimingManager.playbackTimeMs(referenceSeconds) ?: return false
        // THE SINK'S OUTCOME IS THE OUTCOME. Returning true unconditionally reported an action as
        // PERFORMED even when the sink refused it -- and the caller commits history from this result, so
        // a refused seek still recorded its pre-action position against the episode.
        //
        // The sink resolves to Silent for both acceptance and refusal, so it cannot be read off the
        // response type; the expected-episode operation is what makes acceptance decidable here, because
        // a bound operation either reaches the player or is refused for its binding.
        return playbackSink.seekTo(
            (playbackMs / 1000).coerceAtLeast(0),
            episodeDurationSeconds(),
            expectedEpisodeId,
        ) is EpisodeOperationResult.Accepted
    }

    private fun Map<String, Any?>.positionSeconds(): Int? {
        val value = this["position_seconds"] ?: return null
        return when (value) {
            is Number -> value.toInt()
            else -> null
        }
    }

    private fun Map<String, Any?>.referencePositionMs(): Long? {
        val value = this["reference_position_ms"] ?: return null
        return when (value) {
            is Number -> value.toLong()
            else -> null
        }
    }

    companion object {
        private const val KEY_CLOUD_COMING_SOON = "general.cloud_coming_soon"
        private const val KEY_CLOUD_ERROR_PREFIX = "cloud_error_"
        private const val KEY_CLOUD_ERROR_GENERIC = "cloud_error_generic"
        private const val KEY_CLOUD_INCOMPLETE_TURN = "general.cloud_incomplete_turn"
    }
}
