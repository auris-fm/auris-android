package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Durable capture-allowance accounting for ONE stable contributor account (spec delta b768de49).
 *
 * PROPERTIES THIS CLASS GUARANTEES.
 *  - Usage belongs to the ACCOUNT, not the enrollment epoch: sign-out, re-enrollment, queue purge and
 *    process restart do not reset it. The ledger lives beside, never inside, purgeable contribution
 *    content.
 *  - It only ever moves FORWARD. A missing, corrupt or interrupted ledger never re-derives zero usage;
 *    it closes admission instead. Losing accounting is never silently equivalent to first use.
 *  - A charge is preceded by a durable intent that records either outcome, because a rename alone is
 *    not a durability barrier. Recovery reconciles that exact intent and never mints a token for a
 *    previous process's charge.
 *  - No account name is written to disk: the binding is a digest, with a separate domain value for
 *    equality so two distinct accounts can be detected rather than silently merged.
 *
 * OFFLINE ONLY. Nothing here may run on the VAD collector: [chargeForCapture] and [reconcileForTest]
 * perform file I/O and take a file lock. Admission itself uses [CaptureAllowanceOwner.takeToken].
 */
class CaptureAllowanceLedger(
    root: File,
    private val account: String,
    private val limits: AllowanceLimits,
    private val window: AllowanceWindow,
    /**
     * Records every durable write's target name, in order. Test-only observation of the write protocol:
     * the intent must be durable before the charge it describes.
     */
    private val writeObserver: ((AllowanceWrite) -> Unit)? = null,
) {
    private val binding: String = digest(DOMAIN_BINDING, account)
    private val equality: String = digest(DOMAIN_EQUALITY, account)

    /** Each account owns its own area: account A can never read, share or overwrite account B's usage. */
    private val area: File = File(root, digest(DOMAIN_AREA, account).take(AREA_KEY_CHARS))

    private val genesisFile = File(area, GENESIS_FILE)
    private val ledgerFile = File(area, LEDGER_FILE)
    private val intentFile = File(area, INTENT_FILE)
    private val lockFile = File(area, LOCK_FILE)

    /** The JVM-global guard shared by every instance charging the same account in this process. */
    private fun processMutex(): Any = processMutexFor("$binding|${area.absolutePath}")

    @Volatile
    private var closed: Boolean = false

    /**
     * Reads the durable state. [holdingLock] excludes the lock artifact from the emptiness test:
     * acquiring the lock CREATES it, so a holder that counted it would always read its own fresh
     * area as "someone was here". Every other reader treats a lock artifact as evidence.
     */
    private fun readState(holdingLock: Boolean = false): LedgerState {
        if (!genesisFile.isFile) {
            // No genesis at all. Only a genuinely empty area may initialize: the presence of ANY other
            // artifact (ledger, intent, or a lock that this reader does not hold) proves an owner
            // existed and its accounting may be live.
            val others = listOf(ledgerFile, intentFile) + if (holdingLock) emptyList() else listOf(lockFile)
            return if (others.none { it.isFile }) LedgerState.Fresh else LedgerState.Closed("artifacts-without-genesis")
        }
        val genesis = parseOrNull(readTextOrNull(genesisFile)) ?: return LedgerState.Closed("genesis-corrupt")
        if (genesis["state"] != STATE_ESTABLISHED) return LedgerState.Closed("genesis-${genesis["state"]}")
        if (genesis["binding"] != binding) return LedgerState.Closed("binding-mismatch")
        if (genesis["equality"] != equality) return LedgerState.Closed("binding-mismatch")
        val ledger = parseOrNull(readTextOrNull(ledgerFile)) ?: return LedgerState.Closed("ledger-missing-or-corrupt")
        if (ledger["id"] != genesis["id"]) return LedgerState.Closed("identity-mismatch")
        val records = ledger["chargedRecords"]?.toIntOrNull() ?: return LedgerState.Closed("ledger-corrupt")
        val bytes = ledger["chargedBytes"]?.toLongOrNull() ?: return LedgerState.Closed("ledger-corrupt")
        return LedgerState.Established(genesis["id"] ?: return LedgerState.Closed("genesis-corrupt"), records, bytes)
    }

    /**
     * Establishes accounting for a genuinely empty area: genesis INITIALIZING, then the zeroed ledger,
     * each made durable (file sync, rename, DIRECTORY sync) before the next step, and only then
     * ESTABLISHED. Any interruption leaves a state that reads back as closed.
     */
    private fun initialize(): Boolean {
        val id = java.util.UUID.randomUUID().toString()
        writeDurably(genesisFile, stateJson(STATE_INITIALIZING, id), AllowanceWrite.INIT_GENESIS_INITIALIZING)
        writeDurably(ledgerFile, ledgerJson(id, 0, 0), AllowanceWrite.INIT_LEDGER_ZEROED)
        writeDurably(intentFile, intentJson(STATE_SETTLED, id, 0, 0), AllowanceWrite.INIT_INTENT_SETTLED)
        writeDurably(genesisFile, stateJson(STATE_ESTABLISHED, id), AllowanceWrite.INIT_GENESIS_ESTABLISHED)
        return readState(holdingLock = true) is LedgerState.Established
    }

    private fun stateJson(state: String, id: String): String = "{\"schema\":$SCHEMA,\"state\":\"$state\",\"id\":\"$id\",\"binding\":\"$binding\",\"equality\":\"$equality\"}"

    private fun ledgerJson(id: String, records: Int, bytes: Long): String = "{\"schema\":$SCHEMA,\"id\":\"$id\",\"chargedRecords\":$records,\"chargedBytes\":$bytes}"

    private fun intentJson(state: String, transaction: String, records: Int, bytes: Long): String = "{\"schema\":$SCHEMA,\"state\":\"$state\",\"transaction\":\"$transaction\",\"targetRecords\":$records,\"targetBytes\":$bytes}"

    /** A vanished or unreadable artifact reads as absent, never as an exception escaping admission. */
    private fun readTextOrNull(file: File): String = runCatching { file.readText() }.getOrDefault("")

    /** Flat JSON object reader: string values from group 2, NUMERIC values from group 3. */
    private fun parse(text: String): Map<String, String> = REGEX.findAll(text).associate {
        it.groupValues[1] to (it.groupValues[2].ifEmpty { it.groupValues[3] })
    }

    /** A missing or unparseable artifact is null, never an empty map that could read as valid state. */
    private fun parseOrNull(text: String): Map<String, String>? = parse(text).takeIf { it.isNotEmpty() }

    /**
     * Charges one full reservation, or refuses. Refusal precedes any write: an unestablished window or
     * invalid limits never touch the ledger, so a refusal cannot consume allowance.
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
        if (!limits.admits(if (state is LedgerState.Established) state.records else 0, if (state is LedgerState.Established) state.bytes else 0)) {
            return false
        }
        withFileLock {
            val locked = readState(holdingLock = true)
            when (locked) {
                is LedgerState.Closed -> {
                    closed = true
                    false
                }

                LedgerState.Fresh -> {
                    if (initialize()) {
                        chargeUnderLock()
                    } else {
                        closed = true
                        false
                    }
                }

                is LedgerState.Established -> {
                    // An unsettled intent blocks every later charge until recovery settles it.
                    val intent = parseOrNull(readTextOrNull(intentFile))
                    if (intent != null && intent["state"] == STATE_PENDING) return@withFileLock false
                    chargeUnderLock()
                }
            }
        }
    }

    private fun chargeUnderLock(): Boolean {
        val state = readState() as? LedgerState.Established ?: return false
        if (!limits.admits(state.records, state.bytes)) return false
        val targetRecords = state.records + 1
        val targetBytes = state.bytes + limits.reservationBytes
        val transaction = java.util.UUID.randomUUID().toString()
        // The intent is durable BEFORE the charge, and records the conservative post-charge counters:
        // a barrier failure leaves exactly this evidence for a fresh owner to reconcile.
        writeDurably(intentFile, intentJson(STATE_PENDING, transaction, targetRecords, targetBytes), AllowanceWrite.CHARGE_INTENT_PENDING)
        val applied = runCatching { writeDurably(ledgerFile, ledgerJson(state.id, targetRecords, targetBytes), AllowanceWrite.CHARGE_LEDGER) }.isSuccess
        if (!applied) {
            // The charge is UNCONFIRMED, not "not applied": the intent stays pending for recovery.
            return false
        }
        writeDurably(intentFile, intentJson(STATE_SETTLED, transaction, targetRecords, targetBytes), AllowanceWrite.CHARGE_INTENT_SETTLED)
        return true
    }

    /**
     * Reconciles a pending intent left by a process that died mid-charge. Returns true when the
     * ambiguity was resolved durably, false when it cannot be (the ledger then stays closed).
     *
     * A matching post-charge ledger is accepted only after confirming its durability. A pre-charge
     * match discards the never-issued charge, again only after durable reconciliation. Neither path
     * mints a token: recovery settles accounting, it never re-admits a previous process's charge.
     */
    fun reconcileForTest(): Boolean = synchronized(processMutex()) {
        val state = readState()
        if (state is LedgerState.Closed) {
            closed = true
            return false
        }
        withFileLock {
            val intent = parseOrNull(readTextOrNull(intentFile)) ?: return@withFileLock false
            if (intent["state"] != STATE_PENDING) return@withFileLock true
            val current = readState() as? LedgerState.Established ?: return@withFileLock false
            val targetRecords = intent["targetRecords"]?.toIntOrNull() ?: return@withFileLock false
            val targetBytes = intent["targetBytes"]?.toLongOrNull() ?: return@withFileLock false
            when {
                current.records == targetRecords && current.bytes == targetBytes -> {
                    // Confirm the durable ledger matches the intent, then settle.
                    writeDurably(intentFile, intentJson(STATE_SETTLED, intent["transaction"].orEmpty(), targetRecords, targetBytes), AllowanceWrite.RECONCILE_INTENT_SETTLED)
                    true
                }

                current.records == targetRecords - 1 && current.bytes == targetBytes - limits.reservationBytes -> {
                    // The charge never landed: settle the intent and keep the pre-charge counters.
                    writeDurably(intentFile, intentJson(STATE_SETTLED, intent["transaction"].orEmpty(), current.records, current.bytes), AllowanceWrite.RECONCILE_INTENT_SETTLED)
                    true
                }

                else -> {
                    closed = true
                    false
                }
            }
        }
    }

    fun snapshot(): AllowanceSnapshot = when (val state = readState()) {
        is LedgerState.Established -> AllowanceSnapshot(state.records, state.bytes)
        else -> AllowanceSnapshot(0, 0)
    }

    fun isClosed(): Boolean = closed || readState() is LedgerState.Closed

    internal fun bindingAndEqualityForTest(): String = "$binding:$equality"

    fun pendingIntentForTest(): String? = parseOrNull(readTextOrNull(intentFile))?.let { if (it["state"] == STATE_PENDING) it["state"] else null }

    /** Writes [text] durably: temp file, file sync, rename, DIRECTORY sync. */
    private fun writeDurably(target: File, text: String, phase: AllowanceWrite) {
        writeObserver?.invoke(phase)
        area.mkdirs()
        val tmp = File(area, "${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray())
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) throw java.io.IOException("rename failed for ${target.name}")
        syncDirectory()
    }

    private fun syncDirectory() {
        // Directory durability is a separate barrier from the file's own: without it a crash can lose
        // the rename even though the file's contents were synced.
        runCatching {
            val dirChannel = java.nio.channels.FileChannel.open(area.toPath(), java.nio.file.StandardOpenOption.READ)
            dirChannel.force(true)
            dirChannel.close()
        }
    }

    private fun <T> withFileLock(block: () -> T): T {
        area.mkdirs()
        RandomAccessFile(lockFile, "rw").use { raf ->
            val lock: FileLock? = try {
                raf.channel.tryLock()
            } catch (overlapping: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                // Another owner holds it: never initialize or mutate on the strength of a stale read.
                closed = true
                return block()
            }
            return try {
                block()
            } finally {
                runCatching { lock.release() }
            }
        }
    }

    internal sealed interface LedgerState {
        data object Fresh : LedgerState
        data class Established(val id: String, val records: Int, val bytes: Long) : LedgerState
        data class Closed(val reason: String) : LedgerState
    }

    private companion object {
        const val SCHEMA = 1
        const val GENESIS_FILE = "genesis.json"
        const val LEDGER_FILE = "ledger.json"
        const val INTENT_FILE = "intent.json"
        const val LOCK_FILE = "usage.lock"
        const val STATE_INITIALIZING = "INITIALIZING"
        const val STATE_ESTABLISHED = "ESTABLISHED"
        const val STATE_PENDING = "PENDING"
        const val STATE_SETTLED = "SETTLED"
        const val DOMAIN_AREA = "area"
        const val AREA_KEY_CHARS = 32
        const val DOMAIN_BINDING = "binding"
        const val DOMAIN_EQUALITY = "equality"
        val REGEX = Regex("\"([A-Za-z]+)\":(?:\"([^\"]*)\"|(\\d+))")

        /** One lock per (process, account area): the JVM-global guard for the charge critical section. */
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

/** Every durable write the ledger can perform, in protocol order. */
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
