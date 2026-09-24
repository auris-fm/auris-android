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
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

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

    /** The in-flight turn, cancelled when a newer turn supersedes it. */
    private val activeTurnLock = Any()
    private var activeTurn: Job? = null

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

        // A newer turn supersedes the previous one: cancel it before starting.
        val myJob = currentCoroutineContext()[Job]
        synchronized(activeTurnLock) {
            // Register first, then cancel: the successor must already be the
            // current turn when the superseded turn unwinds, otherwise its
            // turn-identity guard sees itself as still current and restores
            // audio the successor is about to pause.
            val previous = activeTurn
            activeTurn = myJob
            if (previous != null && previous !== myJob) previous.cancel()
        }

        var tokenBuffer = ""
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
        val turnState = TurnState()
        val events = openRoute(turn)

        playbackSink.pause()
        turnState.didAutoPause = true

        // Flow.collect's action is crossinline — cannot return@routeToCloud from it.
        var outcome: VoiceResponse? = null
        try {
            events.collect { event ->
                if (outcome != null) return@collect
                when (event) {
                    is CloudRouteEvent.Action ->
                        executeAction(event.tool, event.action, event.params, turnState)

                    is CloudRouteEvent.Token -> tokenBuffer += event.text

                    is CloudRouteEvent.Done -> {
                        analytics.recordTurn(
                            outcome = "done",
                            inputTokens = event.inputTokens,
                            outputTokens = event.outputTokens,
                        )
                        restoreTransientAudioState(turnState)
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
                        restoreTransientAudioState(turnState)
                        // Genuinely unavailable evidence is a state the results
                        // renderer owns whenever one is present (three states,
                        // not two); the spoken message still goes out below.
                        if (searchResultsRenderer != null &&
                            turn.capabilities.contains(CloudRouteCapabilities.SEARCH_RESULTS_V1) &&
                            event.code == CloudRouteErrorCodes.RETRIEVAL_UNAVAILABLE
                        ) {
                            searchResultsRenderer.renderUnavailable()
                        }
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
            // NonCancellable: the resume path itself suspends (play-queue
            // loads), and on a cancelled turn it must still run to completion.
            //
            // Turn-identity guard: the *player* is shared, so a superseded
            // turn's unwind must not restore audio the newer turn has already
            // paused. Only the turn that is still current touches audio state,
            // and it clears its own registration as it goes.
            withContext(NonCancellable) {
                val stillCurrent = synchronized(activeTurnLock) { activeTurn === myJob }
                if (stillCurrent) {
                    restoreTransientAudioState(turnState)
                    synchronized(activeTurnLock) {
                        if (activeTurn === myJob) activeTurn = null
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
    ) {
        if (tool != "playback") return

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
