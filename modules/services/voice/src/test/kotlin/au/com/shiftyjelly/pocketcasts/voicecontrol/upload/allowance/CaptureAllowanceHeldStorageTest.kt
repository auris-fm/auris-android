package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Admission must stay non-waiting while preparation is DEFINITELY inside storage (reviewer 8b5fc0c9
 * #5). The seam blocks the preparing thread at a known point, so the assertions are about real
 * contention rather than a race that "may" overlap.
 */
class CaptureAllowanceHeldStorageTest {
    @get:Rule val tmp = TemporaryFolder()

    private val account = "a@example.com"
    private val window = AllowanceWindow.Established("window-1")
    private val limits = AllowanceLimits(maxDailyRecords = 10, maxDailyBytes = 1_000_000, maxClipBytes = 100_000, maxMetadataBytes = 1_024)

    private fun area(): File = File(tmp.root, "collection-usage").also { it.mkdirs() }

    private fun owner() = CaptureAllowanceOwner(area(), account, limits, window, AllowanceProvisioning.TestOnly)

    @Test fun `retiring while storage is held never publishes a stale token`() {
        val o = owner()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        o.onStorageEntered = {
            entered.countDown()
            release.await(30, TimeUnit.SECONDS)
        }
        val writer = Executors.newSingleThreadExecutor()
        try {
            val prepared = writer.submit<Boolean> { o.prepareToken() }
            assertTrue("preparation must reach storage", entered.await(30, TimeUnit.SECONDS))

            // The writer is INSIDE storage now: admission and retirement must still return at once.
            assertNull("no token exists until the charge completes", o.takeToken())
            o.retire()
            // Release and JOIN the writer; the retirement happened strictly during the charge.
            release.countDown()
            prepared.get(30, TimeUnit.SECONDS)
            writer.shutdown()
            assertTrue(writer.awaitTermination(30, TimeUnit.SECONDS))

            // Without another retire, the stale completion must not be usable.
            assertNull("a completion for a retired generation must not mint", o.takeToken())
            assertFalse("no usable token for the retired generation", o.hasUsableToken())
        } finally {
            release.countDown()
            writer.shutdownNow()
        }
    }

    @Test fun `a completed preparation under held storage is usable only for its own generation`() {
        val o = owner()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        o.onStorageEntered = {
            entered.countDown()
            release.await(30, TimeUnit.SECONDS)
        }
        val writer = Executors.newSingleThreadExecutor()
        try {
            val prepared = writer.submit<Boolean> { o.prepareToken() }
            assertTrue(entered.await(30, TimeUnit.SECONDS))
            release.countDown()
            assertTrue("the preparation completes", prepared.get(30, TimeUnit.SECONDS))
            writer.shutdown()
            assertTrue(writer.awaitTermination(30, TimeUnit.SECONDS))
            assertTrue("the finished charge publishes one token", o.takeToken() != null)
            assertNull("and only one", o.takeToken())
        } finally {
            release.countDown()
            writer.shutdownNow()
        }
    }
}
