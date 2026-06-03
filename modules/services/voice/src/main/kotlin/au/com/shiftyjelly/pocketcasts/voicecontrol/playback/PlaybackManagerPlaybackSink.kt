package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import au.com.shiftyjelly.pocketcasts.analytics.SourceView
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackManagerPlaybackSink @Inject constructor(
    private val playbackManager: PlaybackManager,
) : VoicePlaybackSink {
    override suspend fun pause(): VoiceResponse {
        playbackManager.pauseSuspend(sourceView = SourceView.VOICE_COMMANDS)
        return VoiceResponse.Earcon("pause")
    }

    override suspend fun resume(): VoiceResponse {
        playbackManager.playQueueSuspend(sourceView = SourceView.VOICE_COMMANDS)
        return VoiceResponse.Silent
    }

    override suspend fun skipForward(seconds: Int): VoiceResponse {
        playbackManager.skipForwardSuspend(SourceView.VOICE_COMMANDS, seconds)
        return VoiceResponse.Silent
    }

    override suspend fun skipBackward(seconds: Int): VoiceResponse {
        playbackManager.skipBackwardSuspend(SourceView.VOICE_COMMANDS, seconds)
        return VoiceResponse.Silent
    }

    override suspend fun seekTo(positionMs: Int): VoiceResponse {
        playbackManager.seekToTimeMsSuspend(positionMs)
        return VoiceResponse.Silent
    }

    override fun nextEpisode(): VoiceResponse {
        playbackManager.playNextInQueue(sourceView = SourceView.VOICE_COMMANDS)
        return VoiceResponse.Earcon("next_episode")
    }
}
