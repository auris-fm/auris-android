package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record

import java.math.BigInteger
import kotlin.math.abs

private val FIVE: BigInteger = BigInteger.valueOf(5)
private val TEN: BigInteger = BigInteger.TEN

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

    private const val MAX_SIGNIFICANT_DIGITS = 17

    class CanonicalException(message: String) : Exception(message)

    fun serialize(node: RecordNode): String = buildString { write(node, this) }

    /** Canonical bytes (UTF-8) — what `record_sha256` hashes. */
    fun canonicalBytes(node: RecordNode): ByteArray {
        val text = serialize(node)
        val bytes = text.toByteArray(Charsets.UTF_8)
        // Refuse oversized canonical documents before any digest runs: the contribution record is a
        // bounded metadata envelope, and a runaway size means a construction bug, not a valid upload.
        if (bytes.size > MAX_CANONICAL_BYTES) throw CanonicalException("canonical text exceeds $MAX_CANONICAL_BYTES bytes")
        return bytes
    }

    private const val MAX_CANONICAL_BYTES = 1 shl 20

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
                            if (hex.length != 4 || hex.any { !isHex(it) }) {
                                throw CanonicalException("bad \\u escape '$hex'")
                            }
                            i += 4
                            appendCodeUnit(hex.toInt(16), out, escaped = true)
                        }

                        else -> throw CanonicalException("bad escape '\$e'")
                    }
                } else {
                    if (c.code < 0x20) throw CanonicalException("raw control character")
                    appendCodeUnit(c.code, out, escaped = false)
                }
            }
        }

        // RFC 8259 number grammar, scanned character by character rather than by swallowing every
        // adjacent symbol. A permissive scan accepted things a conformant reader must reject — "1E",
        // "1..2", "-x", "01" — and the extra characters then leaked into whatever followed.
        private fun number(): RecordNode.Num {
            val start = i
            if (s.getOrNull(i) == '-') i++
            when (s.getOrNull(i)) {
                '0' -> i++
                in '1'..'9' -> while (i < s.length && s[i].isDigit()) i++
                else -> throw CanonicalException("bad number near '${s.getOrElse(i) { '?' }}'")
            }
            // Fraction: a dot is only legal when at least one digit follows it.
            if (s.getOrNull(i) == '.') {
                i++
                if (!(s.getOrNull(i)?.isDigit() == true)) throw CanonicalException("number ends with '.'")
                while (i < s.length && s[i].isDigit()) i++
            }
            // Exponent: sign is optional but digits are mandatory.
            if (s.getOrNull(i) == 'e' || s.getOrNull(i) == 'E') {
                i++
                if (s.getOrNull(i) == '+' || s.getOrNull(i) == '-') i++
                if (!(s.getOrNull(i)?.isDigit() == true)) throw CanonicalException("exponent has no digits")
                while (i < s.length && s[i].isDigit()) i++
            }
            val text = s.substring(start, i)
            val value = text.toDoubleOrNull() ?: throw CanonicalException("bad number '$text'")
            if (!value.isFinite()) throw CanonicalException("non-finite number")
            return RecordNode.Num(value)
        }

        // JCS input conformity forbids unpaired surrogates anywhere in a canonical document, whether the
        // code unit arrived raw or as an escape. A high surrogate must be immediately followed by a low one
        // (and vice versa for an orphan low surrogate); the pair is appended together so ordering is checked
        // against the document, not the accumulator.
        private fun appendCodeUnit(code: Int, out: StringBuilder, escaped: Boolean) {
            val c = code.toChar()
            if (c.isHighSurrogate()) {
                val nxt = if (escaped) nextEscapeCodeUnitOrNull() else s.getOrNull(i)
                if (nxt == null || !nxt.isLowSurrogate()) throw CanonicalException("unpaired high surrogate")
                if (!escaped) i++
                out.append(c).append(nxt)
            } else if (c.isLowSurrogate()) {
                throw CanonicalException("unpaired low surrogate")
            } else {
                out.append(c)
            }
        }

        // Look ahead into a \uXXXX escape without consuming it, so a surrogate's pair can be validated
        // before either half reaches the result.
        private fun nextEscapeCodeUnitOrNull(): Char? {
            if (i + 5 > s.length || s[i] != '\\' || s.getOrNull(i + 1) != 'u') return null
            val hex = s.substring(i + 2, i + 6)
            if (hex.any { !isHex(it) }) return null
            return hex.toInt(16).toChar()
        }

        private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

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
            // JCS conformity forbids unpaired surrogates in canonical output, mirroring the parser's
            // appendCodeUnit rule: a high surrogate must be followed by a low one; a lone low surrogate
            // is refused outright.
            if (c.isHighSurrogate()) {
                val nxt = s.getOrNull(i + 1)
                if (nxt == null || !nxt.isLowSurrogate()) throw CanonicalException("unpaired high surrogate")
                out.append(c).append(nxt)
                i += 2
                continue
            }
            if (c.isLowSurrogate()) throw CanonicalException("unpaired low surrogate")
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c.code < 0x20 -> out.append("\\u").append(String.format("%04x", c.code))
                else -> out.append(c)
            }
            i++
        }
        out.append('"')
    }

    /**
     * RFC 8785 §4.1 mandates ES6 Number::toString output. Digit selection is delegated to the
     * vendored Ryu core (see RyuShortest.kt for Apache-2.0 provenance): its ROUND_EVEN boundary
     * acceptance IS the ECMA-262 shortest-selection rule, by construction rather than by search.
     * This layer only applies the ECMAScript presentation rules (fixed vs exponential form) to the
     * core's digits, via renderEs. A candidate's value is digits * 10^(leadingExp - olength + 1);
     * leadingExp is already the place of the most significant digit.
     */
    internal fun formatNumber(value: Double): String {
        if (!value.isFinite()) throw CanonicalException("non-finite number")
        if (value == 0.0) return "0" // covers +0 and -0 (ES6 renders -0 as "0")
        val negative = value < 0.0
        val core = RyuShortest.doubleToShortest(if (negative) -value else value)
        val digits = core.digits.toString()
        check(digits.length == core.olength) { "ryu digit count mismatch: $digits vs ${core.olength}" }
        val body = renderEs(digits, core.leadingExp)
        return if (negative) "-$body" else body
    }

    /**
     * Render the accepted significand [d] (already stripped of trailing zeros) at decimal exponent
     * [e], choosing fixed or exponential form exactly as ECMA-262 Number::toString steps 14–20 do:
     * fixed when -6 <= e < 21, exponential otherwise.
     */
    private fun renderEs(d: String, e: Int): String {
        val k = d.length
        return if (e >= -6 && e < 21) {
            val npos = e + 1 // digits before the decimal point
            when {
                npos <= 0 -> "0." + "0".repeat(-npos) + d
                k > npos -> d.substring(0, npos) + "." + d.substring(npos)
                else -> d + "0".repeat(npos - k)
            }
        } else {
            val mantissa = if (k > 1) "${d[0]}.${d.substring(1)}" else d
            mantissa + "e" + (if (e < 0) "-" else "+") + abs(e)
        }
    }

    private fun trimTrailing(s: String): String = s.trimEnd('0')
}
