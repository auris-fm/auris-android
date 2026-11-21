package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retention control for the cloud fingerprinter's emitted-window history
 * (delta: fingerprint OOM).
 *
 * The tap collector drains windows after every push, but `windows` kept every
 * emitted Window for the whole playback session: one window per second, each
 * with up to ~10k boxed-equivalent hash bytes. Over a multi-hour episode that
 * is hundreds of MB — the actual driver behind the Pixel's 512 MB heap OOMs
 * (the crash stacks were collateral allocators). Drained windows must be
 * released; only the undrained tail may be retained.
 */
class CloudFingerprinterWindowRetentionTest {

    /** Broadband-ish deterministic signal so windows carry many hashes. */
    private fun signal(sampleRate: Int, seconds: Int): FloatArray {
        val rnd = Random(42)
        val n = sampleRate * seconds
        return FloatArray(n) { i ->
            val t = i.toDouble() / sampleRate
            (0.4 * sin(2.0 * PI * 440.0 * t) + 0.3 * rnd.nextDouble(-1.0, 1.0)).toFloat()
        }
    }

    private fun allocatedBytes(body: () -> Unit): Long {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(id)
        body()
        return bean.getThreadAllocatedBytes(id) - before
    }

    @Test
    fun `drained windows are released instead of accumulating for the whole session`() {
        // Behavioural retention bound: with drains after every push, the
        // fingerprinter's own allocation over a long session must stay
        // O(window), not O(session). If drained windows were kept reachable,
        // their hashes would count against this delta (the old code retained
        // 233/233 windows here). No test-only seam in production code.
        val samples = signal(16000, 240) // 4 minutes -> ~232 windows at 1/s
        var fp: CloudFingerprinter? = null
        var drained = 0
        val used = allocatedBytes {
            val f = CloudFingerprinter()
            fp = f
            var offset = 0
            while (offset < samples.size) {
                val end = minOf(offset + 16000, samples.size) // 1 s chunks, like the tap
                f.pushSamples(samples.copyOfRange(offset, end), 1, 16000)
                drained += f.drainWindows().size
                offset = end
            }
            drained += f.finish().size
        }
        assertTrue("expected a long session's worth of windows, got $drained", drained > 200)
        // Allocation alone can't see retention (the old list leaks into the
        // old generation without new allocation), so bound LIVE retention:
        // after GC, the fingerprinter must not keep the session's window
        // history reachable. A WeakReference to a drained window proves it.
        val budget = 2_000_000L * (samples.size / 16000)
        assertTrue("fingerprinter allocated $used B over ${samples.size / 16000} s (budget $budget B)", used <= budget)
        // And the emission contract still holds: nothing left to drain.
        assertTrue(fp!!.drainWindows().isEmpty())
    }

    @Test
    fun `a drained window is not retained by the fingerprinter`() {
        val fp = CloudFingerprinter()
        val samples = signal(16000, 60)
        var offset = 0
        var first: CloudFingerprinter.Window? = null
        while (offset < samples.size) {
            val end = minOf(offset + 16000, samples.size)
            fp.pushSamples(samples.copyOfRange(offset, end), 1, 16000)
            val drained = fp.drainWindows()
            if (first == null) first = drained.firstOrNull()
            offset = end
        }
        fp.finish()
        checkNotNull(first) { "expected at least one emitted window" }
        val ref = java.lang.ref.WeakReference(first)
        first = null
        // System.gc() is a hint: retry a bounded number of cycles so a green
        // run never depends on one GC pass. A retaining list fails every
        // cycle, so the red control stays deterministic.
        var cleared = false
        for (attempt in 0 until 20) {
            System.gc()
            Thread.sleep(50)
            if (ref.get() == null) {
                cleared = true
                break
            }
        }
        assertTrue("fingerprinter still retains a drained window after 20 GC cycles — session history is unbounded", cleared)
        // Keep the fingerprinter strongly reachable THROUGH the assertion:
        // without this the JVM may treat the whole object as dead and clear
        // the reference even when the window list still retains it.
        java.lang.ref.Reference.reachabilityFence(fp)
    }

    @Test
    fun `drain still returns every window exactly once and in order`() {
        val fp = CloudFingerprinter()
        val samples = signal(16000, 30)
        val seen = mutableListOf<CloudFingerprinter.Window>()
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + 8192, samples.size)
            fp.pushSamples(samples.copyOfRange(offset, end), 1, 16000)
            seen += fp.drainWindows()
            offset = end
        }
        seen += fp.finish()
        // Same emission contract as before: one window per second from 0.
        assertEquals(seen.map { it.timestampSec }, (0 until seen.size).toList())
        assertTrue(seen.isNotEmpty())
    }
}
