package au.com.shiftyjelly.pocketcasts.repositories.playback.voicecontrol

import java.util.Locale

class CommandIntentMapper {

    fun map(rawPhrase: String): IntentType {
        val normalized = normalize(rawPhrase)
        if (normalized.isBlank()) {
            return IntentType.UNKNOWN
        }

        return when {
            normalized.hasAny("fast forward", "forward", "skip forward", "jump ahead") -> IntentType.SKIP_FORWARD
            normalized.hasAny("rewind", "go back", "skip back", "back") -> IntentType.REWIND
            normalized.hasAny("speed up", "faster", "increase speed") -> IntentType.SPEED_UP
            normalized.hasAny("slow down", "slower", "decrease speed", "speed down") -> IntentType.SPEED_DOWN
            normalized.hasAny("next episode", "skip episode", "play next") -> IntentType.NEXT_EPISODE
            normalized.hasAny("bookmark", "share", "download", "transcript", "set timer") -> IntentType.UNSUPPORTED_ADVANCED
            else -> IntentType.UNKNOWN
        }
    }

    fun buildIntent(
        sessionId: String,
        rawPhrase: String,
        confidenceLocal: Double? = null,
        cloudConfidence: Double? = null,
    ): CommandIntent {
        return CommandIntent(
            sessionId = sessionId,
            intentType = map(rawPhrase),
            rawPhrase = rawPhrase,
            confidenceLocal = confidenceLocal,
            confidenceCloud = cloudConfidence,
        )
    }

    fun extractWakeWordCommand(rawPhrase: String): String? {
        val normalized = normalize(rawPhrase)
        val wakeWordPrefix = WAKE_WORD_PREFIXES.firstOrNull { normalized.startsWith(it) } ?: return null
        return normalized.removePrefix(wakeWordPrefix).trim().ifBlank { null }
    }

    private fun normalize(rawPhrase: String): String {
        return rawPhrase
            .lowercase(Locale.US)
            .replace(NON_ALPHANUMERIC_REGEX, " ")
            .replace(MULTIPLE_SPACES_REGEX, " ")
            .trim()
    }

    private fun String.hasAny(vararg phrases: String): Boolean {
        return phrases.any { contains(it) }
    }

    companion object {
        private val NON_ALPHANUMERIC_REGEX = "[^a-z0-9 ]".toRegex()
        private val MULTIPLE_SPACES_REGEX = "\\s+".toRegex()
        private val WAKE_WORD_PREFIXES = listOf(
            "hey pocket casts",
            "ok pocket casts",
        )
    }
}
