package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

/** Throwaway probe: prints same-timestamp score distribution. Not committed. */
class DistributionProbeTest {
    @Test
    fun probe() {
        val pcmPath = System.getenv("PROBE_PCM") ?: return
        val refPath = System.getenv("PROBE_REF") ?: return
        val payload = File(refPath).readBytes()
        val matcher = CloudFingerprintReferenceFetcher(
            au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisTokenProvider(
                clientProvider = { null },
                credentialProvider = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisAccountCredentialProviding {
                    override fun credential(): String? = null
                },
            ),
        ).buildMatcher(payload)!!
        val bytes = File(pcmPath).readBytes()
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        val samples = FloatArray(shorts.size) { shorts[it] / 32768f }
        val fp = CloudFingerprinter()
        val windows = mutableListOf<CloudFingerprinter.Window>()
        var off = 0
        while (off < samples.size) {
            val end = minOf(off + 4096, samples.size)
            fp.pushSamples(samples.copyOfRange(off, end), 1, 16000)
            windows += fp.drainWindows()
            off = end
        }
        windows += fp.finish()
        println("PROBE windows=${windows.size} checkpoints=${matcher.count}")
        val sameTs = mutableListOf<Float>()
        for (w in windows.take(120)) {
            val m = matcher.findTopMatches(w.hashes, 1).firstOrNull() ?: continue
            val same = kotlin.math.abs(m.timestampSeconds - w.timestampSec) <= 8f
            if (same) sameTs += m.score
            if (w.timestampSec % 30 == 0) println("PROBE t=${w.timestampSec}s best=${m.score} at=${m.timestampSeconds}s sameTs=$same")
        }
        if (sameTs.isNotEmpty()) {
            val sorted = sameTs.sorted()
            println("PROBE sameTs n=${sameTs.size} min=${sorted.first()} p50=${sorted[sorted.size / 2]} max=${sorted.last()} mean=${sameTs.average()}")
        } else println("PROBE sameTs n=0")
    }
}
