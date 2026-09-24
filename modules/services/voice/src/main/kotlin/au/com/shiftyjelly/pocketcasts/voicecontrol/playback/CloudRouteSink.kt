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
) : VoiceCloudRouteSink {

    @Inject constructor(
        cloudConfig: CloudConfig,
        cloudIdentity: CloudIdentity,
        playbackSink: VoicePlaybackSink,
        fingerprintTimingManager: FingerprintTimingManager,
        playbackContextProvider: PlaybackContextProvider,
        cloudPlaybackContextState: CloudPlaybackContextState,
        analytics: CloudRouteAnalytics,
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
    )

    /**
     * Per-turn audio bookkeeping. Deliberately NOT instance state: this sink
     * is a singleton and a superseded turn unwinds on another thread, so
     * shared flags would let turn A's restore resume playback for turn B
     * (A resumes and clears the flag, then B streams over playing audio).
     */
    private class TurnState {
        var preQuotePositionMs: Long? = null
        var didAutoPause = false
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
            return VoiceResponse.Spoken(COMING_SOON_MESSAGE)
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
                    playbackSink.pause()
                    turnState.didAutoPause = true
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
                        if (searchResultsRenderer != null &&
                            turn.capabilities.contains(CloudRouteCapabilities.SEARCH_RESULTS_V1) &&
                            event.code == CloudRouteErrorCodes.RETRIEVAL_UNAVAILABLE
                        ) {
                            searchResultsRenderer.renderUnavailable()
                        }
                        Timber.e("CloudRouteSink: turn error code=%s message=%s", event.code, event.message)
                        analytics.recordTurn(outcome = "error")
                        outcome = if (event.message.isBlank()) {
                            VoiceResponse.Earcon(EarconId.ERROR)
                        } else {
                            VoiceResponse.Spoken(event.message)
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
                        restoreTransientAudioState(turnState)
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

    private suspend fun restoreTransientAudioState(turnState: TurnState) {
        if (turnState.didAutoPause) {
            playbackSink.resume()
            turnState.didAutoPause = false
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
                    turnState.didAutoPause = false
                }

                "stop_quote" -> {
                    val restoreMs = turnState.preQuotePositionMs ?: return
                    playbackSink.seekTo(restoreMs.toInt())
                }

                "pause" -> {
                    playbackSink.pause()
                    turnState.didAutoPause = false
                }

                "resume" -> {
                    playbackSink.resume()
                    turnState.didAutoPause = false
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
        private const val COMING_SOON_MESSAGE = "Cloud processing is coming soon"
    }
}
