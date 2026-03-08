package au.com.shiftyjelly.pocketcasts.repositories.playback.voicecontrol

import java.time.Instant
import java.util.UUID

enum class RecognitionSource {
    LOCAL,
    CLOUD,
}

data class ArbitrationDecision(
    val decisionId: String = UUID.randomUUID().toString(),
    val intentId: String,
    val arbitrationDeadlineMs: Int = 1000,
    val selectedSource: RecognitionSource,
    val selectedAt: Instant = Instant.now(),
    val lateSourceIgnored: Boolean,
)
