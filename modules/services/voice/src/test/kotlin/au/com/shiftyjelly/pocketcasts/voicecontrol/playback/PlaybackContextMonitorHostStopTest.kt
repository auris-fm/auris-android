package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackState
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Event provenance for the playback-stop closure count, driven through the REAL collector.
 *
 * Calling the collector's decision directly verifies the decision, not the ordering: a pause that
 * occurs before the window closes does not guarantee its observer callback runs before the close.
 * These checks therefore feed the monitor's own flow and control WHEN the emission is processed
 * relative to the closure.
 */
class PlaybackContextMonitorHostStopTest {

    private val playing = PlaybackState(
        state = PlaybackState.State.PLAYING,
        episodeUuid = "ep",
    )

    private val paused = playing.copy(state = PlaybackState.State.PAUSED)

    /** A monitor whose host-audio flow the test drives, with a real grace signal. */
    // Runs submitted coroutines only when the test asks, so the collector cannot process an emission
    // at a moment the test did not choose. This is what makes the ordering explicit: the emission
    // produced before the closure is only PROCESSED when drain() is called, after it.
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

    private class Harness(
        val flow: MutableStateFlow<PlaybackState>,
        val signal: GracePeriodSignal,
        val monitor: PlaybackContextMonitor,
        val driver: QueuedDispatcher,
        val manager: PlaybackManager,
    ) {
        // Waits for the collector to observe `expected` as the host's audio state.
        fun awaitHostPlaying(expected: Boolean) {
            driver.drain()
            check(monitor.isHostAudioActive.value == expected) {
                "the collector must have settled on hostPlaying=$expected"
            }
        }

        // Drains queued collector work, so the watcher processes what it has seen on the test's turn.
        fun drainCollector() = driver.drain()
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
        return Harness(flow, signal, monitor, driver, manager)
    }

    @Test
    fun `a turn-owned pause in effect is not an external stop`() = runTest {
        // The app takes its own hold and declares it in effect; the observer must not read the stop
        // that pause causes as an external playback-stop obligation, whenever it processes it.
        val h = harness()
        h.awaitHostPlaying(true)

        h.signal.setTurnOwnedPause(true)
        h.signal.onAppBackgrounded()
        h.flow.value = paused
        h.awaitHostPlaying(false)

        assertEquals(
            "the app's own hold pause is not an external stop",
            0L,
            h.signal.playbackStopClosureCount(),
        )
    }

    @Test
    fun `an external stop while no turn-owned pause is in effect is counted`() = runTest {
        val h = harness()
        h.awaitHostPlaying(true)

        // No hold in effect: whatever stopped the host is external.
        h.signal.onAppBackgrounded()
        h.flow.value = paused
        h.awaitHostPlaying(false)

        assertEquals(
            "a stop with no turn-owned hold in effect is an external stop",
            1L,
            h.signal.playbackStopClosureCount(),
        )
    }

    @Test
    fun `an external stop arriving before the hold is declared is not exempted`() = runTest {
        // The revision key could not identify the voice pause, so an external stop sharing its number
        // could spend the exemption on itself. The flag has no such ambiguity: a stop observed while
        // no hold is in effect is external, and declaring the hold afterwards cannot retroactively
        // exempt it.
        val h = harness()
        h.awaitHostPlaying(true)

        // External stop FIRST, with no hold in effect.
        h.signal.onAppBackgrounded()
        h.flow.value = paused
        h.awaitHostPlaying(false)

        // The hold is declared only afterwards.
        h.signal.setTurnOwnedPause(true)

        assertEquals(
            "an external stop must not be exempted by a hold declared after it",
            1L,
            h.signal.playbackStopClosureCount(),
        )
    }

    @Test
    fun `a stop after the hold is released is counted`() = runTest {
        val h = harness()
        h.awaitHostPlaying(true)

        h.signal.setTurnOwnedPause(true)
        // The turn releases its hold.
        h.signal.setTurnOwnedPause(false)

        h.signal.onAppBackgrounded()
        h.flow.value = paused
        h.awaitHostPlaying(false)

        assertEquals(
            "once the hold is released a stop is external again",
            1L,
            h.signal.playbackStopClosureCount(),
        )
    }

    @Test
    fun `a stop processed while the window is open is not counted`() = runTest {
        val h = harness()
        h.awaitHostPlaying(true)

        h.flow.value = paused
        h.awaitHostPlaying(false)
        h.signal.onAppBackgrounded()

        assertEquals(
            "a stop processed with the window open is not a closure's playback fact",
            0L,
            h.signal.playbackStopClosureCount(),
        )
    }
}
