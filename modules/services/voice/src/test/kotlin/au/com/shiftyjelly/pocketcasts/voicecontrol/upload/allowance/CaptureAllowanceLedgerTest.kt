package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Durable capture-allowance accounting for spec delta b768de49.
 *
 * The property under test is that usage belongs to the stable contributor account and only ever moves
 * forward: a fresh owner must never re-derive zero usage from a missing, corrupt or interrupted ledger,
 * and an interrupted charge must never become an admitted capture.
 */
class CaptureAllowanceLedgerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val account = "a@example.com"
    private val other = "b@example.com"

    // Production has NO established accounting window, so no charge may ever be authorized.
    private val noWindow: AllowanceWindow = AllowanceWindow.Unestablished
    private val window = AllowanceWindow.Established("window-1")

    @After fun cleanup() = Unit

    private fun area(): File = File(tmp.root, "collection-usage").also { it.mkdirs() }

    private fun ledger(dir: File = area(), win: AllowanceWindow = noWindow) = CaptureAllowanceLedger(dir, account, limits(), win)

    private fun limits() = AllowanceLimits(maxDailyRecords = 10, maxDailyBytes = 1_000_000, reservationBytes = 100_000)

    @Test fun `refuses when window authority is unestablished and charges nothing`() {
        val l = ledger(win = noWindow)
        assertFalse(l.chargeForCapture())
        assertEquals(0, l.snapshot().chargedRecords)
        assertTrue("an unestablished window must not even establish accounting", l.isClosed())
    }

    @Test fun `first use under an established window initializes and charges`() {
        val l = ledger(win = window)
        assertTrue(l.chargeForCapture())
        assertEquals(1, l.snapshot().chargedRecords)
        assertEquals(100_000L, l.snapshot().chargedBytes)
    }

    @Test fun `invalid limits cannot be constructed and therefore never charge`() {
        val refused = runCatching { AllowanceLimits(maxDailyRecords = 0, maxDailyBytes = 1_000, reservationBytes = 10) }
        assertTrue("an invalid limit must be rejected outright", refused.isFailure)
        assertFalse("nothing may be written for limits that never existed", area().walkTopDown().any { it.isFile })
    }

    @Test fun `a reservation larger than the daily byte ceiling refuses before writing`() {
        val l = ledger(win = window)
        assertTrue(l.chargeForCapture())
        val tooBig = CaptureAllowanceLedger(area(), account, AllowanceLimits(10, 1_000_000, 1_000_001), window)
        assertFalse(tooBig.chargeForCapture())
        assertEquals("refusal must not consume allowance", 1, l.snapshot().chargedRecords)
    }

    @Test fun `usage survives a fresh instance and restart`() {
        assertTrue(ledger(win = window).chargeForCapture())
        val reopened = ledger(win = window)
        assertEquals(1, reopened.snapshot().chargedRecords)
        assertEquals(100_000L, reopened.snapshot().chargedBytes)
    }

    @Test fun `accounts never share usage`() {
        assertTrue(ledger(win = window).chargeForCapture())
        val otherLedger = CaptureAllowanceLedger(area(), other, limits(), window)
        assertEquals(0, otherLedger.snapshot().chargedRecords)
        assertTrue(otherLedger.chargeForCapture())
        assertEquals(1, otherLedger.snapshot().chargedRecords)
        assertEquals("first account keeps its own charge", 1, ledger(win = window).snapshot().chargedRecords)
    }

    @Test fun `the account name is never written to disk in any artifact`() {
        assertTrue(ledger(win = window).chargeForCapture())
        val leaked = area().walkTopDown().filter { it.isFile }.filter { f ->
            val bytes = f.readBytes()
            val text = String(bytes, Charsets.ISO_8859_1) + String(bytes, Charsets.UTF_8)
            text.contains("a@example.com") || text.contains(account)
        }.toList()
        assertEquals("usage metadata must stay content-free", emptyList<String>(), leaked.map { it.name })
    }

    @Test fun `exhausted records refuse while keeping the existing charge`() {
        val l = CaptureAllowanceLedger(area(), account, AllowanceLimits(1, 1_000_000, 10_000), window)
        assertTrue(l.chargeForCapture())
        assertFalse(l.chargeForCapture())
        assertEquals(1, l.snapshot().chargedRecords)
    }

    @Test fun `exhausted bytes refuse while keeping the existing charge`() {
        val l = CaptureAllowanceLedger(area(), account, AllowanceLimits(10, 150_000, 100_000), window)
        assertTrue(l.chargeForCapture())
        assertFalse(l.chargeForCapture())
        assertEquals(1, l.snapshot().chargedRecords)
    }

    @Test fun `a deleted ledger under established genesis closes instead of re-creating zero usage`() {
        assertTrue(ledger(win = window).chargeForCapture())
        area().walkTopDown().filter { it.isFile && it.name.contains("ledger") }.toList().forEach { it.delete() }
        // Deleting is not the only loss we must survive; treat an unreadable ledger the same way.
        val reopened = ledger(win = window)
        assertFalse("lost accounting must not re-initialize", reopened.chargeForCapture())
        assertTrue(reopened.isClosed())
    }

    @Test fun `a corrupt ledger closes and preserves its bytes`() {
        assertTrue(ledger(win = window).chargeForCapture())
        val ledgerFile = area().walkTopDown().first { it.isFile && it.name.contains("ledger") }
        ledgerFile.writeText("{ this is not valid json")
        val reopened = ledger(win = window)
        assertFalse(reopened.chargeForCapture())
        assertTrue(reopened.isClosed())
        assertEquals("{ this is not valid json", ledgerFile.readText())
    }

    @Test fun `an initiating genesis without a ledger closes across fresh owners`() {
        assertTrue(ledger(win = window).chargeForCapture())
        val genesis = area().walkTopDown().first { it.isFile && it.name.contains("genesis") }
        genesis.writeText(genesis.readText().replace("\"state\":\"ESTABLISHED\"", "\"state\":\"INITIALIZING\""))
        val reopened = ledger(win = window)
        assertFalse(reopened.chargeForCapture())
        assertTrue(reopened.isClosed())
    }

    @Test fun `an initializer that claimed the lock but crashed stays closed`() {
        assertTrue(ledger(win = window).chargeForCapture())
        area().walkTopDown().filter { it.isFile }.filter { it.name.contains("genesis") || it.name.contains("ledger") }.toList().forEach { it.delete() }
        // Only the lock artifact survives, exactly as a crashed initializer leaves it.
        val reopened = ledger(win = window)
        assertFalse("a lock artifact alone proves an owner existed", reopened.chargeForCapture())
        assertTrue(reopened.isClosed())
    }
}
