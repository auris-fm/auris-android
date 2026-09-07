package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class TimberCloudRouteAnalytics @Inject constructor() : CloudRouteAnalytics {
    override fun recordTurn(outcome: String, inputTokens: Int?, outputTokens: Int?) {
        Timber.i(
            "[VoicePipeline] cloud_assistant_turn outcome=%s input_tokens=%s output_tokens=%s source=voice_commands",
            outcome,
            inputTokens,
            outputTokens,
        )
    }
}
