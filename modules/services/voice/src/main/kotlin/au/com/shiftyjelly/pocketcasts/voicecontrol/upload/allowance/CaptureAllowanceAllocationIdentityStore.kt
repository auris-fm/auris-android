package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

import java.io.File
import java.util.UUID

/**
 * The DURABLE allocation identity: `(request_id, enrollment_epoch, accounting_day)`.
 *
 * The server is idempotent on `request_id` alone, which makes this the client's most expensive
 * obligation: a *fresh* UUID per attempt does not retry, it allocates a NEW batch (reviewer
 * 3ba1e605, cloud a42eea42). So the identity is persisted BEFORE any transport work and reused
 * for every restart, retry and unknown transport outcome, and it is replaced only in the two
 * cases the server names as advancement:
 *
 * | server says                          | client does                                  |
 * |--------------------------------------|----------------------------------------------|
 * | `200 issued` / `200 replayed`        | persist `(id, epoch, day)`, use the batch    |
 * | `503 unavailable`                    | retry the SAME id                            |
 * | `429 exhausted`                      | RETAIN the id, stop allocating for the day   |
 * | `410 window expired`                 | ADVANCE (server-confirmed expiry)            |
 * | `409` / `403` / `422`                | terminal for that id; advance with a new one |
 *
 * The epoch is part of the identity because reusing an id across an enrollment change is a `409`
 * the client should never provoke; the day is part of it because a persisted id silently becomes
 * a `410` at midnight UTC. Advancing on the CLIENT's own calendar is never allowed — device
 * clocks are unvalidated input, and only a server `410` (or proven local batch exhaustion)
 * authorises advancement.
 *
 * Storage is a single small file written atomically (temp + rename): a torn write must never
 * leave an id that was never sent, because the id itself is what makes the retry safe.
 */
class CaptureAllowanceAllocationIdentityStore(
    private val file: File,
) {
    /** The persisted identity, or null when none has been minted for the current epoch. */
    fun current(): Identity? = synchronized(lock) {
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        IdentityEncoding.decode(text)
    }

    /**
     * Returns the identity to use for an attempt against [enrollmentEpoch]: the persisted one when
     * its epoch still matches, a fresh one otherwise. Persisted BEFORE the caller sends anything.
     *
     * THE DAY IS DELIBERATELY NOT PART OF THE KEY (cloud bcc9a42a). The accounting day is
     * server-side state; persisting it invites comparing it against this device's clock, and a
     * device calendar must never decide advancement. What moves the window is the server's `410`,
     * which [advance] honours. A persisted id from another day is therefore reused, answers
     * `410`, and is advanced then -- one round trip, no charge, no client-side calendar.
     */
    fun acquire(enrollmentEpoch: String): Identity = synchronized(lock) {
        val existing = current()
        if (existing != null) {
            if (existing.enrollmentEpoch == enrollmentEpoch) {
                // A RETAINED id is returned too: after a 429 the allocation is gone, and minting a
                // fresh id would refuse identically while storing another server row (cloud
                // a42eea42). Only a 410 -- or a new enrollment epoch -- replaces it.
                return existing
            }
            // A DIFFERENT EPOCH: the persisted id now belongs to an enrollment that no longer
            // exists, and reusing it can only produce a 409 (cloud bcc9a42a). Discard and mint.
            file.delete()
        }
        IdentityEncoding.encode(file, Identity(UUID.randomUUID().toString(), enrollmentEpoch))
    }

    /**
     * ADVANCEMENT, and the only place the identity changes on the server's word: `410` (window
     * expired) or `409` (the persisted id was presented with an epoch it no longer belongs to --
     * a client state-machine bug worth logging loudly, never a transient to retry).
     */
    fun advance() = synchronized(lock) {
        file.delete()
        file.delete() // idempotent: absent is the same state as advanced
        Unit
    }

    /**
     * `429`: the day's allocation is gone. The id is RETAINED so the client's refusal stays
     * observable and no new rows are created; only a server `410` or a new day advances it.
     */
    fun retain(identity: Identity) = synchronized(lock) {
        IdentityEncoding.encode(file, identity.copy(retired = true))
    }

    private val lock = Any()

    data class Identity(
        val requestId: String,
        val enrollmentEpoch: String,
        val retired: Boolean = false,
    ) {
        companion object {
            /** Strict: an unparseable record is treated as ABSENT, never as a usable identity. */
            fun parse(text: String): Identity? = IdentityEncoding.decode(text)
        }
    }
}

/** File encoding for a persisted identity. Strict: an unparseable record is ABSENT, not usable. */
private object IdentityEncoding {
    const val VERSION = "allowance-identity/1"

    fun encode(file: File, identity: CaptureAllowanceAllocationIdentityStore.Identity): CaptureAllowanceAllocationIdentityStore.Identity {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.parentFile?.mkdirs()
        tmp.writeText(
            "$VERSION\n" +
                "${identity.requestId}\n" +
                "${identity.enrollmentEpoch}\n" +
                "${identity.retired}\n",
        )
        // ATOMIC REPLACE: the rename is the commit point, so a crash never leaves a half-written
        // identity that would silently become a different request.
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IllegalStateException("allowance identity could not be persisted")
        }
        return identity
    }

    fun decode(text: String): CaptureAllowanceAllocationIdentityStore.Identity? {
        val lines = text.lines()
        if (lines.size < 4 || lines[0] != VERSION) return null
        val id = lines[1]
        val epoch = lines[2]
        val retired = lines[3].toBooleanStrictOrNull() ?: return null
        // The id must be a UUID: anything else could not have been sent by this client.
        if (runCatching { UUID.fromString(id) }.isFailure) return null
        if (epoch.isEmpty()) return null
        return CaptureAllowanceAllocationIdentityStore.Identity(id, epoch, retired)
    }
}
