package au.com.shiftyjelly.pocketcasts.repositories.playback.voicecontrol

import java.time.Instant
import java.time.temporal.ChronoUnit

class VoiceRetentionManager(
    private val retentionDays: Long = 30,
) {
    fun createRetainedSample(
        sessionId: String,
        capturedAt: Instant,
        retentionEnabled: Boolean,
    ): AnonymizedVoiceSample? {
        if (!retentionEnabled) {
            return null
        }

        return AnonymizedVoiceSample(
            sessionId = sessionId,
            capturedAt = capturedAt,
            expiresAt = capturedAt.plus(retentionDays, ChronoUnit.DAYS),
        )
    }

    fun pruneExpired(
        samples: List<AnonymizedVoiceSample>,
        now: Instant,
    ): List<AnonymizedVoiceSample> {
        return samples.filter { sample -> sample.expiresAt.isAfter(now) }
    }
}
