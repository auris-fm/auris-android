package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

interface CloudRouteAnalytics {
    fun recordTurn(outcome: String, inputTokens: Int? = null, outputTokens: Int? = null)
}
