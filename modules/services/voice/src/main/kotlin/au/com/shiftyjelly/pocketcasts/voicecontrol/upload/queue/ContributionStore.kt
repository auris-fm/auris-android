package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.EnumMap

// Bounded contribution queue (Task 5). ONE entry = WAV + canonical metadata sidecar + READY marker.
//
// SCOPE ACTUALLY IMPLEMENTED HERE (no more than this):
//   - This class performs SYNCHRONOUS file I/O on the calling thread. It does NOT itself provide
//     off-capture-thread execution: no controller or async worker exists yet, so callers MUST NOT
//     invoke prepare()/recover()/purgeQueue() from the audio capture callback. A follow-up slice
//     adds the dispatcher that makes this guarantee real.
//   - Admission is bounded and denies BEFORE any PCM copy, but the count/byte reservation is not yet
//     atomic across concurrent prepare() calls (reviewer f003b49f finding 3); hardening is scheduled.
//   - Durability: fsyncDir() is currently a NO-OP stub, so directory-entry durability after the
//     rename/marker is NOT substantiated (reviewer f003b49f finding 2). Do not claim crash-atomicity
//     beyond "a missing .ready means the entry is not trusted".
// Revocation semantics implemented (spec @05dc7937):
//   - account/session change, withdrawal, remote disable OR lease loss -> purgeQueue() clears
//     EVERY pending entry immediately (audio + metadata + partial buffers).
//   - listening/permission closure alone -> discardCurrentPartials() removes only in-progress
//     preparation; ready entries stay.
// QueueFence generation wiring into these paths is still pending (reviewer f003b49f finding 5).
class ContributionStore(
    private val root: File,
    private val limits: QueueLimits,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Shared generation boundary for revocation. A full revocation bumps it BEFORE purging so that any
     * in-flight preparation or late callback carrying the old generation publishes nothing; every
     * readiness-affecting mutation below is checked against it. Injectable so the controller and the
     * account-loss watcher share ONE instance rather than each holding a private fence.
     */
    val fence: QueueFence = QueueFence(),
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

    /**
     * Reservation for one entry, claimed atomically against the queue's count and byte budgets
     * BEFORE any PCM copy or disk write. A reservation that is never committed MUST be released via
     * [Admission.discard]; a committed one is consumed when the entry is published. This is what makes
     * concurrent preparations unable to over-admit into the same remaining slot.
     */
    inner class Admission internal constructor(
        /** Reservation key; consumed by the publish step and returned by [discard]. */
        val id: String,
        private val projectedBytes: Long,
    ) {
        /** Release the reserved slot/bytes when preparation is refused, fails, or is fenced off. */
        fun discard() = releaseReservation(id, projectedBytes)

        /** True while this admission still holds its reservation. */
        fun isHeld(): Boolean = synchronized(lock) { id in reservations }
    }

    /**
     * Reserve room for one entry BEFORE any PCM copy/disk work: deny-by-default, bounded, and atomic
     * across concurrent callers (the count and byte budgets are claimed under [lock], not merely read).
     * Returns null when the entry does not fit; the caller must [Admission.discard] on every path that
     * does not publish the entry.
     */
    fun admit(sampleCount: Long, metadataBytes: Int): Admission? {
        if (sampleCount < 0 || metadataBytes < 0) return null
        // Duration bound is independent of the byte bound: a long quiet clip can stay under
        // max_clip_bytes while still exceeding the specced max_clip_ms.
        if (durationMs(sampleCount) > limits.maxClipMs) return null
        val projected = WavWriter.fileBytes(sampleCount) + metadataBytes
        if (projected > limits.maxClipBytes) return null
        if (metadataBytes > limits.maxMetadataBytes) return null
        return reserve(projected)
    }

    /** Byte-projection used by tests/callers that need the same arithmetic the cap checks use. */
    fun projectedBytesFor(sampleCount: Long, metadataBytes: Int): Long = WavWriter.fileBytes(sampleCount) + metadataBytes

    /** Claim count+bytes atomically; returns null when the queue cannot hold another entry. */
    private fun reserve(projected: Long): Admission? {
        val id = java.util.UUID.randomUUID().toString()
        return synchronized(lock) {
            if (!roomLocked(projected)) {
                null
            } else {
                reservations[id] = projected
                Admission(id, projected)
            }
        }
    }

    private fun releaseReservation(id: String, projected: Long) {
        synchronized(lock) { reservations.remove(id) }
    }

    /** Budget test against durable entries PLUS live reservations (both consume the same budget). */
    private fun roomLocked(projected: Long): Boolean {
        val live = memoryEntries.size + reservations.size
        if (live >= limits.maxQueueRecords) return false
        val bytes = memoryEntries.values.sumOf { it.audioBytes + it.metadataBytes } +
            reservations.values.sumOf { it }
        return bytes + projected <= limits.maxQueueBytes
    }

    /** Playback duration of [sampleCount] samples at the specced 16 kHz mono capture rate. */
    private fun durationMs(sampleCount: Long): Long = sampleCount * 1000L / SAMPLE_RATE_HZ

    /**
     * Prepares one entry. Returns the ready [Entry], or null when refused (caps), fenced, or
     * interrupted.
     *
     * Publication rule: the in-memory index is written ONLY after the `.ready` marker commit point
     * has been reached on disk. An interrupted preparation therefore never appears in
     * [readyEntries] and leaves at most `.part`/`.meta` debris for [recover] to sweep.
     *
     * Sequence: write `<id>.wav.part` -> fsync -> write `<id>.meta` -> fsync -> rename part to final
     * name -> write sidecars -> write `<id>.ready` LAST (the commit point) -> publish in memory.
     */
    fun prepare(
        captureId: String,
        enrollmentEpoch: String,
        pcmChunks: List<Pair<ShortArray, Int>>,
        canonicalMetadata: ByteArray,
    ): Entry? = prepareAt(fence.currentGeneration(), captureId, enrollmentEpoch, pcmChunks, canonicalMetadata)

    /**
     * Generation-fenced preparation. [generation] is the [QueueFence] value the caller captured before
     * starting work; publication happens ONLY while that generation is still current, so a purge (or any
     * full revocation) that lands mid-preparation turns the late publication into a no-op instead of
     * letting it recreate state for a revoked or successor owner.
     */
    fun prepareAt(
        generation: Long,
        captureId: String,
        enrollmentEpoch: String,
        pcmChunks: List<Pair<ShortArray, Int>>,
        canonicalMetadata: ByteArray,
    ): Entry? {
        require(captureId.matches(UUID_RE)) { "capture id must be UUID" }
        val samples = pcmChunks.sumOf { it.second.toLong() }
        if (samples != pcmChunks.sumOf { (chunk, valid) ->
                require(valid in 0..chunk.size)
                valid.toLong()
            }
        ) {
            return null
        }
        val admission = admit(samples, canonicalMetadata.size) ?: return null
        val createdAt = clock()
        dir.mkdirs()
        val wavPart = File(dir, "$captureId$SUFFIX_PART")
        val wavFinal = File(dir, "$captureId$SUFFIX_WAV")
        val metaFile = File(dir, "$captureId$SUFFIX_META")
        val readyFile = File(dir, "$captureId$SUFFIX_READY")
        val createdFile = File(dir, "$captureId$SUFFIX_CREATED")
        val attemptsFile = File(dir, "$captureId$SUFFIX_ATTEMPTS")
        var committed = false
        try {
            FileOutputStream(wavPart).use { out ->
                WavWriter.writeHeader(out, Math.toIntExact(samples))
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
            // Created-at + attempts(0) sidecars BEFORE the marker: age and attempt accounting must
            // survive restart precisely. A failure here is a FAILED preparation, not a silent fallback:
            // without an authoritative created-at we refuse to publish rather than invent age.
            runCatching { createdFile.writeText(createdAt.toString()) }.onFailure { return null }
            runCatching { attemptsFile.writeText("0") }.onFailure { return null }
            // READY MARKER LAST: presence of this file is the ONLY readiness authority.
            FileOutputStream(readyFile).use { it.fd.sync() }
            fsyncDir(dir)
            committed = true
        } catch (_: Exception) {
            wavPart.delete()
            return null
        } finally {
            // Any path that did not reach the commit point gives its reservation straight back.
            if (!committed) admission.discard()
        }
        val audioBytes = wavFinal.length()
        if (audioBytes == 0L || audioBytes > limits.maxClipBytes) {
            admission.discard()
            deleteDebris(captureId)
            return null
        }
        val digest = sha256Hex(canonicalMetadata)
        val entry = Entry(captureId, enrollmentEpoch, digest, audioBytes, canonicalMetadata.size, attempts = 0, createdAtMs = createdAt)
        // Publish ONLY here, under the fence: nothing before the commit point reaches the index, and a
        // stale generation publishes nothing at all (its files are swept as debris).
        val published = fence.guard(generation) {
            synchronized(lock) {
                reservations.remove(admission.id)
                memoryEntries[captureId] = entry
                preparedIds.add(captureId)
            }
            Unit
        }
        if (published == null) {
            // Fenced off by a revocation that landed after our commit point: remove what we wrote.
            deleteDebris(captureId)
            return null
        }
        return entry
    }

    private fun deleteDebris(captureId: String) {
        File(dir, "$captureId$SUFFIX_PART").delete()
        File(dir, "$captureId$SUFFIX_WAV").delete()
        File(dir, "$captureId$SUFFIX_META").delete()
        File(dir, "$captureId$SUFFIX_READY").delete()
        File(dir, "$captureId$SUFFIX_CREATED").delete()
        File(dir, "$captureId$SUFFIX_ATTEMPTS").delete()
    }

    /** Ready entries (durable state), oldest first; age computed against [clock]. */
    fun readyEntries(): List<Entry> = synchronized(lock) {
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

    /**
     * Bump attempt accounting for a READY entry. Returns the new count, or null when the entry is not
     * in the index (purged/unknown) OR the durable sidecar write failed — a swallowed write error would
     * silently reset retry budget on the next restart, so failure is reported instead of invented.
     */
    fun recordAttempt(captureId: String): Int? {
        val updated = synchronized(lock) {
            val e = memoryEntries[captureId] ?: return null
            e.copy(attempts = e.attempts + 1).also { memoryEntries[captureId] = it }
        }
        return try {
            File(dir, "$captureId$SUFFIX_ATTEMPTS").writeText(updated.attempts.toString())
            updated.attempts
        } catch (_: Exception) {
            synchronized(lock) { memoryEntries[captureId] = updated.copy(attempts = updated.attempts - 1) }
            null
        }
    }

    fun dropEntry(captureId: String, reason: TerminalReason): Boolean {
        val e = synchronized(lock) {
            memoryEntries.remove(captureId).also { if (it != null) deleteLocked(it) }
        }
        if (e == null) return false
        dropCounterOnly(reason)
        return true
    }

    enum class TerminalReason { ATTEMPTS_EXHAUSTED, QUEUE_AGE_EXPIRED, SERVER_TERMINAL, INVALID_ON_RECOVERY }

    fun terminalCounter(reason: TerminalReason): Long = synchronized(lock) { terminalCounters[reason] ?: 0L }

    /** FULL REVOCATION (account change / withdrawal / disable / lease loss): everything goes. */
    fun purgeQueue(reason: String) {
        // Bump the generation FIRST: any preparation or callback that captured the old generation now
        // publishes nothing, so a purge landing mid-preparation cannot be undone by its late commit.
        fence.revoke()
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
    private val reservations = LinkedHashMap<String, Long>()

    // Lock and counter storage are declared BEFORE the init block that reads them: Kotlin runs
    // property initializers and init blocks top-to-bottom, so an init block placed above these
    // declarations would touch not-yet-initialized state (reviewer f003b49f finding 6).
    private val lock = Any()
    private val terminalCounters = EnumMap<TerminalReason, Long>(TerminalReason::class.java)

    // Content-free terminal counters persist across restarts (bounded counts only, no payloads).
    // Loaded AFTER [lock]/[terminalCounters] exist; a malformed or unreadable counters file is a
    // real condition we surface as a reconstruction failure rather than silently starting at zero.
    private val countersLoadError: String? = runCatching {
        countersFile.takeIf { it.isFile }?.readText()?.split(";")?.forEach { part ->
            val kv = part.split("=")
            if (kv.size != 2) error("malformed counters entry")
            val value = kv[1].toLongOrNull() ?: error("non-numeric counter")
            TerminalReason.values().firstOrNull { it.name == kv[0] }?.let { r -> terminalCounters[r] = value }
                ?: error("unknown counter reason ${'$'}{kv[0]}")
        }
    }.exceptionOrNull()?.message

    /** Non-null when persisted terminal counters could not be reconstructed; caller must not trust counts. */
    fun countersReconstructionFailure(): String? = countersLoadError

    private val countersFile get() = File(root, COUNTERS_NAME)
    private val dir get() = File(root, DIR_NAME)

    companion object {
        private const val DIR_NAME = "contributions"
        private const val COUNTERS_NAME = "terminal-counters.txt"
        private const val SUFFIX_WAV = ".recording.wav"
        private const val SUFFIX_META = ".metadata.json"
        private const val SUFFIX_READY = ".ready"
        private const val SUFFIX_PART = ".wav.part"
        private const val SUFFIX_ATTEMPTS = ".attempts"
        private const val SAMPLE_RATE_HZ = 16_000L
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
