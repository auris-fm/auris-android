package au.com.shiftyjelly.pocketcasts.repositories.playback.voicecontrol

import java.time.Instant
import java.util.UUID

enum class ResultType {
    EXECUTED,
    UNSUPPORTED,
    FAILED_SAFELY,
}

enum class FeedbackType {
    SUCCESS,
    UNSUPPORTED,
    FAILED,
    IGNORED,
}

data class IntentResolutionOutcome(
    val outcomeId: String = UUID.randomUUID().toString(),
    val decisionId: String?,
    val resultType: ResultType,
    val userFeedbackType: FeedbackType,
    val executedAt: Instant? = null,
)
