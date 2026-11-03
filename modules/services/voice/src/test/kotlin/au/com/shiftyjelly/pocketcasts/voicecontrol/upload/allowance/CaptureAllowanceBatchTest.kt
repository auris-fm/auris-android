package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Controls for the server-issued capture-allowance document (voice-recording-uploads.md,
 * "Server-issued capture allowance", bdcf5235). The response IS the capture authority, so every
 * control here is a refusal path or an exact-shape path: no lenient reader, no raised ceiling,
 * no transit-extended freshness. Bodies are literal Go-encoded JSON — unquoted numbers, the
 * shape the backend actually sends — not ledger-shaped fixtures.
 */
class CaptureAllowanceBatchTest {

    private val requestId = "11111111-2222-3333-4444-555555555555"
    private val windowId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val batchId = "99999999-8888-4777-8666-555555555555"

    /** Contract-shaped happy body with the initial server limits, numbers unquoted. */
    private fun body(
        disposition: String = "issued",
        validForMs: Long = 300_000,
        records: Int = 4,
        clipMs: Long = 60_000,
    ): String {
        val perCapture = 1_920_044L + 65_536L
        return """
            {"schema_version":1,
             "request_id":"$requestId",
             "window_id":"$windowId",
             "batch_id":"$batchId",
             "enrollment_epoch":"ep-1",
             "disposition":"$disposition",
             "valid_for_ms":$validForMs,
             "batch_max_records":$records,
             "batch_max_bytes":${records * perCapture},
             "max_clip_ms":$clipMs,
             "max_clip_bytes":1920044,
             "max_metadata_bytes":65536}
        """.trimIndent()
    }

    private fun refused(reason: String, parsed: CaptureAllowanceBatch.Parsed) {
        assertEquals(reason, (parsed as CaptureAllowanceBatch.Parsed.Refused).reason)
    }

    @Test
    fun `a go-encoded issued response parses exactly`() {
        val parsed = CaptureAllowanceBatch.parse(body(), requestStartElapsedMs = 1_000, nowElapsedMs = 2_000)
        val batch = parsed as CaptureAllowanceBatch.Parsed.Batch
        assertEquals(requestId, batch.requestId)
        assertEquals(windowId, batch.windowId)
        assertEquals(batchId, batch.batchId)
        assertEquals("ep-1", batch.enrollmentEpoch)
        assertEquals("issued", batch.disposition)
        assertEquals(300_000L, batch.validForMs)
        assertEquals(4, batch.batchMaxRecords)
        assertEquals(60_000L, batch.maxClipMs)
    }

    @Test
    fun `a replayed disposition parses with its identity intact`() {
        val parsed = CaptureAllowanceBatch.parse(body(disposition = "replayed"), 0, 1)
        assertEquals("replayed", (parsed as CaptureAllowanceBatch.Parsed.Batch).disposition)
    }

    @Test
    fun `request envelope is exactly two keys with a json-escaped epoch`() {
        val json = CaptureAllowanceBatch.requestJson(UUID.randomUUID(), "ep-1")
        assertTrue(json.startsWith("{\"request_id\":\""))
        assertTrue(json.contains("\",\"enrollment_epoch\":\"ep-1\"}"))
        // An opaque epoch containing a quote cannot forge extra envelope fields.
        val hostile = CaptureAllowanceBatch.requestJson(UUID.randomUUID(), "ep\"],\"injected\":1,\"x\":\"")
        val epochJson = JSONObject(hostile)
        assertEquals(2, epochJson.length())
        try {
            CaptureAllowanceBatch.requestJson(UUID.randomUUID(), "")
            throw AssertionError("an empty epoch must be refused")
        } catch (expected: IllegalArgumentException) {
            assertEquals(CaptureAllowanceBatch.REASON_EPOCH_TOO_LONG, expected.message)
        }
        try {
            CaptureAllowanceBatch.requestJson(UUID.randomUUID(), "x".repeat(513))
            throw AssertionError("epoch over 512 bytes must be refused")
        } catch (expected: IllegalArgumentException) {
            assertEquals(CaptureAllowanceBatch.REASON_EPOCH_TOO_LONG, expected.message)
        }
    }

    @Test
    fun `freshness is consumed by elapsed monotonic time with exact-boundary expiry`() {
        // At the exact boundary, expiry wins.
        refused(CaptureAllowanceBatch.REASON_EXPIRED, CaptureAllowanceBatch.parse(body(validForMs = 5_000), requestStartElapsedMs = 1_000, nowElapsedMs = 6_000))
        // One tick inside the boundary succeeds.
        assertTrue(CaptureAllowanceBatch.parse(body(validForMs = 5_000), 1_000, 5_999) is CaptureAllowanceBatch.Parsed.Batch)
        // A backwards clock (parse before the request started) is malformed, not expired.
        refused(CaptureAllowanceBatch.REASON_MALFORMED, CaptureAllowanceBatch.parse(body(), requestStartElapsedMs = 5_000, nowElapsedMs = 4_999))
    }

    @Test
    fun `a response raising a compiled ceiling is refused`() {
        refused(CaptureAllowanceBatch.REASON_CEILING, CaptureAllowanceBatch.parse(body(clipMs = 120_000), 0, 1))
        refused(CaptureAllowanceBatch.REASON_CEILING, CaptureAllowanceBatch.parse(body(validForMs = 300_001), 0, 1))
        refused(CaptureAllowanceBatch.REASON_CEILING, CaptureAllowanceBatch.parse(body(records = 5), 0, 1))
    }

    @Test
    fun `a capacity pair that breaks the per-capture identity is refused`() {
        val mismatched = body().replace("\"batch_max_bytes\":${4 * (1_920_044L + 65_536L)}", "\"batch_max_bytes\":1")
        refused(CaptureAllowanceBatch.REASON_CAPACITY_IDENTITY, CaptureAllowanceBatch.parse(mismatched, 0, 1))
    }

    @Test
    fun `wrong schema non-uuid ids extra keys duplicate keys and unknown values are malformed`() {
        refused(CaptureAllowanceBatch.REASON_MALFORMED, CaptureAllowanceBatch.parse(body().replace("\"schema_version\":1", "\"schema_version\":2"), 0, 1))
        refused(CaptureAllowanceBatch.REASON_MALFORMED, CaptureAllowanceBatch.parse(body().replace(requestId, "not-a-uuid"), 0, 1))
        refused(CaptureAllowanceBatch.REASON_MALFORMED, CaptureAllowanceBatch.parse(body().replace(windowId, "not-a-uuid"), 0, 1))
        refused(CaptureAllowanceBatch.REASON_MALFORMED, CaptureAllowanceBatch.parse(body().dropLast(1) + ",\"surprise\":1}", 0, 1))
        refused(CaptureAllowanceBatch.REASON_MALFORMED, CaptureAllowanceBatch.parse(body(disposition = "granted"), 0, 1))
        // A duplicate key must be damage, not an overwrite.
        val dup = body().replace("\"disposition\":\"issued\",", "\"disposition\":\"issued\",\"disposition\":\"replayed\",")
        refused(CaptureAllowanceBatch.REASON_MALFORMED, CaptureAllowanceBatch.parse(dup, 0, 1))
    }

    @Test
    fun `an oversized envelope is refused before parsing`() {
        val big = "{\"k\":\"" + "x".repeat(10_000) + "\"}"
        refused(CaptureAllowanceBatch.REASON_OVERSIZED, CaptureAllowanceBatch.parse(big, 0, 1))
    }

    @Test
    fun `quoted numbers are a different document than measured values`() {
        // Go sends schema_version and every numeric field unquoted; a quoted "1" must be
        // refused, not silently accepted as the number.
        refused(CaptureAllowanceBatch.REASON_MALFORMED, CaptureAllowanceBatch.parse(body().replace("\"schema_version\":1", "\"schema_version\":\"1\""), 0, 1))
        refused(CaptureAllowanceBatch.REASON_MALFORMED, CaptureAllowanceBatch.parse(body().replace("\"valid_for_ms\":300000", "\"valid_for_ms\":\"300000\""), 0, 1))
        // Conversely, identifiers arriving as bare numbers are malformed too.
        refused(CaptureAllowanceBatch.REASON_MALFORMED, CaptureAllowanceBatch.parse(body().replace("\"disposition\":\"issued\"", "\"disposition\":1"), 0, 1))
    }

    @Test
    fun `a trailing comma is malformed and junk literals are refused`() {
        refused(CaptureAllowanceBatch.REASON_MALFORMED, CaptureAllowanceBatch.parse(body().dropLast(1) + ",}", 0, 1))
        refused(CaptureAllowanceBatch.REASON_MALFORMED, CaptureAllowanceBatch.parse(body().replace("1920044", "nul").replace("65536", "nulx"), 0, 1))
        refused(CaptureAllowanceBatch.REASON_MALFORMED, CaptureAllowanceBatch.parse(body().replace("1920044", "'1920044'"), 0, 1))
    }

    @Test
    fun `nested values and trailing content are malformed`() {
        refused(CaptureAllowanceBatch.REASON_MALFORMED, CaptureAllowanceBatch.parse("{\"a\":{\"b\":1}}", 0, 1))
        refused(CaptureAllowanceBatch.REASON_MALFORMED, CaptureAllowanceBatch.parse(body() + " trailing", 0, 1))
    }
}
