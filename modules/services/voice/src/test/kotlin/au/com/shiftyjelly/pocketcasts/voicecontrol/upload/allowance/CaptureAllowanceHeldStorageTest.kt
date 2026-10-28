package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Admission never waits behind publication or storage (reviewer 8b5fc0c9 #5, f64bd7ad #2).
 *
 * Every assertion runs as a BOUNDED future while the interference is still provably in place, so a
 * regression that queues admission behind the seam fails on its timeout instead of eventually
 * passing when the seam releases. Release happens only in `finally`.
 */
class CaptureAllowanceHeldStorageTest {
    @get:Rule val tmp = TemporaryFolder()

    private val account = "a@example.com"
    private val window = AllowanceWindow.Established("window-1")
    private val limits = AllowanceLimits(maxDailyRecords = 10, maxDailyBytes = 1_000_000, maxClipBytes = 100_000, maxMetadataBytes = 1_024)

    private fun area(): File = File(tmp.root, "collection-usage").also { it.mkdirs() }

    private fun owner() = CaptureAllowanceOwner(area(), account, limits, window, AllowanceProvisioning.TestOnly)

    private fun <T> bounded(ms: Long, block: () -> T): T {
        val pool = Executors.newSingleThreadExecutor()
        val future: Future<T> = pool.submit<T> { block() }
        return try {
            future.get(ms, TimeUnit.MILLISECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun `retiring while storage is held never publishes a stale token`() {
        val o = owner()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        o.onStorageEntered = {
            entered.countDown()
            release.await(60, TimeUnit.SECONDS)
        }
        val writer = Executors.newSingleThreadExecutor()
        try {
            val prepared = writer.submit<Boolean> { o.prepareToken() }
            assertTrue("preparation must reach storage", entered.await(30, TimeUnit.SECONDS))

            // The writer is INSIDE storage now. Each probe is bounded so a wait fails loudly.
            assertNull("no token exists until the charge completes", bounded(2_000) { o.takeToken() })
            bounded(2_000) { o.retire() }
            release.countDown()
            prepared.get(30, TimeUnit.SECONDS)
            writer.shutdown()
            assertTrue(writer.awaitTermination(30, TimeUnit.SECONDS))

            // Without another retire, the stale completion must not be usable.
            assertNull("a completion for a retired generation must not mint", bounded(2_000) { o.takeToken() })
            assertFalse("no usable token for the retired generation", bounded(2_000) { o.hasUsableToken() })
        } finally {
            release.countDown()
            writer.shutdownNow()
            writer.awaitTermination(30, TimeUnit.SECONDS)
        }
    }

    @Test fun `admission does not wait behind a held decision lock`() {
        val o = owner()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        // Hold the DECISION lock itself: this is the path publication and retirement take.
        o.onDecisionLockHeldForTest = {
            entered.countDown()
            release.await(60, TimeUnit.SECONDS)
        }
        val holder = Executors.newSingleThreadExecutor()
        try {
            val held = holder.submit<Boolean> { o.hasUsableToken() }
            assertTrue("the decision lock must be held", entered.await(30, TimeUnit.SECONDS))
            // takeToken must REFUSE immediately rather than queue behind the held decision lock.
            val taken = bounded(2_000) { o.takeToken() }
            assertNull("a held decision lock must refuse, not wait", taken)
            release.countDown()
            held.get(30, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            holder.shutdownNow()
            holder.awaitTermination(30, TimeUnit.SECONDS)
        }
    }

    @Test fun `a completed preparation under held storage is usable only for its own generation`() {
        val o = owner()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        o.onStorageEntered = {
            entered.countDown()
            release.await(60, TimeUnit.SECONDS)
        }
        val writer = Executors.newSingleThreadExecutor()
        try {
            val prepared = writer.submit<Boolean> { o.prepareToken() }
            assertTrue(entered.await(30, TimeUnit.SECONDS))
            release.countDown()
            assertTrue("the preparation completes", prepared.get(30, TimeUnit.SECONDS))
            writer.shutdown()
            assertTrue(writer.awaitTermination(30, TimeUnit.SECONDS))
            assertNotNull("the finished charge publishes one token", bounded(2_000) { o.takeToken() })
            assertNull("and only one", bounded(2_000) { o.takeToken() })
        } finally {
            release.countDown()
            writer.shutdownNow()
            writer.awaitTermination(30, TimeUnit.SECONDS)
        }
    }
}
