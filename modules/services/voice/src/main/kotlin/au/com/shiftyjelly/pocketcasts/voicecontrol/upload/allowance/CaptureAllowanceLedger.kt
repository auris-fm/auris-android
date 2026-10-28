package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Durable capture-allowance accounting for ONE stable contributor account (spec b768de49).
 *
 * PROPERTIES.
 *  - Usage belongs to the ACCOUNT, not the enrollment epoch: sign-out, re-enrollment, purge and
 *    restart never reset it. The area sits beside, never inside, purgeable contribution content.
 *  - It only moves FORWARD. A missing, damaged or interrupted ledger closes admission; it never
 *    re-derives zero usage, and total loss is indistinguishable from first use (so only an explicit
 *    [AllowanceProvisioning] authority may ever create accounting).
 *  - A rename is not a durability barrier. Every write is temp -> file sync -> rename -> DIRECTORY
 *    sync, and a failure at ANY of those barriers is reported as failure. A charge is preceded by a
 *    durable intent that records BOTH the pre- and target counters, so a fresh owner can reconcile
 *    the exact outcome instead of guessing.
 *  - Every document is bounded and strictly validated ([AllowanceDocuments]); duplicates, extra
 *    fields, changed counters or a mismatched window read as damage and close admission.
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
    /** Directory-sync barrier; injectable so a control can prove a failed barrier is reported. */
    private val directorySync: ((File) -> Unit)? = null,
) {
    private val binding: String = digest(DOMAIN_BINDING, account)
    private val equality: String = digest(DOMAIN_EQUALITY, account)

    /** Each account owns its own area: one account can never read, share or overwrite another's. */
    private val area: File = File(root, digest(DOMAIN_AREA, account).take(AREA_KEY_CHARS))

    private val genesisFile = File(area, GENESIS_FILE)
    private val ledgerFile = File(area, LEDGER_FILE)
    private val intentFile = File(area, INTENT_FILE)
    private val lockFile = File(area, LOCK_FILE)

    @Volatile
    private var closed: Boolean = false

    /** The JVM-global guard shared by every instance charging this account in this process. */
    private fun processMutex(): Any = processMutexFor("$binding|${area.absolutePath}")

    private val boundWindow: String = window.binding()

    // ---- reading -------------------------------------------------------------------------------

    /**
     * Reads durable state. [holdingLock] excludes the lock artifact from the emptiness test: taking
     * the lock CREATES it, so a holder counting it would always read its own fresh area as prior.
     */
    private fun readState(holdingLock: Boolean = false, ignoringIntent: Boolean = false): LedgerState {
        if (!genesisFile.isFile) {
            val others = listOf(ledgerFile, intentFile) + if (holdingLock) emptyList() else listOf(lockFile)
            return if (others.none { it.isFile }) LedgerState.Fresh else LedgerState.Closed("artifacts-without-genesis")
        }
        val genesis = AllowanceDocuments.parseObject(readTextOrEmpty(genesisFile), AllowanceDocuments.genesisDocs())
            ?: return LedgerState.Closed("genesis-corrupt")
        if (genesis["schema"] != AllowanceDocuments.SCHEMA.toString()) return LedgerState.Closed("genesis-schema")
        if (genesis["state"] != AllowanceDocuments.STATE_ESTABLISHED) return LedgerState.Closed("genesis-${genesis["state"]}")
        if (genesis["binding"] != binding || genesis["equality"] != equality) return LedgerState.Closed("binding-mismatch")
        // The window is PERSISTED and must match this opener's: a different window cannot read old usage.
        if (genesis["window"] != boundWindow) return LedgerState.Closed("window-mismatch")
        val id = genesis["id"] ?: return LedgerState.Closed("genesis-corrupt")
        val ledger = AllowanceDocuments.parseObject(readTextOrEmpty(ledgerFile), AllowanceDocuments.ledgerDocs())
            ?: return LedgerState.Closed("ledger-missing-or-corrupt")
        if (ledger["schema"] != AllowanceDocuments.SCHEMA.toString()) return LedgerState.Closed("ledger-schema")
        if (ledger["id"] != id) return LedgerState.Closed("identity-mismatch")
        if (ledger["window"] != boundWindow) return LedgerState.Closed("window-mismatch")
        val records = ledger["chargedRecords"]?.toIntOrNull() ?: return LedgerState.Closed("ledger-corrupt")
        val bytes = ledger["chargedBytes"]?.toLongOrNull() ?: return LedgerState.Closed("ledger-corrupt")
        if (records < 0 || bytes < 0) return LedgerState.Closed("ledger-corrupt")
        // A settled intent records both ends of the last charge; the ledger must be at one of them.
        // Matching neither means a counter was rewritten, which no reconciliation can justify.
        val settled = if (ignoringIntent) {
            null
        } else {
            AllowanceDocuments.parseObject(readTextOrEmpty(intentFile), AllowanceDocuments.intentDocs())
        }
        if (settled != null && settled["state"] == AllowanceDocuments.STATE_SETTLED) {
            val preRecords = settled["preRecords"]?.toIntOrNull()
            val preBytes = settled["preBytes"]?.toLongOrNull()
            val targetRecords = settled["targetRecords"]?.toIntOrNull()
            val targetBytes = settled["targetBytes"]?.toLongOrNull()
            if (preRecords == null || preBytes == null || targetRecords == null || targetBytes == null) {
                return LedgerState.Closed("intent-corrupt")
            }
            val atPre = records == preRecords && bytes == preBytes
            val atTarget = records == targetRecords && bytes == targetBytes
            if (!atPre && !atTarget) return LedgerState.Closed("ledger-contradicts-intent")
        }
        return LedgerState.Established(id, records, bytes)
    }

    // ---- charging ------------------------------------------------------------------------------

    /**
     * Charges one full reservation, or refuses. Validation precedes every write, so a refusal cannot
     * consume allowance. Refusal is also returned when the OS lock is unavailable: another owner is
     * mutating this account, and no mutation may proceed on the strength of a stale read.
     */
    fun chargeForCapture(): Boolean = synchronized(processMutex()) {
        if (closed) return false
        val state = readState()
        if (state is LedgerState.Closed) {
            closed = true
            return false
        }
        if (window is AllowanceWindow.Unestablished) {
            closed = true
            return false
        }
        val records = if (state is LedgerState.Established) state.records else 0
        val bytes = if (state is LedgerState.Established) state.bytes else 0L
        if (!limits.admits(records, bytes)) return false
        val outcome = withFileLock {
            when (val locked = readState(holdingLock = true)) {
                is LedgerState.Closed -> {
                    closed = true
                    Unit
                }

                LedgerState.Fresh -> {
                    if (provisioning !is AllowanceProvisioning.TestOnly) {
                        // An empty area proves nothing about whether accounting existed.
                        closed = true
                    } else if (!initialize()) {
                        closed = true
                    } else {
                        chargeUnderLock()
                    }
                }

                is LedgerState.Established -> {
                    val intent = AllowanceDocuments.parseObject(readTextOrEmpty(intentFile), AllowanceDocuments.intentDocs())
                    if (intent != null && intent["state"] == AllowanceDocuments.STATE_PENDING) {
                        // An unsettled intent blocks every later charge until recovery settles it.
                        Unit
                    } else {
                        chargeUnderLock()
                    }
                }
            }
        }
        return outcome == ChargeOutcome.CHARGED
    }

    private fun withFileLock(block: () -> Unit): ChargeOutcome {
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
                lastOutcome
            } finally {
                runCatching { lock.release() }
            }
        }
    }

    private var lastOutcome: ChargeOutcome = ChargeOutcome.REFUSED

    private fun chargeUnderLock() {
        val state = readState(holdingLock = true) as? LedgerState.Established ?: run {
            lastOutcome = ChargeOutcome.REFUSED
            return
        }
        if (!limits.admits(state.records, state.bytes)) {
            lastOutcome = ChargeOutcome.REFUSED
            return
        }
        val targetRecords = state.records + 1
        val targetBytes = state.bytes + limits.wavesReservationBytes()
        val transaction = java.util.UUID.randomUUID().toString()
        // The intent is durable BEFORE the charge and records both ends of the transition, so a
        // reconciliation never has to infer what "before" was.
        val intentWritten = writeDurably(
            intentFile,
            AllowanceDocuments.intent(
                AllowanceDocuments.STATE_PENDING,
                transaction,
                binding,
                boundWindow,
                state.records,
                state.bytes,
                targetRecords,
                targetBytes,
            ),
            AllowanceWrite.CHARGE_INTENT_PENDING,
        )
        if (!intentWritten) {
            // The charge is UNCONFIRMED, not "not applied": the intent (if it landed) carries both
            // ends for recovery, and no admission may be granted either way.
            lastOutcome = ChargeOutcome.FAILED_BARRIER
            return
        }
        val applied = writeDurably(
            ledgerFile,
            AllowanceDocuments.ledger(state.id, boundWindow, targetRecords, targetBytes),
            AllowanceWrite.CHARGE_LEDGER,
        )
        if (!applied) {
            lastOutcome = ChargeOutcome.FAILED_BARRIER
            return
        }
        val settled = writeDurably(
            intentFile,
            AllowanceDocuments.intent(
                AllowanceDocuments.STATE_SETTLED,
                transaction,
                binding,
                boundWindow,
                state.records,
                state.bytes,
                targetRecords,
                targetBytes,
            ),
            AllowanceWrite.CHARGE_INTENT_SETTLED,
        )
        lastOutcome = if (settled) ChargeOutcome.CHARGED else ChargeOutcome.FAILED_BARRIER
    }

    /** Establishes accounting for a genuinely empty area under explicit provisioning authority. */
    private fun initialize(): Boolean {
        val id = java.util.UUID.randomUUID().toString()
        val ok = writeDurably(genesisFile, AllowanceDocuments.genesis(id, binding, equality, boundWindow, false), AllowanceWrite.INIT_GENESIS_INITIALIZING) &&
            writeDurably(ledgerFile, AllowanceDocuments.ledger(id, boundWindow, 0, 0), AllowanceWrite.INIT_LEDGER_ZEROED) &&
            writeDurably(
                intentFile,
                AllowanceDocuments.intent(AllowanceDocuments.STATE_SETTLED, id, binding, boundWindow, 0, 0, 0, 0),
                AllowanceWrite.INIT_INTENT_SETTLED,
            ) &&
            writeDurably(genesisFile, AllowanceDocuments.genesis(id, binding, equality, boundWindow, true), AllowanceWrite.INIT_GENESIS_ESTABLISHED)
        return ok && readState(holdingLock = true) is LedgerState.Established
    }

    // ---- recovery ------------------------------------------------------------------------------

    /**
     * Reconciles a pending intent left by a process that died mid-charge, returning true only when
     * the ambiguity was resolved durably. Both ends of the transition are recorded, so the decision
     * is a comparison against the intent itself rather than an arithmetic guess.
     *
     * Recovery settles ACCOUNTING and never mints a token: it can never re-admit a previous
     * process's charge.
     */
    fun reconcileForTest(): Boolean = synchronized(processMutex()) {
        val before = readState()
        if (before is LedgerState.Closed) {
            closed = true
            return false
        }
        withFileLock {
            val intent = AllowanceDocuments.parseObject(readTextOrEmpty(intentFile), AllowanceDocuments.intentDocs())
                ?: run {
                    lastOutcome = ChargeOutcome.REFUSED
                    return@withFileLock
                }
            if (intent["schema"] != AllowanceDocuments.SCHEMA.toString() ||
                intent["account"] != binding ||
                intent["window"] != boundWindow
            ) {
                closed = true
                lastOutcome = ChargeOutcome.REFUSED
                return@withFileLock
            }
            if (intent["state"] != AllowanceDocuments.STATE_PENDING) {
                lastOutcome = ChargeOutcome.CHARGED
                return@withFileLock
            }
            val current = readState(holdingLock = true, ignoringIntent = true) as? LedgerState.Established ?: run {
                lastOutcome = ChargeOutcome.REFUSED
                return@withFileLock
            }
            val preRecords = intent["preRecords"]?.toIntOrNull()
            val preBytes = intent["preBytes"]?.toLongOrNull()
            val targetRecords = intent["targetRecords"]?.toIntOrNull()
            val targetBytes = intent["targetBytes"]?.toLongOrNull()
            if (preRecords == null || preBytes == null || targetRecords == null || targetBytes == null) {
                closed = true
                lastOutcome = ChargeOutcome.REFUSED
                return@withFileLock
            }
            val settled = when {
                current.records == targetRecords && current.bytes == targetBytes -> writeDurably(
                    intentFile,
                    AllowanceDocuments.intent(
                        AllowanceDocuments.STATE_SETTLED,
                        intent["transaction"].orEmpty(),
                        binding,
                        boundWindow,
                        preRecords,
                        preBytes,
                        targetRecords,
                        targetBytes,
                    ),
                    AllowanceWrite.RECONCILE_INTENT_SETTLED,
                )

                current.records == preRecords && current.bytes == preBytes -> writeDurably(
                    intentFile,
                    AllowanceDocuments.intent(
                        AllowanceDocuments.STATE_SETTLED,
                        intent["transaction"].orEmpty(),
                        binding,
                        boundWindow,
                        preRecords,
                        preBytes,
                        preRecords,
                        preBytes,
                    ),
                    AllowanceWrite.RECONCILE_INTENT_SETTLED,
                )

                else -> {
                    // Neither recorded end: this is not the transition the intent describes.
                    closed = true
                    false
                }
            }
            lastOutcome = if (settled) ChargeOutcome.CHARGED else ChargeOutcome.BARRIER_SETTLE_FAILED
        }
        lastOutcome == ChargeOutcome.CHARGED
    }

    fun snapshot(): AllowanceSnapshot = when (val state = readState()) {
        is LedgerState.Established -> AllowanceSnapshot(state.records, state.bytes)
        else -> AllowanceSnapshot(0, 0)
    }

    fun isClosed(): Boolean = closed || readState() is LedgerState.Closed

    internal fun bindingAndEqualityForTest(): String = "$binding:$equality"

    internal fun pendingIntentForTest(): String? = AllowanceDocuments.parseObject(readTextOrEmpty(intentFile), AllowanceDocuments.intentDocs())
        ?.let { if (it["state"] == AllowanceDocuments.STATE_PENDING) it["state"] else null }

    // ---- durability ----------------------------------------------------------------------------

    /** Creates the area and the lock artifact as a durability barrier of their own. */
    private fun ensureAreaAndLockArtifact(): Boolean = runCatching {
        if (!area.isDirectory && !area.mkdirs() && !area.isDirectory) return false
        if (!lockFile.isFile && !lockFile.createNewFile() && !lockFile.isFile) return false
        true
    }.getOrDefault(false)

    /**
     * Temp -> file sync -> rename -> DIRECTORY sync. EVERY barrier's failure is reported: a write that
     * cannot prove directory durability is not a durable write, and callers must not treat it as one.
     */
    private fun writeDurably(target: File, text: String, phase: AllowanceWrite): Boolean {
        writeObserver?.invoke(phase)
        val tmp = File(area, "${target.name}.tmp")
        return try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray())
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) throw java.io.IOException("rename failed for ${target.name}")
            (directorySync ?: ::syncDirectoryOrThrow)(area)
            true
        } catch (failure: Exception) {
            runCatching { tmp.delete() }
            false
        }
    }

    /** Directory durability: a failure PROPAGATES, so writeDurably reports it rather than passing. */
    private fun syncDirectoryOrThrow(dir: File) {
        FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    // ---- parsing helpers -----------------------------------------------------------------------

    private fun readTextOrEmpty(file: File): String = runCatching { file.readText() }.getOrDefault("")

    internal sealed interface LedgerState {
        data object Fresh : LedgerState
        data class Established(val id: String, val records: Int, val bytes: Long) : LedgerState
        data class Closed(val reason: String) : LedgerState
    }

    private companion object {
        const val GENESIS_FILE = "genesis.json"
        const val LEDGER_FILE = "ledger.json"
        const val INTENT_FILE = "intent.json"
        const val LOCK_FILE = "usage.lock"
        const val DOMAIN_AREA = "area"
        const val AREA_KEY_CHARS = 32
        const val DOMAIN_BINDING = "binding"
        const val DOMAIN_EQUALITY = "equality"

        val processLocks = ConcurrentHashMap<String, Any>()

        fun processMutexFor(key: String): Any = processLocks.computeIfAbsent(key) { Any() }

        fun digest(domain: String, account: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(domain.toByteArray())
            md.update(0)
            md.update(account.toByteArray())
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** The exact outcome of a charge attempt: a failed barrier is never reported as a refusal or a pass. */
internal enum class ChargeOutcome { CHARGED, REFUSED, FAILED_BARRIER, BARRIER_SETTLE_FAILED }

/** Every durable write the ledger can perform, in protocol order (observable for ordering controls). */
enum class AllowanceWrite {
    INIT_GENESIS_INITIALIZING,
    INIT_LEDGER_ZEROED,
    INIT_INTENT_SETTLED,
    INIT_GENESIS_ESTABLISHED,
    CHARGE_INTENT_PENDING,
    CHARGE_LEDGER,
    CHARGE_INTENT_SETTLED,
    RECONCILE_INTENT_SETTLED,
}

data class AllowanceSnapshot(val chargedRecords: Int, val chargedBytes: Long)
