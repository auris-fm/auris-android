package au.com.shiftyjelly.pocketcasts.voicecontrol.playback.appops

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject
import org.json.JSONTokener

/**
 * File-backed [AppOperationJournal.Store]. Takes a root [File] seam so host tests exercise the real
 * implementation in a temporary directory.
 *
 * Rules:
 * - claim, capacity admission, settlement and reads run inside ONE serialized, cross-instance
 *   boundary: a shared per-root monitor AND an OS advisory file lock on the root. If that lock cannot
 *   be acquired the operation FAILS CLOSED and does not run at all;
 * - the capacity decision is taken INSIDE [claim], not before it;
 * - a claim is an exclusive create via hard link; a losing claimant never deletes a record it did
 *   not create and never overwrites one;
 * - replacement is atomic (unique staged file, fsync, atomic move), never a truncate in place;
 * - every fsync/link/rename failure refuses the operation: durability is never silently downgraded;
 * - unresolved-claim handling is deliberately simple: a durable claim without an outcome replays as
 *   `unknown` with reason `execution_unresolved`, concurrent or recovered, and is never re-executed;
 * - missing storage is `null`; corrupt, oversized, wrongly shaped or unreadable storage raises
 *   [AppOperationJournal.CorruptStorageException] so it can never be mistaken for an empty journal.
 */
class AppOperationJournalStore(
    private val root: File,
    /** Retention bound: at most this many records; new operations are refused beyond it. */
    private val maxRecords: Int = 512,
) : AppOperationJournal.Store {
    val session: String = UUID.randomUUID().toString()

    private fun <T> locked(block: () -> T): T = synchronized(rootLock(rootCanonicalPath())) {
        withRootLock { block() }
    }

    private fun rootCanonicalPath(): String = try {
        root.canonicalPath
    } catch (_: Exception) {
        root.absolutePath
    }

    /**
     * Runs [block] while holding the OS lock on the root. FAILS CLOSED: when the lock cannot be
     * created or acquired, the block does not run at all. The channel is closed as well as released.
     */
    private fun <T> withRootLock(block: () -> T): T {
        val lockFile = File(root, LOCK_NAME)
        if (!root.exists() && !root.mkdirs()) throw LockUnavailable()
        if (lockFile.isDirectory) throw LockUnavailable()
        val channel = try {
            FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        } catch (_: Exception) {
            throw LockUnavailable()
        }
        var lock: FileLock? = null
        try {
            lock = try {
                channel.lock()
            } catch (_: Exception) {
                throw LockUnavailable()
            }
            return block()
        } finally {
            try {
                lock?.release()
            } catch (_: Exception) {
            }
            try {
                channel.close()
            } catch (_: Exception) {
            }
        }
    }

    /** Thrown internally when the OS lock cannot be held; every public entry point fails closed. */
    private class LockUnavailable : Exception()

    private fun recordFile(claim: AppOperationJournal.Claim): File = File(root, "${recordName(claim)}.json")

    private fun recordName(claim: AppOperationJournal.Claim): String = MessageDigest.getInstance("SHA-256").digest(claim.key.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    override fun claim(claim: AppOperationJournal.Claim, contentsDigest: String): Boolean = claimOperation(claim, contentsDigest) is AppOperationJournal.ClaimResult.Claimed

    /**
     * The exclusive create, the operation-id binding check and the capacity decision all inside ONE
     * root-locked section.
     *
     * The binding check is a scan of the owner's records under the SAME lock as the create: two turns
     * presenting the same operation id therefore serialize, and the second one sees the first one's
     * record instead of both observing "not bound".
     */
    override fun claimOperation(
        claim: AppOperationJournal.Claim,
        contentsDigest: String,
    ): AppOperationJournal.ClaimResult = try {
        locked<AppOperationJournal.ClaimResult> {
            if (!root.exists() && !root.mkdirs()) return@locked AppOperationJournal.ClaimResult.CapacityExhausted
            val target = recordFile(claim)
            if (target.exists()) return@locked AppOperationJournal.ClaimResult.AlreadyClaimed
            // An operation id is bound to the turn that first claimed it. A different turn presenting
            // the same id is refused here, before any effect, not by hashing into a different key.
            // A binding that cannot be READ is not an absent binding. Skipping an unreadable record
            // would erase the protection the record exists for and let a second turn execute the same
            // operation id, so any unreadable or unenumerable state refuses the claim outright.
            val boundElsewhere = boundKeyLocked(claim.operationId, claim.key)
            if (boundElsewhere != null) return@locked AppOperationJournal.ClaimResult.BoundElsewhere(boundElsewhere)
            if (recordCount() >= maxRecords) return@locked AppOperationJournal.ClaimResult.CapacityExhausted
            if (!linkNewRecord(target, baseRecord(claim, contentsDigest))) {
                return@locked AppOperationJournal.ClaimResult.CapacityExhausted
            }
            AppOperationJournal.ClaimResult.Claimed
        }
    } catch (_: LockUnavailable) {
        AppOperationJournal.ClaimResult.CapacityExhausted
    } catch (_: AppOperationJournal.CorruptStorageException) {
        // Unreadable journal state is NOT capacity. The caller must answer it as corrupt storage
        // rather than as a transient refusal, and must never treat it as "nothing is bound".
        AppOperationJournal.ClaimResult.Unreadable
    }

    /**
     * The locked-section form of the operation-id binding lookup.
     *
     * Global rather than owner-scoped: the operation ID is the identity, so a record carrying this id
     * under ANY owner/conversation/turn is the binding, and a different owner presenting the same id
     * must be refused rather than served a fresh claim under a key that merely hashes differently.
     */
    private fun boundKeyLocked(operationId: String, ownKey: String): String? {
        // `listFiles()` returns null for a directory that cannot be enumerated -- I/O failure or
        // absence -- and both mean the set of existing bindings is unknown, which must not read as
        // "nothing is bound".
        val entries = root.listFiles() ?: throw AppOperationJournal.CorruptStorageException("journal directory unreadable")
        for (file in entries) {
            if (!file.isFile || !file.name.endsWith(".json")) continue
            // A record whose identity cannot be established refuses the claim. Specifically: a record
            // that will not parse, has no `operation_id`, or carries a malformed identity may BE the
            // binding for this operation id, and there is no safe way to decide that it is not.
            val record = try {
                JSONObject(JSONTokener(file.readText()))
            } catch (e: Exception) {
                throw AppOperationJournal.CorruptStorageException("unreadable record ${file.name}: ${e.message}")
            }
            val storedId = record.strictOrNull("operation_id")
                ?: throw AppOperationJournal.CorruptStorageException("record ${file.name} without operation id")
            val storedKey = record.strictOrNull("key")
                ?: throw AppOperationJournal.CorruptStorageException("record ${file.name} without key")
            if (storedId != operationId) continue
            if (storedKey != ownKey) return storedKey
        }
        return null
    }

    override fun markInProgress(claim: AppOperationJournal.Claim): Boolean = try {
        locked {
            try {
                val target = recordFile(claim)
                val obj = readObject(target) ?: return@locked false
                requireIdentity(obj, claim)
                obj.put("in_progress", true).put("session", session)
                replaceDurably(target, obj)
            } catch (_: Exception) {
                false
            }
        }
    } catch (_: LockUnavailable) {
        false
    }

    override fun storeOutcome(
        claim: AppOperationJournal.Claim,
        contentsDigest: String,
        outcome: String,
        reason: String?,
        state: JSONObject?,
    ): Boolean = try {
        locked {
            try {
                val target = recordFile(claim)
                val obj = readObject(target) ?: return@locked false
                requireIdentity(obj, claim)
                if (obj.optString("digest") != contentsDigest) return@locked false
                val recorded = obj.strictOrNull("outcome")
                if (recorded != null) {
                    // A settled result is immutable: neither outcome NOR reason/state may be rewritten.
                    return@locked recorded == outcome &&
                        obj.strictOrNull("reason") == reason &&
                        jsonEquals(obj.optJSONObject("state"), state)
                }
                obj.put("in_progress", false).put("outcome", outcome).put("settled_by", session)
                if (reason != null) obj.put("reason", reason) else obj.remove("reason")
                if (state != null) obj.put("state", JSONObject(state.toString())) else obj.remove("state")
                replaceDurably(target, obj)
            } catch (_: Exception) {
                false
            }
        }
    } catch (_: LockUnavailable) {
        false
    }

    override fun read(claim: AppOperationJournal.Claim): AppOperationJournal.Record? = try {
        locked {
            val obj = readObject(recordFile(claim)) ?: return@locked null
            requireIdentity(obj, claim)
            AppOperationJournal.Record(
                contentsDigest = obj.strictOrNull("digest")
                    ?: throw AppOperationJournal.CorruptStorageException("record without digest"),
                inProgress = obj.optBoolean("in_progress"),
                outcome = obj.strictOrNull("outcome")?.also { validateOutcomeShape(obj, it) },
                reason = obj.strictOrNull("reason"),
                state = obj.optJSONObject("state")?.let { JSONObject(it.toString()) },
            )
        }
    } catch (_: LockUnavailable) {
        null
    }

    private fun recordCount(): Int = root.listFiles()?.count { it.name.endsWith(".json") } ?: 0

    private fun baseRecord(claim: AppOperationJournal.Claim, contentsDigest: String): JSONObject = JSONObject()
        .put("key", claim.key)
        .put("operation_id", claim.operationId)
        .put("digest", contentsDigest)
        .put("in_progress", false)

    private fun requireIdentity(obj: JSONObject, claim: AppOperationJournal.Claim) {
        val key = obj.strictOrNull("key") ?: throw AppOperationJournal.CorruptStorageException("record without key")
        if (key != claim.key) throw AppOperationJournal.CorruptStorageException("identity mismatch")
        val opId = obj.strictOrNull("operation_id") ?: throw AppOperationJournal.CorruptStorageException("record without operation id")
        if (opId != claim.operationId) throw AppOperationJournal.CorruptStorageException("operation id mismatch")
    }

    /** Every read validates the record's shape, not only its JSON syntax. */
    private fun validateOutcomeShape(obj: JSONObject, outcome: String) {
        if (!AppOperationV1.isOutcome(outcome)) throw AppOperationJournal.CorruptStorageException("unknown outcome $outcome")
        val reason = obj.strictOrNull("reason")
        val state = obj.optJSONObject("state")
        if (outcome == AppOperationV1.OUTCOME_SUCCEEDED) {
            if (state == null) throw AppOperationJournal.CorruptStorageException("success without state")
            if (reason != null) throw AppOperationJournal.CorruptStorageException("success with reason")
        } else {
            if (state != null) throw AppOperationJournal.CorruptStorageException("non-success with state")
            if (reason == null || !REASON_REGEX.matches(reason)) {
                throw AppOperationJournal.CorruptStorageException("non-success without a valid reason")
            }
        }
    }

    /**
     * Reads a record, distinguishing absent (null) from unreadable/corrupt (exception). A partially
     * written staged file is never consulted; only the record path is read.
     */
    private fun readObject(target: File): JSONObject? {
        if (!target.exists()) return null
        if (target.length() > AppOperationV1.MAX_ENVELOPE_BYTES) {
            throw AppOperationJournal.CorruptStorageException("record exceeds its bound")
        }
        val text = try {
            target.readText()
        } catch (e: Exception) {
            throw AppOperationJournal.CorruptStorageException("unreadable: ${e.message}")
        }
        if (text.isBlank()) throw AppOperationJournal.CorruptStorageException("empty record")
        return try {
            JSONObject(JSONTokener(text))
        } catch (e: Exception) {
            throw AppOperationJournal.CorruptStorageException("unparseable: ${e.message}")
        }
    }

    /**
     * Create-if-absent with durable contents. The link is the exclusive create; on failure this call
     * leaves any existing record untouched — it never deletes a file it did not create.
     */
    private fun linkNewRecord(target: File, obj: JSONObject): Boolean {
        val parent = target.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val staged = File.createTempFile(target.name, ".staged", parent)
        return try {
            staged.writeText(obj.toString())
            fsyncFile(staged)
            Files.createLink(target.toPath(), staged.toPath())
            syncDirectory(parent)
            true
        } catch (_: Exception) {
            false
        } finally {
            // Only the staged file this call created is removed; the record path is never touched.
            staged.delete()
        }
    }

    /** Atomic replacement of an existing record with a durable one. */
    private fun replaceDurably(target: File, obj: JSONObject): Boolean {
        val parent = target.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val staged = File.createTempFile(target.name, ".staged", parent)
        return try {
            staged.writeText(obj.toString())
            fsyncFile(staged)
            try {
                Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                return false // durability cannot be established: refuse rather than downgrade
            }
            syncDirectory(parent)
            true
        } catch (_: Exception) {
            false
        } finally {
            staged.delete()
        }
    }

    private fun fsyncFile(file: File) {
        RandomAccessFile(file, "rw").use { it.fd.sync() }
    }

    /** Directory fsync is REQUIRED: if the entry cannot be made durable the operation is refused. */
    private fun syncDirectory(dir: File) {
        FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    private fun jsonEquals(a: JSONObject?, b: JSONObject?): Boolean = when {
        a == null && b == null -> true
        a == null || b == null -> false
        else -> a.toString() == b.toString()
    }

    private fun JSONObject.strictOrNull(key: String): String? {
        val v = opt(key) ?: return null
        return if (v is String && v.isNotBlank()) v else null
    }

    private companion object {
        private val REASON_REGEX = Regex("[a-z][a-z0-9_]{0,127}")
        private const val LOCK_NAME = ".appops-root.lock"

        /** One monitor per canonical root path, shared by every store instance in this process. */
        private val rootLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

        private fun rootLock(canonicalPath: String): Any = rootLocks.getOrPut(canonicalPath) { Any() }
    }
}
