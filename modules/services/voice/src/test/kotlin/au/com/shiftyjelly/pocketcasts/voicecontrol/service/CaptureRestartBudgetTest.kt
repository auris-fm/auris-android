package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The budget that keeps an automatically restarting capture from spinning, and that also gates the
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
    fun `a restart waits for the settle delay`() {
        now = 10_000
        budget.onTerminated()

        now += 100
        assertFalse("a just-ended capture must not reopen immediately", budget.allows())

        now += 500
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
        now += 6_000

        now += 500 // past the settle delay of the last termination
        assertTrue("terminations spaced beyond the window do not accumulate", budget.allows())
    }

    @Test
    fun `too many endings inside the window block restarts for the cooldown`() {
        now = 10_000
        repeat(4) {
            budget.onTerminated()
            now += 600 // within the 5s window, past the settle delay
        }

        assertFalse("the ceiling is reached and restarts are withheld", budget.allows())

        now += 30_000
        assertTrue("the cooldown passes and a restart is allowed again", budget.allows())
    }

    @Test
    fun `right after an ending the wait is the settle delay`() {
        now = 10_000
        budget.onTerminated()

        assertFalse(budget.allows())
        assertEquals("a recovery waits the settle delay rather than refusing", 500, budget.delayUntilAllowedMs())

        now += 500
        assertEquals(0, budget.delayUntilAllowedMs())
        assertTrue(budget.allows())
    }

    @Test
    fun `a tripped ceiling reports the cooldown remaining rather than refusing outright`() {
        now = 10_000
        repeat(4) {
            budget.onTerminated()
            now += 600
        }

        assertTrue(budget.isBlocked)
        assertEquals("the wait covers the cooldown, not just the settle delay", 29_400, budget.delayUntilAllowedMs())

        now += 29_400
        assertFalse(budget.isBlocked)
        assertTrue(budget.allows())
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
        assertTrue("a user-driven start is never held by a previous session's cooldown", budget.allows())
    }
}
