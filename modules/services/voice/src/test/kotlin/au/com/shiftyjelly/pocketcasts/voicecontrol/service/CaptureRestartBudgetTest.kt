package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The restart rule of `voice-control-core.md` > Automatic capture restarts: capture that ends on
 * its own gets ONE automatic restart. If that restart fails to start or the restarted capture ends
 * again within the healthy window, automatic restarts are withheld until a real trigger re-arms
 * them (a device change, a blocker clearing, the user turning voice control on). Capture that ran
 * the healthy window or longer is working, so its next self-end gets a fresh restart.
 */
class CaptureRestartBudgetTest {

    private val budget = CaptureRestartBudget()

    @Test
    fun `a first start is allowed`() {
        assertTrue(budget.allows())
    }

    @Test
    fun `a healthy self-ended capture gets exactly one automatic restart`() {
        budget.onTerminated(ranForMs = 120_000)
        assertTrue("the healthy end grants one restart", budget.allows())

        budget.onAutomaticRestart()
        assertFalse("the granted restart is spent once it begins", budget.allows())
    }

    @Test
    fun `a restart that dies within the healthy window withholds further restarts`() {
        budget.onTerminated(ranForMs = 120_000)
        budget.onAutomaticRestart()
        budget.onTerminated(ranForMs = 30_000)

        assertFalse("the fast-dying restart consumes the credit", budget.allows())
    }

    @Test
    fun `a restart that runs the healthy window restores the credit`() {
        budget.onTerminated(ranForMs = 120_000)
        budget.onAutomaticRestart()
        budget.onTerminated(ranForMs = 61_000)

        assertTrue("healthy capture resets the rule", budget.allows())
    }

    @Test
    fun `a normal capture that dies fast still gets its one restart`() {
        budget.onTerminated(ranForMs = 5_000)

        assertTrue("the first self-end always restarts, whatever its length", budget.allows())
    }

    @Test
    fun `a real trigger re-arms the budget`() {
        budget.onTerminated(ranForMs = 120_000)
        budget.onAutomaticRestart()
        budget.onTerminated(ranForMs = 30_000)
        assertFalse(budget.allows())

        budget.rearm()

        assertTrue("a device change, blocker clearing or user re-enable re-arms restarts", budget.allows())
    }

    @Test
    fun `a user-driven start clears the budget`() {
        budget.onTerminated(ranForMs = 120_000)
        budget.onAutomaticRestart()
        budget.onTerminated(ranForMs = 30_000)
        assertFalse(budget.allows())

        budget.reset()

        assertTrue("a user-driven start is never held by a previous session's rule", budget.allows())
    }
}
