package au.com.shiftyjelly.pocketcasts.repositories.cloud

import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The prefetch hint observes playback state in its own coroutine: it never
 * participates in preparation, fires once per playback start, and stays dark
 * until the flag is switched on.
 */
@kotlinx.coroutines.ExperimentalCoroutinesApi
class CloudPrefetchObserverTest {

    private class FakePrefetchClient : CloudPrefetchHinter {
        val requested = mutableListOf<String>()

        override suspend fun prefetch(episodeId: String, podcastId: String?): CloudPrefetchClient.Outcome {
            requested += episodeId
            return CloudPrefetchClient.Outcome.ACCEPTED
        }
    }

    private class FakeSettings(private val enabled: Boolean) : CloudPrefetchFlag {
        override fun isEnabled(): Boolean = enabled
    }

    private fun observer(
        scope: kotlinx.coroutines.CoroutineScope,
        playbackStates: MutableStateFlow<PlaybackState>,
        client: CloudPrefetchHinter,
        enabled: Boolean,
    ): CloudPrefetchObserver {
        val playbackManager = mock<PlaybackManager>()
        whenever(playbackManager.playbackStateFlow).thenReturn(playbackStates.asStateFlow())
        return CloudPrefetchObserver(
            applicationScope = scope,
            playbackManager = playbackManager,
            prefetchClient = client,
            settings = FakeSettings(enabled),
        )
    }

    private fun playing(episodeUuid: String): PlaybackState = PlaybackState(state = PlaybackState.State.PLAYING, episodeUuid = episodeUuid)

    @Test
    fun `fires once per playback start when enabled`() = runTest(UnconfinedTestDispatcher()) {
        val states = MutableStateFlow(playing("ep-1"))
        val client = FakePrefetchClient()
        val subject = observer(backgroundScope, states, client, enabled = true)

        subject.start()
        advanceUntilIdle()
        assertEquals(listOf("ep-1"), client.requested)

        // Re-emitting the same playing state does not re-fire.
        states.value = playing("ep-1")
        advanceUntilIdle()
        assertEquals(1, client.requested.size)

        // A different episode (a new playback start) does fire.
        states.value = playing("ep-2")
        advanceUntilIdle()
        assertEquals(listOf("ep-1", "ep-2"), client.requested)
    }

    @Test
    fun `stays dark while the flag is off`() = runTest(UnconfinedTestDispatcher()) {
        val states = MutableStateFlow(playing("ep-1"))
        val client = FakePrefetchClient()
        observer(backgroundScope, states, client, enabled = false).start()

        advanceUntilIdle()
        states.value = playing("ep-2")
        advanceUntilIdle()

        assertTrue(client.requested.isEmpty())
    }

    @Test
    fun `ignores not-playing states and blank episode ids`() = runTest(UnconfinedTestDispatcher()) {
        val states = MutableStateFlow(playing("ep-1"))
        val client = FakePrefetchClient()
        observer(backgroundScope, states, client, enabled = true).start()
        advanceUntilIdle()

        states.value = PlaybackState(state = PlaybackState.State.PAUSED, episodeUuid = "ep-3")
        states.value = playing("")
        advanceUntilIdle()

        assertEquals(listOf("ep-1"), client.requested)
    }

    @Test
    fun `start is idempotent`() = runTest(UnconfinedTestDispatcher()) {
        val states = MutableStateFlow(playing("ep-1"))
        val client = FakePrefetchClient()
        val subject = observer(backgroundScope, states, client, enabled = true)

        subject.start()
        subject.start()
        advanceUntilIdle()

        assertEquals(1, client.requested.size)
    }
}
