package au.com.shiftyjelly.pocketcasts.repositories.playback

import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager.SeekCompletionEvent
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager.SeekCompletionSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE OWNERSHIP SEAM: the native completion path may only settle the invocation whose token
 * travelled through the seek request. Every control here drives a REAL [SeekCompletionCoordinator]
 * dispatching REAL [SeekCompletionEvent]s -- the same classes the manager and player wire in
 * production -- never a direct signal completion.
 */
class SeekCompletionCoordinatorTest {

    /** An eager observer of the signal, so a completed event can be inspected without more suspension. */
    private class Observer(dispatcher: kotlinx.coroutines.CoroutineDispatcher) {
        var observed: SeekCompletionEvent? = null
        private val scope = CoroutineScope(dispatcher)

        fun watch(signal: SeekCompletionSignal) {
            scope.launch { observed = signal.await() }
        }
    }

    private fun completionEvent(token: String?, episode: String = "episode-A") = SeekCompletionEvent(episodeUuid = episode, positionMs = 42_000, endOfFile = false, completionToken = token)

    @Test
    fun `an untracked UI seek's completion cannot consume an armed invocation`() = runTest {
        val dispatcher = Dispatchers.Unconfined
        val coordinator = SeekCompletionCoordinator()
        val signal = SeekCompletionSignal()
        coordinator.arm(signal)

        // A UI seek completed first: its event carries NO token (the player emits null for an
        // untracked seek). It must be DROPPED -- never queued, never settled by the waiter.
        assertFalse(coordinator.dispatch(completionEvent(token = null)))
        assertFalse(signal.isCompleted())

        // The invocation's own completion still lands: the drop consumed nothing.
        assertTrue(coordinator.dispatch(completionEvent(token = signal.token)))
        assertTrue(signal.isCompleted())
    }

    @Test
    fun `a missing first callback cannot let a later seek settle the stale waiter`() = runTest {
        val coordinator = SeekCompletionCoordinator()
        val stale = SeekCompletionSignal()
        coordinator.arm(stale)
        // The first operation's callback never arrives. The coordinator CANNOT settle `stale` by
        // arrival -- only by its token. A second, unrelated invocation is armed; its completion must
        // complete ONLY ITSELF, leaving the stale waiter untouched.
        val second = SeekCompletionSignal()
        val observer = Observer(Dispatchers.Unconfined)
        observer.watch(second)
        coordinator.arm(second)
        assertTrue(coordinator.dispatch(completionEvent(token = second.token)))
        assertFalse(stale.isCompleted())
        assertTrue(second.isCompleted())
        assertEquals(42_000, observer.observed?.positionMs)
    }

    @Test
    fun `overlapping same-episode seeks settle by identity, not arrival order`() = runTest {
        val coordinator = SeekCompletionCoordinator()
        val first = SeekCompletionSignal()
        val second = SeekCompletionSignal()
        val firstObserver = Observer(Dispatchers.Unconfined)
        val secondObserver = Observer(Dispatchers.Unconfined)
        firstObserver.watch(first)
        secondObserver.watch(second)
        coordinator.arm(first)
        coordinator.arm(second)

        // The SECOND invocation's native completion arrives first.
        assertTrue(coordinator.dispatch(completionEvent(token = second.token)))
        assertFalse(first.isCompleted())
        assertTrue(second.isCompleted())
        assertEquals(42_000, secondObserver.observed?.positionMs)
        assertNull(firstObserver.observed)

        // Then the first's.
        assertTrue(coordinator.dispatch(completionEvent(token = first.token)))
        assertTrue(first.isCompleted())
        assertEquals(42_000, firstObserver.observed?.positionMs)
    }

    @Test
    fun `an event for an unknown token is dropped without side effects`() = runTest {
        val coordinator = SeekCompletionCoordinator()
        val signal = SeekCompletionSignal()
        coordinator.arm(signal)
        assertFalse(coordinator.dispatch(completionEvent(token = "never-issued")))
        assertFalse(signal.isCompleted())
        // The armed signal is untouched by the foreign event.
        assertTrue(coordinator.isArmed(signal))
    }

    @Test
    fun `a released token is no longer completable and a late event finds nothing`() = runTest {
        val coordinator = SeekCompletionCoordinator()
        val signal = SeekCompletionSignal()
        coordinator.arm(signal)
        coordinator.release(signal)
        assertFalse(coordinator.isArmed(signal))
        // The late native completion for the settled/timed-out operation lands nowhere.
        assertFalse(coordinator.dispatch(completionEvent(token = signal.token)))
        assertFalse(signal.isCompleted())
    }

    @Test
    fun `dispatch completes exactly once and removes the entry`() = runTest {
        val coordinator = SeekCompletionCoordinator()
        val signal = SeekCompletionSignal()
        val observer = Observer(Dispatchers.Unconfined)
        observer.watch(signal)
        coordinator.arm(signal)
        assertTrue(coordinator.dispatch(completionEvent(token = signal.token)))
        // A duplicate of the same event (a second callback for one seek) settles nothing new.
        assertFalse(coordinator.dispatch(completionEvent(token = signal.token)))
        assertEquals(42_000, observer.observed?.positionMs)
    }

    @Test
    fun `tokens are distinct per invocation`() {
        val a = SeekCompletionSignal()
        val b = SeekCompletionSignal()
        assertTrue(a.token != b.token)
    }

    @Test
    fun `the event carries the completion's episode label for the sink to verify`() = runTest {
        val coordinator = SeekCompletionCoordinator()
        val signal = SeekCompletionSignal()
        val observer = Observer(Dispatchers.Unconfined)
        observer.watch(signal)
        coordinator.arm(signal)
        coordinator.dispatch(completionEvent(token = signal.token, episode = "episode-B"))
        // The manager stamps the RELAY episode onto the event; ownership is proven by the token, and
        // the SINK verifies the episode label against the initiating item. Assert the label passes
        // through unaltered.
        assertEquals("episode-B", observer.observed?.episodeUuid)
    }

    @Test
    fun `an untokened event is constructable and reports no identity`() {
        // The coordinator is episode-agnostic: the null-token event is the untracked UI seek's
        // completion, constructable like the player emits it.
        assertNull(completionEvent(token = null).completionToken)
    }
}
