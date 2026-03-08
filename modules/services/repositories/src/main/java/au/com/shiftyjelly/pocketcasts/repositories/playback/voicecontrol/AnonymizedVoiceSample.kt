package au.com.shiftyjelly.pocketcasts.repositories.playback.voicecontrol

import java.time.Instant
import java.util.UUID

data class AnonymizedVoiceSample(
    val sampleId: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val capturedAt: Instant,
    val expiresAt: Instant,
    val retentionPolicyVersion: String = RETENTION_POLICY_VERSION,
) {
    companion object {
        const val RETENTION_POLICY_VERSION = "v1"
    }
}
