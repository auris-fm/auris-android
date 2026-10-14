package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The limiter that keeps an automatically restarting capture from looping, and that also gates the
 * acquisition and start paths the routing-release callbacks re-enter.
 */
class CaptureRestartBudgetTest {

    private var now = 0L
    private val budget = CaptureRestartBudget(nowMs = { now })

    @Test
    fun `a first start is allowed`() {
        assertTrue(budget.allows())
    }

    @Test
    fun `restarts spread beyond the window are not counted together`() {
        now = 10_000
        budget.onTerminated()
        now += 6_000
        budget.onTerminated()
        now += 6_000
        budget.onTerminated()
        now += 6_000
        budget.onTerminated()

        assertTrue("terminations spaced beyond the window do not accumulate", budget.allows())
    }

    @Test
    fun `too many endings inside the window withhold restarts`() {
        now = 10_000
        repeat(4) {
            budget.onTerminated()
            now += 600 // within the 5s window
        }

        assertFalse("the ceiling is reached and restarts are withheld", budget.allows())
    }

    @Test
    fun `the limiter lifts once a full window passes without another ending`() {
        now = 10_000
        repeat(4) {
            budget.onTerminated()
            now += 600
        }
        assertFalse(budget.allows())

        now += 5_000
        assertTrue("a quiet window re-arms a restart on the next trigger", budget.allows())
    }

    @Test
    fun `a user-driven start clears the budget`() {
        now = 10_000
        repeat(4) {
            budget.onTerminated()
            now += 600
        }
        assertFalse(budget.allows())

        budget.reset()
        assertTrue("a user-driven start is never held by a previous session's limiter", budget.allows())
    }
}
