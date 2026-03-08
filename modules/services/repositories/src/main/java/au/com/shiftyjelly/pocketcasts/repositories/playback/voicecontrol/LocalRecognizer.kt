package au.com.shiftyjelly.pocketcasts.repositories.playback.voicecontrol

import kotlinx.coroutines.delay

interface LocalRecognizer {
    suspend fun recognize(sessionId: String, phrase: String): RecognizedCommand?
}

class LocalRuleBasedRecognizer(
    private val mapper: CommandIntentMapper,
    private val delayMs: Long = 0,
) : LocalRecognizer {
    override suspend fun recognize(sessionId: String, phrase: String): RecognizedCommand {
        val startedAt = System.currentTimeMillis()
        if (delayMs > 0) {
            delay(delayMs)
        }
        val intent = mapper.buildIntent(
            sessionId = sessionId,
            rawPhrase = phrase,
            confidenceLocal = 0.9,
        )
        return RecognizedCommand(
            intent = intent,
            latencyMs = System.currentTimeMillis() - startedAt,
            source = RecognitionSource.LOCAL,
        )
    }
}
