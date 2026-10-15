package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cap that keeps an automatically restarting capture from looping, and that also gates the
 * acquisition and start paths the routing-release callbacks re-enter.
 */
class CaptureRestartBudgetTest {

    private val budget = CaptureRestartBudget()

    @Test
    fun `a first start is allowed`() {
        assertTrue(budget.allows())
    }

    @Test
    fun `automatic restarts are allowed up to the cap`() {
        repeat(3) { attempt ->
            budget.onTerminated()
            assertTrue("restart ${attempt + 1} is within the cap", budget.allows())
        }
    }

    @Test
    fun `the cap refuses further automatic restarts`() {
        repeat(4) { budget.onTerminated() }

        assertFalse("the cap is reached and restarts are withheld", budget.allows())
    }

    @Test
    fun `a user-driven start clears the cap`() {
        repeat(4) { budget.onTerminated() }
        assertFalse(budget.allows())

        budget.reset()

        assertTrue("a user-driven start is never held by a previous session's cap", budget.allows())
    }
}
