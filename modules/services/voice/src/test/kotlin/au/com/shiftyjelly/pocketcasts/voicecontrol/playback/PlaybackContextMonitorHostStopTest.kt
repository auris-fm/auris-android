package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Event provenance for the playback-stop closure count. A closed voice window alone cannot identify
 * who paused playback, so the counting rule is pinned at the decision the watcher applies: only a
 * playing-to-stopped transition DURING a closed window counts. The rule is the same function the
 * production collector consults, so these checks constrain the shipped decision rather than a
 * counter a test pokes.
 */
class PlaybackContextMonitorHostStopTest {

    private fun monitor(): PlaybackContextMonitor {
        val manager = org.mockito.kotlin.mock<PlaybackManager>()
        return PlaybackContextMonitor(
            playbackManager = manager,
            gracePeriodSignal = GracePeriodSignal(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    @Test
    fun `a playing to stopped transition during a closed window counts`() {
        val monitor = monitor()
        monitor.gracePeriodSignalForTest().onAppBackgrounded()
        assertTrue(
            "the production decision must count a stop observed during the closure",
            monitor.hostStoppedWhileClosed(wasPlaying = true, isPlaying = false),
        )
    }

    @Test
    fun `a stop while the window is open does not count`() {
        val monitor = monitor()
        assertFalse(
            "with the window open the transition is not a closure's playback fact",
            monitor.hostStoppedWhileClosed(wasPlaying = true, isPlaying = false),
        )
    }

    @Test
    fun `a transition that is not a stop does not count even when closed`() {
        val monitor = monitor()
        monitor.gracePeriodSignalForTest().onAppBackgrounded()
        assertFalse(
            "only a playing-to-stopped transition is a playback stop",
            monitor.hostStoppedWhileClosed(wasPlaying = false, isPlaying = true),
        )
        assertFalse(
            "no transition, no fact",
            monitor.hostStoppedWhileClosed(wasPlaying = true, isPlaying = true),
        )
    }

    private fun PlaybackContextMonitor.gracePeriodSignalForTest(): GracePeriodSignal {
        val field = PlaybackContextMonitor::class.java.getDeclaredField("gracePeriodSignal")
        field.isAccessible = true
        return field.get(this) as GracePeriodSignal
    }
}
