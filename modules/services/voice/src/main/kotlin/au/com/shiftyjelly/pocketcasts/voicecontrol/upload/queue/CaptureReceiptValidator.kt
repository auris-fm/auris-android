package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import com.squareup.moshi.JsonReader
import okio.Buffer

/**
 * Production [ContributionAckResolver.AcceptValidator]: decides, from the server's response alone, whether
 * it proves THIS capture was durably accepted.
 *
 * RECONCILED AGAINST THE OWNING WIRE CONTRACT (worker `src/contributions/upload_route.ts`: `UploadReceipt`),
 * not against a private enum (spec 79c6560b). A success body is exactly:
 *
 * ```
 * { "schema_version": 1, "capture_id": ..., "record_sha256": ...,
 *   "status": "accepted" | "duplicate", "receipt_id": ..., "accepted_at": ...,
 *   "retention_expires_at": ... }
 * ```
 *
 * An acceptance therefore must:
 *
 *  1. parse as a flat JSON OBJECT (duplicate keys, escapes and non-object bodies are ambiguous),
 *  2. carry `schema_version` 1 - a different schema is not this contract,
 *  3. echo the SAME `capture_id` (the only deduplication key Worker and client share),
 *  4. echo the SAME `record_sha256` as the capture on disk (the only identity evidence the queue holds),
 *  5. report `status` `accepted` or `duplicate` - the Worker's two success values - and
 *  6. carry a nonempty `receipt_id`, `accepted_at` and `retention_expires_at`.
 *
 * `duplicate` IS an acceptance: the Worker returns it when the first acceptance already happened, and the
 * first acceptance's `receipt_id`/`accepted_at`/`retention_expires_at` are echoed unchanged.
 *
 * Anything else - a different id or digest, a missing field, unparseable bytes, a 2xx whose body is an
 * `UploadErrorBody` (`schema_version`/`code`) - is NOT an acceptance. The resolver then keeps ownership:
 * an immutable capture is always safe to re-post, and it is never safe to drop audio the server may not
 * have stored.
 */
class CaptureReceiptValidator(
    /** The digest the queue recorded for this capture when it durably published the entry. */
    private val recordedDigest: (captureId: String) -> String?,
) : ContributionAckResolver.AcceptValidator {

    override fun validate(captureId: String, payload: ByteArray?, httpStatus: Int?): Boolean {
        val body = payload ?: return false
        val receipt = runCatching { CanonicalReceipt.parse(body) }.getOrNull() ?: return false
        if (receipt.schemaVersion != SUPPORTED_SCHEMA_VERSION) return false
        if (receipt.captureId != captureId) return false
        if (receipt.status !in ACCEPTANCE_STATUSES) return false
        // The owning contract PAIRS each success status with one HTTP status: 201 for a first acceptance
        // and 200 for a duplicate. A receipt whose status disagrees with the response status it arrived on
        // is a malformed pair, and releasing on it would drop audio on a server's inconsistency.
        // `null` means the caller could not supply a status, which is not proof of a good pair.
        if (httpStatus == null) return false
        if (STATUS_FOR_HTTP[httpStatus] != receipt.status) return false
        if (receipt.receiptId.isEmpty()) return false
        // The receipt timestamps must be REAL ISO-8601 instants: a nonempty placeholder such as "t"
        // authorizes deletion just as wrongly as a wrong digest. A timestamp that cannot parse is a
        // malformed receipt, not an acceptance.
        val acceptedAt = runCatching { java.time.Instant.parse(receipt.acceptedAt) }.getOrNull() ?: return false
        val retentionExpiresAt = runCatching { java.time.Instant.parse(receipt.retentionExpiresAt) }.getOrNull() ?: return false
        if (!retentionExpiresAt.isAfter(acceptedAt)) return false
        val expected = recordedDigest(captureId) ?: return false
        // The digest must match EXACTLY: a truncated or case-folded echo is a different value, not a match.
        return receipt.recordSha256 == expected
    }

    companion object {
        /** The wire contract's schema version for a receipt body. */
        const val SUPPORTED_SCHEMA_VERSION = 1L

        /** The Worker's two SUCCESS statuses. `duplicate` means "already accepted", never "rejected". */
        val ACCEPTANCE_STATUSES = setOf("accepted", "duplicate")

        /** The Worker's own pairing: a first acceptance is 201, a duplicate is 200 (upload_route.ts). */
        val STATUS_FOR_HTTP = mapOf(201 to "accepted", 200 to "duplicate")

        /** Digest lookup for a store: the digest the queue recorded for the entry it is holding. */
        fun forStore(store: ContributionStore): CaptureReceiptValidator = CaptureReceiptValidator(store::recordedDigest)
    }
}

/**
 * The receipt shape the client is willing to read, parsed with the SAME strict Moshi [JsonReader] pattern
 * the account projection uses (spec bcace44b): the parser owns syntax strictness (quoted names only, no
 * single quotes, no trailing junk, no bare strings where literals are required), this layer owns the
 * contract (decoded-key duplicate detection, exact field set, exact value types).
 *
 * `schema_version` must be the NUMBER 1 - a quoted `"1"` is a different type and is rejected. Every other
 * field must be a non-empty STRING: `null` is rejected rather than stringified, and control characters or
 * escapes are rejected rather than interpreted.
 */
internal data class CanonicalReceipt(
    val schemaVersion: Long,
    val captureId: String,
    val recordSha256: String,
    val status: String,
    val receiptId: String,
    val acceptedAt: String,
    val retentionExpiresAt: String,
) {
    companion object {
        private val ALLOWED_KEYS = setOf(
            "schema_version",
            "capture_id",
            "record_sha256",
            "status",
            "receipt_id",
            "accepted_at",
            "retention_expires_at",
        )

        fun parse(body: ByteArray): CanonicalReceipt? {
            val fields = parseFields(body) ?: return null
            // schema_version must be the NUMBER 1: a quoted value is a different type (parseFields would
            // keep it as a string, and toLongOrNull would happily accept it), so it is checked here.
            if (fields.keys != ALLOWED_KEYS) return null
            if (!isNumericField("schema_version")) return null
            val version = fields["schema_version"]?.toLongOrNull() ?: return null
            return CanonicalReceipt(
                schemaVersion = version,
                captureId = fields["capture_id"] ?: return null,
                recordSha256 = fields["record_sha256"] ?: return null,
                status = fields["status"] ?: return null,
                receiptId = fields["receipt_id"] ?: return null,
                acceptedAt = fields["accepted_at"] ?: return null,
                retentionExpiresAt = fields["retention_expires_at"] ?: return null,
            )
        }

        // Strict flat-object parse into decoded fields. Numbers are returned as their exact lexeme and only
        // schema_version may be numeric; every other value must be a non-empty string without control
        // characters. Returns null on ANY deviation - this never guesses.
        // Keys whose value was a NUMBER token, so a quoted number is rejected as a type mismatch.
        private var numericKeys: Set<String> = emptySet()

        fun parseFields(body: ByteArray): Map<String, String>? {
            val reader = JsonReader.of(Buffer().write(body))
            val fields = LinkedHashMap<String, String>()
            val numeric = HashSet<String>()
            return try {
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    if (key in fields) return null // decoded-key duplicate detection
                    val next = reader.peek()
                    when {
                        next == JsonReader.Token.STRING -> {
                            // schema_version must be a NUMBER: a quoted "1" is a type mismatch.
                            if (key == "schema_version") return null
                            val value = reader.nextString()
                            if (value.isEmpty()) return null
                            if (value.any { it.isISOControl() }) return null
                            fields[key] = value
                        }

                        // Only schema_version is numeric in the contract; any other number is a type error.
                        next == JsonReader.Token.NUMBER && key == "schema_version" -> {
                            fields[key] = reader.nextString()
                            numeric += key
                        }

                        else -> return null // booleans, nulls, objects, arrays and stray numbers are rejected
                    }
                }
                reader.endObject()
                if (reader.peek() != JsonReader.Token.END_DOCUMENT) return null // trailing junk
                numericKeys = numeric
                // A receipt body is exactly the contract's field set: unknown keys are rejected.
                if (fields.keys != ALLOWED_KEYS) return null
                fields
            } catch (malformed: Throwable) {
                null
            }
        }

        // True only when [key] was parsed from a NUMBER token in the last parseFields call.
        fun isNumericField(key: String): Boolean = key in numericKeys
    }
}
