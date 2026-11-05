package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackState
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * REGRESSION CONTROL for the construction race reviewer-confirmed after CI run
 * 42034704907: the init-block watcher reads `isHostAudioActive` on a
 * Dispatchers.Default worker that can interleave with construction. While the
 * property was declared AFTER the init block, the watcher could read it before
 * its initializer ran and NPE on a null flow read; the uncaught failure leaked
 * out of an earlier test in the same JVM and failed the NEXT runTest in line
 * (VoiceControlServiceAcquisitionTest, UncaughtExceptionsBeforeTest).
 *
 * Determinism: this control constructs the REAL monitor under
 * Dispatchers.Unconfined, which runs the launched watcher inline on the
 * constructing thread — so the old declaration order fails HERE, not by suite
 * timing. The failure is observed through a [CoroutineExceptionHandler] on THIS
 * test's own scope, so only this monitor's failures are seen and no JVM-wide
 * handler is touched. The scope is cancelled so no watcher outlives the test.
 */
class PlaybackContextMonitorConstructionRaceTest {
    private val failures = CopyOnWriteArrayList<Throwable>()

    @Test
    fun `the construction watcher reads an initialized flow and still counts a real stop`() = runTest {
        val playbackStates = MutableStateFlow(PlaybackState(state = PlaybackState.State.STOPPED))
        val playbackManager: PlaybackManager = mock {
            on { playbackStateFlow } doReturn playbackStates
        }
        val signal = GracePeriodSignal()
        val scope = CoroutineScope(
            Job() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, e -> failures.add(e) },
        )
        try {
            // Construction itself: with the old declaration order the inline watcher dies
            // here on the uninitialized property and the handler records the failure.
            val monitor = PlaybackContextMonitor(playbackManager, signal, scope)
            assertTrue(
                "constructing the monitor must not fail the watcher (old order read an " +
                    "uninitialized flow): $failures",
                failures.isEmpty(),
            )
            assertEquals(
                "the idle monitor reports no host audio and no privacy closure",
                false to false,
                monitor.isHostAudioActive.value to monitor.isPrivacyClosed(),
            )

            // Playback behavior on the REAL watcher: playing -> privacy closed -> stopped
            // must still advance the playback-stop closure count.
            playbackStates.value = PlaybackState(state = PlaybackState.State.PLAYING, episodeUuid = "ep")
            signal.onAppBackgrounded()
            assertTrue("the window must be closed by privacy before the stop", signal.isClosedByPrivacy())
            playbackStates.value = PlaybackState(state = PlaybackState.State.STOPPED, episodeUuid = "ep")
            assertEquals(
                "the watcher must count the playing-to-stopped closure (old order would have " +
                    "died on the uninitialized property instead): $failures",
                1L,
                signal.playbackStopClosureCount(),
            )
            assertTrue("no watcher failure may occur during the whole control: $failures", failures.isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
