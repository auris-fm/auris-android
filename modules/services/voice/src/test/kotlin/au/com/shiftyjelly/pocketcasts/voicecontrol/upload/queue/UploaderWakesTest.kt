package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The uploader's wake source (spec ruling, reviewer simplification S4): the uploader does NOT
 * poll on a 60-second timer. It runs a pass, then sleeps until either an EVENT wake arrives
 * (recording published, sign-in restored, app foreground, network back) or exactly ONE timer
 * set to the earliest retry time fires. A retryable answer schedules that one timer; a wake
 * during the backoff window drains immediately.
 */
class UploaderWakesTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `each wake drains`() = runTest {
        var passes = 0
        val wakes = UploaderWakes(
            earliestRetryAtMs = { null },
            nowMs = { 0L },
            pass = { passes += 1 },
        )
        // A dedicated scope: the worker's tasks are ordinary scheduler tasks the virtual clock
        // advances (runTest's backgroundScope tasks are not advanced by advanceUntilIdle here).
        val workerScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        wakes.startPass(workerScope)
        advanceUntilIdle()
        val initial = passes
        wakes.wake()
        wakes.wake()
        advanceUntilIdle()
        assertEquals("the initial pass plus each wake must drain", initial + 2, passes)
        workerScope.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a retryable answer schedules exactly ONE timer at the backoff time`() = runTest {
        var passes = 0
        // The loop schedules a retry on the first (failing) pass; the retry firing CLEARS it —
        // the real loop only re-schedules when the retry itself fails, which this fixture does
        // not model. (A self-rearming timer here would never go idle, hanging the scheduler.)
        var retryScheduled = false
        val wakes = UploaderWakes(
            earliestRetryAtMs = { if (retryScheduled) 60_000L else null },
            nowMs = { 0L },
            pass = {
                passes += 1
                if (passes == 1) retryScheduled = true
                if (passes > 1) retryScheduled = false
            },
        )
        // A dedicated scope: the worker's tasks are ordinary scheduler tasks the virtual clock
        // advances (runTest's backgroundScope tasks are not advanced by advanceUntilIdle here).
        val workerScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        wakes.startPass(workerScope)
        // UNDISPATCHED start: the initial pass already ran synchronously; the retry timer is
        // armed. (advanceUntilIdle must NOT be used while a timer is armed: it advances ALL
        // virtual time and would fire it immediately.)
        assertEquals(1, passes)
        // Inside the backoff window nothing runs: the single timer has not fired yet.
        advanceTimeBy(59_999)
        assertEquals("no pass before the timer is due", 1, passes)
        // The timer fires exactly at the backoff time, once. The fired retry clears, so the
        // worker then suspends on the wake channel and the scheduler goes idle.
        advanceTimeBy(1)
        advanceUntilIdle()
        assertEquals("the retry timer fires once, at the backoff time", 2, passes)
        // And it does not repeat: with no new retry scheduled, no further pass.
        advanceTimeBy(600_000)
        assertEquals(2, passes)
        workerScope.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a wake during the backoff window drains immediately instead of waiting for the timer`() = runTest {
        var passes = 0
        // The loop schedules a retry on the first (failing) pass; the retry firing CLEARS it —
        // the real loop only re-schedules when the retry itself fails, which this fixture does
        // not model. (A self-rearming timer here would never go idle, hanging the scheduler.)
        var retryScheduled = false
        val wakes = UploaderWakes(
            earliestRetryAtMs = { if (retryScheduled) 60_000L else null },
            nowMs = { 0L },
            pass = {
                passes += 1
                if (passes == 1) retryScheduled = true
                if (passes > 1) retryScheduled = false
            },
        )
        // A dedicated scope: the worker's tasks are ordinary scheduler tasks the virtual clock
        // advances (runTest's backgroundScope tasks are not advanced by advanceUntilIdle here).
        val workerScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        wakes.startPass(workerScope)
        advanceTimeBy(10_000)
        assertEquals("still inside the backoff window", 1, passes)
        wakes.wake()
        advanceUntilIdle()
        assertEquals("an event wake must not wait out the retry timer", 2, passes)
        workerScope.cancel()
    }
}
