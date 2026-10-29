package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    private val limits = AllowanceLimits(10, 1_000_000, maxClipBytes = 100_000, maxMetadataBytes = 1_024, ceilings = Ceilings(10, 1_000_000, 1_000_000L, 8_192))

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
        // The barrier is selected by the NAMED phase of the write it occurs in, so this fails the
        // CHARGE_LEDGER rename - never an initialization write.
        var currentPhase: AllowanceWrite? = null
        var fired = false
        var targetVisibleWhenFired = false
        var intentPendingWhenFired = false
        val fail = object : (AllowanceBarrier) -> Unit {
            override fun invoke(step: AllowanceBarrier) {
                if (currentPhase != AllowanceWrite.CHARGE_LEDGER || step != AllowanceBarrier.Renamed) return
                // At this instant the target HAS been renamed into place and NOT yet synced.
                fired = true
                targetVisibleWhenFired = area().walkTopDown().any {
                    it.isFile && it.name == "ledger.json" && it.readText().contains("\"chargedRecords\":\"1\"")
                }
                intentPendingWhenFired = area().walkTopDown().any {
                    it.isFile && it.name == "intent.json" && it.readText().contains("\"state\":\"PENDING\"")
                }
                throw java.io.IOException("injected CHARGE_LEDGER rename barrier failure")
            }
        }
        val observer = object : (AllowanceWrite) -> Unit {
            override fun invoke(phase: AllowanceWrite) {
                currentPhase = phase
            }
        }
        val ledger = CaptureAllowanceLedger(area(), account, limits, window, AllowanceProvisioning.TestOnly, observer, null, fail)
        assertFalse("a rename without its directory sync is not durable", ledger.chargeForCapture())
        assertTrue("the CHARGE_LEDGER rename barrier must actually fire", fired)
        assertTrue("the target ledger must be VISIBLE when the failure fires", targetVisibleWhenFired)
        assertTrue("the intent must still be PENDING when the failure fires", intentPendingWhenFired)

        // Reopening must confirm the ledger durably BEFORE claiming settlement, and never mint a token
        // for the previous process's charge.
        val phases = mutableListOf<AllowanceWrite>()
        val recording = object : (AllowanceWrite) -> Unit {
            override fun invoke(phase: AllowanceWrite) {
                phases += phase
            }
        }
        val reopened = CaptureAllowanceLedger(area(), account, limits, window, AllowanceProvisioning.TestOnly, recording)
        val owner = CaptureAllowanceOwner(area(), account, limits, window, AllowanceProvisioning.TestOnly)
        assertNull("recovery must not mint a token for the previous charge", owner.takeToken())
        // The recovery must actually SUCCEED: a mutation that refuses everything must fail here.
        assertTrue("recovery must settle the visible target", reopened.reconcileForTest())
        val after = artifacts()
        assertTrue("the charge must survive recovery, not be discarded", after.values.any { it.contains("\"chargedRecords\":\"1\"") })
        val intent = after.entries.first { it.key == "intent.json" }.value
        assertTrue("the intent must END SETTLED, not left pending", intent.contains("\"state\":\"SETTLED\""))
        assertTrue("the settled intent must carry the exact pre counters", intent.contains("\"preRecords\":\"0\"") && intent.contains("\"preBytes\":\"0\""))
        assertTrue("and the exact target counters", intent.contains("\"targetRecords\":\"1\"") && intent.contains("\"targetBytes\":\"101024\""))
        // The ledger must be made durable BEFORE the intent may claim so.
        val ledgerPhase = phases.indexOf(AllowanceWrite.RECOVERY_LEDGER)
        val settlePhase = phases.indexOf(AllowanceWrite.RECOVERY_INTENT_SETTLED)
        assertTrue("recovery must write the ledger first: $phases", ledgerPhase >= 0)
        assertTrue("recovery must settle the intent second: $phases", settlePhase >= 0)
        assertTrue("RECOVERY_LEDGER must precede RECOVERY_INTENT_SETTLED: $phases", ledgerPhase < settlePhase)
        assertNull("and still no token is minted by recovery", owner.takeToken())
    }

    @Test fun `an initially absent accounting root initializes and charges`() {
        // Asserted BEFORE any helper touches the directory: helpers like artifacts() create it.
        assertFalse("the root must not exist yet", area().exists())
        val ledger = ledger { _: AllowanceBarrier -> }
        assertTrue("a first run under provisioning authority may establish accounting", ledger.chargeForCapture())
        assertEquals(1, ledger.recordsForTest())
        assertTrue(area().isDirectory)
    }

    @Test fun `every newly created directory and its containing parent is synced`() {
        val synced = mutableListOf<String>()
        val recorder = object : (File) -> Unit {
            override fun invoke(dir: File) {
                synced += dir.absolutePath
            }
        }
        // Each level is ABSENT, so mkdirs creates a, b and collection-usage during the first charge.
        val a = File(tmp.root, "a")
        val b = File(a, "b")
        val deepRoot = File(b, "collection-usage")
        val ledger = CaptureAllowanceLedger(deepRoot, account, limits, window, AllowanceProvisioning.TestOnly, null, recorder)
        assertTrue(ledger.chargeForCapture())
        // ABSOLUTE paths: a name-only check cannot tell tmp/ from a/ and would hide an omitted parent.
        assertTrue("the accounting root must be synced: $synced", synced.contains(deepRoot.absolutePath))
        assertTrue("its containing parent b must be synced: $synced", synced.contains(b.absolutePath))
        assertTrue("its containing parent a must be synced: $synced", synced.contains(a.absolutePath))
        // The OUTERMOST created directory's containing parent (tmp.root) names `a`; without syncing it
        // a crash could still lose the whole chain.
        assertTrue(
            "the outermost created directory's parent must be synced: $synced",
            synced.contains(tmp.root.absolutePath),
        )
    }

    @Test fun `each named barrier fires immediately after the sync it names`() {
        val order = mutableListOf<String>()
        val recorder = object : (File) -> Unit {
            override fun invoke(dir: File) {
                order += "sync:${dir.name}"
            }
        }
        val barrier = object : (AllowanceBarrier) -> Unit {
            override fun invoke(step: AllowanceBarrier) {
                order += "barrier:$step"
            }
        }
        // Initialization so the directory already exists, then one charge to observe its ordering.
        val warm = CaptureAllowanceLedger(area(), account, limits, window, AllowanceProvisioning.TestOnly, null, recorder, barrier)
        assertTrue(warm.chargeForCapture())
        order.clear()
        assertTrue(warm.chargeForCapture())
        // The EXACT interleaving, not just relative ordering: each hook must be the immediate
        // successor of the sync it names, so moving both after all syncs cannot pass.
        val entryBarrier = order.indexOf("barrier:${AllowanceBarrier.EntrySynced}")
        val parentBarrier = order.indexOf("barrier:${AllowanceBarrier.ParentSynced}")
        assertTrue("EntrySynced must be observed: $order", entryBarrier >= 0)
        assertTrue("ParentSynced must be observed: $order", parentBarrier >= 0)
        assertTrue(
            "EntrySynced must IMMEDIATELY follow the entry sync: $order",
            entryBarrier >= 1 && order[entryBarrier - 1].startsWith("sync:"),
        )
        assertTrue(
            "ParentSynced must IMMEDIATELY follow the parent sync: $order",
            parentBarrier >= 1 && order[parentBarrier - 1].startsWith("sync:"),
        )
        assertTrue("ParentSynced must come after EntrySynced: $order", parentBarrier > entryBarrier)
        // And exactly one sync separates them: the entry's own sync precedes EntrySynced, the parent's
        // precedes ParentSynced, so there is no "all syncs then all hooks" ordering.
        assertEquals(
            "exactly one sync must sit between the two hooks: $order",
            1,
            order.subList(entryBarrier + 1, parentBarrier).count { it.startsWith("sync:") },
        )
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

    /**
     * A CONSTRUCTED pre-charge state: documents are edited directly, so NO runtime interruption is
     * exercised here. It asserts only that recovery refuses without rewriting the constructed state.
     */
    @Test fun `an interruption before the ledger write is refused without rewriting the history`() {
        // Constructed, not executed: the PENDING intent and unchanged ledger are written by hand.
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
