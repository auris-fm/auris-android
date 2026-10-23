package au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals

import au.com.shiftyjelly.pocketcasts.sharedtest.MainCoroutineRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GracePeriodSignalTest {

    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    @Test
    fun `a current completion extends the window through the timer`() = runTest {
        // Extending is observable through the timer: a completion that extends
        // pushes expiry past the original deadline.
        val signal = GracePeriodSignal(timeoutMs = 100L)
        signal.onWakeWordDetected()
        val generation = signal.currentGeneration

        delay(80L)
        signal.onCommandRecognized(fromGeneration = generation)
        delay(40L)

        assertTrue("expiry moved out past the original deadline", signal.isActive.value)
    }

    @Test
    fun `a completion from a superseded window does not extend the new one`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 100L)
        signal.onWakeWordDetected()
        val superseded = signal.currentGeneration

        // Privacy close, then a fresh wake: a new window and a new generation.
        signal.onAppBackgrounded()
        signal.onWakeWordDetected()

        delay(80L)
        // The old turn lands now. It belongs to a window that is gone, so it
        // must not hold the new one open — that is the difference between a
        // state check and an identity check.
        signal.onCommandRecognized(fromGeneration = superseded)
        delay(40L)
        assertFalse("a superseded completion must not extend", signal.isActive.value)
    }

    @Test
    fun `a completion after a privacy close does not reopen the window`() {
        val signal = GracePeriodSignal(timeoutMs = 30_000L)

        signal.onWakeWordDetected()
        val generation = signal.currentGeneration

        // The network turn is still in flight when the privacy event lands.
        signal.onAppBackgrounded()
        signal.onCommandRecognized(fromGeneration = generation)

        assertFalse("a completion must not undo a close", signal.isActive.value)
        assertFalse(signal.isCurrent(signal.currentGeneration))
    }

    @Test
    fun `a privacy close is final until a new wake`() {
        val signal = GracePeriodSignal(timeoutMs = 30_000L)

        signal.onWakeWordDetected()
        val generation = signal.currentGeneration
        signal.onAppBackgrounded()

        // No caller can reopen it: not a command, not a completion.
        signal.onCommandRecognized()
        assertFalse(signal.isActive.value)
        signal.onCommandRecognized(fromGeneration = generation)
        assertFalse(signal.isActive.value)

        // A fresh wake is a new act, so it opens a window again.
        signal.onWakeWordDetected()
        assertTrue(signal.isActive.value)
    }

    @Test
    fun `an expired or closed window refuses a captured request origin`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 100L)

        signal.onWakeWordDetected()
        delay(150L)
        assertFalse(signal.isActive.value)
        assertFalse("an expired window is not an open act", signal.isCurrent(signal.currentGeneration))

        signal.onWakeWordDetected()
        signal.onAudioRouteChanged()
        assertFalse("a privacy-closed window is not an open act", signal.isCurrent(signal.currentGeneration))

        signal.onWakeWordDetected()
        signal.onAppBackgrounded()
        assertFalse("a privacy-closed window is not an open act", signal.isCurrent(signal.currentGeneration))
    }

    @Test
    fun `initially inactive`() {
        val signal = GracePeriodSignal()
        assertFalse(signal.isActive.value)
    }

    @Test
    fun `active after recognized command`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 100L)
        signal.onCommandRecognized()
        assertTrue(signal.isActive.value)
    }

    @Test
    fun `expires after timeout`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 100L)
        signal.onCommandRecognized()
        assertTrue(signal.isActive.value)
        delay(150L)
        assertFalse(signal.isActive.value)
    }

    @Test
    fun `command resets timer`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 200L)
        signal.onCommandRecognized()
        delay(150L)
        signal.onCommandRecognized() // reset
        delay(150L)
        assertTrue(signal.isActive.value) // still active
        delay(60L)
        assertFalse(signal.isActive.value) // 210ms after last command
    }

    @Test
    fun `active after wake word detection`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 100L)
        signal.onWakeWordDetected()
        assertTrue(signal.isActive.value)
    }

    @Test
    fun `wake word detection expires after timeout`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 100L)
        signal.onWakeWordDetected()
        assertTrue(signal.isActive.value)
        delay(150L)
        assertFalse(signal.isActive.value)
    }

    @Test
    fun `wake word resets the same timer as command`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 200L)
        signal.onCommandRecognized()
        delay(150L)
        signal.onWakeWordDetected() // resets the shared timer
        delay(150L)
        assertTrue(signal.isActive.value) // still active
        delay(60L)
        assertFalse(signal.isActive.value) // 210ms after wake word
    }

    @Test
    fun `route change breaks grace period`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 200L)
        signal.onCommandRecognized()
        assertTrue(signal.isActive.value)
        signal.onAudioRouteChanged()
        assertFalse(signal.isActive.value)
    }

    @Test
    fun `app switch breaks grace period`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 200L)
        signal.onCommandRecognized()
        assertTrue(signal.isActive.value)
        signal.onAppBackgrounded()
        assertFalse(signal.isActive.value)
    }

    @Test
    fun `a privacy close advances the closure counter and a wake does not`() {
        // The sink's guard depends on this count being monotonic: it is compared across a suspension,
        // and a wake clears the live boolean before the suspended turn's cleanup runs, so a boolean
        // cannot answer "did a privacy event happen since?". This pins the property the fix rests on,
        // through the real signal rather than a test-supplied value.
        val signal = GracePeriodSignal()

        signal.onWakeWordDetected()
        val afterWake = signal.privacyClosureCount()

        signal.onAudioRouteChanged()
        assertEquals(
            "a route-loss privacy close must advance the counter",
            afterWake + 1,
            signal.privacyClosureCount(),
        )

        // A wake reopens the window and clears the live flag; the count must survive it, since that
        // survival is exactly what lets a suspended turn detect the closure.
        signal.onWakeWordDetected()
        assertFalse("the wake clears the live flag", signal.isClosedByPrivacy())
        assertEquals(
            "a wake must not reset the counter",
            afterWake + 1,
            signal.privacyClosureCount(),
        )

        signal.onAppBackgrounded()
        assertEquals(
            "backgrounding is the other privacy close and must advance it too",
            afterWake + 2,
            signal.privacyClosureCount(),
        )
    }

    @Test
    fun `an expired extension CLOSES its window rather than only reporting retirement`() = runTest {
        // @reviewer's defect in my rework: the reset/extension timer notified retirement but no longer
        // cleared `isActive`, so an expired extension left the window reading as OPEN -- the microphone
        // would stay in Continuous mode with nothing behind it.
        val signal = GracePeriodSignal(timeoutMs = 50L)
        signal.onWakeWordDetected()
        assertTrue("the window is open", signal.isActive.value)

        // A COMMAND EXTENDS it, arming a fresh timer. That timer must still close on expiry.
        signal.onCommandRecognized(fromGeneration = signal.capturedGeneration())
        delay(120L)

        assertFalse(
            "an expired extension must leave the window CLOSED, not open",
            signal.isActive.value,
        )
    }

    @Test
    fun `an UNSTAMPED command that replaces a window still reports the window it ended`() = runTest {
        // @reviewer: the unstamped branch called `openWindowLocked()` and DISCARDED the ended
        // generation, so a command that replaced a window emitted NO retirement callback. "A replacement
        // is an ending" -- and this was the one open path that ignored it.
        val retired = mutableListOf<Long>()
        val signal = GracePeriodSignal(timeoutMs = 30_000L)
        signal.onWindowEnded = { retired += it }
        signal.onWakeWordDetected()
        val first = signal.capturedGeneration()

        // An unstamped command: a fresh act the app handled locally, with no window of its own.
        signal.onCommandRecognized(fromGeneration = null)
        val second = signal.capturedGeneration()

        assertTrue("the window advanced", second != first)
        assertTrue(
            "the replaced window must be reported even on the unstamped path (retired=$retired)",
            retired.contains(first),
        )
    }

    @Test
    fun `the readers agree with the transitions they read`() {
        // @reviewer's reader-lock finding. `isClosedByPrivacy` and `currentGeneration`
        // read the SAME fields the transitions write, so leaving them unlocked meant a reader could
        // observe a HALF-APPLIED transition. Holding the monitor must block them, exactly as it blocks a
        // writer -- if a reader answers while the monitor is held, it is reading a state mid-change.
        val signal = GracePeriodSignal(timeoutMs = 30_000L)
        signal.onWakeWordDetected()

        val holding = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val answered = java.util.concurrent.CountDownLatch(1)

        val holder = Thread {
            synchronized(signal.windowMonitorForTest()) {
                holding.countDown()
                release.await(3, java.util.concurrent.TimeUnit.SECONDS)
            }
        }.apply { start() }
        holding.await(3, java.util.concurrent.TimeUnit.SECONDS)

        // EACH READER IS OBSERVED ON ITS OWN. Calling all three on one thread let the FIRST locked one
        // hide an unlocked sibling: the mutation that unlocked `isClosedByPrivacy` survived, because the
        // other two still blocked and the thread never finished. A control has to make the reader it is
        // about the only thing that can decide the outcome.
        val readers: List<Pair<String, () -> Any?>> = listOf(
            "isClosedByPrivacy" to { signal.isClosedByPrivacy() },
            "currentGeneration" to { signal.currentGeneration },
        )
        val unguarded = mutableListOf<String>()
        readers.forEach { (name, read) ->
            val done = java.util.concurrent.CountDownLatch(1)
            Thread {
                read()
                done.countDown()
            }.apply { start() }
            if (done.await(150, java.util.concurrent.TimeUnit.MILLISECONDS)) unguarded += name
        }
        release.countDown()
        holder.join(3_000)

        assertTrue(
            "every reader of window state must wait for the monitor; answered while held: $unguarded",
            unguarded.isEmpty(),
        )
    }

    @Test
    fun `a wake's marker is never visible before its window`() {
        // Mutation B taught me the previous case could not see this: holding the monitor only proves the
        // wake WAITS, and a marker written BEFORE it waits is invisible to that observation. What the
        // split actually produces is a MARKER AHEAD OF ITS WINDOW -- the next wake's generation, while
        // the current window is still the old one. `isWakeOriginated` must never report a window that
        // does not exist yet, so this reads the marker while the monitor is held.
        val signal = GracePeriodSignal(timeoutMs = 30_000L)
        signal.onWakeWordDetected()
        val window = signal.capturedGeneration()

        val holding = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val holder = Thread {
            synchronized(signal.windowMonitorForTest()) {
                holding.countDown()
                release.await(3, java.util.concurrent.TimeUnit.SECONDS)
            }
        }.apply { start() }
        holding.await(3, java.util.concurrent.TimeUnit.SECONDS)

        // Attempt a wake while the monitor is held. A correct wake writes NOTHING before it waits, so
        // the only wake-originated window is still `window`; a split wake has already written the NEXT
        // generation as wake-originated, and that window does not exist at all.
        val wake = Thread { signal.onWakeWordDetected() }.apply { start() }
        try {
            Thread.sleep(100)
            val ahead = signal.wakeOriginatedGenerationForTest()
            assertTrue(
                "the wake marker must not name a window ahead of the live one " +
                    "(marker=$ahead live=$window)",
                ahead == window,
            )
        } finally {
            release.countDown()
            holder.join(3_000)
            wake.join(3_000)
            assertFalse("wake must finish before the test resets Dispatchers.Main", wake.isAlive)
            signal.onAppBackgrounded()
        }
    }

    @Test
    fun `a wake sets its marker in the same transition that opens the window`() {
        // The marker and the opening are the SAME act. Setting the marker around `openWindow()` left a
        // moment where the window was open under the PREVIOUS marker, which a concurrent reader could
        // observe. This holds the monitor and asserts a wake cannot proceed at all while it is held --
        // so the marker cannot be written outside the transition either.
        val signal = GracePeriodSignal(timeoutMs = 30_000L)
        signal.onWakeWordDetected()

        val holding = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val woke = java.util.concurrent.CountDownLatch(1)

        val holder = Thread {
            synchronized(signal.windowMonitorForTest()) {
                holding.countDown()
                release.await(3, java.util.concurrent.TimeUnit.SECONDS)
            }
        }.apply { start() }
        holding.await(3, java.util.concurrent.TimeUnit.SECONDS)

        Thread {
            signal.onWakeWordDetected()
            woke.countDown()
        }.apply { start() }
        assertFalse(
            "a wake must not open or mark while the monitor is held",
            woke.await(150, java.util.concurrent.TimeUnit.MILLISECONDS),
        )
        release.countDown()
        assertTrue("and it proceeds once released", woke.await(3, java.util.concurrent.TimeUnit.SECONDS))
        holder.join(3_000)
    }

    @Test
    fun `recording a prepared request blocks on the same monitor as the transitions`() {
        // `notePreparedRequest` was `@Synchronized`, which takes the INSTANCE monitor -- a DIFFERENT lock
        // from windowLock, so it serialized against other `@Synchronized` members and against nothing
        // that mattered. The window it notes a request for is decided by the transitions, so the write
        // must be ordered with them.
        val signal = GracePeriodSignal(timeoutMs = 30_000L)
        signal.onWakeWordDetected()
        val window = signal.capturedGeneration()

        val holding = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val noted = java.util.concurrent.CountDownLatch(1)

        val holder = Thread {
            synchronized(signal.windowMonitorForTest()) {
                holding.countDown()
                release.await(3, java.util.concurrent.TimeUnit.SECONDS)
            }
        }.apply { start() }
        holding.await(3, java.util.concurrent.TimeUnit.SECONDS)

        Thread {
            signal.notePreparedRequest(window)
            noted.countDown()
        }.apply { start() }

        assertFalse(
            "recording a prepared request must wait for the window monitor",
            noted.await(150, java.util.concurrent.TimeUnit.MILLISECONDS),
        )
        release.countDown()
        assertTrue(
            "and it proceeds once the monitor is free",
            noted.await(3, java.util.concurrent.TimeUnit.SECONDS),
        )
        holder.join(3_000)
    }

    @Test
    fun `a completion PARKED at the monitor cannot reopen a window privacy closed`() {
        // @reviewer's parked-completion control, and @spec's requirement that the origin/liveness
        // validation sit INSIDE the same windowLock transaction as the extension. When the check runs
        // before the lock, a completion that passed it can be parked while a privacy close takes the
        // lock, then resume and -- on the strength of the earlier check -- REOPEN LISTENING. The
        // measured result was exactly that: listening reopened after privacy closed it.
        val signal = GracePeriodSignal(timeoutMs = 30_000L)
        signal.onWakeWordDetected()
        val window = signal.capturedGeneration()

        val completionEntered = java.util.concurrent.CountDownLatch(1)
        val holdTheLock = java.util.concurrent.CountDownLatch(1)
        val completionDone = java.util.concurrent.CountDownLatch(1)

        // Hold the monitor from another thread, so a completion that takes it will PARK there.
        val holder = Thread {
            synchronized(signal.windowMonitorForTest()) {
                completionEntered.countDown()
                holdTheLock.await(3, java.util.concurrent.TimeUnit.SECONDS)
            }
        }.apply { start() }
        completionEntered.await(3, java.util.concurrent.TimeUnit.SECONDS)

        val completion = Thread {
            signal.onCommandRecognized(fromGeneration = window)
            completionDone.countDown()
        }.apply { start() }

        // PRIVACY CLOSES while the completion is parked: it still needs the monitor, so it waits.
        val privacy = Thread { signal.onAppBackgrounded() }.apply { start() }
        privacy.join(3_000)

        // Now release the monitor and let the parked completion resume.
        holdTheLock.countDown()
        completion.join(4_000)
        holder.join(4_000)
        assertTrue("the completion ran", completionDone.await(2, java.util.concurrent.TimeUnit.SECONDS))

        assertFalse(
            "a completion parked at the monitor must NOT reopen a window privacy closed",
            signal.isActive.value,
        )
    }

    @Test
    fun `a timer that lost ownership by REPLACEMENT cannot close its successor`() = runTest {
        // The generation check's own case. The cancel above stops a coroutine, but `armTimer` checks
        // ownership too, so a timer that reaches its body while a LATER generation is current must
        // still refuse. Here the successor is armed by a path that CANCELS NOTHING -- so the only thing
        // standing between the old timer and the successor's state is the ownership check itself.
        val retired = mutableListOf<Long>()
        val signal = GracePeriodSignal(timeoutMs = 100L)
        signal.onWindowEnded = { retired += it }
        signal.onWakeWordDetected()
        val first = signal.capturedGeneration()

        delay(60L)
        // REPLACE the window without cancelling the outstanding timer: the old timer is still scheduled
        // to fire at its original deadline, and by then it owns nothing.
        signal.replaceWindowWithoutCancellingTimer()
        val second = signal.capturedGeneration()
        assertTrue("the window advanced", second != first)
        val retiredBefore = retired.toList()

        delay(80L)
        assertTrue(
            "a timer whose window was REPLACED must not close the successor " +
                "(retired=${retired.toList()} before=$retiredBefore)",
            signal.isActive.value,
        )
        assertTrue("and it must not retire it", retired.toList() == retiredBefore)
    }

    @Test
    fun `a cancelled old timer cannot close or retire its successor`() = runTest {
        // The other timer defect: a timer read the LIVE generation at expiry, so one CANCELLED by a
        // successor could close that successor when it finally fired.
        val retired = mutableListOf<Long>()
        val signal = GracePeriodSignal(timeoutMs = 100L)
        signal.onWindowEnded = { retired += it }
        signal.onWakeWordDetected()
        val first = signal.capturedGeneration()

        delay(60L)
        // A SUCCESSOR opens before the first timer expires: the old timer is cancelled.
        signal.onWakeWordDetected()
        val second = signal.capturedGeneration()
        assertTrue("the window advanced", second != first)
        val retiredBefore = retired.toList()

        // Let the OLD timer's original deadline pass. It must do nothing.
        delay(80L)

        assertTrue(
            "the successor is still open after the old timer's deadline " +
                "(retired=${retired.toList()} before=$retiredBefore)",
            signal.isActive.value,
        )
        assertTrue(
            "and the old timer did not retire its successor",
            retired.toList() == retiredBefore,
        )
    }

    @Test
    fun `a delayed arm cannot take over a window that already ended`() = runTest {
        // @reviewer's finding. `armTimerForLocked` checked GENERATION ownership only -- and a PRIVACY
        // CLOSE PRESERVES THE GENERATION, because it ends the window without advancing it. So a
        // completion that was valid when it started could still arm this window's timer AFTER the window
        // was closed: the closed window holds a live timer owned by nobody, and that timer later reports
        // an ENDING for a window that already ended -- TWO endings for one window.
        //
        // Ownership is generation AND liveness. The check lives in the protected assignment because the
        // caller releases windowLock before arming, so that is the last point ownership can be settled.
        val signal = GracePeriodSignal(timeoutMs = 30_000L)
        signal.onWakeWordDetected()
        val closedWindow = signal.currentGeneration

        signal.onAppBackgrounded()
        assertFalse("the privacy close really ended activity", signal.isActive.value)

        val before = signal.timerJobForTest()
        // The delayed arm, arriving after the close.
        signal.armTimerForLockedForTest(closedWindow)

        assertTrue(
            "a delayed arm for an ENDED window must not replace the current timer",
            before === signal.timerJobForTest(),
        )
        assertFalse("and it must not reopen the window", signal.isActive.value)
    }

    @Test
    fun `a delayed arm for a REPLACED window cannot take over its successor`() = runTest {
        // The neighbouring property, so the liveness check cannot be satisfied by refusing everything:
        // a REPLACED window DOES advance the generation, so the delayed arm must be refused for that
        // reason and the successor's timer must survive.
        val signal = GracePeriodSignal(timeoutMs = 30_000L)
        signal.onWakeWordDetected()
        val replaced = signal.currentGeneration

        signal.onWakeWordDetected()
        assertTrue("replacement advanced ownership", replaced != signal.currentGeneration)

        val before = signal.timerJobForTest()
        signal.armTimerForLockedForTest(replaced)

        assertTrue(
            "a delayed arm for a REPLACED window must not replace its successor's timer",
            before === signal.timerJobForTest(),
        )
        assertTrue("and the successor is still open", signal.isActive.value)
    }
}
