package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import au.com.shiftyjelly.pocketcasts.analytics.SourceView
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackState
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Event provenance for the playback-stop closure count, driven through the REAL collector.
 *
 * The cause must ride ON each emission, not be read from shared "last command" state when the
 * collector eventually runs: a newer command can replace that field before a queued stop is
 * processed, and the stop would be charged with a cause that is not its own.
 */
class PlaybackContextMonitorHostStopTest {

    private val playing = PlaybackState(
        state = PlaybackState.State.PLAYING,
        episodeUuid = "ep",
    )

    private fun pausedBy(source: SourceView, hold: Boolean = false) = playing.copy(
        state = PlaybackState.State.PAUSED,
        changedBySource = source.key,
        changedByVoiceHold = hold,
    )

    private class Harness(
        val flow: MutableStateFlow<PlaybackState>,
        val signal: GracePeriodSignal,
        val monitor: PlaybackContextMonitor,
        val driver: QueuedDispatcher,
    ) {
        fun awaitHostPlaying(expected: Boolean) {
            driver.drain()
            check(monitor.isHostAudioActive.value == expected) {
                "the collector must have settled on hostPlaying=$expected"
            }
        }
    }

    private fun harness(): Harness {
        val flow = MutableStateFlow(playing)
        val manager = mock<PlaybackManager>()
        whenever(manager.playbackStateFlow).thenReturn(flow)
        val signal = GracePeriodSignal()
        val driver = QueuedDispatcher()
        val monitor = PlaybackContextMonitor(
            playbackManager = manager,
            gracePeriodSignal = signal,
            scope = CoroutineScope(driver),
        )
        return Harness(flow, signal, monitor, driver)
    }

    // Runs submitted coroutines only when the test asks, so the collector cannot process an emission
    // at a moment the test did not choose.
    private class QueuedDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
        private val queue = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()

        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            queue.add(block)
        }

        fun drain() {
            while (true) {
                val task = queue.poll() ?: return
                task.run()
            }
        }
    }

    @Test
    fun `a stop caused by the voice path's own pause is not an external stop`() = runTest {
        val h = harness()
        h.awaitHostPlaying(true)

        h.signal.onAppBackgrounded()
        h.flow.value = pausedBy(SourceView.VOICE_COMMANDS, hold = true)
        h.awaitHostPlaying(false)

        assertEquals(
            "the voice path's own pause is not an external playback stop",
            0L,
            h.signal.playbackStopClosureCount(),
        )
    }

    @Test
    fun `an external stop during the hold is counted`() = runTest {
        val h = harness()
        h.awaitHostPlaying(true)

        h.signal.onAppBackgrounded()
        h.flow.value = pausedBy(SourceView.UNKNOWN)
        h.awaitHostPlaying(false)

        assertEquals(
            "an external stop must be counted and supersede the auto-resume",
            1L,
            h.signal.playbackStopClosureCount(),
        )
    }

    @Test
    fun `two queued stops from different sources each keep their own cause`() = runTest {
        // The reason the cause must ride the emission: with a shared "last command" field, processing
        // both stops after the fact would attribute BOTH to whichever source was recorded last. Here
        // the voice-path stop is queued first and an external stop second, both before delivery.
        val h = harness()
        h.awaitHostPlaying(true)
        h.signal.onAppBackgrounded()

        h.flow.value = pausedBy(SourceView.VOICE_COMMANDS, hold = true)
        h.flow.value = playing
        h.flow.value = pausedBy(SourceView.UNKNOWN)
        h.driver.drain()

        assertEquals(
            "only the stop that was NOT the voice path's own is an external obligation",
            1L,
            h.signal.playbackStopClosureCount(),
        )
    }

    @Test
    fun `a user pause sharing the voice interface tag is not exempted`() = runTest {
        // VOICE_COMMANDS is the interface the command arrived through, not who asked for it. A user's
        // spoken pause reaches the player through that same tag, and must supersede the answer hold
        // like any other user pause rather than being exempted as the turn's own pause.
        val h = harness()
        h.awaitHostPlaying(true)

        h.signal.onAppBackgrounded()
        // The user's pause arrives through the SAME interface tag, but is not the turn's hold.
        h.flow.value = pausedBy(SourceView.VOICE_COMMANDS, hold = false)
        h.awaitHostPlaying(false)

        assertEquals(
            "a user's spoken pause must count despite sharing the voice interface's tag",
            1L,
            h.signal.playbackStopClosureCount(),
        )
    }

    @Test
    fun `a stop processed while the window is open is not counted`() = runTest {
        val h = harness()
        h.awaitHostPlaying(true)

        h.flow.value = pausedBy(SourceView.UNKNOWN)
        h.awaitHostPlaying(false)
        h.signal.onAppBackgrounded()

        assertEquals(
            "a stop processed with the window open is not a closure's playback fact",
            0L,
            h.signal.playbackStopClosureCount(),
        )
    }
}
