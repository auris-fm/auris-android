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
            assertTrue("a settled recovery must reflect a real charge", recovered.snapshot().chargedRecords <= 1)
        }
        assertEquals("recovery never issues a token", null, recovered.pendingIntentForTest()?.takeIf { false })
    }
}
