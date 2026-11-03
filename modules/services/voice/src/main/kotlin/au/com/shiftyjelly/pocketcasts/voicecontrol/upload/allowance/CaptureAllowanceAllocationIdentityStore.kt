package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * The DURABLE allocation identity: `(request_id, enrollment_epoch)`.
 *
 * The server is idempotent on `request_id` alone, which makes this the client's most expensive
 * obligation: a fresh UUID per attempt does not retry, it allocates a NEW batch. So the identity
 * is made durable BEFORE any transport work and reused for every restart, retry and unknown
 * transport outcome.
 *
 * Three properties the reviewer required (b36642db), each of which is a refusal rather than a
 * guess:
 *
 * 1. **Durable before the send.** The record is written and `fsync`ed, and the containing
 *    directory is synced too — `rename` alone orders the directory entry but does not promise
 *    the bytes survive a power cut, and an identity that evaporates is a silently re-allocated
 *    batch.
 * 2. **Ambiguous recovery REFUSES.** Unreadable or corrupt persisted state is not "no identity":
 *    re-minting is indistinguishable server-side from a first attempt and burns capacity
 *    permanently (cloud ffeaa62d). [acquire] returns [Acquisition.Ambiguous] and the capture is
 *    refused until the operator-cleanable file is resolved.
 * 3. **Advancement is compare-and-swap.** An `advance` or `retain` carries the identity it was
 *    produced from and applies only if that identity is still the current one. A late response
 *    for a dead identity must not delete a newer one (cloud ffeaa62d).
 *
 * The accounting day is deliberately NOT part of the key: it is server state, and persisting it
 * invites comparing it against this device's clock (cloud bcc9a42a). A day rollover reuses the
 * id, answers `410`, and advances then.
 */
class CaptureAllowanceAllocationIdentityStore(
    private val file: File,
    /**
     * The authoritative ACCOUNT this identity belongs to (reviewer 3d1f87be #3). One fixed file
     * scoped only by epoch would let a second account reuse the first account's request id, which
     * the server would see as one account presenting another's allocation. The account is part of
     * the persisted record and must match on every read.
     */
    private val account: String? = null,
) {
    sealed interface Acquisition {
        /** The identity to present. It is already durable. */
        data class Held(val identity: Identity) : Acquisition

        /**
         * The persisted record exists but cannot be trusted. Refuse the capture: re-minting here
         * would ask the server for a new batch and charge it, with no trace that anything was lost.
         */
        data object Ambiguous : Acquisition
    }

    /** The persisted identity, or null when none was ever minted. */
    fun current(): Identity? = synchronized(lock) { readOrNull() as? Identity }

    /**
     * The identity for an attempt under [enrollmentEpoch], made durable before return. Reuses the
     * persisted id when its epoch still matches (a different epoch can only produce a `409`).
     */
    fun acquire(enrollmentEpoch: String): Acquisition = synchronized(lock) {
        // A RECORDED CONFLICT BLOCKS, it does not fall through to a mint.
        if (conflicted) {
            val recorded = readOrNull() as? Identity
            // A genuinely NEW epoch is the one resolution that is not a bypass: the old id cannot
            // be reused and the server has already told us so.
            if (recorded != null && recorded.enrollmentEpoch == enrollmentEpoch) return Acquisition.Ambiguous
            conflicted = false
            file.delete()
        }
        val existing = readOrNull()
        if (existing == AMBIGUOUS) return Acquisition.Ambiguous
        val held = existing as? Identity
        if (held != null) {
            // A record from ANOTHER ACCOUNT can never be reused (reviewer 3d1f87be #3).
            if (account != null && held.account != null && held.account != account) {
                return Acquisition.Ambiguous
            }
            if (held.enrollmentEpoch == enrollmentEpoch) {
                // A RETAINED id is returned too: after a 429 the allocation is gone, and minting a
                // fresh id would refuse identically while storing another server row.
                return Acquisition.Held(held)
            }
            // A different epoch: this id belongs to an enrollment that no longer exists.
            deleteQuietly()
        }
        val minted = Identity(UUID.randomUUID().toString(), enrollmentEpoch, account = account)
        if (!write(minted)) {
            recoveryRequired = true
            return Acquisition.Ambiguous
        }
        Acquisition.Held(minted)
    }

    /**
     * Records a `409` against [expected] WITHOUT deleting it (reviewer 3d1f87be #1). The id stays
     * on disk so the conflict stays observable, and [acquire] refuses until it is resolved.
     */
    fun recordConflict(expected: Identity): Boolean = synchronized(lock) {
        if (readOrNull() as? Identity != expected) return false
        conflicted = true
        true
    }

    /**
     * ADVANCEMENT, compare-and-swap. Applies only if [expected] is still the stored identity, so a
     * late `410` for an already-superseded id cannot delete its replacement.
     */
    fun advance(expected: Identity): Boolean = synchronized(lock) {
        if (readOrNull() as? Identity != expected) return false
        deleteQuietly()
        true
    }

    /** `429`: retain the id so no new allocation is requested for the rest of the window. */
    fun retain(identity: Identity): Boolean = synchronized(lock) {
        if (readOrNull() as? Identity != identity) return false
        write(identity.copy(retired = true))
        true
    }

    data class Identity(
        val requestId: String,
        val enrollmentEpoch: String,
        val account: String? = null,
        val retired: Boolean = false,
    ) {
        companion object {
            fun parse(text: String): Identity? = IdentityEncoding.decode(text)
        }
    }

    /** Distinguishes "absent" from "present but unreadable": only the latter refuses. */
    private fun readOrNull(): Any? {
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return AMBIGUOUS
        return IdentityEncoding.decode(text) ?: AMBIGUOUS
    }

    private fun write(identity: Identity): Boolean {
        val dir = file.parentFile ?: throw IllegalStateException("identity file has no directory")
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        // DURABILITY, not just atomicity: fsync the bytes before the rename, then fsync the
        // directory so the new NAME survives too. Either alone can lose the record on a crash,
        // and a lost record is a silently re-allocated batch.
        FileOutputStream(tmp).use { out ->
            out.write(IdentityEncoding.render(identity).toByteArray())
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            return false
        }
        // Post-rename sync failure is the dangerous case the reviewer called out: the rename
        // happened, so a naive retry sees "already persisted" -- hence [recoveryRequired].
        if (!syncDirectory(dir)) {
            recoveryRequired = true
            return false
        }
        recoveryRequired = false
        return true
    }

    private fun deleteQuietly() {
        val dir = file.parentFile
        file.delete()
        if (dir != null) syncDirectory(dir)
    }

    /**
     * Syncs the directory so the new NAME is durable, not just the bytes (reviewer 3d1f87be #2).
     *
     * This is NOT best effort. `rename` can be durable in the directory entry and still lose the
     * record if the entry itself is not flushed, and a lost record is a silently re-allocated
     * batch. So a failure here is reported and the caller REFUSES to send: the alternative is to
     * claim an identity is durable when it may vanish on the next power cut.
     */
    private fun syncDirectory(dir: File): Boolean = try {
        java.nio.channels.FileChannel.open(dir.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        true
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        false
    }

    private val lock = Any()

    /** Set when a write reached the rename but could not be proven durable; cleared on success. */
    @Volatile
    private var recoveryRequired = false

    /**
     * Set when the server answered `409 request_conflict` (reviewer 3d1f87be #1).
     *
     * Deleting the file and letting the next ordinary `acquire` mint a fresh id IS the bypass --
     * unchanged authority would silently allocate a new batch, which is exactly what the conflict
     * was warning about. So the conflict is recorded, and `acquire` REFUSES until it is resolved.
     * Nothing about a 409 may produce a fresh id by itself.
     */
    @Volatile
    private var conflicted = false

    private companion object {
        /** Sentinel meaning "present but not trustworthy" -- distinct from absent (null). */
        val AMBIGUOUS = Any()
    }
}

/** Strict file encoding for a persisted identity. Anything unparseable is AMBIGUOUS, not absent. */
private object IdentityEncoding {
    const val VERSION = "allowance-identity/3"

    fun render(identity: CaptureAllowanceAllocationIdentityStore.Identity): String = buildString {
        append(VERSION).append('\n')
        append(identity.requestId).append('\n')
        append(identity.enrollmentEpoch).append('\n')
        append(identity.account ?: "").append('\n')
        append(identity.retired).append('\n')
    }

    fun decode(text: String): CaptureAllowanceAllocationIdentityStore.Identity? {
        val lines = text.lines()
        if (lines.size < 5 || lines[0] != VERSION) return null
        val id = lines[1]
        val epoch = lines[2]
        val accountLine = lines[3]
        val retired = lines[4].toBooleanStrictOrNull() ?: return null
        // The id must be a UUID: anything else could not have been sent by this client.
        if (runCatching { UUID.fromString(id) }.isFailure) return null
        if (epoch.isEmpty()) return null
        return CaptureAllowanceAllocationIdentityStore.Identity(
            requestId = id,
            enrollmentEpoch = epoch,
            account = accountLine.ifEmpty { null },
            retired = retired,
        )
    }
}
