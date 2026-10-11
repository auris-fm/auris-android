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
    fun `a turn-owned pause processed after the closure is not an external stop`() = runTest {
        // Event-time ownership. The turn takes its own hold while the window is open; the observer
        // processes that transition only after backgrounding closed the window. Classifying by
        // observation time would record the app's OWN pause as an external playback-stop obligation
        // and strand the hold it was taken for. The sink marks the stop it caused, so the observer
        // skips it.
        val h = harness()
        h.awaitHostPlaying(true)

        // The sink takes its hold and declares the revision of the stop it causes.
        val revision = h.manager.playbackCommandRevision()
        h.signal.markTurnOwnedStop(revision)

        h.signal.onAppBackgrounded()
        h.flow.value = paused
        h.awaitHostPlaying(false)

        assertEquals(
            "the app's own pause must not become an external playback-stop obligation",
            0L,
            h.signal.playbackStopClosureCount(),
        )
    }

    @Test
    fun `an external stop processed after the closure still counts`() = runTest {
        // The complement: a stop the app did NOT cause, processed after the closure, is a real
        // external playback stop and must count.
        val h = harness()
        h.awaitHostPlaying(true)

        h.signal.onAppBackgrounded()
        h.flow.value = paused
        h.awaitHostPlaying(false)

        assertEquals(
            "a stop the app did not cause is an external playback-stop obligation",
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
