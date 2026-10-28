package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The OS lock must actually exclude a SECOND OS PROCESS (reviewer 82cdd583 #1). A same-JVM monitor
 * cannot prove this, so the contention here is a real `FileLock` held on a real descriptor by a
 * separate `RandomAccessFile` - the same primitive another process would use.
 */
class CaptureAllowanceLockContentionTest {
    @get:Rule val tmp = TemporaryFolder()

    private val account = "a@example.com"
    private val window = AllowanceWindow.Established("window-1")
    private val limits = AllowanceLimits(maxDailyRecords = 10, maxDailyBytes = 1_000_000, maxClipBytes = 100_000, maxMetadataBytes = 1_024)

    private fun area(): File = File(tmp.root, "collection-usage").also { it.mkdirs() }

    private fun owner() = CaptureAllowanceOwner(area(), account, limits, window, AllowanceProvisioning.TestOnly)

    private fun lockFile(): File = area().walkTopDown().first { it.isFile && it.name == "usage.lock" }

    private fun ledgerBytes(): String = area().walkTopDown()
        .filter { it.isFile && it.name == "ledger.json" }
        .joinToString { it.readText() }

    @Test fun `a refused lock does not mutate the ledger and leaves its bytes unchanged`() {
        val o = owner()
        assertTrue(o.prepareToken())
        val before = ledgerBytes()
        val beforeRecords = o.ledgerChargesForTest()
        assertFalse("a refresh with a token pending must not charge", o.prepareToken())

        // Hold the OS lock from a separate descriptor, exactly as another process would.
        RandomAccessFile(lockFile(), "rw").use { raf ->
            val held: FileLock? = raf.channel.tryLock()
            assertNotNull(held)
            try {
                // A FRESH owner has no pending token, so this preparation really reaches the OS lock
                // instead of being short-circuited by the one-pending-refill rule.
                val contender = owner()
                assertFalse("a contended lock must refuse the charge", contender.prepareToken())
                assertEquals("the established ledger must be untouched", before, ledgerBytes())
                assertEquals("no charge may be recorded without the lock", beforeRecords, contender.ledgerChargesForTest())
            } finally {
                held?.release()
            }
        }
    }

    @Test fun `the charge may proceed once the consuming owner releases the lock`() {
        val first = owner()
        assertTrue(first.prepareToken())
        val second = owner()
        assertTrue(second.prepareToken())
    }

    private fun assertTrue(value: Boolean) = org.junit.Assert.assertTrue(value)
}
