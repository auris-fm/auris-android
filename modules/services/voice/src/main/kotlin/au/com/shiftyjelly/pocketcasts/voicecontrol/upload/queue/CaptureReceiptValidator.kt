package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

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

    override fun validate(captureId: String, payload: ByteArray?): Boolean {
        val body = payload ?: return false
        val receipt = runCatching { CanonicalReceipt.parse(body) }.getOrNull() ?: return false
        if (receipt.schemaVersion != SUPPORTED_SCHEMA_VERSION) return false
        if (receipt.captureId != captureId) return false
        if (receipt.status !in ACCEPTANCE_STATUSES) return false
        if (receipt.receiptId.isEmpty() || receipt.acceptedAt.isEmpty() || receipt.retentionExpiresAt.isEmpty()) return false
        val expected = recordedDigest(captureId) ?: return false
        // The digest must match EXACTLY: a truncated or case-folded echo is a different value, not a match.
        return receipt.recordSha256 == expected
    }

    companion object {
        /** The wire contract's schema version for a receipt body. */
        const val SUPPORTED_SCHEMA_VERSION = 1L

        /** The Worker's two SUCCESS statuses. `duplicate` means "already accepted", never "rejected". */
        val ACCEPTANCE_STATUSES = setOf("accepted", "duplicate")

        /** Digest lookup for a store: the digest the queue recorded for the entry it is holding. */
        fun forStore(store: ContributionStore): CaptureReceiptValidator = CaptureReceiptValidator(store::recordedDigest)
    }
}

/**
 * The receipt shape the client is willing to read. Parsing is strict and total: anything not matching the
 * wire contract yields null, never a guess.
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
        fun parse(body: ByteArray): CanonicalReceipt? {
            val text = String(body, Charsets.UTF_8).trim()
            if (!text.startsWith("{") || !text.endsWith("}")) return null
            val fields = mutableMapOf<String, String>()
            for ((key, rawValue) in members(text.removePrefix("{").removeSuffix("}")) ?: return null) {
                if (key in fields) return null // duplicate keys are ambiguous, not "last wins"
                fields[key] = rawValue
            }
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

        /** Flat field view of any JSON object body (used for error bodies too); null if malformed. */
        fun parseFields(body: ByteArray): Map<String, String>? {
            val text = String(body, Charsets.UTF_8).trim()
            if (!text.startsWith("{") || !text.endsWith("}")) return null
            val fields = mutableMapOf<String, String>()
            for ((key, value) in members(text.removePrefix("{").removeSuffix("}")) ?: return null) {
                if (key in fields) return null
                fields[key] = value
            }
            return fields
        }

        // Split the object body into key/raw-value members at TOP-LEVEL commas only: a comma or colon
        // inside a string value is not a separator. Returns null on any structural surprise.
        private fun members(body: String): List<Pair<String, String>>? {
            val out = mutableListOf<Pair<String, String>>()
            var index = 0
            while (index < body.length) {
                while (index < body.length && body[index].isWhitespace()) index++
                if (index >= body.length) break
                if (body[index] != '"') return null
                val keyEnd = closingQuote(body, index) ?: return null
                val key = unquote(body.substring(index, keyEnd + 1)) ?: return null
                index = keyEnd + 1
                while (index < body.length && body[index].isWhitespace()) index++
                if (index >= body.length || body[index] != ':') return null
                index++
                while (index < body.length && body[index].isWhitespace()) index++
                val valueEnd = if (index < body.length && body[index] == '"') {
                    closingQuote(body, index) ?: return null
                } else {
                    var scan = index
                    while (scan < body.length && body[scan] != ',') scan++
                    scan - 1
                }
                val raw = body.substring(index, valueEnd + 1).trim()
                // String members are unquoted here (escapes rejected); bare members keep their text.
                val value = if (raw.startsWith("\"")) unquote(raw) ?: return null else raw
                if (value.isEmpty()) return null
                out += key to value
                index = valueEnd + 1
                while (index < body.length && body[index].isWhitespace()) index++
                if (index < body.length) {
                    if (body[index] != ',') return null
                    index++
                }
            }
            return out
        }

        private fun closingQuote(text: String, open: Int): Int? {
            var scan = open + 1
            while (scan < text.length) {
                if (text[scan] == '\\') return null // escapes are rejected, never interpreted
                if (text[scan] == '"') return scan
                scan++
            }
            return null
        }

        private fun unquote(token: String): String? {
            if (token.length < 2 || !token.startsWith('"') || !token.endsWith('"')) return null
            val inner = token.substring(1, token.length - 1)
            // Escapes are rejected rather than interpreted: no receipt field needs them, and accepting
            // them would let a crafted body smuggle a different id past the comparison.
            if (inner.contains('\\')) return null
            return inner
        }
    }
}
