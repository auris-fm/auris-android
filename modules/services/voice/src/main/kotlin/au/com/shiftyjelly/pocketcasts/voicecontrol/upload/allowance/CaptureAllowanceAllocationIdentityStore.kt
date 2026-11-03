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
     * The identity for an attempt under [enrollmentEpoch] by [account], made durable before return.
     *
     * [account] is passed PER DECISION, never captured at construction (reviewer 7c220c1d #2): a
     * cold cache supplies null on the first call and a later account switch changes the answer, so
     * a captured value is either permanently disabled or permanently stale. The server keys
     * allocations by `(user_id, request_id)`, which means an id presented under a different user
     * looks like a first attempt and is issued fresh -- the cross-owner protection would pass
     * silently while doing nothing.
     */
    fun acquire(enrollmentEpoch: String, account: String): Acquisition = synchronized(lock) {
        // A RECORDED CONFLICT BLOCKS, it does not fall through to a mint.
        val recordedConflict = readOrNull() as? Identity
        if (recordedConflict != null && recordedConflict.conflicted) {
            // A genuinely NEW epoch is the one resolution that is not a bypass: the old id cannot
            // be reused and the server has already told us so. Anything else refuses.
            if (recordedConflict.enrollmentEpoch == enrollmentEpoch) return Acquisition.Ambiguous
            if (!deleteQuietly()) {
                recoveryRequired = true
                return Acquisition.Ambiguous
            }
        }
        // A FAILED POST-RENAME SYNC must actually refuse (reviewer 7c220c1d #1, cloud b2f0df12).
        // The flag was previously assigned and never read, so the first refusal became an
        // UNCONFIRMED SEND on retry -- a warning recorded but not consulted is not a refusal.
        // A FAILED POST-RENAME SYNC IS A PERSISTED BLOCK (reviewer 6a6eec2a). The flag used to be
        // process memory, so reconstructing the store read the renamed record and returned Held
        // without confirming anything -- the block vanished on the one event that produces it.
        //
        // Recovery CONFIRMS durability of the SAME id rather than minting a replacement (reviewer
        // c690ad32): a successful sync establishes current durability even though it cannot prove
        // what would have survived an earlier crash. Preserving the id keeps the server-side
        // allocation attributable.
        val pendingRecovery = (readOrNull() as? Identity)?.takeIf { it.recoveryRequired }
        if (recoveryRequired || pendingRecovery != null) {
            val dir = file.parentFile
            if (dir == null) return Acquisition.Ambiguous
            val record = pendingRecovery
            if (record == null) {
                // The flag is set but no record is readable: nothing exists to confirm.
                if (file.exists()) return Acquisition.Ambiguous
                recoveryRequired = false
            } else {
                // RE-PERSIST THE SAME ID WITHOUT THE BLOCK, then STOP.
                //
                // ONCE DURABILITY IS CONFIRMED, THIS ATTEMPT MAY PROCEED (spec a91a907e).
                //
                // An earlier version refused here so the confirmation would be "an observable
                // event". That was a testing benefit dressed as a correctness rule: the safety
                // property is that nothing is SENT until the record is proven durable, and a
                // successful durableWrite above establishes exactly that, for exactly this id.
                // Refusing afterwards buys no additional safety and costs the user an utterance.
                // The confirmation stays observable as a test seam on the write, not as a refusal.
                // THE RECOVERY WRITE ITSELF CAN FAIL (reviewer d4cff2e5 #1). Using the raw
                // durableWrite left a hole: if its rename succeeds and its directory sync fails,
                // the readable record has the block CLEARED and no wrapper restores it, so a new
                // store instance reads it as Held. Use the policy wrapper instead, which re-blocks
                // on failure; and treat a failure as a refusal either way.
                if (!write(record.copy(recoveryRequired = false))) return Acquisition.Ambiguous
                recoveryRequired = false
            }
        }
        val existing = readOrNull()
        if (existing == AMBIGUOUS) return Acquisition.Ambiguous
        val held = existing as? Identity
        if (held != null) {
            // AN UNPROVEN ACCOUNT REFUSES (spec e2cd78d8, cloud 8d1ffaaf). An earlier version let
            // an unknown on either side through, arguing that refusing would wedge a legitimate
            // retry after a cold start. That was wrong: an id presented under the wrong `user_id`
            // is recorded server-side as a FIRST ATTEMPT by a new principal, so an unbound request
            // is not a mismatch risk -- it is guaranteed to land somewhere the client cannot relate
            // to the identity it meant. A cold cache is a readiness condition, not permission.
            if (held.account != account) return Acquisition.Ambiguous
            if (held.enrollmentEpoch == enrollmentEpoch) {
                // A RECORD IS ONLY AS DURABLE AS ITS WRITE (reviewer f77aaa69 #2).
                //
                // The marker is not sufficient evidence on its own: it is written best-effort
                // AFTER an unconfirmed rename, so a process interruption before the rewrite -- or a
                // failure writing the marker -- leaves a readable, unmarked record whose durability
                // nobody established. Depending on a second successful write to remember that the
                // first one failed is exactly the dependency that breaks in the case it exists for.
                //
                // So an unmarked record is CONFIRMED before use rather than trusted: re-syncing the
                // directory establishes current durability for this record. If that cannot be done,
                // the record is not returned as Held.
                // DERIVED FROM READABLE STATE, NOT FROM A MARKER (cloud c8c8fdd8).
                // Gating this on `!recoveryRequired` made the marker load-bearing again: an absent
                // marker would stand for "someone once proved this", which is the same dependence on
                // a write that may not have survived, one indirection further out. Confirming
                // unconditionally derives durability from the record we can actually read, so
                // nothing has to survive a failed write to tell us we are uncertain. The cost is one
                // directory sync on a read path; the alternative is trusting a proxy for proof.
                if (!confirmDurable()) return Acquisition.Ambiguous
                // A RETAINED id is returned too: after a 429 the allocation is gone, and minting a
                // fresh id would refuse identically while storing another server row.
                return Acquisition.Held(held)
            }
            // A different epoch: this id belongs to an enrollment that no longer exists. If the
            // removal is not durable we cannot claim the old id is gone, so we refuse.
            if (!deleteQuietly()) {
                recoveryRequired = true
                return Acquisition.Ambiguous
            }
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
        // PERSISTED, not in-memory: a restart must not clear the block.
        write(expected.copy(conflicted = true))
    }

    /**
     * ADVANCEMENT, compare-and-swap. Applies only if [expected] is still the stored identity, so a
     * late `410` for an already-superseded id cannot delete its replacement.
     */
    fun advance(expected: Identity): Boolean = synchronized(lock) {
        val current = readOrNull() as? Identity
        if (current != expected) return false
        // A FAILED DELETE SYNC MEANS THE ADVANCE IS UNCONFIRMED (reviewer 6a6eec2a, c690ad32).
        // Returning true anyway reported a successful retirement while the old id might still be
        // on disk, so the next attempt could present it -- or mint a replacement on a state nobody
        // can vouch for. The caller must stop rather than send.
        if (!deleteQuietly()) {
            recoveryRequired = true
            return false
        }
        true
    }

    /** `429`: retain the id so no new allocation is requested for the rest of the window. */
    fun retain(identity: Identity): Boolean = synchronized(lock) {
        if (readOrNull() as? Identity != identity) return false
        write(identity.copy(retired = true))
        true
    }

    /**
     * Establishes that the CURRENT record on disk is durable, without rewriting it.
     *
     * A directory sync is the confirming act: it flushes the entry that already exists. This does
     * not prove what would have survived an earlier crash (no later sync can), which is why a
     * record that IS marked unconfirmed still follows the recovery path rather than this one.
     */
    private fun confirmDurable(): Boolean {
        val dir = file.parentFile ?: return false
        return syncDirectory(dir)
    }

    /** Persists the recovery block on the CURRENT record, if one is readable. */
    private fun markRecoveryBlock() {
        val current = readOrNull() as? Identity ?: return
        if (current.recoveryRequired) return
        write(current.copy(recoveryRequired = true))
    }

    /**
     * Best-effort persistence of the recovery block after a post-rename sync failure. The rename
     * DID land, so a readable record exists; rewriting it with the block set is what makes the
     * refusal survive a restart.
     */
    private fun writeRecoveryBlock() {
        val current = readOrNull() as? Identity ?: return
        if (current.recoveryRequired) return
        // Deliberately does not re-enter write(): that would recurse into the same sync failure.
        val dir = file.parentFile ?: return
        val tmp = File(dir, file.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(IdentityEncoding.render(current.copy(recoveryRequired = true)).toByteArray())
                out.flush()
                out.fd.sync()
            }
            if (tmp.renameTo(file)) syncDirectory(dir)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

    data class Identity(
        val requestId: String,
        val enrollmentEpoch: String,
        val account: String,
        val retired: Boolean = false,
        val conflicted: Boolean = false,
        /**
         * The record reached the rename but its durability was never confirmed (reviewer 6a6eec2a).
         * Persisted, not held in a field: the consequence is "must not send", and restart is
         * exactly the operation a crash performs, so an in-memory block is defeated by the very
         * event it exists for.
         */
        val recoveryRequired: Boolean = false,
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

    /** Policy wrapper: a post-rename sync failure also persists the recovery block. */
    private fun write(identity: Identity): Boolean = if (durableWrite(identity)) {
        true
    } else {
        writeRecoveryBlock()
        false
    }

    /** The raw durable write: fsync the bytes, rename, then fsync the directory. */
    private fun durableWrite(identity: Identity): Boolean {
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
        if (!syncDirectory(dir)) return false
        return true
    }

    /**
     * Removes the record, and reports whether the REMOVAL is durable.
     *
     * A delete that cannot be synced is the same hazard as a rename that cannot be synced: the
     * next read may still see a record we believe we removed, or lose one we believe we wrote.
     * Silently ignoring it would leave [acquire] minting on the strength of a state nobody can
     * vouch for.
     */
    private fun deleteQuietly(): Boolean {
        val dir = file.parentFile
        file.delete()
        return dir == null || syncDirectory(dir)
    }

    /**
     * Syncs the directory so the new NAME is durable, not just the bytes (reviewer 3d1f87be #2).
     *
     * This is NOT best effort. `rename` can be durable in the directory entry and still lose the
     * record if the entry itself is not flushed, and a lost record is a silently re-allocated
     * batch. So a failure here is reported and the caller REFUSES to send: the alternative is to
     * claim an identity is durable when it may vanish on the next power cut.
     *
     * The seam below forces the POST-RENAME sync to fail, which is the case the reviewer named and
     * the one unreachable on a healthy filesystem. It is a one-shot, reachable only from tests.
     */
    @Volatile
    var failNextSyncForTest: Boolean = false

    private fun syncDirectory(dir: File): Boolean = try {
        if (failNextSyncForTest) {
            failNextSyncForTest = false
            return false
        }
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
     * A `409 request_conflict` is recorded IN THE PERSISTED RECORD (reviewer 7c220c1d #3, cloud
     * b2f0df12). Holding it in memory made the recovery a PROCESS RESTART -- the one operation that
     * loses it -- so the client's response to a conflict would be "send again after a crash", which
     * the server cannot distinguish from a fresh well-behaved attempt that allocates.
     *
     * The record keeps `conflicted = true` on disk, and [acquire] refuses for as long as it is
     * there. Nothing about a 409 may produce a fresh id by itself; the only clearings are a
     * genuinely new enrollment epoch or a proven-exhaustion advance -- never time, attempts or a
     * restart (cloud 09efcfea).
     */

    private companion object {
        /** Sentinel meaning "present but not trustworthy" -- distinct from absent (null). */
        val AMBIGUOUS = Any()
    }
}

/** Strict file encoding for a persisted identity. Anything unparseable is AMBIGUOUS, not absent. */
private object IdentityEncoding {
    const val VERSION = "allowance-identity/5"

    fun render(identity: CaptureAllowanceAllocationIdentityStore.Identity): String = buildString {
        append(VERSION).append('\n')
        append(identity.requestId).append('\n')
        append(identity.enrollmentEpoch).append('\n')
        append(identity.account).append('\n')
        append(identity.retired).append('\n')
        append(identity.conflicted).append('\n')
        append(identity.recoveryRequired).append('\n')
    }

    fun decode(text: String): CaptureAllowanceAllocationIdentityStore.Identity? {
        val lines = text.lines()
        if (lines.size < 7 || lines[0] != VERSION) return null
        val id = lines[1]
        val epoch = lines[2]
        val accountLine = lines[3]
        val retired = lines[4].toBooleanStrictOrNull() ?: return null
        val conflicted = lines[5].toBooleanStrictOrNull() ?: return null
        val recoveryRequired = lines[6].toBooleanStrictOrNull() ?: return null
        // The id must be a UUID: anything else could not have been sent by this client.
        if (runCatching { UUID.fromString(id) }.isFailure) return null
        if (epoch.isEmpty()) return null
        return CaptureAllowanceAllocationIdentityStore.Identity(
            requestId = id,
            enrollmentEpoch = epoch,
            account = accountLine,
            retired = retired,
            conflicted = conflicted,
            recoveryRequired = recoveryRequired,
        )
    }
}
