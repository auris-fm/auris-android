package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteCapabilities
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteClient
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteContext
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteConversationEntry
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteEvent
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteHint
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteLimits
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn
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

    private var preQuotePositionMs: Long? = null
    private var didAutoPause = false

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
            activeTurn?.takeIf { it !== myJob }?.cancel()
            activeTurn = myJob
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
        val events = openRoute(turn)

        playbackSink.pause()
        didAutoPause = true

        // Flow.collect's action is crossinline — cannot return@routeToCloud from it.
        var outcome: VoiceResponse? = null
        try {
            events.collect { event ->
                if (outcome != null) return@collect
                when (event) {
                    is CloudRouteEvent.Action -> executeAction(event.tool, event.action, event.params)

                    is CloudRouteEvent.Token -> tokenBuffer += event.text

                    is CloudRouteEvent.Done -> {
                        analytics.recordTurn(
                            outcome = "done",
                            inputTokens = event.inputTokens,
                            outputTokens = event.outputTokens,
                        )
                        restoreTransientAudioState()
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
                        // results never auto-play.
                        val renderer = searchResultsRenderer
                        if (renderer != null) {
                            if (event.results.items.isEmpty()) {
                                renderer.renderEmpty(event.results.scope)
                            } else {
                                renderer.renderResults(event.results)
                            }
                        }
                    }

                    is CloudRouteEvent.Error -> {
                        tokenBuffer = ""
                        restoreTransientAudioState()
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
            withContext(NonCancellable) { restoreTransientAudioState() }
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

    private suspend fun restoreTransientAudioState() {
        if (didAutoPause) {
            playbackSink.resume()
            didAutoPause = false
        }
    }

    private suspend fun executeAction(tool: String, action: String, params: Map<String, Any?>) {
        if (tool != "playback") return

        when (action) {
            "seek_to" -> {
                val referenceMs = params.referencePositionMs() ?: return
                capturePreActionPosition(referenceMs)
                seekToReference(referenceMs)
            }

            "play_quote" -> {
                val referenceMs = params.referencePositionMs() ?: return
                capturePreActionPosition(referenceMs)
                seekToReference(referenceMs)
                playbackSink.resume()
                didAutoPause = false
            }

            "stop_quote" -> {
                val restoreMs = preQuotePositionMs ?: return
                playbackSink.seekTo(restoreMs.toInt())
            }

            "pause" -> {
                playbackSink.pause()
                didAutoPause = false
            }

            "resume" -> {
                playbackSink.resume()
                didAutoPause = false
            }
        }
    }

    private fun capturePreActionPosition(referenceMs: Long) {
        val previousPlaybackMs = playbackContextProvider.current().clientPositionMs
        // preQuotePositionMs stays on the playback timeline: stop_quote seeks
        // the local player back to it.
        preQuotePositionMs = previousPlaybackMs
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
