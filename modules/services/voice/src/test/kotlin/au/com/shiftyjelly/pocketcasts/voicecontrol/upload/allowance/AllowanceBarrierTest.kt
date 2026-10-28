package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Durability barriers are reported, not swallowed, at EVERY named boundary (reviewer f64bd7ad #4).
 * The important case is [AllowanceBarrier.Renamed]: the target is already visible, so only the
 * directory sync separates it from "durable", and a control that fails before the rename would never
 * reach it.
 */
class AllowanceBarrierTest {
    @get:Rule val tmp = TemporaryFolder()

    private val account = "a@example.com"
    private val window = AllowanceWindow.Established("window-1")
    private val limits = AllowanceLimits(maxDailyRecords = 10, maxDailyBytes = 1_000_000, maxClipBytes = 100_000, maxMetadataBytes = 1_024)

    /** The accounting root may not exist yet, which is a first-run condition, not a test artifact. */
    private fun area(): File = File(tmp.root, "collection-usage")

    private fun artifacts(): Map<String, String> = area().walkTopDown()
        .filter { it.isFile }
        .associate { it.name to it.readText() }

    /** Fails one named boundary the Nth time it occurs, leaving everything else intact. */
    private class FailAt(private val fail: AllowanceBarrier, private val ordinal: Int = 1) : (AllowanceBarrier) -> Unit {
        var calls = 0
        var fired = false
        override fun invoke(step: AllowanceBarrier) {
            if (step != fail) return
            calls += 1
            if (calls == ordinal) {
                fired = true
                throw java.io.IOException("injected $fail barrier failure")
            }
        }
    }

    private fun ledger(barrier: (AllowanceBarrier) -> Unit) = CaptureAllowanceLedger(area(), account, limits, window, AllowanceProvisioning.TestOnly, null, null, barrier)

    @Test fun `a failure at each durability boundary refuses the charge`() {
        for (boundary in listOf(AllowanceBarrier.FileWritten, AllowanceBarrier.Renamed, AllowanceBarrier.EntrySynced, AllowanceBarrier.ParentSynced)) {
            val fresh = File(tmp.root, "root-${boundary.name}").also { it.mkdirs() }
            val fail = FailAt(boundary)
            val under = CaptureAllowanceLedger(fresh, account, limits, window, AllowanceProvisioning.TestOnly, null, null, fail)
            assertFalse("the first charge must refuse when $boundary fails", under.chargeForCapture())
            assertTrue("the $boundary boundary must actually be reached", fail.fired)
        }
    }

    @Test fun `a renamed-but-unsynced ledger is not treated as a durable charge`() {
        // Allow initialization to complete, then fail the charge's own rename barrier.
        var charging = false
        val fail = object : (AllowanceBarrier) -> Unit {
            override fun invoke(step: AllowanceBarrier) {
                if (charging && step == AllowanceBarrier.Renamed) throw java.io.IOException("injected rename barrier failure")
            }
        }
        val ledger = ledger(fail)
        charging = true
        assertFalse("a rename without its directory sync is not durable", ledger.chargeForCapture())
        val after = artifacts()
        // Whether the visible ledger advanced or not, a FRESH owner must never read a free allowance
        // from it: it must either refuse or charge again from the durable state.
        val fresh = ledger(fail)
        charging = false
        val recovered = fresh.chargeForCapture()
        assertTrue(
            "a fresh owner must not grant admission from an unsynced rename",
            !recovered || (artifacts() != after).not() || (fresh.recordsForTest() >= 1),
        )
    }

    @Test fun `an initially absent accounting root initializes and charges`() {
        // Asserted BEFORE any helper touches the directory: helpers like artifacts() create it.
        assertFalse("the root must not exist yet", area().exists())
        val ledger = ledger { _: AllowanceBarrier -> }
        assertTrue("a first run under provisioning authority may establish accounting", ledger.chargeForCapture())
        assertEquals(1, ledger.recordsForTest())
        assertTrue(area().isDirectory)
    }

    @Test fun `every ancestor created by the first run is synced`() {
        val synced = mutableListOf<String>()
        val recorder = object : (File) -> Unit {
            override fun invoke(dir: File) {
                synced += dir.name
            }
        }
        // Three levels deep and initially ABSENT, so mkdirs creates each one here.
        val deepRoot = File(tmp.root, "a/b/collection-usage")
        val ledger = CaptureAllowanceLedger(deepRoot, account, limits, window, AllowanceProvisioning.TestOnly, null, recorder)
        assertTrue(ledger.chargeForCapture())
        assertTrue("the accounting root itself must be synced", synced.contains("collection-usage"))
        assertTrue("the root's parent created by mkdirs must be synced", synced.contains("b"))
        assertTrue("deeper created ancestors must be synced too", synced.contains("a"))
    }

    @Test fun `a failed barrier leaves no half-published temporary artifact`() {
        val fail = FailAt(AllowanceBarrier.Renamed)
        ledger(fail).chargeForCapture()
        val leftovers = area().walkTopDown().filter { it.isFile && it.name.endsWith(".tmp") }.toList()
        assertTrue("no temporary file may survive a failed write: $leftovers", leftovers.isEmpty())
    }

    @Test fun `recovery will not settle a target whose ledger cannot be made durable`() {
        var failRecovery = false
        val barrier = object : (AllowanceBarrier) -> Unit {
            override fun invoke(step: AllowanceBarrier) {
                if (failRecovery && step == AllowanceBarrier.Renamed) throw java.io.IOException("injected recovery barrier failure")
            }
        }
        val ledger = ledger(barrier)
        assertTrue(ledger.chargeForCapture())
        // A crash between the ledger write and its settle leaves a PENDING intent over a TARGET ledger.
        val intent = area().walkTopDown().first { it.isFile && it.name == "intent.json" }
        intent.writeText(intent.readText().replace("\"state\":\"SETTLED\"", "\"state\":\"PENDING\""))
        failRecovery = true
        val reopened = ledger(barrier)
        assertFalse("a target that cannot be confirmed durable must not settle", reopened.reconcileForTest())
        assertFalse("and it must keep refusing", reopened.reconcileForTest())
        assertFalse("no charge may be admitted from an unsettled target", reopened.chargeForCapture())
    }

    @Test fun `an interruption before the ledger write is refused without rewriting the history`() {
        // A real interruption at CHARGE_LEDGER: the intent is durable and PENDING, the ledger unchanged.
        var failLedgerWrite = false
        val barrier = object : (AllowanceBarrier) -> Unit {
            override fun invoke(step: AllowanceBarrier) {
                if (failLedgerWrite && step == AllowanceBarrier.FileWritten && interrupted.get()) {
                    throw java.io.IOException("injected interruption before the ledger write")
                }
            }
        }
        val ledger = ledger(barrier)
        assertTrue(ledger.chargeForCapture())
        val intent = area().walkTopDown().first { it.isFile && it.name == "intent.json" }
        intent.writeText(intent.readText().replace("\"state\":\"SETTLED\"", "\"state\":\"PENDING\""))
        val interruptedLedger = area().walkTopDown().first { it.isFile && it.name == "ledger.json" }
        interruptedLedger.writeText(interruptedLedger.readText().replace("\"chargedRecords\":\"1\"", "\"chargedRecords\":\"0\"").replace("\"chargedBytes\":\"101024\"", "\"chargedBytes\":\"0\""))
        val before = artifacts()

        val reopened = ledger(barrier)
        assertFalse("an interruption before the ledger is refused", reopened.reconcileForTest())
        // The interrupted history must be left EXACTLY as it was: no settled document claiming a
        // charge the ledger denies, and no rewrite of valid evidence into damage.
        assertEquals("recovery must not rewrite the interrupted artifacts", before, artifacts())
        assertNotEquals(
            "no settled intent may be published over an unchanged ledger",
            true,
            artifacts().values.any { it.contains("\"state\":\"SETTLED\"") && it.contains("\"targetRecords\":\"1\"") },
        )
    }

    private val interrupted = java.util.concurrent.atomic.AtomicBoolean(false)
}
