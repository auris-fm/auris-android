package au.com.shiftyjelly.pocketcasts.repositories.playback.voicecontrol

data class VoiceRecognitionResult(
    val decision: ArbitrationDecision?,
    val selectedCommand: RecognizedCommand?,
)

class VoiceRecognitionOrchestrator(
    private val localRecognizer: LocalRecognizer,
    private val cloudRecognizer: CloudRecognizer,
    private val voiceArbitrationEngine: VoiceArbitrationEngine,
) {
    suspend fun recognize(
        sessionId: String,
        phrase: String,
        useCloud: Boolean,
    ): VoiceRecognitionResult {
        val arbitrationResult = voiceArbitrationEngine.arbitrate(
            localCall = { localRecognizer.recognize(sessionId = sessionId, phrase = phrase) },
            cloudCall = if (useCloud) {
                { cloudRecognizer.recognize(sessionId = sessionId, phrase = phrase) }
            } else {
                null
            },
            arbitrationDeadlineMs = 1000,
        )

        return VoiceRecognitionResult(
            decision = arbitrationResult.decision,
            selectedCommand = arbitrationResult.selectedCommand,
        )
    }
}
