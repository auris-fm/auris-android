package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record

import kotlin.math.abs

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

    // Conformant parser used for re-canonicalization checks and queue persistence readback.
    // Rejects duplicate keys and non-finite numbers (RFC 8785 input conformity).
    fun parse(text: String): RecordNode {
        val reader = JsonReader(text)
        val node = reader.value()
        reader.skipWhitespace()
        if (!reader.atEnd()) throw CanonicalException("trailing input")
        return node
    }

    private class JsonReader(private val s: String) {
        private var i = 0
        fun atEnd(): Boolean = i >= s.length
        fun skipWhitespace() {
            while (i < s.length && s[i].isJsonWhitespace()) i++
        }

        fun value(): RecordNode {
            skipWhitespace()
            if (atEnd()) throw CanonicalException("empty input")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> RecordNode.Str(string())
                't' -> literal("true", RecordNode.Bool(true))
                'f' -> literal("false", RecordNode.Bool(false))
                'n' -> literal("null", RecordNode.Null)
                else -> if (c == '-' || c.isDigit()) number() else throw CanonicalException("unexpected '$c'")
            }
        }

        private fun obj(): RecordNode.Obj {
            expect('{')
            val fields = LinkedHashMap<String, RecordNode>()
            skipWhitespace()
            if (i < s.length && s[i] == '}') {
                i++
                return RecordNode.Obj(emptyMap())
            }
            while (true) {
                val key = string()
                if (fields.containsKey(key)) throw CanonicalException("duplicate key '$key'")
                skipWhitespace()
                expect(':')
                fields[key] = value()
                skipWhitespace()
                when (s.getOrNull(i)) {
                    ',' -> i++

                    '}' -> {
                        i++
                        return RecordNode.Obj(fields.toMap())
                    }

                    else -> throw CanonicalException("expected , or }")
                }
            }
        }

        private fun arr(): RecordNode.Arr {
            expect('[')
            val items = mutableListOf<RecordNode>()
            skipWhitespace()
            if (i < s.length && s[i] == ']') {
                i++
                return RecordNode.Arr(emptyList())
            }
            while (true) {
                items += value()
                skipWhitespace()
                when (s.getOrNull(i)) {
                    ',' -> i++

                    ']' -> {
                        i++
                        return RecordNode.Arr(items.toList())
                    }

                    else -> throw CanonicalException("expected , or ]")
                }
            }
        }

        private fun string(): String {
            skipWhitespace()
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (atEnd()) throw CanonicalException("unterminated string")
                val c = s[i++]
                if (c == '"') return out.toString()
                if (c == 92.toChar()) {
                    when (val e = s[i++]) {
                        '"' -> out.append('"')

                        92.toChar() -> out.append(92.toChar())

                        '/' -> out.append('/')

                        'b' -> out.append(8.toChar())

                        'f' -> out.append(12.toChar())

                        'n' -> out.append(10.toChar())

                        'r' -> out.append(13.toChar())

                        't' -> out.append(9.toChar())

                        'u' -> {
                            val hex = s.substring(i, i + 4)
                            i += 4
                            out.append(hex.toInt(16).toChar())
                        }

                        else -> throw CanonicalException("bad escape '\$e'")
                    }
                } else {
                    if (c.code < 0x20) throw CanonicalException("raw control character")
                    out.append(c)
                }
            }
        }

        private fun number(): RecordNode.Num {
            val start = i
            if (s.getOrNull(i) == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            val text = s.substring(start, i)
            val value = text.toDoubleOrNull() ?: throw CanonicalException("bad number '$text'")
            if (!value.isFinite()) throw CanonicalException("non-finite number")
            return RecordNode.Num(value)
        }

        private fun literal(word: String, node: RecordNode): RecordNode {
            if (!s.startsWith(word, i)) throw CanonicalException("bad literal")
            i += word.length
            return node
        }

        private fun expect(c: Char) {
            if (s.getOrNull(i) != c) throw CanonicalException("expected '$c'")
            i++
        }
    }

    private fun Char.isJsonWhitespace(): Boolean = code == 32 || code == 9 || code == 10 || code == 13

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
        if (value == 0.0) return "0" // covers +0 and -0 (ES6 renders -0 as "0")
        // ECMAScript `Number.prototype.toString()` selects the rendering by EXPONENT bounds
        // (fixed when -6 <= E < 21), NOT Java's thresholds (scientific below 1e-3 / above 1e7).
        // Take Java's shortest-round-trip digits, then re-render with ES selection — RFC 8785 4.1.
        val negative = value < 0.0
        val repr = java.lang.Double.toString(if (negative) -value else value)
        val eIdx = repr.indexOf('E')
        val digits: String
        val point: Int
        if (eIdx >= 0) {
            val mantissa = repr.substring(0, eIdx)
            val exp = repr.substring(eIdx + 1).toInt()
            val dot = mantissa.indexOf('.')
            val intPart = if (dot < 0) mantissa else mantissa.substring(0, dot)
            val fracPart = if (dot < 0) "" else mantissa.substring(dot + 1).trimEnd('0')
            digits = intPart + fracPart
            point = intPart.length + exp
        } else {
            val dot = repr.indexOf('.')
            val intPart = repr.substring(0, dot)
            val fracPart = repr.substring(dot + 1).trimEnd('0')
            digits = intPart + fracPart
            point = intPart.length
        }
        val sig = digits.indexOfFirst { it != '0' }
        if (sig < 0) return "0"
        val d = digits.substring(sig)
        val n = d.length
        val exponent = point - sig - 1 // normalized: value = d[0].d[1..] x 10^exponent
        val body = if (exponent in -6..20) {
            val k = exponent + 1
            when {
                k <= 0 -> "0." + "0".repeat(-k) + d
                n >= k -> d.substring(0, k) + if (n > k) "." + d.substring(k) else ""
                else -> d + "0".repeat(k - n)
            }
        } else {
            val mantissa = if (n > 1) "${d[0]}.${d.substring(1)}" else d
            mantissa + "e" + (if (exponent < 0) "-" else "+") + abs(exponent)
        }
        return if (negative) "-$body" else body
    }

    private fun trimTrailing(s: String): String = s.trimEnd('0')
}
