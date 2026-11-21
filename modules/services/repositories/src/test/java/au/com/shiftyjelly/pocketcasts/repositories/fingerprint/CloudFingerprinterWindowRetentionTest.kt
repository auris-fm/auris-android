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

    @Test
    fun `drained windows are released instead of accumulating for the whole session`() {
        val fp = CloudFingerprinter()
        val samples = signal(16000, 240) // 4 minutes -> ~232 windows at 1/s
        var offset = 0
        var drained = 0
        while (offset < samples.size) {
            val end = minOf(offset + 16000, samples.size) // 1 s chunks, like the tap
            fp.pushSamples(samples.copyOfRange(offset, end), 1, 16000)
            drained += fp.drainWindows().size
            offset = end
        }
        drained += fp.finish().size
        assertTrue("expected a long session's worth of windows, got $drained", drained > 200)

        // After draining, the retained history must be bounded (undrained tail
        // only), not the whole session. windowsSoFar() is the retention seam.
        val retained = fp.windowsSoFar().size
        assertTrue("retained $retained windows after draining $drained — history is unbounded", retained <= 16)
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
