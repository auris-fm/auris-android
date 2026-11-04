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
 * episode-bound seek carries INTO the request is the token the completion event carries OUT. An
 * untracked UI seek emits a null token, and can never masquerade as an operation's completion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalPlayerSeekTokenTest {

    private class TestPlayer(sink: (Player, PlayerEvent) -> Unit) : LocalPlayer(sink) {
        val issuedSeeks = mutableListOf<Int>()

        var currentPosition = 0
        override fun handlePrepare() {}
        override fun handleStop() {}
        override fun handlePause() {}
        override fun handlePlay() {}
        override fun handleSeekToTimeMs(positionMs: Int) {
            issuedSeeks += positionMs
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

        /** The player engine's completion, delivered the way SimplePlayer delivers one. */
        fun engineComplete(positionMs: Int) {
            onSeekComplete(positionMs)
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
        assertEquals(listOf(1_000), player.issuedSeeks)

        player.engineComplete(1_000)
        val event = seekCompletions().single()
        assertEquals("token-1", event.completionToken)
    }

    @Test
    fun `an untracked UI seek's completion carries a null token`() = runTest {
        player.seekToTimeMs(500)
        player.engineComplete(500)
        val event = seekCompletions().single()
        assertNull(event.completionToken)
    }

    @Test
    fun `a UI seek cannot inherit an in-flight operation's token`() = runTest {
        player.seekToTimeMsIfEpisode(1_000, "episode-A", "token-1")
        // The engine completed NOTHING for the operation yet, and the user seeks NOW.
        player.seekToTimeMs(2_000)
        player.engineComplete(2_000)
        val event = seekCompletions().single()
        // The completion the UI seek produced carries NO token: the operation's token was consumed
        // by the seek that actually completed, not by the UI seek that happened to fire.
        assertNull(event.completionToken)
    }

    @Test
    fun `a seek refused on episode mismatch never arms a token`() = runTest {
        val outcome = player.seekToTimeMsIfEpisode(1_000, "episode-B", "token-1")
        assertTrue(!outcome.accepted)
        assertTrue(player.issuedSeeks.isEmpty())
        val completions = seekCompletions()
        assertTrue(completions.isEmpty())
    }

    @Test
    fun `distinct operations carry distinct tokens through the same player`() = runTest {
        val first = player.seekToTimeMsIfEpisode(1_000, "episode-A", "token-1")
        player.engineComplete(1_000)
        val second = player.seekToTimeMsIfEpisode(3_000, "episode-A", "token-2")
        player.engineComplete(3_000)
        assertNotNull(first)
        assertNotNull(second)
        val completions = seekCompletions()
        assertEquals(listOf("token-1", "token-2"), completions.map { it.completionToken })
    }
}
