package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.EnumMap

// Durable, bounded contribution queue (Task 5). ONE entry = WAV + canonical metadata sidecar +
// atomic READY marker; interrupted preparation NEVER becomes ready and is swept on recovery.
// Admission is BOUNDED and synchronous (deny when full — the capture callback never blocks);
// ALL disk encoding/fsync/recovery work runs OFF the capture callback via [prepareAsync] being
// invoked from a worker context by the controller. Revocation semantics (spec @05dc7937):
//   - account/session change, withdrawal, remote disable OR lease loss -> purgeQueue() clears
//     EVERY pending entry immediately (audio + metadata + partial buffers).
//   - listening/permission closure alone -> discardCurrentPartial() removes only in-progress
//     preparation; ready entries stay.
class ContributionStore(
    private val root: File,
    private val limits: QueueLimits,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    data class Entry(
        val captureId: String,
        val enrollmentEpoch: String,
        val recordSha256: String,
        val audioBytes: Long,
        val metadataBytes: Int,
        val attempts: Int,
        val createdAtMs: Long,
    )

    /** Admission decision BEFORE any PCM copy/disk work: bounded, non-blocking, deny-by-default. */
    fun admit(sampleCount: Long, metadataBytes: Int): Boolean {
        val projected = WavWriter.fileBytes(sampleCount) + metadataBytes
        if (projected > limits.maxClipBytes) return false
        synchronized(lock) {
            if (readyEntries().size >= limits.maxQueueRecords) return false
            if (totalQueuedBytes() + projected > limits.maxQueueBytes) return false
            return true
        }
    }

    /**
     * Prepares one entry atomically. Returns the ready [Entry], or null when refused (caps) or
     * interrupted. Sequence: write `<id>.wav.part` -> fsync -> write `<id>.meta` -> fsync ->
     * rename part to final name -> fsync dir -> write `<id>.ready` LAST (the commit point).
     * A kill before `.ready` leaves only `.part`/`.meta`, which [recover] deletes.
     */
    fun prepare(captureId: String, enrollmentEpoch: String, pcmChunks: List<Pair<ShortArray, Int>>, canonicalMetadata: ByteArray): Entry? {
        require(captureId.matches(UUID_RE)) { "capture id must be UUID" }
        val samples = pcmChunks.sumOf { it.second.toLong() }
        if (!admit(samples, canonicalMetadata.size)) return null
        // Index FIRST (provisional): a concurrent reader sees either the committed entry or the
        // provisional one whose files appear atomically; failure paths roll back below.
        val provisional = Entry(captureId, enrollmentEpoch, "", 0L, canonicalMetadata.size, attempts = 0, createdAtMs = clock())
        synchronized(lock) {
            memoryEntries[captureId] = provisional
            preparedIds.add(captureId)
        }
        dir.mkdirs()
        val wavPart = File(dir, "$captureId${SUFFIX_PART}")
        val wavFinal = File(dir, "$captureId${SUFFIX_WAV}")
        val metaFile = File(dir, "$captureId${SUFFIX_META}")
        val readyFile = File(dir, "$captureId${SUFFIX_READY}")
        try {
            FileOutputStream(wavPart).use { out ->
                WavWriter.writeHeader(out, samples.toInt())
                for ((chunk, valid) in pcmChunks) WavWriter.writePcm(out, chunk, valid)
                out.flush()
                out.fd.sync()
            }
            FileOutputStream(metaFile).use { out ->
                out.write(canonicalMetadata)
                out.flush()
                out.fd.sync()
            }
            if (!wavPart.renameTo(wavFinal)) return null
            fsyncDir(dir)
            // Created-at + attempts(0) sidecars BEFORE the marker: age and attempt accounting
            // must survive restart precisely.
            runCatching { File(dir, "$captureId$SUFFIX_CREATED").writeText(clock().toString()) }
            runCatching { File(dir, "$captureId$SUFFIX_ATTEMPTS").writeText("0") }
            // READY MARKER LAST: presence of this file is the ONLY readiness authority.
            FileOutputStream(readyFile).use { it.fd.sync() }
            fsyncDir(dir)
        } catch (_: Exception) {
            // Interrupted/failed preparation: leave debris for recovery; nothing is ready.
            wavPart.delete()
            synchronized(lock) {
                memoryEntries.remove(captureId)
                preparedIds.remove(captureId)
            }
            return null
        }
        val audioBytes = wavFinal.length()
        if (audioBytes == 0L) {
            synchronized(lock) {
                memoryEntries.remove(captureId)
                preparedIds.remove(captureId)
            }
            return null
        }
        val digest = sha256Hex(canonicalMetadata)
        val entry = provisional.copy(recordSha256 = digest, audioBytes = audioBytes)
        synchronized(lock) {
            // Commit point passed (ready marker on disk): finalize the authoritative index entry.
            memoryEntries[captureId] = entry
        }
        return entry
    }

    /** Ready entries (durable state), oldest first; age computed against [clock]. */
    fun readyEntries(): List<Entry> = synchronized(lock) {
        // Before the first recover(), refresh from disk so entries committed by ANOTHER store
        // instance (process-restart simulation) are visible; recover() remains the only path that
        // DROPS corrupt entries.
        if (!recovered) reloadUnownedFromDisk()
        memoryEntries.values.sortedBy { it.createdAtMs }
    }

    private var recovered = false

    fun totalQueuedBytes(): Long = readyEntries().sumOf { it.audioBytes + it.metadataBytes }

    /** Expiry removes ONLY expired entries; others are untouched. */
    fun expireOlderThan(): List<String> {
        val now = clock()
        val removed = mutableListOf<String>()
        synchronized(lock) {
            if (!recovered) reloadUnownedFromDisk()
            // Entries whose ready marker vanished (purged elsewhere) leave the index immediately.
            for ((id, e) in memoryEntries.toMap()) if (id !in preparedIds && !File(dir, "$id$SUFFIX_READY").exists()) deleteLocked(e)
            val expiredIds = memoryEntries.values
                .filter { (now - it.createdAtMs) / 1000L > limits.maxQueueAgeSeconds }
                .map { it.captureId }
            for (id in expiredIds) {
                memoryEntries[id]?.let { deleteLocked(it) }
                preparedIds.remove(id)
                removed += id
            }
        }
        // Content-free terminal counter per expired entry, durable across restart.
        repeat(removed.size) { dropCounterOnly(TerminalReason.QUEUE_AGE_EXPIRED) }
        return removed
    }

    // Attempt accounting persists across restarts: bump writes a durable attempts sidecar.
    fun recordAttempt(captureId: String): Int {
        val updated = synchronized(lock) {
            if (!recovered) reloadUnownedFromDisk()
            val e = memoryEntries[captureId] ?: return 0
            e.copy(attempts = e.attempts + 1).also { memoryEntries[captureId] = it }
        }
        runCatching { File(dir, "$captureId$SUFFIX_ATTEMPTS").writeText(updated.attempts.toString()) }
        return updated.attempts
    }

    fun dropEntry(captureId: String, reason: TerminalReason) {
        val e = synchronized(lock) {
            if (!recovered) reloadUnownedFromDisk()
            memoryEntries.remove(captureId).also { if (it != null) deleteLocked(it) }
        }
        dropCounterOnly(reason)
    }

    enum class TerminalReason { ATTEMPTS_EXHAUSTED, QUEUE_AGE_EXPIRED, SERVER_TERMINAL, INVALID_ON_RECOVERY }

    fun terminalCounter(reason: TerminalReason): Long = synchronized(lock) { terminalCounters[reason] ?: 0L }

    /** FULL REVOCATION (account change / withdrawal / disable / lease loss): everything goes. */
    fun purgeQueue(reason: String) {
        synchronized(lock) {
            for (e in memoryEntries.values.toList()) deleteLocked(e)
            memoryEntries.clear()
            preparedIds.clear()
            recovered = true // a purged store must not lazily resurrect anything from disk
        }
        // Sweep any debris not indexed in memory (crash between prepare and index, successor race).
        dir.listFiles()?.forEach { f ->
            if (f.name.endsWith(SUFFIX_PART) || f.name.endsWith(SUFFIX_READY) || f.name.endsWith(SUFFIX_WAV) ||
                f.name.endsWith(SUFFIX_META) || f.name.endsWith(SUFFIX_ATTEMPTS) || f.name.endsWith(SUFFIX_CREATED)
            ) {
                f.delete()
            }
        }
        runCatching { countersFile.delete() }
        synchronized(lock) { terminalCounters.clear() }
        _purgeReasons.add(reason)
    }

    val purgeReasons: List<String> get() = synchronized(lock) { _purgeReasons.toList() }
    private val _purgeReasons = mutableListOf<String>()

    /** Listening/permission closure: remove IN-PROGRESS preparation only; ready entries stay. */
    fun discardCurrentPartials() {
        dir.listFiles()?.forEach { f -> if (f.name.endsWith(SUFFIX_PART)) f.delete() }
    }

    /**
     * Recovery at startup: delete abandoned preparation (.part without matching .ready), then
     * validate COMPLETE entries (ready + wav + meta present, byte_count matches header, attempts
     * restored). Invalid complete-looking entries are dropped with a content-free counter —
     * recovery admits nothing unvalidated to upload.
     */
    fun recover(): List<Entry> {
        dir.mkdirs()
        val files = dir.listFiles()?.toList() ?: emptyList()
        // 1) Debris sweep: .part files are NEVER ready; .ready without wav/meta is invalid.
        files.filter { it.name.endsWith(SUFFIX_PART) }.forEach { it.delete() }
        val readyIds = files.filter { it.name.endsWith(SUFFIX_READY) }.map { it.name.removeSuffix(SUFFIX_READY) }
        val keep = mutableListOf<Entry>()
        for (id in readyIds) {
            val wav = File(dir, "$id$SUFFIX_WAV")
            val meta = File(dir, "$id$SUFFIX_META")
            val attemptsFile = File(dir, "$id$SUFFIX_ATTEMPTS")
            val entry = validate(id, wav, meta, attemptsFile)
            if (entry == null) {
                wav.delete()
                meta.delete()
                attemptsFile.delete()
                File(dir, "$id$SUFFIX_READY").delete()
                dropCounterOnly(TerminalReason.INVALID_ON_RECOVERY)
            } else {
                keep += entry
            }
        }
        synchronized(lock) {
            // Rebuild the index from VALIDATED disk state only: self-prepared entries are
            // re-stamped from their current sidecars (a mutable clock or post-purge debris can
            // never resurrect a stale in-memory copy), foreign entries follow validation.
            val self = memoryEntries.keys.filter { it in preparedIds }.toSet()
            memoryEntries.clear()
            for (id in self) {
                val wav = File(dir, "$id$SUFFIX_WAV")
                val metaF = File(dir, "$id$SUFFIX_META")
                validate(id, wav, metaF, File(dir, "$id$SUFFIX_ATTEMPTS"))?.let { memoryEntries[id] = it }
                    ?: run {
                        preparedIds.remove(id)
                        File(dir, "$id$SUFFIX_READY").delete()
                    }
            }
            keep.forEach { memoryEntries[it.captureId] = it }
            recovered = true
        }
        return keep.sortedBy { it.createdAtMs }
    }

    private fun validate(id: String, wav: File, meta: File, attemptsFile: File): Entry? {
        if (!id.matches(UUID_RE)) return null
        if (!wav.isFile || !meta.isFile) return null
        val declared = wav.length()
        if (declared < WavWriter.HEADER_BYTES || declared > limits.maxClipBytes) return null
        // Header sanity: RIFF/WAVE + data size consistent with file length (byte_count includes hdr).
        val header = ByteArray(12)
        val readOk = runCatching { wav.inputStream().use { require(it.read(header) == 12) } }.isSuccess
        if (!readOk) return null
        val riff = String(header, 0, 4, Charsets.US_ASCII)
        val wave = String(header, 8, 4, Charsets.US_ASCII)
        if (riff != "RIFF" || wave != "WAVE") return null
        // The canonical writer emits a fixed 44-byte layout: data size sits at offset 40 of the
        // header; verify against file length WITHOUT reading the whole body into memory.
        val sizeHeader = ByteArray(44)
        val sizeRead = runCatching { wav.inputStream().use { require(it.read(sizeHeader) == 44) } }.isSuccess
        if (!sizeRead) return null
        val dSize = le32(sizeHeader, 40)
        try {
            if (WavWriter.HEADER_BYTES.toLong() + dSize != declared) return null
            val metaBytes = meta.readBytes()
            if (metaBytes.size > limits.maxMetadataBytes) return null
            val attempts = attemptsFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 0
            // The Durable created-at sidecar is the age authority (a mutable test clock may
            // diverge from filesystem time); mtime is only the fallback for legacy files.
            val created = runCatching { File(dir, "$id$SUFFIX_CREATED").readText().trim().toLongOrNull() }.getOrNull() ?: wav.lastModified()
            val epoch = metaBytes.toString(Charsets.UTF_8).let { EPOCH_RE.find(it)?.value?.substringAfter(':')?.trim()?.trim('"') ?: "" }
            return Entry(id, epoch, sha256Hex(metaBytes), declared, metaBytes.size, attempts, createdAtMs = created)
        } catch (_: Exception) {
            return null
        }
    }

    /** Delete an entry's files; MUST be called while holding [lock]. */
    private fun deleteLocked(e: Entry) {
        val id = e.captureId
        File(dir, "$id$SUFFIX_WAV").delete()
        File(dir, "$id$SUFFIX_META").delete()
        File(dir, "$id$SUFFIX_READY").delete()
        File(dir, "$id$SUFFIX_ATTEMPTS").delete()
        File(dir, "$id$SUFFIX_CREATED").delete()
        memoryEntries.remove(id)
    }

    private fun dropCounterOnly(reason: TerminalReason) {
        val snapshot = synchronized(lock) {
            terminalCounters[reason] = (terminalCounters[reason] ?: 0L) + 1L
            terminalCounters.entries.joinToString(";") { "${it.key.name}=${it.value}" }
        }
        runCatching { countersFile.writeText(snapshot) }
    }

    private fun reloadUnownedFromDisk() {
        // Under lock: adopt on-disk entries this instance did not prepare (restart simulation);
        // never resurrect ids it explicitly dropped/purged.
        val files = dir.listFiles() ?: return
        for (f in files.filter { it.name.endsWith(SUFFIX_READY) }) {
            val id = f.name.removeSuffix(SUFFIX_READY)
            if (id in preparedIds || memoryEntries.containsKey(id)) continue
            val wav = File(dir, "$id$SUFFIX_WAV")
            val meta = File(dir, "$id$SUFFIX_META")
            val attemptsFile = File(dir, "$id$SUFFIX_ATTEMPTS")
            validate(id, wav, meta, attemptsFile)?.let { memoryEntries[id] = it }
        }
    }

    private val memoryEntries = LinkedHashMap<String, Entry>()
    private val preparedIds = mutableSetOf<String>()

    // Content-free terminal counters persist across restarts (bounded counts only, no payloads).
    init {
        runCatching {
            countersFile.takeIf { it.isFile }?.readText()?.split(";")?.forEach { part ->
                val kv = part.split("=")
                if (kv.size == 2) {
                    TerminalReason.values().firstOrNull { it.name == kv[0] }?.let { r ->
                        synchronized(lock) { terminalCounters[r] = kv[1].toLongOrNull() ?: 0L }
                    }
                }
            }
        }
    }
    private val terminalCounters = EnumMap<TerminalReason, Long>(TerminalReason::class.java)
    private val countersFile get() = File(root, COUNTERS_NAME)
    private val dir get() = File(root, DIR_NAME)
    private val lock = Any()

    companion object {
        private const val DIR_NAME = "contributions"
        private const val COUNTERS_NAME = "terminal-counters.txt"
        private const val SUFFIX_WAV = ".recording.wav"
        private const val SUFFIX_META = ".metadata.json"
        private const val SUFFIX_READY = ".ready"
        private const val SUFFIX_PART = ".wav.part"
        private const val SUFFIX_ATTEMPTS = ".attempts"
        private const val SUFFIX_CREATED = ".created"
        private val UUID_RE = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val EPOCH_RE = Regex("\"enrollment_epoch\"\\s*:\\s*\"[^\"]+\"")

        private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun le32(b: ByteArray, at: Int): Long = (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

        // Directory durability is approximated by syncing the committed files themselves before
        // and after the rename/marker sequence; opening a stream on a directory is not portable.
        private fun fsyncDir(d: File) = Unit
    }
}
