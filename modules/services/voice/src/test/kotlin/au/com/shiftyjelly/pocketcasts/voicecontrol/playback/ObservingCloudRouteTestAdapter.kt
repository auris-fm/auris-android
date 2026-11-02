package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudConversationMode
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteContext
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteHint
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.PlaybackContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import org.mockito.kotlin.mock

/** Fixture adapter: exercises invocation-local duck reporting through main's prepared dispatch API. */
abstract class ObservingCloudRouteTestAdapter : VoiceCloudRouteSink by mock<VoiceCloudRouteSink>() {
    abstract suspend fun reportRoute(request: String, tier: VoiceIntent.CloudTier, context: PlaybackContext, onDuck: ((VoiceActionOutcome) -> Unit)?): VoiceResponse

    override suspend fun prepareTurn(request: String, tier: VoiceIntent.CloudTier, context: PlaybackContext, isLiveGraceTurn: Boolean, capturedOrigin: Long?, isWakeOriginated: Boolean, hint: CloudRouteHint?, logicalRequestId: String?, isRetry: Boolean, sourceLanguage: String?): CloudRouteTurn = CloudRouteTurn(
        request = request,
        context = CloudRouteContext(),
        requestId = logicalRequestId ?: java.util.UUID.randomUUID().toString(),
        conversationId = java.util.UUID.randomUUID().toString(),
        conversationMode = CloudConversationMode.Create,
        capturedOrigin = capturedOrigin,
        sourceLanguage = sourceLanguage,
    )

    override suspend fun dispatchPreparedReporting(prepared: CloudRouteTurn, isRetry: Boolean, onDuck: ((VoiceActionOutcome) -> Unit)?): CloudTurnResult = CloudTurnResult(
        feedback = reportRoute(prepared.request, VoiceIntent.CloudTier.Premium, PlaybackContext(), onDuck),
        action = CloudRouteActionOutcome.Unknown,
    )
}
