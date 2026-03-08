package au.com.shiftyjelly.pocketcasts.repositories.playback.voicecontrol

import java.time.Instant
import java.util.UUID

enum class PlaybackCommandState {
    ACTIVE,
    PAUSED,
    STOPPED,
}

enum class ListeningMode {
    CONTINUOUS,
    WAKE_WORD,
}

data class VoiceCommandSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val startedAt: Instant = Instant.now(),
    val endedAt: Instant? = null,
    val playbackStateAtStart: PlaybackCommandState,
    val listeningMode: ListeningMode,
    val retentionEnabled: Boolean,
)
