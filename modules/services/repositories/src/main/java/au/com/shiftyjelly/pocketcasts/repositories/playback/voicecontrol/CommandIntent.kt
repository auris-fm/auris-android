package au.com.shiftyjelly.pocketcasts.repositories.playback.voicecontrol

import java.util.UUID

enum class IntentType {
    SKIP_FORWARD,
    REWIND,
    SPEED_UP,
    SPEED_DOWN,
    NEXT_EPISODE,
    UNSUPPORTED_ADVANCED,
    UNKNOWN,
}

data class CommandIntent(
    val intentId: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val intentType: IntentType,
    val rawPhrase: String,
    val confidenceLocal: Double? = null,
    val confidenceCloud: Double? = null,
)
