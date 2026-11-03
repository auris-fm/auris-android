package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * The server-issued capture-allowance batch (docs/specs/voice-recording-uploads.md,
 * "Server-issued capture allowance", review head bdcf5235).
 *
 * TRANSPORT SHAPE. `POST /api/v1/account/voice-sharing/capture-allowance` with a flat request
 * `{request_id, enrollment_epoch}` and an 8 KiB-capped JSON response. The response is parsed by
 * a bounded, DUPLICATE-AWARE flat reader, not the ledger's private quoted-format parser: the
 * backend emits real JSON with unquoted numeric fields, and a ledger-shaped reader would reject
 * it while a lenient JSON reader would silently collapse duplicate keys. Exactly the twelve
 * contract keys must appear; any extra, missing or repeated key is malformed.
 *
 * COMPILED CEILINGS (independent of the response; a response may never raise them):
 * clip 60,000 ms / 1,920,044 WAV bytes; metadata 65,536 bytes; four captures per batch; a
 * freshness interval of at most 300,000 ms.
 *
 * CAPACITY IDENTITY: the contract states a batch's byte capacity equals its record capacity
 * times the full per-capture reservation (`max_clip_bytes + max_metadata_bytes`). A response
 * whose capacity pair does not satisfy that identity is malformed, not merely unusual — the
 * two numbers are what the ledger charges, and disagreeing pair members would let admission
 * and accounting disagree.
 *
 * FRESHNESS ANCHORING: the caller captures the monotonic elapsed time BEFORE starting the
 * transport request and supplies it as [parse]'s `requestStartElapsedMs`; the interval is
 * evaluated as `nowElapsedMs - requestStartElapsedMs` against `valid_for_ms`, so transit time
 * cannot extend the server interval. A backwards clock or an elapsed interval at or beyond the
 * boundary is refused, never permission.
 */
object CaptureAllowanceBatch {
    const val MAX_ENVELOPE_BYTES: Int = 8_192
    const val MAX_EPOCH_BYTES: Int = 512
    const val MAX_BATCH_RECORDS: Int = 4
    const val MAX_VALID_FOR_MS: Long = 300_000
    const val MAX_CLIP_MS: Long = 60_000
    const val MAX_CLIP_BYTES: Long = 1_920_044
    const val MAX_METADATA_BYTES: Long = 65_536
    const val MAX_ENTRIES: Int = 64

    const val SCHEMA_VERSION = "1"
    const val DISPOSITION_ISSUED = "issued"
    const val DISPOSITION_REPLAYED = "replayed"

    /** The strict refusal reasons this parser produces; never a success-shaped value. */
    const val REASON_MALFORMED = "malformed_allowance"
    const val REASON_OVERSIZED = "oversized_allowance"
    const val REASON_CEILING = "allowance_ceiling_exceeded"
    const val REASON_EXPIRED = "allowance_expired"
    const val REASON_CAPACITY_IDENTITY = "allowance_capacity_identity"
    const val REASON_EPOCH_TOO_LONG = "allowance_epoch_too_long"

    private val UUID_SHAPE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    private val KEYS: Set<String> = setOf(
        "schema_version",
        "request_id",
        "window_id",
        "batch_id",
        "enrollment_epoch",
        "disposition",
        "valid_for_ms",
        "batch_max_records",
        "batch_max_bytes",
        "max_clip_ms",
        "max_clip_bytes",
        "max_metadata_bytes",
    )

    /** The strict request envelope: exactly two keys, UUID request id, non-empty bounded epoch. */
    fun requestJson(requestId: UUID, enrollmentEpoch: String): String {
        if (enrollmentEpoch.isEmpty() || enrollmentEpoch.toByteArray(Charsets.UTF_8).size > MAX_EPOCH_BYTES) {
            throw IllegalArgumentException(REASON_EPOCH_TOO_LONG)
        }
        // The epoch is an opaque server string: JSON-escape it rather than interpolate, so a
        // quote or backslash inside it cannot forge extra envelope fields.
        val escaped = JSONObject.quote(enrollmentEpoch)
        return "{\"request_id\":\"$requestId\",\"enrollment_epoch\":$escaped}"
    }

    /** A validated batch, or the exact refusal reason. Never both. */
    sealed class Parsed {
        data class Batch(
            val requestId: String,
            val windowId: String,
            val batchId: String,
            val enrollmentEpoch: String,
            val disposition: String,
            val validForMs: Long,
            val batchMaxRecords: Int,
            val batchMaxBytes: Long,
            val maxClipMs: Long,
            val maxClipBytes: Long,
            val maxMetadataBytes: Long,
        ) : Parsed()

        data class Refused(val reason: String) : Parsed()
    }

    /**
     * Strict parse of the 200 response body. `requestStartElapsedMs` is the monotonic elapsed
     * time captured before the transport call started; `nowElapsedMs` is the monotonic elapsed
     * time at parse. `valid_for_ms` is consumed by the elapsed span between them.
     */
    fun parse(text: String?, requestStartElapsedMs: Long, nowElapsedMs: Long): Parsed {
        if (text == null) return Parsed.Refused(REASON_MALFORMED)
        // NEGATIVE INPUTS FIRST: the elapsed-time API does not enforce nonnegativity, and
        // subtracting from a negative start could turn an expired response into a live one.
        if (requestStartElapsedMs < 0 || nowElapsedMs < 0) return Parsed.Refused(REASON_MALFORMED)
        if (nowElapsedMs < requestStartElapsedMs) return Parsed.Refused(REASON_MALFORMED)
        if (text.toByteArray(Charsets.UTF_8).size > MAX_ENVELOPE_BYTES) return Parsed.Refused(REASON_OVERSIZED)
        val doc = parseFlatObject(text) ?: return Parsed.Refused(REASON_MALFORMED)
        if (doc.keys != KEYS) return Parsed.Refused(REASON_MALFORMED)

        // TYPE FIDELITY: the Go backend sends schema_version and every numeric field UNQUOTED and
        // every identifier/disposition as a JSON string. A quoted "1" is a different document, and
        // accepting it would let string-encoded fields masquerade as measured values. The raw
        // string of a scalar is only valid where its JSON type matches the field.
        fun scalar(key: String): Scalar = doc.getValue(key)
        fun requireNumber(key: String): String? = scalar(key).takeIf { !it.quoted }?.raw
        fun requireString(key: String): String? = scalar(key).takeIf { it.quoted }?.raw

        // SCHEMA AND IDENTITY: the response must declare the supported schema and carry
        // well-shaped identifiers; anything else is a different protocol wearing this one's URL.
        if (scalar("schema_version").let { it.quoted || it.raw != SCHEMA_VERSION }) return Parsed.Refused(REASON_MALFORMED)
        val requestId = requireString("request_id") ?: return Parsed.Refused(REASON_MALFORMED)
        if (!UUID_SHAPE.matches(requestId)) return Parsed.Refused(REASON_MALFORMED)
        val windowId = requireString("window_id") ?: return Parsed.Refused(REASON_MALFORMED)
        val batchId = requireString("batch_id") ?: return Parsed.Refused(REASON_MALFORMED)
        if (!UUID_SHAPE.matches(windowId) || !UUID_SHAPE.matches(batchId)) {
            return Parsed.Refused(REASON_MALFORMED)
        }

        val epoch = requireString("enrollment_epoch") ?: return Parsed.Refused(REASON_MALFORMED)
        if (epoch.isEmpty() || epoch.toByteArray(Charsets.UTF_8).size > MAX_EPOCH_BYTES) {
            return Parsed.Refused(REASON_EPOCH_TOO_LONG)
        }

        val disposition = requireString("disposition") ?: return Parsed.Refused(REASON_MALFORMED)
        if (disposition != DISPOSITION_ISSUED && disposition != DISPOSITION_REPLAYED) {
            return Parsed.Refused(REASON_MALFORMED)
        }

        val validForMs = requireNumber("valid_for_ms")?.toLongOrNull() ?: return Parsed.Refused(REASON_MALFORMED)
        if (validForMs <= 0 || validForMs > MAX_VALID_FOR_MS) return Parsed.Refused(REASON_CEILING)
        // The interval is consumed by elapsed monotonic time since the request started. AT OR
        // BEYOND the boundary, expiry wins (contract arbitration); a backwards clock is already
        // refused above, and subtraction here cannot overflow on non-negative monotonic reads.
        val elapsed = nowElapsedMs - requestStartElapsedMs
        if (validForMs <= elapsed) return Parsed.Refused(REASON_EXPIRED)

        val batchMaxRecords = requireNumber("batch_max_records")?.toIntOrNull() ?: return Parsed.Refused(REASON_MALFORMED)
        if (batchMaxRecords < 1 || batchMaxRecords > MAX_BATCH_RECORDS) return Parsed.Refused(REASON_CEILING)
        val batchMaxBytes = requireNumber("batch_max_bytes")?.toLongOrNull() ?: return Parsed.Refused(REASON_MALFORMED)
        val maxClipMs = requireNumber("max_clip_ms")?.toLongOrNull() ?: return Parsed.Refused(REASON_MALFORMED)
        val maxClipBytes = requireNumber("max_clip_bytes")?.toLongOrNull() ?: return Parsed.Refused(REASON_MALFORMED)
        val maxMetadataBytes = requireNumber("max_metadata_bytes")?.toLongOrNull() ?: return Parsed.Refused(REASON_MALFORMED)

        // COMPILED CEILINGS: the response may select lower values, never raise them.
        if (maxClipMs < 1 || maxClipMs > MAX_CLIP_MS) return Parsed.Refused(REASON_CEILING)
        if (maxClipBytes < 1 || maxClipBytes > MAX_CLIP_BYTES) return Parsed.Refused(REASON_CEILING)
        if (maxMetadataBytes < 1 || maxMetadataBytes > MAX_METADATA_BYTES) return Parsed.Refused(REASON_CEILING)

        // CAPACITY IDENTITY: bytes = records x (clip + metadata reservation), exactly.
        val perCapture = maxClipBytes + maxMetadataBytes
        if (batchMaxRecords.toLong() * perCapture != batchMaxBytes) {
            return Parsed.Refused(REASON_CAPACITY_IDENTITY)
        }

        return Parsed.Batch(
            requestId = requestId,
            windowId = windowId,
            batchId = batchId,
            enrollmentEpoch = epoch,
            disposition = disposition,
            validForMs = validForMs,
            batchMaxRecords = batchMaxRecords,
            batchMaxBytes = batchMaxBytes,
            maxClipMs = maxClipMs,
            maxClipBytes = maxClipBytes,
            maxMetadataBytes = maxMetadataBytes,
        )
    }

    // Bounded duplicate-aware flat-object reader over real JSON (unquoted numbers included).
    // Values must be JSON scalars — a nested object or array is malformed — and the whole
    // document must be exactly one object with no trailing content.

    // A raw scalar with its JSON type preserved: numbers arrive unquoted, strings quoted.
    private data class Scalar(val raw: String, val quoted: Boolean)

    private fun parseFlatObject(text: String): Map<String, Scalar>? {
        val map = LinkedHashMap<String, Scalar>()
        try {
            val tokener = JSONTokener(text)
            if (tokener.nextClean() != '{') return null
            var afterComma = false
            while (true) {
                var c = tokener.nextClean()
                if (c == '}') {
                    if (afterComma) return null // trailing comma, never an empty entry
                    // Exactly one object: any trailing non-whitespace byte is damage.
                    while (true) {
                        val rest = tokener.nextClean()
                        if (rest == 0.toChar()) break
                        if (!rest.isWhitespace()) return null
                    }
                    return map
                }
                if (c != '"') return null
                val key = tokener.nextString(c)
                if (tokener.nextClean() != ':') return null
                val scalar = readScalar(tokener) ?: return null
                // DUPLICATE-AWARE: a repeated key is damage, not an overwrite.
                if (map.put(key, scalar) != null) return null
                if (map.size > MAX_ENTRIES) return null
                c = tokener.nextClean()
                if (c == '}') {
                    // Exactly one object: any trailing non-whitespace byte is damage.
                    while (true) {
                        val rest = tokener.nextClean()
                        if (rest == 0.toChar()) break
                        if (!rest.isWhitespace()) return null
                    }
                    return map
                }
                if (c != ',') return null
                afterComma = true
            }
        } catch (e: JSONException) {
            return null
        }
    }

    private val NUMBER_LITERAL = Regex("-?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    /**
     * Reads one JSON scalar preserving its TYPE. org.json's JSONTokener returns unquoted
     * literals (numbers, true/false/null) as plain Strings, so quoting is detected from the
     * first consumed character, not from the value class. Single quotes, nested containers and
     * non-number junk literals are malformed.
     */
    private fun readScalar(tokener: JSONTokener): Scalar? {
        val first = tokener.nextClean()
        if (first == '{' || first == '[' || first == '\'') return null
        tokener.back()
        val value = tokener.nextValue()
        if (value is JSONObject || value is JSONArray) return null
        val raw = value.toString()
        val quoted = first == '"'
        if (!quoted && raw != "true" && raw != "false" && raw != "null" && !NUMBER_LITERAL.matches(raw)) {
            return null
        }
        return Scalar(raw, quoted)
    }
}
