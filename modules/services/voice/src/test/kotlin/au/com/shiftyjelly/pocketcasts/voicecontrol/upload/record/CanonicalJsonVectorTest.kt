package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record

import org.junit.Assert.assertEquals
import org.junit.Test

// Task 4 remaining: canonicalization vectors (key ordering, escaping, numbers) per plan.
class CanonicalJsonVectorTest {

    @Test
    fun `object keys sort by UTF-16 code units`() {
        val obj = RecordNode.Obj(
            mapOf(
                "b" to RecordNode.ofInt(1),
                "A" to RecordNode.ofInt(2), // uppercase sorts BEFORE lowercase
                "a" to RecordNode.ofInt(3),
                "Zz" to RecordNode.ofInt(4),
                "é" to RecordNode.ofInt(5), // U+00E9 sorts after ASCII
            ),
        )
        assertEquals("""{"A":2,"Zz":4,"a":3,"b":1,"é":5}""", CanonicalJson.serialize(obj))
    }

    @Test
    fun `string escapes follow the JCS escape set`() {
        val bs = 92.toChar() // backslash without literal-escape ambiguity
        val input = "q" + '"' + '\n' + bs + '\r' + '\t' + '\u0008' + '\u000C' + '\u001F' + 'a' + '\u0660'
        val expected =
            "\"q" + bs + "\"" + bs + "n" + bs + bs + bs + "r" + bs + "t" + bs + "b" + bs + "f" + bs + "u001Fa" + "\u0660\""
        assertEquals(expected, CanonicalJson.serialize(RecordNode.Str(input)))
        // Surrogate pairs pass through unescaped (well-formed UTF-16 above the control range).
        assertEquals("\"😀\"", CanonicalJson.serialize(RecordNode.Str("😀")))
    }

    @Test
    fun `number vectors match ES6 rendering`() {
        val cases = linkedMapOf(
            1.0 to "1",
            -0.0 to "0",
            0.5 to "0.5",
            1e21 to "1e+21",
            1e-7 to "1e-7",
            123456.789 to "123456.789",
            1.2345678E7 to "12345678",
            -1.5e-8 to "-1.5e-8",
            9.0E-6 to "0.000009",
        )
        for ((v, want) in cases) assertEquals(want, CanonicalJson.formatNumber(v))
    }

    @Test
    fun `arrays keep order and nesting is compact`() {
        val arr = RecordNode.Arr(listOf(RecordNode.Obj(mapOf("b" to RecordNode.Bool(true), "a" to RecordNode.Null)), RecordNode.ofInt(0)))
        assertEquals("""[{"a":null,"b":true},0]""", CanonicalJson.serialize(arr))
    }

    @Test
    fun `re-serialization is byte-stable so digest survives round-trips`() {
        val record = RecordNode.Obj(
            mapOf(
                "capture_id" to RecordNode.Str("3f2b1a6e-0000-4000-8000-000000000001"),
                "audio" to RecordNode.Obj(mapOf("byte_count" to RecordNode.ofInt(44), "sha256" to RecordNode.Str("b".repeat(64)))),
                "observations" to RecordNode.Obj(mapOf("asr" to RecordNode.Obj(mapOf("state" to RecordNode.Str("not_run"))))),
            ),
        )
        val first = CanonicalJson.canonicalBytes(record)
        val again = CanonicalJson.canonicalBytes(CanonicalJson.parse(String(first, Charsets.UTF_8)))
        assertEquals(java.util.Arrays.toString(first), java.util.Arrays.toString(again))
    }

    @Test
    fun `parser rejects duplicate keys and trailing input`() {
        runCatching { CanonicalJson.parse("""{"a":1,"a":2}""") }.onSuccess { throw AssertionError("duplicate key accepted") }
        runCatching { CanonicalJson.parse("""{"a":1} x""") }.onSuccess { throw AssertionError("trailing input accepted") }
        runCatching { CanonicalJson.parse("""{"a":1e999}""") }.onSuccess { throw AssertionError("non-finite accepted") }
    }
}
