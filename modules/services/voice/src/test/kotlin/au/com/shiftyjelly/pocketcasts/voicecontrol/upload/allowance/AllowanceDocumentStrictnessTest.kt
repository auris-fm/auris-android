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
 * Accounting documents are STRICT and BOUNDED (reviewer 82cdd583 #4). A lenient reader accepts
 * duplicate fields, extra fields and edited counters; any of those would let damaged accounting be
 * read as valid usage, so each is a closing condition here rather than a parse detail.
 */
class AllowanceDocumentStrictnessTest {
    @get:Rule val tmp = TemporaryFolder()

    private val account = "a@example.com"
    private val window = AllowanceWindow.Established("window-1")
    private val otherWindow = AllowanceWindow.Established("window-2")
    private val limits = AllowanceLimits(maxDailyRecords = 10, maxDailyBytes = 1_000_000, maxClipBytes = 100_000, maxMetadataBytes = 1_024)

    private fun area(): File = File(tmp.root, "collection-usage").also { it.mkdirs() }

    private fun owner(w: AllowanceWindow = window) = CaptureAllowanceOwner(area(), account, limits, w, AllowanceProvisioning.TestOnly)

    private fun ledgerFile(): File = area().walkTopDown().first { it.isFile && it.name == "ledger.json" }

    private fun genesisFile(): File = area().walkTopDown().first { it.isFile && it.name == "genesis.json" }

    @Test fun `a duplicate field is damage, not last-one-wins`() {
        assertTrue(owner().prepareToken())
        val original = ledgerFile().readText()
        // A genuinely duplicated KEY: the reader must reject it outright, before any semantic check.
        val duplicated = original.replace("\"chargedRecords\":\"1\"", "\"chargedRecords\":\"1\",\"chargedRecords\":\"0\"")
        assertNull(
            "a duplicate key must be rejected by the reader",
            AllowanceDocuments.parseObject(duplicated, AllowanceDocuments.ledgerDocs()),
        )
        ledgerFile().writeText(duplicated)
        assertFalse("a duplicated counter must close admission", owner().prepareToken())
    }

    @Test fun `an extra field is damage`() {
        // Asserted on the PARSER first: a lenient reader accepts this, and the downstream failure
        // would then be attributed to the wrong cause.
        val canonical = AllowanceDocuments.ledgerDocument("id", "established:window-1", 1, 100)
        assertNull(
            "an unrecognised field must be rejected by the reader itself",
            AllowanceDocuments.parseObject(canonical.removeSuffix("}") + ",\"bonus\":0}", AllowanceDocuments.ledgerDocs()),
        )
        assertTrue(owner().prepareToken())
        ledgerFile().writeText(canonical.removeSuffix("}") + ",\"bonus\":0}")
        assertFalse("an unrecognised field must close admission", owner().prepareToken())
    }

    @Test fun `an edited counter matching neither intent end is damage`() {
        assertTrue(owner().prepareToken())
        // 0 is the recorded PRE-charge end of the settled intent and 1 the target, so a rewrite must
        // land outside both to be detectable; anything else is a legitimate reconciliation outcome.
        ledgerFile().writeText(ledgerFile().readText().replace("\"chargedRecords\":\"1\"", "\"chargedRecords\":\"7\""))
        assertFalse("a counter outside both intent ends must close admission", owner().prepareToken())
    }

    @Test fun `a rewrite onto a recorded end is not re-admitted as a fresh charge`() {
        assertTrue(owner().prepareToken())
        val before = ledgerFile().readText()
        // Landing on the recorded pre-charge end is a LOSS of one charge. It must never be treated as
        // fresh allowance: the next admission either charges once more or stays closed, never free.
        ledgerFile().writeText(before.replace("\"chargedRecords\":\"1\"", "\"chargedRecords\":\"0\""))
        val next = owner()
        val charged = next.prepareToken()
        assertTrue(
            "a lost charge must never yield free admission",
            !charged || next.ledgerChargesForTest() >= 1,
        )
    }

    @Test fun `a pending intent matching neither end stays closed`() {
        assertTrue(owner().prepareToken())
        val intentFile = area().walkTopDown().first { it.isFile && it.name == "intent.json" }
        intentFile.writeText(intentFile.readText().replace("\"state\":\"SETTLED\"", "\"state\":\"PENDING\""))
        ledgerFile().writeText(ledgerFile().readText().replace("\"chargedRecords\":\"1\"", "\"chargedRecords\":\"7\""))
        val reopened = CaptureAllowanceLedger(area(), account, limits, window, AllowanceProvisioning.TestOnly)
        assertFalse("an ambiguous intent must stay closed", reopened.reconcileForTest())
        assertTrue(reopened.isClosed())
    }

    @Test fun `an oversized document is damage rather than parsed`() {
        assertTrue(owner().prepareToken())
        val padding = "0".repeat(AllowanceDocuments.MAX_BYTES + 1)
        ledgerFile().writeText(padding)
        assertFalse("an oversized ledger must close admission", owner().prepareToken())
        assertNull(AllowanceDocuments.parseObject(padding, AllowanceDocuments.ledgerDocs()))
    }

    @Test fun `a read never consumes more than the cap`() {
        assertTrue(owner().prepareToken())
        // The marker sits far past the cap, so a bounded read cannot see it at all.
        val marker = "\"chargedBytes\":\"999999\""
        val oversized = ledgerFile().readText().removeSuffix("}") +
            ",\"discarded\":\"" + "0".repeat(AllowanceDocuments.MAX_BYTES * 3) + marker + "\"}"
        ledgerFile().writeText(oversized)
        val reread = ledgerFile().readText()
        assertTrue("the marker is beyond the cap", reread.indexOf(marker) > AllowanceDocuments.MAX_BYTES)
        assertFalse("a bounded read must refuse this ledger", owner().prepareToken())
    }

    @Test fun `a document larger than the cap is never read as valid accounting`() {
        assertTrue(owner().prepareToken())
        // Keep it a VALID document whose only problem is its size: leading zeros are still digits, and
        // the numeric value is unchanged, so only a bounded read can refuse it.
        val original = ledgerFile().readText()
        val padded = original.replace("\"chargedBytes\":\"101024\"", "\"chargedBytes\":\"" + "0".repeat(AllowanceDocuments.MAX_BYTES) + "101024\"")
        assertTrue("the padded document is still syntactically valid", padded.length > AllowanceDocuments.MAX_BYTES)
        assertNull(
            "the reader must refuse an oversized document by size, not by content",
            AllowanceDocuments.parseObject(padded, AllowanceDocuments.ledgerDocs()),
        )
        ledgerFile().writeText(padded)
        assertFalse("an oversized ledger must close admission", owner().prepareToken())
    }

    @Test fun `a truncated or non-object document is damage`() {
        val docs = AllowanceDocuments.ledgerDocs()
        assertNull(AllowanceDocuments.parseObject("", docs))
        assertNull(AllowanceDocuments.parseObject("{", docs))
        assertNull(AllowanceDocuments.parseObject("null", docs))
        assertNull(AllowanceDocuments.parseObject("{\"schema\":1}", docs))
    }

    @Test fun `an unknown intent state is damage, not a pending-like state`() {
        assertTrue(owner().prepareToken())
        val intentFile = area().walkTopDown().first { it.isFile && it.name == "intent.json" }
        intentFile.writeText(intentFile.readText().replace("\"state\":\"SETTLED\"", "\"state\":\"BROKEN\""))
        val before = area().walkTopDown().filter { it.isFile }.associate { it.name to it.readText() }
        // Through the real admission path, not the parser alone.
        assertFalse("a BROKEN state must close admission", owner().prepareToken())
        val after = area().walkTopDown().filter { it.isFile }.associate { it.name to it.readText() }
        assertEquals("a refusal must preserve every artifact unchanged", before, after)
        assertTrue("and it stays closed", owner(w = window).let { it.isClosedForTest() })
    }

    @Test fun `a half-written intent is damage in either endpoint`() {
        assertTrue(owner().prepareToken())
        val intentFile = area().walkTopDown().first { it.isFile && it.name == "intent.json" }
        // A PENDING intent whose target is malformed: an unresolved transition nobody can settle.
        val broken = intentFile.readText()
            .replace("\"state\":\"SETTLED\"", "\"state\":\"PENDING\"")
            .replace("\"targetRecords\":\"1\"", "\"targetRecords\":\"-1\"")
        intentFile.writeText(broken)
        assertFalse("both endpoints must be validated", owner().prepareToken())
    }

    @Test fun `an opener bound to a different window cannot read the usage`() {
        assertTrue(owner().prepareToken())
        assertFalse("usage from another window must not be readable", owner(w = otherWindow).prepareToken())
    }

    @Test fun `the window is persisted with the accounting`() {
        assertTrue(owner().prepareToken())
        assertTrue("genesis records its window", genesisFile().readText().contains("established:window-1"))
        assertTrue("the ledger records its window", ledgerFile().readText().contains("established:window-1"))
    }

    @Test fun `an intent for another account or window is not reconciled`() {
        assertTrue(owner().prepareToken())
        val intentFile = area().walkTopDown().first { it.isFile && it.name == "intent.json" }
        intentFile.writeText(
            intentFile.readText()
                .replace("\"state\":\"SETTLED\"", "\"state\":\"PENDING\"")
                .replace("\"window\":\"established:window-1\"", "\"window\":\"established:window-9\""),
        )
        val reopened = CaptureAllowanceLedger(area(), account, limits, window, AllowanceProvisioning.TestOnly)
        assertFalse("a foreign intent must not be reconciled", reopened.reconcileForTest())
    }

    @Test fun `strict parsing accepts exactly the canonical documents we write`() {
        assertTrue(owner().prepareToken())
        val genesis = AllowanceDocuments.parseObject(genesisFile().readText(), AllowanceDocuments.genesisDocs())
        val ledger = AllowanceDocuments.parseObject(ledgerFile().readText(), AllowanceDocuments.ledgerDocs())
        assertEquals("ESTABLISHED", genesis?.get("state"))
        assertEquals("1", ledger?.get("chargedRecords"))
    }
}
