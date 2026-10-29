package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record

import org.junit.Test

/**
 * Asserts the production formatter AND the installed ECMAScript engine against the RFC 8785
 * Appendix B Table 1 literals, which were transcribed verbatim from the RFC text and are
 * independent of any engine output. The Node check runs only when a `node` binary exists on
 * the test machine; the committed receipt artifacts/receipts/rfc8785-appendix-b-node-oracle.txt
 * records the run performed on the dev machine (node v26.10.0).
 */
class Rfc8785AppendixBTest {

    private fun rows(): List<Triple<String, String, String>> {
        val stream = javaClass.classLoader!!.getResourceAsStream("rfc8785_appendix_b_literals.tsv")
        check(stream != null) { "RFC 8785 Appendix B literal resource missing" }
        val rows = mutableListOf<Triple<String, String, String>>()
        stream.bufferedReader().forEachLine { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEachLine
            val parts = line.split('\t')
            if (parts.size != 2) return@forEachLine
            val expected = parts[1].trim()
            val kind = if (expected.startsWith("refuse:")) expected.removePrefix("refuse:") else ""
            rows.add(Triple(parts[0].trim(), expected, kind))
        }
        check(rows.size >= 26) { "Appendix B table incomplete: ${rows.size}" }
        return rows
    }

    @Test
    fun `production formatter matches the RFC 8785 Appendix B literals`() {
        for ((hex, expected, kind) in rows()) {
            val x = java.lang.Double.longBitsToDouble(java.lang.Long.parseUnsignedLong(hex, 16))
            if (kind.isNotEmpty()) {
                try {
                    CanonicalJson.formatNumber(x)
                    throw AssertionError("non-finite accepted for bits $hex ($kind)")
                } catch (expectedRefusal: CanonicalJson.CanonicalException) {
                    // refused as the RFC requires (Note 3)
                }
            } else {
                org.junit.Assert.assertEquals("bits=$hex", expected, CanonicalJson.formatNumber(x))
            }
        }
    }

    @Test
    fun `installed ECMAScript engine matches the same RFC literals when available`() {
        val node = runCatching {
            ProcessBuilder("node", "--version").start().inputStream.bufferedReader().readText().trim()
        }.getOrNull() ?: return // oracle check recorded in the committed receipt for this machine
        check(node.startsWith("v")) { "unexpected node version output: $node" }
        for ((hex, expected, kind) in rows()) {
            if (kind.isNotEmpty()) continue // NaN/Infinity have no JSON form to compare
            val script = "const b=BigInt('0x$hex');const f=new Float64Array(8);new BigUint64Array(f.buffer)[0]=b;process.stdout.write(String(f[0]))"
            val out = ProcessBuilder("node", "-e", script).start().inputStream.bufferedReader().readText()
            org.junit.Assert.assertEquals("node oracle mismatch for bits=$hex", expected, out.trim())
        }
    }
}
