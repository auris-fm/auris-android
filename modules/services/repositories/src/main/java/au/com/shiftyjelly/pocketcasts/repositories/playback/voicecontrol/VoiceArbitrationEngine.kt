package au.com.shiftyjelly.pocketcasts.repositories.playback.voicecontrol

import java.time.Clock
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

data class RecognizedCommand(
    val intent: CommandIntent,
    val latencyMs: Long,
    val source: RecognitionSource,
)

data class ArbitrationResult(
    val decision: ArbitrationDecision?,
    val selectedCommand: RecognizedCommand?,
)

interface VoiceArbitrationEngine {
    suspend fun arbitrate(
        localCall: suspend () -> RecognizedCommand?,
        cloudCall: (suspend () -> RecognizedCommand?)?,
        arbitrationDeadlineMs: Int = 1000,
    ): ArbitrationResult
}

class DeterministicVoiceArbitrationEngine(
    private val clock: Clock = Clock.systemUTC(),
) : VoiceArbitrationEngine {
    override suspend fun arbitrate(
        localCall: suspend () -> RecognizedCommand?,
        cloudCall: (suspend () -> RecognizedCommand?)?,
        arbitrationDeadlineMs: Int,
    ): ArbitrationResult = coroutineScope {
        val localDeferred = async { localCall() }
        val cloudDeferred = cloudCall?.let { async { it() } }

        val selectedCommand = if (cloudDeferred == null) {
            localDeferred.await()
        } else {
            val cloudResult = withTimeoutOrNull(arbitrationDeadlineMs.toLong()) {
                cloudDeferred.await()
            }
            cloudResult ?: localDeferred.await()
        }

        if (selectedCommand == null) {
            cloudDeferred?.cancel()
            localDeferred.cancel()
            return@coroutineScope ArbitrationResult(decision = null, selectedCommand = null)
        }

        val lateSourceIgnored = selectedCommand.source == RecognitionSource.LOCAL &&
            cloudDeferred != null &&
            !cloudDeferred.isCompleted

        if (selectedCommand.source == RecognitionSource.CLOUD && !localDeferred.isCompleted) {
            localDeferred.cancel()
        }
        if (selectedCommand.source == RecognitionSource.LOCAL && cloudDeferred != null && !cloudDeferred.isCompleted) {
            cloudDeferred.cancel()
        }

        ArbitrationResult(
            decision = ArbitrationDecision(
                intentId = selectedCommand.intent.intentId,
                arbitrationDeadlineMs = arbitrationDeadlineMs,
                selectedSource = selectedCommand.source,
                selectedAt = clock.instant(),
                lateSourceIgnored = lateSourceIgnored,
            ),
            selectedCommand = selectedCommand,
        )
    }
}
