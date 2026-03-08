package au.com.shiftyjelly.pocketcasts.repositories.playback.voicecontrol

class ListeningModeResolver {
    fun resolve(isPlaybackActive: Boolean): ListeningMode {
        return if (isPlaybackActive) ListeningMode.CONTINUOUS else ListeningMode.WAKE_WORD
    }
}
