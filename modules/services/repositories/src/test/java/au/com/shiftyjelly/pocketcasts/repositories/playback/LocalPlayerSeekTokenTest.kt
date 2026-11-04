package au.com.shiftyjelly.pocketcasts.repositories.playback

import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * THE PLAYER HALF OF SEEK INVOCATION IDENTITY, on the real [LocalPlayer] path: the token an
 * episode-bound seek carries INTO the request is the token its OWN completion carries OUT, and the
 * admitted deadline is enforced at the mutation, before the seek is issued.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalPlayerSeekTokenTest {

    private class TestPlayer(sink: (Player, PlayerEvent) -> Unit) : LocalPlayer(sink) {
        val issuedSeeks = mutableListOf<Pair<Int, String?>>()

        /** Milliseconds on the admitted-deadline epoch; controls set it directly. */
        var nowMs: Long = 0
        override fun admittedClockMs(): Long = nowMs

        var currentPosition = 0
        override fun handlePrepare() {}
        override fun handleStop() {}
        override fun handlePause() {}
        override fun handlePlay() {}
        override fun handleSeekToTimeMs(positionMs: Int, completionToken: String?) {
            issuedSeeks += positionMs to completionToken
        }
        override fun handleIsBuffering(): Boolean = false
        override fun handleIsPrepared(): Boolean = true
        override fun handleCurrentPositionMs(): Int = currentPosition
        override suspend fun isPlaying(): Boolean = false
        override suspend fun durationMs(): Int? = null
        override suspend fun bufferedUpToMs(): Int = 0
        override suspend fun bufferedPercentage(): Int = 0
        override fun supportsTrimSilence(): Boolean = false
        override fun supportsVolumeBoost(): Boolean = false
        override fun supportsVideo(): Boolean = false
        override fun setVolume(volume: Float) {}
        override fun setPodcast(podcast: au.com.shiftyjelly.pocketcasts.models.entity.Podcast?) {}
        override var isPip: Boolean = false

        /**
         * The player engine's completion, delivered the way the real request site delivers one:
         * WITH the token of the request being completed -- never read from shared state.
         */
        fun engineComplete(positionMs: Int, completionToken: String?) {
            onSeekComplete(positionMs, completionToken)
        }
    }

    private lateinit var player: TestPlayer
    private lateinit var observedEvents: MutableList<PlayerEvent>

    private fun seekCompletions(): List<PlayerEvent.SeekComplete> = observedEvents.filterIsInstance<PlayerEvent.SeekComplete>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val events = mutableListOf<PlayerEvent>()
        observedEvents = events
        player = TestPlayer { _, event -> events += event }
        player.setEpisode(PodcastEpisode(uuid = "episode-A", publishedDate = Date()), preferStream = false)
    }

    @Test
    fun `an episode-bound seek's completion carries the invocation token`() = runTest {
        val outcome = player.seekToTimeMsIfEpisode(1_000, "episode-A", "token-1")
        assertTrue(outcome.accepted)
        assertEquals(listOf(1_000 to "token-1"), player.issuedSeeks)

        player.engineComplete(1_000, "token-1")
        val event = seekCompletions().single()
        assertEquals("token-1", event.completionToken)
    }

    @Test
    fun `an untracked UI seek's completion carries a null token`() = runTest {
        player.seekToTimeMs(500)
        player.engineComplete(500, null)
        val event = seekCompletions().single()
        assertNull(event.completionToken)
    }

    @Test
    fun `a seek refused on episode mismatch never arms a token`() = runTest {
        val outcome = player.seekToTimeMsIfEpisode(1_000, "episode-B", "token-1")
        assertTrue(!outcome.accepted)
        assertTrue(player.issuedSeeks.isEmpty())
        assertTrue(seekCompletions().isEmpty())
    }

    @Test
    fun `distinct operations carry distinct tokens through the same player`() = runTest {
        val first = player.seekToTimeMsIfEpisode(1_000, "episode-A", "token-1")
        player.engineComplete(1_000, "token-1")
        val second = player.seekToTimeMsIfEpisode(3_000, "episode-A", "token-2")
        player.engineComplete(3_000, "token-2")
        assertNotNull(first)
        assertNotNull(second)
        val completions = seekCompletions()
        assertEquals(listOf("token-1", "token-2"), completions.map { it.completionToken })
    }

    @Test
    fun `an overlapping native request's completion carries ITS OWN request's token`() = runTest {
        // THE REVIEWER'S EXACT TRACE: issue A(1000, token A), issue B(3000, token B), deliver A's
        // callback at 1000. A mutable latest-tag would emit A's callback with token B; the request
        // site emits it with A's OWN token.
        player.seekToTimeMsIfEpisode(1_000, "episode-A", "token-A")
        player.seekToTimeMsIfEpisode(3_000, "episode-A", "token-B")
        assertEquals(
            listOf(1_000 to "token-A", 3_000 to "token-B"),
            player.issuedSeeks,
        )
        player.engineComplete(1_000, "token-A")
        val event = seekCompletions().single()
        assertEquals("token-A", event.completionToken)
        assertEquals(1_000, event.positionMs)
    }

    @Test
    fun `a UI seek issued between tracked requests cannot borrow a token`() = runTest {
        player.seekToTimeMsIfEpisode(1_000, "episode-A", "token-A")
        player.seekToTimeMs(2_000) // untracked UI seek in the overlap window
        player.engineComplete(2_000, null)
        assertEquals(null, seekCompletions().single().completionToken)
        player.engineComplete(1_000, "token-A")
        assertEquals("token-A", seekCompletions().last().completionToken)
    }

    @Test
    fun `an expired admitted deadline refuses at the mutation without issuing the seek`() = runTest {
        player.nowMs = 5_000
        val outcome = player.seekToTimeMsIfEpisode(1_000, "episode-A", "token-1", admittedDeadlineMs = 5_000)
        assertTrue(!outcome.accepted)
        // NO EFFECT: the seek was never issued, so no completion can ever arrive.
        assertTrue(player.issuedSeeks.isEmpty())
        assertTrue(seekCompletions().isEmpty())
    }

    @Test
    fun `a live admitted deadline still issues the seek with its token`() = runTest {
        player.nowMs = 5_000
        val outcome = player.seekToTimeMsIfEpisode(1_000, "episode-A", "token-1", admittedDeadlineMs = 6_000)
        assertTrue(outcome.accepted)
        assertEquals(listOf(1_000 to "token-1"), player.issuedSeeks)
    }
}
