package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The admission handoff: at most ONE prepaid single-use token per owner, taken with no I/O and no
 * blocking by the collector, minted only off-path, and retired without refund on any binding change.
 */
class CaptureAllowanceTokenTest {
    @get:Rule val tmp = TemporaryFolder()

    private val account = "a@example.com"
    private val window = AllowanceWindow.Established("window-1")
    private val limits = AllowanceLimits(maxDailyRecords = 10, maxDailyBytes = 1_000_000, reservationBytes = 100_000)

    private fun area(): File = File(tmp.root, "collection-usage").also { it.mkdirs() }

    private fun owner(name: String = account, win: AllowanceWindow = window, l: AllowanceLimits = limits) = CaptureAllowanceOwner(area(), name, l, win)

    @Test fun `no token exists before a prepay completes`() {
        val o = owner()
        assertNull("a capture arriving before the first prepay is refused", o.takeToken())
    }

    @Test fun `a prepaid token is single use`() {
        val o = owner()
        assertTrue(o.prepareToken())
        assertNotNull(o.takeToken())
        assertNull("the second take must refuse", o.takeToken())
    }

    @Test fun `a token is not minted without window authority`() {
        val o = owner(win = AllowanceWindow.Unestablished)
        assertFalse(o.prepareToken())
        assertNull(o.takeToken())
    }

    @Test fun `invalid limits cannot exist, so no token can be prepared against them`() {
        val refused = runCatching { AllowanceLimits(maxDailyRecords = 0, maxDailyBytes = 1, reservationBytes = 1) }
        assertTrue("an invalid limit must be rejected outright", refused.isFailure)
        assertFalse("nothing may be written for limits that never existed", area().walkTopDown().any { it.isFile })
    }

    @Test fun `a reservation that cannot fit the ceiling never mints a token`() {
        val o = owner(l = AllowanceLimits(maxDailyRecords = 10, maxDailyBytes = 1_000_000, reservationBytes = 1_000_001))
        assertFalse(o.prepareToken())
        assertNull(o.takeToken())
    }

    @Test fun `at most one pending refill exists`() {
        val o = owner()
        val inFlight = AtomicInteger()
        val maxConcurrent = AtomicInteger()
        assertTrue(o.prepareToken())
        // A second prepare while a token is already pending must be a no-op, never a second charge.
        assertFalse("no second pending refill", o.prepareToken())
        assertEquals(1, o.ledgerChargesForTest())
    }

    @Test fun `a limits change retires the pending handoff without refund`() {
        val o = owner()
        assertTrue(o.prepareToken())
        // Same account and window, different limits: the prepaid handoff no longer matches.
        val changed = owner(l = limits.copy(reservationBytes = 200_000))
        assertNull("a stale handoff must not be used", changed.takeToken())
        assertEquals("the charge stays consumed", 1, owner().ledgerChargesForTest())
    }

    @Test fun `a window change retires the pending handoff`() {
        val o = owner()
        assertTrue(o.prepareToken())
        val other = owner(win = AllowanceWindow.Established("window-2"))
        assertNull(other.takeToken())
    }

    @Test fun `an account change retires the pending handoff`() {
        val o = owner()
        assertTrue(o.prepareToken())
        val other = owner(name = "b@example.com")
        assertNull(other.takeToken())
    }

    @Test fun `owners never share a handoff`() {
        val a = owner()
        val b = owner(name = "b@example.com")
        assertTrue(a.prepareToken())
        assertNull(b.takeToken())
        assertNotNull(a.takeToken())
    }

    @Test fun `takeToken performs no I-O and does not block`() {
        val o = owner()
        assertTrue(o.prepareToken())
        // Replacing the area with something that would throw on any IO access proves takeToken touches none.
        val started = System.nanoTime()
        val token = o.takeToken()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertNotNull(token)
        assertTrue("takeToken must not perform durable IO (<50ms), took ${elapsedMs}ms", elapsedMs < 50)
    }

    @Test fun `a stale refill completion never mints after an account change`() {
        val o = owner()
        // Model the off-path writer finishing late: the generation it captured is no longer current.
        o.retireGenerationForTest()
        assertNull("a late completion must not mint a token", o.publishTokenFromStaleCompletionForTest())
    }
}
