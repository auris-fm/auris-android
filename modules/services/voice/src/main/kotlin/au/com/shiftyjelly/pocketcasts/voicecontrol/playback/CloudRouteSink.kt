package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteClient
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteContext
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteEvent
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.CloudConfig
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.CloudIdentity
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintTimingManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconId
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.PlaybackContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class CloudRouteSink internal constructor(
    private val resolveBaseUrl: () -> String,
    private val resolveUserId: () -> String,
    private val playbackSink: VoicePlaybackSink,
    private val fingerprintTimingManager: FingerprintTimingManager,
    private val playbackContextProvider: PlaybackContextProvider,
    private val cloudPlaybackContextState: CloudPlaybackContextState,
    private val analytics: CloudRouteAnalytics,
    private val routeInvoker: ((String, CloudRouteContext) -> Flow<CloudRouteEvent>)?,
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
    )

    private var preQuotePositionMs: Long? = null
    private var didAutoPause = false

    override suspend fun routeToCloud(
        request: String,
        tier: VoiceIntent.CloudTier,
        context: PlaybackContext,
    ): VoiceResponse {
        if (resolveBaseUrl().isBlank()) {
            return VoiceResponse.Spoken(COMING_SOON_MESSAGE)
        }

        var tokenBuffer = ""
        val routeContext = buildRouteContext(context)
        val events = openRoute(request, routeContext)

        playbackSink.pause()
        didAutoPause = true

        // Flow.collect's action is crossinline — cannot return@routeToCloud from it.
        var outcome: VoiceResponse? = null
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
                    outcome = if (tokenBuffer.isEmpty()) {
                        VoiceResponse.Silent
                    } else {
                        VoiceResponse.Spoken(tokenBuffer)
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

        if (outcome == null) {
            restoreTransientAudioState()
        }
        return outcome ?: VoiceResponse.Silent
    }

    private fun openRoute(request: String, context: CloudRouteContext): Flow<CloudRouteEvent> {
        routeInvoker?.let { return it(request, context) }
        return CloudRouteClient(resolveBaseUrl(), resolveUserId()).route(request, context)
    }

    private fun buildRouteContext(context: PlaybackContext): CloudRouteContext {
        val state = cloudPlaybackContextState.snapshot()
        return CloudRouteContext(
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
        val previous = playbackContextProvider.current().clientPositionMs
        preQuotePositionMs = previous
        cloudPlaybackContextState.record(
            referencePositionMs = referenceMs,
            previousReferencePositionMs = previous,
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
