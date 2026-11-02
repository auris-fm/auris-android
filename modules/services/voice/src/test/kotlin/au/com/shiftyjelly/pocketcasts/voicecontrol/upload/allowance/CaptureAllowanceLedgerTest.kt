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

    @Test fun `an empty area is not permission to create accounting without provisioning authority`() {
        val l = CaptureAllowanceLedger(area(), account, limits(), window, AllowanceProvisioning.Unavailable)
        assertFalse("no trusted first-provisioning authority exists in production", l.chargeForCapture())
        assertTrue(l.isClosed())
        assertFalse(
            "no accounting artifact may be created without authority",
            area().walkTopDown().any { it.isFile && it.name !in setOf("usage.lock") },
        )
    }

    @Test fun `total loss of the ledger and its claim refuses instead of re-provisioning`() {
        assertTrue(ledger(win = window).chargeForCapture())
        // Wipe the ENTIRE area: usage counters, genesis and the lock artifact all disappear, which is
        // indistinguishable from a machine that was never provisioned.
        area().walkTopDown().filter { it.isFile }.toList().forEach { it.delete() }
        val reopened = CaptureAllowanceLedger(area(), account, limits(), window, AllowanceProvisioning.Unavailable)
        assertFalse("a wiped area must not re-derive zero usage", reopened.chargeForCapture())
        assertTrue(reopened.isClosed())
    }

    // Production has NO established accounting window, so no charge may ever be authorized.
    private val noWindow: AllowanceWindow = AllowanceWindow.Unestablished
    private val window = AllowanceWindow.Established("window-1")

    @After fun cleanup() = Unit

    private fun area(): File = File(tmp.root, "collection-usage").also { it.mkdirs() }

    /** Tests opt in to first provisioning explicitly; production has no such authority. */
    private fun ledger(dir: File = area(), win: AllowanceWindow = noWindow) = CaptureAllowanceLedger(dir, account, limits(), win, provisioning = AllowanceProvisioning.TestOnly)

    private fun limits() = AllowanceLimits(10, 1_000_000, maxClipBytes = 100_000 - 1024, maxMetadataBytes = 1024, ceilings = Ceilings(10, 1_000_000, 1_000_000L, 8_192))

    @Test fun `refuses when window authority is unestablished and charges nothing`() {
        val l = ledger(win = noWindow)
        assertFalse(l.chargeForCapture())
        assertEquals("a closed ledger reports -1, never an empty allowance", -1, l.snapshotRecordCount())
        assertTrue("an unestablished window must not even establish accounting", l.isClosed())
    }

    @Test fun `first use under an established window initializes and charges`() {
        val l = ledger(win = window)
        assertTrue(l.chargeForCapture())
        assertEquals(1, l.snapshotRecordCount())
        assertEquals(100_000L, l.snapshotByteCount())
    }

    @Test fun `invalid limits cannot be constructed and therefore never charge`() {
        val refused = runCatching { AllowanceLimits(0, 1_000, maxClipBytes = 10 - 1024, maxMetadataBytes = 1024, ceilings = Ceilings(0, 1_000, 1_000_000L, 8_192)) }
        assertTrue("an invalid limit must be rejected outright", refused.isFailure)
        assertFalse("nothing may be written for limits that never existed", area().walkTopDown().any { it.isFile })
    }

    @Test fun `each selected value is validated against its own independent ceiling`() {
        val ok = Ceilings(maxDailyRecords = 10, maxDailyBytes = 1_000_000, maxClipBytes = 500_000, maxMetadataBytes = 8_192)
        fun limitsWith(ceilings: Ceilings) = AllowanceLimits(10, 1_000_000, maxClipBytes = 100_000, maxMetadataBytes = 1_024, ceilings = ceilings)
        assertTrue("a limit set inside its ceilings is valid", runCatching { limitsWith(ok) }.isSuccess)

        // One control per selected field, each exceeding ONLY its own counterpart.
        assertTrue(
            "selected records must not exceed their ceiling",
            runCatching { limitsWith(ok.copy(maxDailyRecords = 9)) }.isFailure,
        )
        assertTrue(
            "selected bytes must not exceed their ceiling",
            runCatching { limitsWith(ok.copy(maxDailyBytes = 999_999)) }.isFailure,
        )
        assertTrue(
            "selected clip must not exceed its ceiling",
            runCatching { limitsWith(ok.copy(maxClipBytes = 99_999)) }.isFailure,
        )
        assertTrue(
            "selected metadata must not exceed its ceiling",
            runCatching { limitsWith(ok.copy(maxMetadataBytes = 1_023)) }.isFailure,
        )

        // A daily byte ceiling that merely FITS one clip is not a clip ceiling: an over-policy clip must
        // still be refused even though the day could hold it.
        val roomy = Ceilings(maxDailyRecords = 10, maxDailyBytes = 10_000_000, maxClipBytes = 100_000, maxMetadataBytes = 8_192)
        assertTrue(
            "an over-ceiling clip must be refused even when the daily byte total allows it",
            runCatching {
                AllowanceLimits(10, 10_000_000, maxClipBytes = 900_000, maxMetadataBytes = 1_024, ceilings = roomy)
            }.isFailure,
        )
    }

    @Test fun `ceilings are independent inputs, not derived from the selected bounds`() {
        assertTrue(
            "a record ceiling of zero must be rejected",
            runCatching { Ceilings(0, 1_000_000, 1_000_000L, 8_192) }.isFailure,
        )
        val tight = AllowanceLimits(
            1,
            101_024,
            maxClipBytes = 100_000,
            maxMetadataBytes = 1_024,
            ceilings = Ceilings(1, 101_024, 100_000, 1_024),
        )
        assertTrue(tight.admits(0, 0))
        assertFalse("one record only", tight.admits(1, 0))
    }

    @Test fun `admission arithmetic cannot wrap at the byte ceiling`() {
        // A clip bound whose reservation cannot be represented must not be constructible at all.
        assertTrue(
            "a reservation that overflows Long must be rejected, not wrapped",
            runCatching {
                AllowanceLimits(10, Long.MAX_VALUE, maxClipBytes = Long.MAX_VALUE - 1, maxMetadataBytes = 2_000, ceilings = Ceilings(10, Long.MAX_VALUE, Long.MAX_VALUE, 8_192))
            }.isFailure,
        )
        val huge = AllowanceLimits(10, Long.MAX_VALUE, maxClipBytes = Long.MAX_VALUE - 1_000, maxMetadataBytes = 500, ceilings = Ceilings(10, Long.MAX_VALUE, Long.MAX_VALUE, 8_192))
        assertFalse("a wrapping reservation must not admit", huge.admits(chargedRecords = 1, chargedBytes = Long.MAX_VALUE - 500))
        assertFalse("a negative total must not admit", huge.admits(chargedRecords = 0, chargedBytes = -1))
        assertTrue("a reservation that genuinely fits is still admitted", huge.admits(0, 0))
    }

    @Test fun `a daily ceiling smaller than one reservation cannot be constructed`() {
        val refused = runCatching {
            AllowanceLimits(10, 1_000, maxClipBytes = 2_000, maxMetadataBytes = 1_024, ceilings = Ceilings(10, 1_000, 1_000_000L, 8_192))
        }
        assertTrue("a ceiling that admits no capture must be rejected", refused.isFailure)
    }

    @Test fun `usage survives a fresh instance and restart`() {
        assertTrue(ledger(win = window).chargeForCapture())
        val reopened = ledger(win = window)
        assertEquals(1, reopened.snapshotRecordCount())
        assertEquals(100_000L, reopened.snapshotByteCount())
    }

    @Test fun `accounts never share usage`() {
        assertTrue(ledger(win = window).chargeForCapture())
        val otherLedger = CaptureAllowanceLedger(area(), other, limits(), window, provisioning = AllowanceProvisioning.TestOnly)
        assertEquals("a second account starts with no accounting of its own", -1, otherLedger.snapshotRecordCount())
        assertTrue(otherLedger.chargeForCapture())
        assertEquals(1, otherLedger.snapshotRecordCount())
        assertEquals("first account keeps its own charge", 1, ledger(win = window).snapshotRecordCount())
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
        val l = CaptureAllowanceLedger(area(), account, AllowanceLimits(1, 1_000_000, maxClipBytes = 10_000 - 1024, maxMetadataBytes = 1024, ceilings = Ceilings(1, 1_000_000, 1_000_000L, 8_192)), window, provisioning = AllowanceProvisioning.TestOnly)
        assertTrue(l.chargeForCapture())
        assertFalse(l.chargeForCapture())
        assertEquals(1, l.snapshotRecordCount())
    }

    @Test fun `exhausted bytes refuse while keeping the existing charge`() {
        val l = CaptureAllowanceLedger(area(), account, AllowanceLimits(10, 150_000, maxClipBytes = 100_000 - 1024, maxMetadataBytes = 1024, ceilings = Ceilings(10, 150_000, 1_000_000L, 8_192)), window, provisioning = AllowanceProvisioning.TestOnly)
        assertTrue(l.chargeForCapture())
        assertFalse(l.chargeForCapture())
        assertEquals(1, l.snapshotRecordCount())
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
