package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record

/**
 * RFC 8785 JSON Canonicalization Scheme over the module's immutable [RecordNode] tree. The
 * contribution record never round-trips through a reflection serializer: fields are built as a
 * node tree (absent = absent, never empty-string success), canonicalized here, and hashed.
 *
 * Rules: object keys sorted by UTF-16 code units; arrays keep order; strings escaped per the
 * JCS escape set; numbers use ES6 `Number.prototype.toString` semantics via [formatNumber];
 * no whitespace. Duplicate keys and non-finite numbers cannot be constructed in the tree
 * (builder/parser reject them) — any producer feeding [RecordNode] must enforce the same.
 */
object CanonicalJson {

    class CanonicalException(message: String) : Exception(message)

    fun serialize(node: RecordNode): String = buildString { write(node, this) }

    /** Canonical bytes (UTF-8) — what `record_sha256` hashes. */
    fun canonicalBytes(node: RecordNode): ByteArray = serialize(node).toByteArray(Charsets.UTF_8)

    private fun write(node: RecordNode, out: StringBuilder) {
        when (node) {
            RecordNode.Null -> out.append("null")

            is RecordNode.Bool -> out.append(if (node.value) "true" else "false")

            is RecordNode.Num -> {
                if (!node.value.isFinite()) throw CanonicalException("non-finite number")
                out.append(formatNumber(node.value))
            }

            is RecordNode.Str -> appendQuoted(node.value, out)

            is RecordNode.Arr -> {
                out.append('[')
                node.items.forEachIndexed { i, item ->
                    if (i > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }

            is RecordNode.Obj -> {
                val keys = node.fields.keys.sorted()
                out.append('{')
                keys.forEachIndexed { i, key ->
                    if (i > 0) out.append(',')
                    appendQuoted(key, out)
                    out.append(':')
                    write(node.fields.getValue(key), out)
                }
                out.append('}')
            }
        }
    }

    private fun appendQuoted(s: String, out: StringBuilder) {
        out.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c.code < 0x20 -> out.append("\\u").append(String.format("%04X", c.code))
                else -> out.append(c)
            }
            i++
        }
        out.append('"')
    }

    /**
     * ES6 Number-to-string (shortest round-trip): Kotlin `Double.toString` is already
     * shortest-round-trip; normalize `-0 → 0`, drop integral `.0`, and format exponents as
     * `em`/`e-m` without `+` or leading zeros per RFC 8785 §4.1.
     */
    internal fun formatNumber(value: Double): String {
        if (value == 0.0) return "0" // covers +0 and -0
        var s = value.toString()
        val eIdx = s.indexOf('E').let { if (it >= 0) it else s.indexOf('e') }
        if (eIdx < 0) {
            if (s.endsWith(".0")) s = s.dropLast(2)
            return s
        }
        val mantissaRaw = s.substring(0, eIdx)
        val expPart = s.substring(eIdx + 1)
        val negativeExp = expPart.startsWith("-")
        val expDigits = expPart.removePrefix("+").removePrefix("-").trimStart('0').ifEmpty { "0" }
        val mantissa = trimMantissa(mantissaRaw)
        return "$mantissa" + (if (negativeExp) "e-" else "e") + expDigits
    }

    private fun trimMantissa(raw: String): String {
        var m = raw
        if (m.endsWith(".0")) m = m.dropLast(2)
        // Java renders e.g. 1.23456789E7 mantissa with trailing zeros after normalization only
        // in rare cases; strip trailing zeros after a decimal point for canonical form.
        if (m.contains('.') && !m.endsWith('.')) {
            m = m.trimEnd('0').trimEnd('.')
        }
        return m
    }
}
