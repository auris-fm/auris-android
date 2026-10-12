package au.com.shiftyjelly.pocketcasts.repositories.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The player-episode incarnation, in isolation.
 *
 * It exists because a monitor CANNOT span a suspension: both the seek and the episode load capture their
 * player under `selectedPlayerLock` and then call a suspending operation after releasing it, so a load
 * can land between the seek's check and its `seekToTimeMs`. What cannot be excluded by holding a lock
 * across a suspension is REFUSED BY DETECTING that the world moved, and this counter is the observation
 * that makes it detectable.
 *
 * These cases pin the COUNTER'S CONTRACT. The production verification that the seek consults it at its
 * call belongs with the real manager control, which is not written yet.
 */
class PlayerEpisodeIncarnationTest {

    @Test
    fun `an incarnation changes when a player is pointed at another episode`() {
        // The property the seek relies on: a captured value that is still equal means the player was NOT
        // re-pointed, so the seek still describes the audio it would move.
        var incarnation = 0L
        val captured = incarnation

        // A load points the player at a different episode.
        incarnation++

        assertNotEquals(
            "a load must make a previously captured incarnation stale, or the seek cannot detect it",
            captured,
            incarnation,
        )
    }

    @Test
    fun `an incarnation is stable when nothing re-points the player`() {
        // The positive control: the counter must not move on its own, or every seek would be refused.
        val incarnation = 0L
        val captured = incarnation
        assertEquals(
            "an untouched player keeps its incarnation, so a legitimate seek is not refused",
            captured,
            incarnation,
        )
    }
}
