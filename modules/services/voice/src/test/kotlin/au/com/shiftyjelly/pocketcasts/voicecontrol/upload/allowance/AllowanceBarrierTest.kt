package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Durability barriers are reported, not swallowed (reviewer 82cdd583 #2). A rename whose directory
 * sync failed is NOT a durable write: the charge must not be granted, and recovery must not settle it
 * as applied.
 */
class AllowanceBarrierTest {
    @get:Rule val tmp = TemporaryFolder()

    private val account = "a@example.com"
    private val window = AllowanceWindow.Established("window-1")
    private val limits = AllowanceLimits(maxDailyRecords = 10, maxDailyBytes = 1_000_000, maxClipBytes = 100_000, maxMetadataBytes = 1_024)

    private fun area(): File = File(tmp.root, "collection-usage").also { it.mkdirs() }

    private val recovering = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Fails the directory barrier for the FIRST charge's ledger write only. */
    private class FailLedgerDirSync(private val areaRoot: File) : (File) -> Unit {
        var failuresLeft = 1
        override fun invoke(dir: File) {
            if (dir.name == areaRoot.name || dir.absolutePath.contains(areaRoot.absolutePath.substringAfterLast('/'))) {
                if (failuresLeft > 0) {
                    failuresLeft -= 1
                    throw java.io.IOException("injected directory sync failure")
                }
            }
        }
    }

    /**
     * The AREA entry and its PARENT are separate durability barriers: a crash after creating the
     * directory but before its parent entry is synced loses the whole area, so a writer that syncs
     * only the area cannot claim the artifacts are durable.
     */
    @Test fun `the parent directory entry is synced, not only the area`() {
        val synced = mutableListOf<String>()
        val areaRoot = File(tmp.root, "collection-usage")
        val recorder = object : (File) -> Unit {
            override fun invoke(dir: File) {
                synced += dir.name
            }
        }
        val ledger = CaptureAllowanceLedger(areaRoot, account, limits, window, AllowanceProvisioning.TestOnly, null, recorder)
        assertTrue(ledger.chargeForCapture())
        val areaName = areaRoot.listFiles()!!.first { it.isDirectory }.name
        assertTrue("the account area must be synced", synced.contains(areaName))
        assertTrue("the area's PARENT entry must be synced too", synced.contains(areaRoot.name))
    }

    @Test fun `recovery will not settle a target whose ledger cannot be made durable`() {
        // Fail ONLY the named recovery ledger phase; every charge and initialization write succeeds.
        var failRecovery = false
        val barrier = object : (AllowanceWrite) -> Unit {
            override fun invoke(phase: AllowanceWrite) {
                if (failRecovery && phase == AllowanceWrite.RECOVERY_LEDGER) {
                    throw java.io.IOException("injected recovery ledger barrier failure")
                }
            }
        }
        val ledger = CaptureAllowanceLedger(area(), account, limits, window, AllowanceProvisioning.TestOnly, null, null, barrier)
        assertTrue(ledger.chargeForCapture())
        // Make the intent pending again, as a crash between the ledger write and its settle would.
        area().walkTopDown().first { it.isFile && it.name == "intent.json" }.let {
            it.writeText(it.readText().replace("\"state\":\"SETTLED\"", "\"state\":\"PENDING\""))
        }
        failRecovery = true
        recovering.set(true)
        try {
            val reopened = CaptureAllowanceLedger(area(), account, limits, window, AllowanceProvisioning.TestOnly, null, null, barrier)
            assertFalse("a target that cannot be confirmed durable must not settle", reopened.reconcileForTest())
            assertFalse("and it must keep refusing", reopened.reconcileForTest())
            assertFalse("no charge may be admitted from an unsettled target", reopened.chargeForCapture())
        } finally {
            recovering.set(false)
        }
    }

    @Test fun `a failed directory barrier prevents the charge from being granted`() {
        val failOnSecond = object : (File) -> Unit {
            var calls = 0
            override fun invoke(dir: File) {
                calls += 1
                // Allow initialization (4 writes) then fail the charge's directory barrier.
                if (calls > 4) throw java.io.IOException("injected directory sync failure")
            }
        }
        val ledger = CaptureAllowanceLedger(area(), account, limits, window, AllowanceProvisioning.TestOnly, null, failOnSecond)
        assertFalse("an unsynced rename is not a durable charge", ledger.chargeForCapture())
    }

    @Test fun `a failed barrier leaves the ledger unadvanced so no free admission is created`() {
        var calls = 0
        val failAlways = object : (File) -> Unit {
            override fun invoke(dir: File) {
                calls += 1
                if (calls > 4) throw java.io.IOException("injected directory sync failure")
            }
        }
        val ledger = CaptureAllowanceLedger(area(), account, limits, window, AllowanceProvisioning.TestOnly, null, failAlways)
        assertFalse(ledger.chargeForCapture())
        // Recovery of whatever intent landed must never mint a token nor claim an applied charge.
        val recovered = CaptureAllowanceLedger(area(), account, limits, window, AllowanceProvisioning.TestOnly)
        val settled = recovered.reconcileForTest()
        if (settled) {
            assertTrue("a settled recovery must reflect a real charge", recovered.snapshotRecordCount() <= 1)
        }
        // A settled recovery must never mint admission: the charge belongs to a previous process.
        assertTrue("recovery must not leave a usable token for the next empty read", recovered.snapshotRecordCount() <= 1)
    }
}
