package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Durable capture-allowance accounting for ONE stable contributor account (spec b768de49).
 *
 * PROPERTIES.
 *  - Usage belongs to the ACCOUNT, not the enrollment epoch: sign-out, re-enrollment, purge and
 *    restart never reset it. The area sits beside, never inside, purgeable contribution content.
 *  - It only moves FORWARD. Usage never decreases across reopen or recovery, and no reading may
 *    exceed the configured ceilings. A missing, damaged or interrupted ledger closes admission; it
 *    never re-derives zero usage, and total loss is indistinguishable from first use, so only an
 *    explicit [AllowanceProvisioning] authority may ever create accounting.
 *  - No write is retracted and no artifact is repaired in place: damage refuses and leaves the
 *    evidence as it is, so a later reader cannot mistake a rewrite for a valid history.
 *  - A rename is not a durability barrier. Every write is temp -> file sync -> rename -> DIRECTORY
 *    sync of both the entry and its parent, and a failure at ANY barrier is reported. A charge is
 *    preceded by a durable intent recording BOTH the pre- and target counters, so recovery settles
 *    an exact transition instead of guessing - and settles it only onto the recorded TARGET.
 *  - Every document is bounded and strictly validated ([AllowanceDocuments]); duplicates, extra
 *    fields, changed counters, a foreign account/window/incarnation or a corrupt intent close
 *    admission and are never overwritten.
 *
 * OFF-PATH ONLY. Nothing here may run on the VAD collector: it performs file I/O and takes an OS
 * lock. Admission uses [CaptureAllowanceOwner.takeToken], which does none of that.
 */
class CaptureAllowanceLedger(
    root: File,
    private val account: String,
    private val limits: AllowanceLimits,
    private val window: AllowanceWindow,
    private val provisioning: AllowanceProvisioning = AllowanceProvisioning.Unavailable,
    private val writeObserver: ((AllowanceWrite) -> Unit)? = null,
    /** Injectable directory barrier; a control fails it to prove the failure is reported. */
    private val directorySync: ((File) -> Unit)? = null,
    /**
     * Injectable barrier at a NAMED boundary, so a control can fail exactly one durability step.
     * [AllowanceBarrier.FileWritten], [.FileSynced] and [.Renamed] happen BEFORE the directory sync,
     * which is what makes "visible but not durable" testable; [.EntrySynced]/[.ParentSynced] follow it.
     */
    private val writeBarrier: ((AllowanceBarrier) -> Unit)? = null,
) {
    private val binding: String = digest(DOMAIN_BINDING, account)
    private val equality: String = digest(DOMAIN_EQUALITY, account)

    /** Each account owns its own area: one account can never read, share or overwrite another's. */
    private val area: File = File(root, digest(DOMAIN_AREA, account).take(AREA_KEY_CHARS))

    /**
     * Every directory whose ENTRY was created by this instance, i.e. each created directory AND the
     * parent that contains it. For a fresh `.../a/b/collection-usage` that is `collection-usage`, `b`,
     * `a` and `a`'s containing parent: without the last one, a crash can lose the whole chain even
     * though each inner directory was itself synced.
     */
    private val createdEntries = mutableListOf<File>()

    private val genesisFile = File(area, GENESIS_FILE)
    private val ledgerFile = File(area, LEDGER_FILE)
    private val intentFile = File(area, INTENT_FILE)
    private val lockFile = File(area, LOCK_FILE)

    @Volatile
    private var closed: Boolean = false

    /**
     * Coordination key. File locking is JVM-wide per file, so a second instance in this process would
     * throw OverlappingFileLockException rather than observe contention; the OS lock on the mutation
     * path is therefore the ONLY mutual-exclusion authority, never a JVM monitor layered over it.
     */
    private val boundWindow: String = window.binding()

    // ---- reading -------------------------------------------------------------------------------

    /**
     * Reads durable state. [holdingLock] excludes the lock artifact from the emptiness test: taking
     * the lock CREATES it, so a holder counting it would always read its own fresh area as prior.
     */
    private fun readState(holdingLock: Boolean = false): LedgerState {
        if (!genesisFile.isFile) {
            val others = listOf(ledgerFile, intentFile) + if (holdingLock) emptyList() else listOf(lockFile)
            return if (others.none { it.isFile }) LedgerState.Fresh else LedgerState.Closed("artifacts-without-genesis")
        }
        val genesis = AllowanceDocuments.parseObject(readBounded(genesisFile), AllowanceDocuments.genesisDocs())
            ?: return LedgerState.Closed("genesis-missing-or-corrupt")
        if (genesis["schema"] != AllowanceDocuments.SCHEMA.toString()) return LedgerState.Closed("genesis-schema")
        if (genesis["state"] != AllowanceDocuments.STATE_ESTABLISHED) return LedgerState.Closed("genesis-${genesis["state"]}")
        if (genesis["binding"] != binding || genesis["equality"] != equality) return LedgerState.Closed("binding-mismatch")
        if (genesis["window"] != boundWindow) return LedgerState.Closed("window-mismatch")
        val id = genesis["id"] ?: return LedgerState.Closed("genesis-corrupt")

        val ledger = AllowanceDocuments.parseObject(readBounded(ledgerFile), AllowanceDocuments.ledgerDocs())
            ?: return LedgerState.Closed("ledger-missing-or-corrupt")
        if (ledger["schema"] != AllowanceDocuments.SCHEMA.toString()) return LedgerState.Closed("ledger-schema")
        if (ledger["id"] != id) return LedgerState.Closed("identity-mismatch")
        if (ledger["window"] != boundWindow) return LedgerState.Closed("window-mismatch")
        val records = ledger["chargedRecords"]?.toIntOrNull() ?: return LedgerState.Closed("ledger-corrupt")
        val bytes = ledger["chargedBytes"]?.toLongOrNull() ?: return LedgerState.Closed("ledger-corrupt")
        if (records < 0 || bytes < 0) return LedgerState.Closed("ledger-corrupt")
        if (records > limits.maxDailyRecords) return LedgerState.Closed("ledger-exceeds-ceiling")

        // The intent must be a COMPLETE, well-formed document for THIS account, window and
        // incarnation. Missing, unparseable, foreign or half-written intent is damage, not "no
        // intent": treating it as absent is what would let a broken history charge again.
        val intent = parseIntent(id) ?: return LedgerState.Closed("intent-missing-or-corrupt")
        return when (intent.state) {
            AllowanceDocuments.STATE_SETTLED -> {
                // A settled transition is COMPLETE: its target is durable and the current ledger must
                // be AT it. Accepting the pre-counters would let a rewritten pair restore consumed
                // allowance, so usage could decrease and be spent twice.
                if (records != intent.targetRecords || bytes != intent.targetBytes) {
                    return LedgerState.Closed("ledger-below-settled-target")
                }
                LedgerState.Established(id, records, bytes, intent)
            }

            else -> {
                // Pending: the charge may or may not have landed, and only recovery may decide.
                LedgerState.Established(id, records, bytes, intent)
            }
        }
    }

    /** Parses and fully validates the intent document, including its incarnation binding. */
    private fun parseIntent(incarnation: String): IntentRecord? {
        val doc = AllowanceDocuments.parseObject(readBounded(intentFile), AllowanceDocuments.intentDocs()) ?: return null
        if (doc["schema"] != AllowanceDocuments.SCHEMA.toString()) return null
        if (doc["binding"] != binding) return null
        if (doc["window"] != boundWindow) return null
        if (doc["incarnation"] != incarnation) return null
        // Exactly two states exist. Anything else is a document we did not write, and treating it as
        // "pending-like" would let admission overwrite an unresolved transition.
        val state = when (doc["state"]) {
            AllowanceDocuments.STATE_PENDING -> AllowanceDocuments.STATE_PENDING
            AllowanceDocuments.STATE_SETTLED -> AllowanceDocuments.STATE_SETTLED
            else -> return null
        }
        val transaction = doc["transaction"]?.takeIf { it.isNotEmpty() } ?: return null
        val preRecords = doc["preRecords"]?.toIntOrNull() ?: return null
        val preBytes = doc["preBytes"]?.toLongOrNull() ?: return null
        val targetRecords = doc["targetRecords"]?.toIntOrNull() ?: return null
        val targetBytes = doc["targetBytes"]?.toLongOrNull() ?: return null
        // A transition is exactly one reservation forward, or the zero genesis state. Anything else is
        // not a document we wrote.
        if (preRecords < 0 || preBytes < 0) return null
        if (targetRecords < 0 || targetBytes < 0) return null
        if (targetRecords < preRecords || targetBytes < preBytes) return null
        if (targetRecords == preRecords) {
            if (preRecords != 0 || targetBytes != preBytes) return null
        } else {
            if (targetRecords != preRecords + 1) return null
            if (targetBytes != saturatingAdd(preBytes, limits.wavesReservationBytes())) return null
        }
        return IntentRecord(state, transaction, preRecords, preBytes, targetRecords, targetBytes)
    }

    // ---- charging ------------------------------------------------------------------------------

    /**
     * Charges one full reservation, or refuses. Validation precedes every write, so a refusal cannot
     * consume allowance. Every branch returns its OWN outcome; no previous success can leak into a
     * later refusal.
     */
    fun chargeForCapture(): Boolean {
        if (closed) return false
        if (window is AllowanceWindow.Unestablished) {
            closed = true
            return false
        }
        val before = readState()
        if (before is LedgerState.Closed) {
            closed = true
            return false
        }
        if (before is LedgerState.Established) {
            // A pending intent is an unfinished transition: refuse until recovery settles it.
            if (before.intent?.state == AllowanceDocuments.STATE_PENDING) return false
            if (!limits.admits(before.records, before.bytes)) return false
        }
        val outcome = withFileLock {
            when (val locked = readState(holdingLock = true)) {
                is LedgerState.Closed -> {
                    closed = true
                    ChargeOutcome.REFUSED
                }

                LedgerState.Fresh -> {
                    if (provisioning !is AllowanceProvisioning.TestOnly) {
                        // An empty area proves nothing about whether accounting existed.
                        closed = true
                        ChargeOutcome.REFUSED
                    } else if (!initialize()) {
                        closed = true
                        ChargeOutcome.FAILED_BARRIER
                    } else {
                        chargeUnderLock()
                    }
                }

                is LedgerState.Established -> {
                    if (locked.intent?.state == AllowanceDocuments.STATE_PENDING) {
                        ChargeOutcome.REFUSED
                    } else {
                        chargeUnderLock()
                    }
                }
            }
        }
        return outcome == ChargeOutcome.CHARGED
    }

    private fun withFileLock(block: () -> ChargeOutcome): ChargeOutcome {
        if (!ensureAreaAndLockArtifact()) return ChargeOutcome.FAILED_BARRIER
        RandomAccessFile(lockFile, "rw").use { raf ->
            val lock: FileLock? = try {
                raf.channel.tryLock()
            } catch (overlapping: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                // Another owner holds it. Do NOT run the mutation body on a stale read.
                return ChargeOutcome.REFUSED
            }
            return try {
                block()
            } finally {
                runCatching { lock.release() }
            }
        }
    }

    private fun chargeUnderLock(): ChargeOutcome {
        val state = readState(holdingLock = true) as? LedgerState.Established ?: return ChargeOutcome.REFUSED
        if (!limits.admits(state.records, state.bytes)) return ChargeOutcome.REFUSED
        // Saturation is checked by `admits` against the ceiling, which is <= Long.MAX_VALUE.
        val targetRecords = state.records + 1
        val targetBytes = saturatingAdd(state.bytes, limits.wavesReservationBytes())
        val transaction = UUID.randomUUID().toString()
        // The intent is durable BEFORE the charge and records both ends of the transition, so a
        // reconciliation never has to infer what "before" was.
        val intentWritten = writeDurably(
            intentFile,
            AllowanceDocuments.intent(
                AllowanceDocuments.STATE_PENDING,
                transaction,
                binding,
                boundWindow,
                state.id,
                state.records,
                state.bytes,
                targetRecords,
                targetBytes,
            ),
            AllowanceWrite.CHARGE_INTENT_PENDING,
        )
        if (intentWritten != WriteOutcome.DURABLE) return ChargeOutcome.FAILED_BARRIER
        val applied = writeDurably(
            ledgerFile,
            AllowanceDocuments.ledger(state.id, boundWindow, targetRecords, targetBytes),
            AllowanceWrite.CHARGE_LEDGER,
        )
        // The ledger is NOT settled until its own directory entry is durable, and recovery must not
        // be able to see a target it cannot rely on afterwards.
        if (applied != WriteOutcome.DURABLE) return ChargeOutcome.FAILED_BARRIER
        val settled = writeDurably(
            intentFile,
            AllowanceDocuments.intent(
                AllowanceDocuments.STATE_SETTLED,
                transaction,
                binding,
                boundWindow,
                state.id,
                state.records,
                state.bytes,
                targetRecords,
                targetBytes,
            ),
            AllowanceWrite.CHARGE_INTENT_SETTLED,
        )
        return if (settled == WriteOutcome.DURABLE) ChargeOutcome.CHARGED else ChargeOutcome.FAILED_BARRIER
    }

    // ---- initialization ------------------------------------------------------------------------

    /**
     * Creates accounting ONLY under an explicit provisioning authority. The genesis file is the
     * exclusive claim, created and made durable first; everything else is written only after it is
     * published, so a partially initialized area reads as damage rather than as zero usage.
     */
    private fun initialize(): Boolean {
        val id = UUID.randomUUID().toString()
        val genesis = AllowanceDocuments.genesis(id, binding, equality, boundWindow, AllowanceDocuments.STATE_INITIALIZING)
        if (writeDurably(genesisFile, genesis, AllowanceWrite.GENESIS_INITIALIZING) != WriteOutcome.DURABLE) return false
        if (writeDurably(ledgerFile, AllowanceDocuments.ledger(id, boundWindow, 0, 0), AllowanceWrite.GENESIS_LEDGER) != WriteOutcome.DURABLE) {
            return false
        }
        // A zero ledger and a zero intent are CONSISTENT at pre == target == 0: `chargeUnderLock` then
        // charges exactly one reservation forward from the very first capture.
        val noIntent = AllowanceDocuments.intent(
            AllowanceDocuments.STATE_SETTLED,
            UUID.randomUUID().toString(),
            binding,
            boundWindow,
            id,
            0,
            0,
            0,
            0,
        )
        if (writeDurably(intentFile, noIntent, AllowanceWrite.GENESIS_INTENT) != WriteOutcome.DURABLE) return false
        val established = AllowanceDocuments.genesis(id, binding, equality, boundWindow, AllowanceDocuments.STATE_ESTABLISHED)
        if (writeDurably(genesisFile, established, AllowanceWrite.GENESIS_ESTABLISHED) != WriteOutcome.DURABLE) return false
        return readState(holdingLock = true) is LedgerState.Established
    }

    // ---- recovery ------------------------------------------------------------------------------

    /**
     * Settles an interrupted transition, WITHOUT issuing a token (a previous process's charge is not
     * this process's admission). A post-charge ledger is completed onto its recorded target; a
     * pre-charge ledger has the interrupted transition discarded. Anything else closes admission.
     */
    fun reconcileForTest(): Boolean {
        val outcome = withFileLock {
            val read = readState(holdingLock = true)
            val state = read as? LedgerState.Established ?: return@withFileLock ChargeOutcome.REFUSED
            val intent = state.intent ?: return@withFileLock ChargeOutcome.REFUSED
            if (intent.state == AllowanceDocuments.STATE_SETTLED) return@withFileLock ChargeOutcome.CHARGED
            when {
                state.records == intent.preRecords && state.bytes == intent.preBytes -> {
                    // The ledger never advanced. Settling now would publish a document claiming a
                    // charge the ledger denies, i.e. rewrite a valid interrupted history into damage;
                    // applying the target would grant allowance the interrupted capture never spent.
                    // Neither is safe, so recovery REFUSES and leaves every artifact untouched.
                    closed = true
                    ChargeOutcome.REFUSED
                }

                state.records == intent.targetRecords && state.bytes == intent.targetBytes -> {
                    // The charge landed but never settled: the ledger must be durable BEFORE the
                    // intent may claim so, otherwise a crash could strand a target nobody synced.
                    val confirmed = writeDurably(
                        ledgerFile,
                        AllowanceDocuments.ledger(state.id, boundWindow, intent.targetRecords, intent.targetBytes),
                        AllowanceWrite.RECOVERY_LEDGER,
                    )
                    if (confirmed != WriteOutcome.DURABLE) {
                        closed = true
                        return@withFileLock ChargeOutcome.BARRIER_SETTLE_FAILED
                    }
                    val settled = writeDurably(
                        intentFile,
                        AllowanceDocuments.intent(
                            AllowanceDocuments.STATE_SETTLED,
                            intent.transaction,
                            binding,
                            boundWindow,
                            state.id,
                            intent.preRecords,
                            intent.preBytes,
                            intent.targetRecords,
                            intent.targetBytes,
                        ),
                        AllowanceWrite.RECOVERY_INTENT_SETTLED,
                    )
                    if (settled != WriteOutcome.DURABLE) {
                        closed = true
                        ChargeOutcome.BARRIER_SETTLE_FAILED
                    } else {
                        ChargeOutcome.CHARGED
                    }
                }

                else -> {
                    // Neither recorded end: this is not the transition the intent describes.
                    closed = true
                    ChargeOutcome.REFUSED
                }
            }
        }
        // A settle that damaged the accounting must never present itself as settled.
        return !closed && outcome == ChargeOutcome.CHARGED
    }

    // ---- artifacts -----------------------------------------------------------------------------

    /** Creates the area and its lock artifact, syncing the PARENT entry that records them. */
    private fun ensureAreaAndLockArtifact(): Boolean {
        noteCreatedAncestors()
        if (!area.isDirectory && !area.mkdirs() && !area.isDirectory) return false
        noteCreatedAncestors()
        if (!lockFile.isFile && !lockFile.createNewFile() && !lockFile.isFile) return false
        return syncEntryAndParent(area, createdEntries.toList())
    }

    /**
     * Syncs the entry and its parent, plus EVERY ancestor this operation created: `mkdirs` can create
     * the accounting root itself, and that root's own parent entry must be durable too or a crash can
     * lose the whole directory.
     */
    private fun syncEntryAndParent(target: File, created: List<File> = emptyList()): Boolean = try {
        val sync = directorySync ?: ::syncDirectoryOrThrow
        sync(target)
        // Each barrier runs IMMEDIATELY after the sync it names, so a failure exercises that ordering.
        writeBarrier?.invoke(AllowanceBarrier.EntrySynced)
        target.parentFile?.let { sync(it) }
        writeBarrier?.invoke(AllowanceBarrier.ParentSynced)
        created.forEach { sync(it) }
        true
    } catch (failure: java.io.IOException) {
        false
    }

    /** Directory durability: a failure PROPAGATES, so a writer reports it rather than passing. */
    private fun syncDirectoryOrThrow(dir: File) {
        FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    private enum class WriteOutcome { DURABLE, FAILED }

    private fun writeDurably(target: File, content: String, phase: AllowanceWrite): WriteOutcome {
        writeObserver?.invoke(phase)
        // The directory containing the temporary file must itself be durable before the rename can be
        // relied on, and it may not EXIST yet (a first run creates the whole root).
        val tmp = File(area, "${target.name}.tmp")
        try {
            // The stream creates the area if it does not exist yet: note what that created BEFORE it.
            noteCreatedAncestors()
            FileOutputStream(tmp).use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
                stream.flush()
                stream.fd.sync()
            }
        } catch (failure: java.io.IOException) {
            tmp.delete()
            return WriteOutcome.FAILED
        }
        if (barrier(AllowanceBarrier.FileWritten).not()) {
            tmp.delete()
            return WriteOutcome.FAILED
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            return WriteOutcome.FAILED
        }
        // Renamed but NOT yet durable: exactly the window a failed directory sync leaves behind.
        if (barrier(AllowanceBarrier.Renamed).not()) return WriteOutcome.FAILED
        if (!syncEntryAndParent(area, noteCreatedAncestors())) return WriteOutcome.FAILED
        return WriteOutcome.DURABLE
    }

    private fun barrier(step: AllowanceBarrier): Boolean = try {
        writeBarrier?.invoke(step)
        true
    } catch (failure: java.io.IOException) {
        false
    }

    /**
     * Records EVERY directory this instance created, plus the parent entry of the outermost one.
     * The path is derived structurally from [area], so no directory listing (which may not exist yet)
     * is needed: each component of the chain is recorded, and the chain's containing parent with it.
     */
    private fun noteCreatedAncestors(): List<File> {
        // The chain from the area up to (excluding) the outermost existing directory, outermost first.
        val chain = ArrayDeque<File>()
        var current: File? = area
        while (current != null && !current.exists()) {
            chain.addFirst(current)
            current = current.parentFile
        }
        // `current` is now the first EXISTING ancestor: its own entry names the outermost created dir.
        if (chain.isEmpty()) {
            // Nothing new was created, so only the area and its parent are ever synced.
            addEntry(area)
            area.parentFile?.let { addEntry(it) }
            return createdEntries.toList()
        }
        current?.let { addEntry(it) }
        chain.forEach { addEntry(it) }
        return createdEntries.toList()
    }

    private fun addEntry(dir: File?) {
        if (dir == null) return
        if (createdEntries.none { it.absolutePath == dir.absolutePath }) createdEntries += dir
    }

    /** Every directory entry this instance created, in discovery order. */
    internal fun createdEntriesForTest(): List<File> = createdEntries.toList()

    /**
     * Bounded read: the byte count READ is capped at MAX_BYTES + 1, so an oversized document costs a
     * bounded read and is then rejected by [AllowanceDocuments.parseObject]. Never an unbounded
     * `readText` of a file whose size is not ours to trust.
     */
    private fun readBounded(file: File): String? = runCatching {
        if (!file.isFile) return@runCatching null
        val limit = AllowanceDocuments.MAX_BYTES + 1
        java.io.FileInputStream(file).use { stream ->
            val buffer = ByteArray(limit)
            var total = 0
            while (total < limit) {
                val read = stream.read(buffer, total, limit - total)
                if (read < 0) break
                total += read
            }
            String(buffer, 0, total, Charsets.UTF_8)
        }
    }.getOrNull()

    // ---- accessors -----------------------------------------------------------------------------

    internal fun snapshot(): AllowanceSnapshot = when (val state = readState()) {
        is LedgerState.Established -> AllowanceSnapshot.Entry(state.records, state.bytes)

        is LedgerState.Closed -> {
            closed = true
            AllowanceSnapshot.Closed
        }

        LedgerState.Fresh -> AllowanceSnapshot.Closed
    }

    fun isClosed(): Boolean = closed || readState() is LedgerState.Closed

    companion object {
        private const val AREA_KEY_CHARS = 32
        private const val GENESIS_FILE = "genesis.json"
        private const val LEDGER_FILE = "ledger.json"
        private const val INTENT_FILE = "intent.json"
        private const val LOCK_FILE = "usage.lock"
        private const val DOMAIN_AREA = "auris.capture-allowance.area"
        private const val DOMAIN_BINDING = "auris.capture-allowance.binding"
        private const val DOMAIN_EQUALITY = "auris.capture-allowance.equality"

        private fun digest(domain: String, value: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(domain.toByteArray(Charsets.UTF_8))
            md.update(0)
            md.update(value.toByteArray(Charsets.UTF_8))
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** Ceiling-safe addition: never wraps into a smaller (or negative) value. */
        internal fun saturatingAdd(left: Long, right: Long): Long {
            val sum = left + right
            return if (sum < left) Long.MAX_VALUE else sum
        }
    }
}

/** The transition recorded by the durable intent, fully validated against this account. */
internal data class IntentRecord(
    val state: String,
    val transaction: String,
    val preRecords: Int,
    val preBytes: Long,
    val targetRecords: Int,
    val targetBytes: Long,
)

internal sealed interface LedgerState {
    data object Fresh : LedgerState

    data class Established(
        val id: String,
        val records: Int,
        val bytes: Long,
        val intent: IntentRecord?,
    ) : LedgerState

    data class Closed(val reason: String) : LedgerState
}

/** The outcome of one charge attempt. Every caller gets this attempt's result, never a stale one. */
internal enum class ChargeOutcome { CHARGED, REFUSED, FAILED_BARRIER, BARRIER_SETTLE_FAILED }

/** The observed accounting for diagnostics and controls. */
internal sealed interface AllowanceSnapshot {
    data class Entry(val chargedRecords: Int, val chargedBytes: Long) : AllowanceSnapshot

    data object Closed : AllowanceSnapshot
}

/** Named durability boundaries inside one durable write, in the order they occur. */
enum class AllowanceBarrier {
    /** The temporary file is written and synced; nothing is visible yet. */
    FileWritten,

    /** The temporary file has been RENAMED onto the target: visible, but not yet durable. */
    Renamed,

    /** The entry's directory is synced. */
    EntrySynced,

    /** The entry's parent directory (and any ancestor created) is synced. */
    ParentSynced,
}

/** Ordered write phases, exposed so a control can fail a NAMED barrier. */
enum class AllowanceWrite {
    GENESIS_INITIALIZING,
    GENESIS_LEDGER,
    GENESIS_INTENT,
    GENESIS_ESTABLISHED,
    CHARGE_INTENT_PENDING,
    CHARGE_LEDGER,
    CHARGE_INTENT_SETTLED,
    RECOVERY_LEDGER,
    RECOVERY_INTENT_SETTLED,
}

/** Test-facing reads of the accounting, expressed so a closed ledger cannot be read as "zero". */
internal fun CaptureAllowanceLedger.snapshotRecordCount(): Int = when (val snap = snapshot()) {
    is AllowanceSnapshot.Entry -> snap.chargedRecords
    AllowanceSnapshot.Closed -> -1
}

internal fun CaptureAllowanceLedger.snapshotByteCount(): Long = when (val snap = snapshot()) {
    is AllowanceSnapshot.Entry -> snap.chargedBytes
    AllowanceSnapshot.Closed -> -1L
}

/** The charged record count, or -1 when the accounting is closed (never a misleading zero). */
internal fun CaptureAllowanceLedger.recordsForTest(): Int = snapshotRecordCount()
