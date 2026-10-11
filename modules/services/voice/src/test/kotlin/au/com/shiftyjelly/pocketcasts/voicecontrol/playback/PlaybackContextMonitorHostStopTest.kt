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
    fun `a stop caused by the voice path's own pause is not an external stop`() = runTest {
        val h = harness()
        h.awaitHostPlaying(true)

        // The voice path pauses: the command records VOICE_COMMANDS as the stop's provenance.
        org.mockito.kotlin.whenever(h.manager.lastStopSourceView())
            .thenReturn(au.com.shiftyjelly.pocketcasts.analytics.SourceView.VOICE_COMMANDS)

        h.signal.onAppBackgrounded()
        h.flow.value = paused
        h.awaitHostPlaying(false)

        assertEquals(
            "the voice path's own pause is not an external playback stop",
            0L,
            h.signal.playbackStopClosureCount(),
        )
    }

    @Test
    fun `an external stop DURING the hold is still counted`() = runTest {
        // The distinguishing case: an external or user stop arrives while the turn's hold is in
        // effect, before the delayed own-pause event. Provenance separates them; the interval during
        // which a hold exists does not, which is why the hold's lifetime cannot be the discriminator.
        val h = harness()
        h.awaitHostPlaying(true)

        // The turn's own pause is outstanding...
        // ...but THIS stop carries an external source.
        org.mockito.kotlin.whenever(h.manager.lastStopSourceView())
            .thenReturn(au.com.shiftyjelly.pocketcasts.analytics.SourceView.UNKNOWN)

        h.signal.onAppBackgrounded()
        h.flow.value = paused
        h.awaitHostPlaying(false)

        assertEquals(
            "an external stop during a hold must be counted and supersede the auto-resume",
            1L,
            h.signal.playbackStopClosureCount(),
        )
    }

    @Test
    fun `a voice-path stop delivered after the hold is released is not an external obligation`() = runTest {
        // The own-pause event may be delivered after the turn already released its hold. Provenance is
        // a property of the stop, not of the hold's lifetime, so the successor is not charged with an
        // external obligation for the predecessor's own pause.
        val h = harness()
        h.awaitHostPlaying(true)

        org.mockito.kotlin.whenever(h.manager.lastStopSourceView())
            .thenReturn(au.com.shiftyjelly.pocketcasts.analytics.SourceView.VOICE_COMMANDS)

        // No hold is declared in effect at all here -- provenance alone must decide.
        h.signal.onAppBackgrounded()
        h.flow.value = paused
        h.awaitHostPlaying(false)

        assertEquals(
            "the predecessor's own pause is not an external obligation for the successor",
            0L,
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
