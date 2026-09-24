package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteCapabilities
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteClient
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteContext
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteConversationEntry
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteErrorCodes
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteEvent
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteHint
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteLimits
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudSearchResults
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.CloudConfig
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.CloudIdentity
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintTimingManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconId
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.SpokenTemplateResolver
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.PlaybackContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
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
    private val resolveUserId: () -> String,
    private val playbackSink: VoicePlaybackSink,
    private val fingerprintTimingManager: FingerprintTimingManager,
    private val playbackContextProvider: PlaybackContextProvider,
    private val cloudPlaybackContextState: CloudPlaybackContextState,
    private val analytics: CloudRouteAnalytics,
    private val routeInvoker: ((CloudRouteTurn) -> Flow<CloudRouteEvent>)?,
    /** Non-null only when this client can render structured discovery results. */
    private val searchResultsRenderer: CloudSearchResultsRenderer? = null,
    private val conversationMemory: CloudConversationMemory = CloudConversationMemory(),
    private val templateResolver: SpokenTemplateResolver = SpokenTemplateResolver(emptyMap()),
) : VoiceCloudRouteSink {

    @Inject constructor(
        cloudConfig: CloudConfig,
        cloudIdentity: CloudIdentity,
        playbackSink: VoicePlaybackSink,
        fingerprintTimingManager: FingerprintTimingManager,
        playbackContextProvider: PlaybackContextProvider,
        cloudPlaybackContextState: CloudPlaybackContextState,
        analytics: CloudRouteAnalytics,
        templateResolver: SpokenTemplateResolver,
    ) : this(
        resolveBaseUrl = cloudConfig::baseUrl,
        resolveUserId = cloudIdentity::userId,
        playbackSink = playbackSink,
        fingerprintTimingManager = fingerprintTimingManager,
        playbackContextProvider = playbackContextProvider,
        cloudPlaybackContextState = cloudPlaybackContextState,
        analytics = analytics,
        routeInvoker = null,
        searchResultsRenderer = null,
        conversationMemory = CloudConversationMemory(),
        templateResolver = templateResolver,
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
     * True while the shared player is paused *because of a cloud turn*.
     *
     * Ownership-scoped rather than per-turn: when a turn is superseded the
     * successor inherits the paused player, so the "we paused it" knowledge
     * has to transfer with ownership — otherwise a successor that fails
     * between registration and its own pause leaves audio paused with no
     * owner (a handoff gap, not just a race).
     */
    private var playerAutoPaused = false
    private val turnCounter = AtomicLong(0)

    override suspend fun routeToCloud(
        request: String,
        tier: VoiceIntent.CloudTier,
        context: PlaybackContext,
    ): VoiceResponse = routeTurn(request, tier, context, hint = null)

    override suspend fun routeToCloudWithHint(
        request: String,
        tier: VoiceIntent.CloudTier,
        context: PlaybackContext,
        hint: CloudRouteHint,
    ): VoiceResponse = routeTurn(request, tier, context, hint = hint)

    private suspend fun routeTurn(
        request: String,
        tier: VoiceIntent.CloudTier,
        context: PlaybackContext,
        hint: CloudRouteHint?,
    ): VoiceResponse {
        if (resolveBaseUrl().isBlank()) {
            return VoiceResponse.Spoken(templateResolver.resolve(KEY_CLOUD_COMING_SOON))
        }

        val myJob = currentCoroutineContext()[Job]
        val myId = turnCounter.incrementAndGet()
        val turnState = TurnState()
        var tokenBuffer = ""
        var outcome: VoiceResponse? = null

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
            if (previous != null && previous !== myJob) previous.cancel()

            val routeContext = buildRouteContext(context)
            val turn = CloudRouteTurn(
                request = request,
                context = routeContext,
                // Stable per logical turn; a transport retry reuses it, and the
                // server rejects a repeat rather than re-executing (no auto-retry
                // is attempted by this client).
                requestId = UUID.randomUUID().toString(),
                capabilities = advertisedCapabilities(),
                routeHint = hint,
            )
            val events = openRoute(turn)

            turnMutex.withLock {
                if (activeTurnId == myId) {
                    // Mark intent *before* the suspending pause: the player can
                    // already be paused when cancellation lands mid-call, and a
                    // flag set afterwards would leave the finally thinking there
                    // is nothing to restore — audio stuck paused.
                    playerAutoPaused = true
                    playbackSink.pause()
                }
            }

            // Flow.collect's action is crossinline — cannot return@routeTurn from it.
            events.collect { event ->
                if (outcome != null) return@collect
                when (event) {
                    is CloudRouteEvent.Action ->
                        executeAction(event.tool, event.action, event.params, turnState, myId)

                    is CloudRouteEvent.Token -> tokenBuffer += event.text

                    is CloudRouteEvent.Done -> {
                        analytics.recordTurn(
                            outcome = "done",
                            inputTokens = event.inputTokens,
                            outputTokens = event.outputTokens,
                        )
                        if (tokenBuffer.isNotEmpty()) {
                            conversationMemory.record(request, tokenBuffer)
                        }
                        outcome = if (tokenBuffer.isEmpty()) {
                            VoiceResponse.Silent
                        } else {
                            VoiceResponse.Spoken(tokenBuffer)
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
                        // A server-supplied message passes through (localising it
                        // is the server's job). When the code came from this
                        // client it carries no prose: resolve a localized
                        // template for it if one exists, and fall back to the
                        // error earcon when it doesn't — an internal diagnostic
                        // is a sound, not a foreign sentence.
                        val spoken = event.message.ifEmpty {
                            templateResolver.resolve(KEY_CLOUD_ERROR_PREFIX + event.code)
                        }
                        outcome = if (spoken.isEmpty()) {
                            VoiceResponse.Earcon(EarconId.ERROR)
                        } else {
                            VoiceResponse.Spoken(spoken)
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
                        restoreTransientAudioState()
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
        return CloudRouteClient(resolveBaseUrl(), resolveUserId()).route(turn)
    }

    /** Advertise a capability only when its renderer is actually available. */
    private fun advertisedCapabilities(): List<String> = if (searchResultsRenderer != null) {
        listOf(CloudRouteCapabilities.SEARCH_RESULTS_V1)
    } else {
        emptyList()
    }

    private fun buildRouteContext(context: PlaybackContext): CloudRouteContext {
        val state = cloudPlaybackContextState.snapshot()
        return CloudRouteContext(
            recentConversation = CloudRouteLimits.clampConversation(conversationMemory.recent())
                .takeIf { it.isNotEmpty() },
            episodeId = context.episodeId,
            podcastId = context.podcastId.takeIf { it.isNotEmpty() },
            referencePositionMs = context.referencePositionMs,
            clientPositionMs = context.clientPositionMs,
            recentReferencePositions = context.recentReferencePositions.ifEmpty {
                state.recentReferencePositions
            },
            previousReferencePositionMs = context.previousReferencePositionMs
                ?: state.previousReferencePositionMs,
        )
    }

    /** Owner-only: resume the player if this turn chain auto-paused it. */
    private suspend fun restoreTransientAudioState() {
        if (playerAutoPaused) {
            playbackSink.resume()
            playerAutoPaused = false
        }
    }

    private suspend fun executeAction(
        tool: String,
        action: String,
        params: Map<String, Any?>,
        turnState: TurnState,
        myId: Long,
    ) {
        if (tool != "playback") return

        // Player-state changes are serialized with ownership: a superseded
        // turn must not seek/resume/pause the player its successor now owns.
        turnMutex.withLock {
            if (activeTurnId != myId) return@withLock
            when (action) {
                "seek_to" -> {
                    val referenceMs = params.referencePositionMs() ?: return
                    capturePreActionPosition(referenceMs, turnState)
                    seekToReference(referenceMs)
                }

                "play_quote" -> {
                    val referenceMs = params.referencePositionMs() ?: return
                    capturePreActionPosition(referenceMs, turnState)
                    seekToReference(referenceMs)
                    playbackSink.resume()
                    playerAutoPaused = false
                }

                "stop_quote" -> {
                    val restoreMs = turnState.preQuotePositionMs ?: return
                    playbackSink.seekTo(restoreMs.toInt())
                }

                "pause" -> {
                    playbackSink.pause()
                    // Explicit pause: not ours to undo later.
                    playerAutoPaused = false
                }

                "resume" -> {
                    playbackSink.resume()
                    playerAutoPaused = false
                }
            }
        }
    }

    private fun capturePreActionPosition(referenceMs: Long, turnState: TurnState) {
        val previousPlaybackMs = playbackContextProvider.current().clientPositionMs
        // preQuotePositionMs stays on the playback timeline: stop_quote seeks
        // the local player back to it.
        turnState.preQuotePositionMs = previousPlaybackMs
        // previous_reference_position_ms is a reference-timeline value on the
        // wire — convert from the playback timeline (ad/intro offset). When no
        // mapping exists yet, omit the field rather than send a wrong-timeline
        // value.
        val previousReferenceMs = fingerprintTimingManager
            .referenceTime(previousPlaybackMs.toInt())
            ?.let { referenceSeconds -> (referenceSeconds * 1000).toLong() }
        cloudPlaybackContextState.record(
            referencePositionMs = referenceMs,
            previousReferencePositionMs = previousReferenceMs,
        )
    }

    private suspend fun seekToReference(referenceMs: Long) {
        val referenceSeconds = referenceMs / 1000.0
        val playbackMs = fingerprintTimingManager.playbackTimeMs(referenceSeconds)
            ?: referenceMs.toInt()
        playbackSink.seekTo(playbackMs.coerceAtLeast(0))
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
    }
}
