package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Interrupted-charge protocol (reviewer 131e248b / 080e1f01): a rename alone is not durability, so a
 * charge is preceded by a durable intent that records either outcome. Recovery reconciles that exact
 * intent and never mints a token for a previous process's charge.
 */
class CaptureAllowanceRecoveryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val account = "a@example.com"
    private val window = AllowanceWindow.Established("window-1")
    private val limits = AllowanceLimits(maxDailyRecords = 10, maxDailyBytes = 1_000_000, maxClipBytes = 100_000 - 1024, maxMetadataBytes = 1024)

    private fun area(): File = File(tmp.root, "collection-usage").also { it.mkdirs() }

    private fun owner() = CaptureAllowanceOwner(area(), account, limits, window, AllowanceProvisioning.TestOnly)

    /** Recovery drives the LEDGER's reconciliation; the owner adds the admission half. */
    private fun ledger() = CaptureAllowanceLedger(area(), account, limits, window, provisioning = AllowanceProvisioning.TestOnly)

    private fun File.artifacts(): List<File> = walkTopDown().filter { it.isFile }.toList()

    @Test fun `a durable intent is written before the charge is applied`() {
        val l = ledger()
        assertTrue(l.chargeForCapture())
        // The intent ARTIFACT must exist and be settled: its presence is what a recovering owner reads.
        val intentFile = area().artifacts().firstOrNull { it.name.contains("intent") }
        assertTrue("a charge must leave a durable intent artifact", intentFile != null)
        assertTrue("the settled intent records the applied target", intentFile!!.readText().contains("\"state\":\"SETTLED\""))
        // Nothing is left pending on the happy path (a pending intent would block every later charge).
        assertEquals("no pending intent after a successful charge", null, l.pendingIntentForTest())
    }

    @Test fun `the intent is written before the ledger, so a recovered owner always has evidence`() {
        val observer = RecordingAreaObserver()
        val l = CaptureAllowanceLedger(area(), account, limits, window, AllowanceProvisioning.TestOnly, observer)
        assertTrue(l.chargeForCapture())
        // Initialization writes its own AREA (a different account area), so this area's writes are the
        // charge's alone: PENDING intent, then the ledger, then the settled intent.
        val writes = observer.writes.toList()
        assertEquals(
            "the charge writes its intent before the ledger it describes",
            listOf(
                AllowanceWrite.INIT_GENESIS_INITIALIZING,
                AllowanceWrite.INIT_LEDGER_ZEROED,
                AllowanceWrite.INIT_INTENT_SETTLED,
                AllowanceWrite.INIT_GENESIS_ESTABLISHED,
                AllowanceWrite.CHARGE_INTENT_PENDING,
                AllowanceWrite.CHARGE_LEDGER,
                AllowanceWrite.CHARGE_INTENT_SETTLED,
            ),
            writes,
        )
    }

    @Test fun `an intent whose post-charge counters are present reconciles to APPLIED`() {
        assertTrue(owner().prepareToken())
        // Simulate a death after the ledger became durable but before the intent was settled.
        val intentFile = area().artifacts().first { it.name.contains("intent") }
        val ledgerFile = area().artifacts().first { it.name.contains("ledger") }
        val intent = intentFile.readText()
        intentFile.writeText(intent.replace("\"state\":\"SETTLED\"", "\"state\":\"PENDING\""))
        val reopened = ledger()
        val opened = reopened
        assertTrue("recovery settles the applied charge", opened.reconcileForTest())
        assertEquals(1, opened.snapshot().chargedRecords)
    }

    @Test fun `an intent whose post-charge counters are ABSENT discards the unissued charge`() {
        assertTrue(owner().prepareToken())
        val intentFile = area().artifacts().first { it.name.contains("intent") }
        val ledgerFile = area().artifacts().first { it.name.contains("ledger") }
        intentFile.writeText(intentFile.readText().replace("\"state\":\"SETTLED\"", "\"state\":\"PENDING\""))
        ledgerFile.writeText(ledgerFile.readText().replace("\"chargedRecords\":1", "\"chargedRecords\":0").replace("\"chargedBytes\":100000", "\"chargedBytes\":0"))
        val reopened = ledger()
        assertTrue(reopened.reconcileForTest())
        assertEquals("a charge that never landed is discarded, not counted", 0, reopened.snapshot().chargedRecords)
    }

    @Test fun `an ambiguous intent stays closed`() {
        assertTrue(owner().prepareToken())
        val intentFile = area().artifacts().first { it.name.contains("intent") }
        val ledgerFile = area().artifacts().first { it.name.contains("ledger") }
        intentFile.writeText(intentFile.readText().replace("\"state\":\"SETTLED\"", "\"state\":\"PENDING\""))
        // Neither the pre- nor the post-charge counters: unreadable/unknown state.
        ledgerFile.writeText(ledgerFile.readText().replace("\"chargedRecords\":1", "\"chargedRecords\":7"))
        val reopened = ledger()
        assertFalse(reopened.reconcileForTest())
        assertTrue(reopened.isClosed())
    }

    @Test fun `another charge is blocked until the pending intent is settled`() {
        assertTrue(owner().prepareToken())
        val intentFile = area().artifacts().first { it.name.contains("intent") }
        intentFile.writeText(intentFile.readText().replace("\"state\":\"SETTLED\"", "\"state\":\"PENDING\""))
        val reopened = ledger()
        assertFalse("an unsettled intent blocks new charges", owner().prepareToken())
        assertTrue(reopened.reconcileForTest())
        assertTrue("settled accounting may charge again", owner().prepareToken())
    }

    @Test fun `recovery never issues a token for a previous process charge`() {
        assertTrue(owner().prepareToken())
        val intentFile = area().artifacts().first { it.name.contains("intent") }
        intentFile.writeText(intentFile.readText().replace("\"state\":\"SETTLED\"", "\"state\":\"PENDING\""))
        val recovered = ledger()
        assertTrue(recovered.reconcileForTest())
        assertEquals("never reissues the recovered charge", 1, recovered.snapshot().chargedRecords)
        assertNull("recovery must not hand out a token for a previous process's charge", owner().takeToken())
        // Recovery settles accounting only: the owner still has NO prepaid token, so a capture is refused
        // until a fresh off-path charge mints one.
    }

    @Test fun `competing initializers - only the exclusive claim winner may initialize`() {
        val l = ledger()
        // The first initializer claims the lock artifact exclusively and establishes accounting.
        assertTrue(l.chargeForCapture())
        // A second, concurrently-started ledger in the same area must not overwrite the live genesis.
        val genesisBefore = area().artifacts().first { it.name.contains("genesis") }.readText()
        val competitor = owner()
        assertEquals("a competing initializer must not replace the winner's genesis", genesisBefore, area().artifacts().first { it.name.contains("genesis") }.readText())
        // The competitor may charge once (accounting is established and shared), but only as an ordinary
        // increment: the winner's genesis and identity are untouched.
        val charged = competitor.prepareToken()
        assertEquals("a competing initializer must not replace the winner's genesis", genesisBefore, area().artifacts().first { it.name.contains("genesis") }.readText())
        if (charged) assertEquals(2, l.snapshot().chargedRecords) else assertEquals(1, l.snapshot().chargedRecords)
    }
}

/** Orders durable writes so a test can prove the intent precedes the charge. */
private class RecordingAreaObserver : (AllowanceWrite) -> Unit {
    val writes = java.util.concurrent.CopyOnWriteArrayList<AllowanceWrite>()
    override fun invoke(phase: AllowanceWrite) {
        writes.add(phase)
    }
}
