package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record

import org.junit.Test

class Es6DifferentialTest {
    @Test
    fun literal_RFC_8785_Appendix_B_tie_vector() {
        // Reviewer-supplied: bits 0x43143ff3c1cb0959 = exactly 1424953923781206.25; ES6 requires
        // round-half-to-even -> "1424953923781206.2". Node v26.10.0 confirms.
        val tie = java.lang.Double.longBitsToDouble(java.lang.Long.parseUnsignedLong("43143ff3c1cb0959", 16))
        org.junit.Assert.assertEquals("1424953923781206.2", CanonicalJson.formatNumber(tie))
        val back = CanonicalJson.parse(CanonicalJson.formatNumber(tie)) as RecordNode.Num
        org.junit.Assert.assertEquals(tie, back.value, 0.0)
    }

    @Test
    fun differential_corpus_against_validated_ECMAScript_reference() {
        val stream = javaClass.classLoader!!.getResourceAsStream("es6_number_corpus_small.tsv")
        check(stream != null) { "corpus resource missing" }
        var checked = 0
        stream.bufferedReader().forEachLine { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEachLine
            val parts = line.split('\t')
            if (parts.size < 3) return@forEachLine
            val bits = java.lang.Long.parseUnsignedLong(parts[0].trim())
            val x = java.lang.Double.longBitsToDouble(bits)
            if (!x.isFinite()) return@forEachLine
            org.junit.Assert.assertEquals(
                "bits=" + parts[0].trim() + " value=" + x,
                parts[2].trim(),
                CanonicalJson.formatNumber(x),
            )
            checked++
        }
        check(checked > 200) { "corpus unexpectedly small: " + checked }
    }
}
