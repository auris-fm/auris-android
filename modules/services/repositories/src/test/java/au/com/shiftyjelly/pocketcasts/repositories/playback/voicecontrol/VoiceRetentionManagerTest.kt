package au.com.shiftyjelly.pocketcasts.repositories.playback.voicecontrol

import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRetentionManagerTest {
    private val retentionManager = VoiceRetentionManager()

    @Test
    fun `creates retained sample when retention is enabled`() {
        val capturedAt = Instant.parse("2026-03-06T12:00:00Z")

        val sample = retentionManager.createRetainedSample(
            sessionId = "session-1",
            capturedAt = capturedAt,
            retentionEnabled = true,
        )

        assertNotNull(sample)
        assertEquals(capturedAt, sample?.capturedAt)
        assertEquals(capturedAt.plus(30, ChronoUnit.DAYS), sample?.expiresAt)
    }

    @Test
    fun `does not create retained sample when retention is disabled`() {
        val sample = retentionManager.createRetainedSample(
            sessionId = "session-1",
            capturedAt = Instant.parse("2026-03-06T12:00:00Z"),
            retentionEnabled = false,
        )

        assertNull(sample)
    }

    @Test
    fun `prunes expired samples`() {
        val now = Instant.parse("2026-03-06T12:00:00Z")
        val activeSample = AnonymizedVoiceSample(
            sessionId = "active",
            capturedAt = now.minus(1, ChronoUnit.DAYS),
            expiresAt = now.plus(10, ChronoUnit.DAYS),
        )
        val expiredSample = AnonymizedVoiceSample(
            sessionId = "expired",
            capturedAt = now.minus(40, ChronoUnit.DAYS),
            expiresAt = now.minus(10, ChronoUnit.DAYS),
        )

        val kept = retentionManager.pruneExpired(
            samples = listOf(activeSample, expiredSample),
            now = now,
        )

        assertEquals(1, kept.size)
        assertTrue(kept.contains(activeSample))
    }
}
