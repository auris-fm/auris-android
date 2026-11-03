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
        val existing = readOrNull()
        if (existing == AMBIGUOUS) return Acquisition.Ambiguous
        val held = existing as? Identity
        if (held != null) {
            if (held.enrollmentEpoch == enrollmentEpoch) {
                // A RETAINED id is returned too: after a 429 the allocation is gone, and minting a
                // fresh id would refuse identically while storing another server row.
                return Acquisition.Held(held)
            }
            // A different epoch: this id belongs to an enrollment that no longer exists.
            deleteQuietly()
        }
        val minted = Identity(UUID.randomUUID().toString(), enrollmentEpoch)
        write(minted)
        Acquisition.Held(minted)
    }

    /**
     * ADVANCEMENT, compare-and-swap. Applies only if [expected] is still the stored identity, so a
     * late `409`/`410` for an already-superseded id cannot delete its replacement. `410` is the
     * server's window moving; `409` is a client state-machine bug (report it, then discard).
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

    private fun write(identity: Identity) {
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
            throw IllegalStateException("allowance identity could not be persisted")
        }
        syncDirectory(dir)
    }

    private fun deleteQuietly() {
        val dir = file.parentFile
        file.delete()
        if (dir != null) syncDirectory(dir)
    }

    private fun syncDirectory(dir: File) {
        // Best effort: some filesystems refuse to open a directory for sync. A failure here is
        // not a reason to fail an otherwise durable write, but it is never silently assumed.
        runCatching {
            java.nio.channels.FileChannel.open(dir.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        }
    }

    private val lock = Any()

    private companion object {
        /** Sentinel meaning "present but not trustworthy" -- distinct from absent (null). */
        val AMBIGUOUS = Any()
    }
}

/** Strict file encoding for a persisted identity. Anything unparseable is AMBIGUOUS, not absent. */
private object IdentityEncoding {
    const val VERSION = "allowance-identity/2"

    fun render(identity: CaptureAllowanceAllocationIdentityStore.Identity): String = "$VERSION\n${identity.requestId}\n${identity.enrollmentEpoch}\n${identity.retired}\n"

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
