package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

/**
 * Production [ContributionAckResolver.AcceptValidator]: decides, from the server's response body alone,
 * whether it proves THIS capture was durably accepted (spec task73; reviewer "transport/ack composition"
 * finding).
 *
 * The capture id is the only deduplication key the Worker and the client share, and the SHA-256 digest is
 * the only identity evidence the queue records for the bytes it holds. An acceptance therefore must:
 *
 *  1. be well-formed canonical JSON of an OBJECT,
 *  2. echo the SAME `capture_id`,
 *  3. echo the SAME `sha256` digest as the capture on disk, and
 *  4. report a status that means "stored", not merely "received".
 *
 * Anything else - a different id, a different digest, a missing field, unparseable bytes, a status such
 * as `accepted` that only means "the request arrived" - is NOT an acceptance. The resolver then treats the
 * response as ambiguous, which retains ownership: it is always safe to re-post an immutable capture, and
 * never safe to drop audio the server may not have stored.
 */
class CaptureReceiptValidator(
    /** The digest the queue recorded for this capture when it durably published the entry. */
    private val recordedDigest: (captureId: String) -> String?,
) : ContributionAckResolver.AcceptValidator {

    override fun validate(captureId: String, payload: ByteArray?): Boolean {
        val body = payload ?: return false
        val receipt = runCatching { CanonicalReceipt.parse(body) }.getOrNull() ?: return false
        if (receipt.captureId != captureId) return false
        if (receipt.status !in STORED_STATUSES) return false
        val expected = recordedDigest(captureId) ?: return false
        // Constant-time-ish comparison is unnecessary here (neither value is a secret), but the digest
        // must match EXACTLY: a truncated or case-folded echo is a different value, not a match.
        return receipt.sha256 == expected
    }

    companion object {
        /** Statuses that assert the Worker stored the capture. `accepted`/`received` are NOT included. */
        val STORED_STATUSES = setOf("stored", "duplicate")

        /** Digest lookup for a store: the digest the queue recorded for the entry it is holding. */
        fun forStore(store: ContributionStore): CaptureReceiptValidator = CaptureReceiptValidator(store::recordedDigest)
    }
}

/**
 * The minimal canonical receipt shape the client is willing to read: a flat JSON object whose values are
 * strings. Parsing is strict and total - anything not matching this shape yields null, never a guess.
 */
internal data class CanonicalReceipt(val captureId: String, val sha256: String, val status: String) {
    companion object {
        fun parse(body: ByteArray): CanonicalReceipt? {
            val text = String(body, Charsets.UTF_8).trim()
            if (!text.startsWith("{") || !text.endsWith("}")) return null
            val fields = mutableMapOf<String, String>()
            for (raw in text.removePrefix("{").removeSuffix("}").split(",")) {
                val entry = raw.trim()
                if (entry.isEmpty()) continue
                val colon = entry.indexOf(':')
                if (colon <= 0) return null
                val key = unquote(entry.substring(0, colon).trim()) ?: return null
                val value = unquote(entry.substring(colon + 1).trim()) ?: return null
                if (key in fields) return null // duplicate keys are ambiguous, not "last wins"
                fields[key] = value
            }
            val id = fields["capture_id"] ?: return null
            val digest = fields["sha256"] ?: return null
            val status = fields["status"] ?: return null
            return CanonicalReceipt(id, digest, status)
        }

        private fun unquote(token: String): String? {
            if (token.length < 2 || !token.startsWith('"') || !token.endsWith('"')) return null
            val inner = token.substring(1, token.length - 1)
            // Escapes are rejected rather than interpreted: no receipt field needs them, and accepting
            // them would let a crafted body smuggle a different id past the comparison.
            if (inner.contains('\\') || inner.contains('"')) return null
            return inner
        }
    }
}
