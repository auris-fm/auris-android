package au.com.shiftyjelly.pocketcasts.repositories.playback.voicecontrol

import kotlinx.coroutines.delay

interface CloudRecognizer {
    suspend fun recognize(sessionId: String, phrase: String): RecognizedCommand?
}

class CloudRuleBasedRecognizer(
    private val mapper: CommandIntentMapper,
    private val delayMs: Long = 150,
) : CloudRecognizer {
    override suspend fun recognize(sessionId: String, phrase: String): RecognizedCommand {
        val startedAt = System.currentTimeMillis()
        if (delayMs > 0) {
            delay(delayMs)
        }
        val intent = mapper.buildIntent(
            sessionId = sessionId,
            rawPhrase = phrase,
            cloudConfidence = 0.92,
        )
        return RecognizedCommand(
            intent = intent,
            latencyMs = System.currentTimeMillis() - startedAt,
            source = RecognitionSource.CLOUD,
        )
    }
}
