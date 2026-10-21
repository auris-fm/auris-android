package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record

import org.junit.Test

/**
 * Full-corpus differential receipt generator. Runs formatNumber against the complete
 * 30,057-value node-validated corpus resource and prints a machine-readable summary.
 * Kept as a named source file (not @Ignored) so the receipt is reproducible from the
 * committed tree; CI uses the small-subset and literal tests for speed.
 */
class FullCorpusProbe {
    @Test
    fun fullCorpusDifferential() {
        val stream = javaClass.classLoader!!.getResourceAsStream("es6_number_corpus_full.tsv")
        check(stream != null) { "full corpus resource missing" }
        var checked = 0
        var mismatches = 0
        stream.bufferedReader().forEachLine { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEachLine
            val parts = line.split('\t')
            if (parts.size < 3) return@forEachLine
            val bits = java.lang.Long.parseUnsignedLong(parts[0].trim())
            val x = java.lang.Double.longBitsToDouble(bits)
            if (!x.isFinite()) return@forEachLine
            checked++
            val got = CanonicalJson.formatNumber(x)
            if (got != parts[2].trim()) {
                mismatches++
                println("MISMATCH bits=${parts[0].trim()} value=$x expected=${parts[2].trim()} got=$got")
            }
        }
        println("FULL_CORPUS_CHECKED=$checked MISMATCHES=$mismatches")
        org.junit.Assert.assertEquals("full-corpus mismatches", 0, mismatches)
    }
}
