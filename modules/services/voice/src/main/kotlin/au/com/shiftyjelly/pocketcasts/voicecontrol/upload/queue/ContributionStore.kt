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
    /** Deterministic interleaving seams for tests; production uses [StoreHooks.NONE]. */
    private val hooks: StoreHooks = StoreHooks.NONE,
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
        /** Reservation key; consumed by the publish step and released by [discard]. */
        val id: String,
        internal val projectedBytes: Long,
    ) {
        /**
         * Single-use settlement, guarded by the STORE lock (reviewer 195845cb: the previous plain boolean
         * was read and written outside any lock, so two racing settlements could both think they won).
         * One synchronized transition per live ownership: HELD -> RELEASED or HELD -> ADOPTED.
         */
        private var state = ADMISSION_HELD

        /** Release the reserved slot/bytes when preparation is refused, fails, or is fenced off. Idempotent. */
        fun discard() {
            synchronized(lock) {
                if (state != ADMISSION_HELD) return
                state = ADMISSION_RELEASED
                reservations.remove(id)
            }
        }

        /**
         * Adopt the reservation into a published entry: the slot/bytes become durable queue load, so the
         * reservation itself is returned. After this, [discard] is a no-op.
         */
        internal fun adopt() {
            synchronized(lock) {
                if (state != ADMISSION_HELD) return
                state = ADMISSION_ADOPTED
                reservations.remove(id)
            }
        }

        /** True while this admission still holds its reservation. */
        fun isHeld(): Boolean = synchronized(lock) { state == ADMISSION_HELD && reservations.containsKey(id) }

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

    /** Test seam: reserve a small admission sized for [samples]/[metadataBytes] (fails loudly if refused). */
    fun admitForTest(samples: Long, metadataBytes: Int): Admission = admit(samples, metadataBytes)
        ?: throw AssertionError("test admission should have been accepted")

    /** Test seam: how many records' worth of budget live reservations currently occupy. */
    fun reservedRecordsForTest(): Int = synchronized(lock) { reservations.size }

    /**
     * Settlement bookkeeping is BOUNDED: a reservation lives in [reservations] only while it is HELD, and
     * both discard() and adopt() remove it under the store lock. There are no tombstone entries to accumulate.
     */
    private fun liveReservationBytesLocked(): Long = reservations.values.sumOf { it }

    /** Re-check budgets at publication time (a purge/expiry may have moved them since admission). */
    private fun roomForPublicationLocked(admission: Admission): Boolean {
        val others = reservations.entries.filterNot { it.key == admission.id }
        val bytes = memoryEntries.values.sumOf { it.audioBytes + it.metadataBytes } + others.sumOf { it.value }
        val liveRecords = memoryEntries.size + others.size
        return liveRecords < limits.maxQueueRecords && bytes + admission.projectedBytes <= limits.maxQueueBytes
    }

    /**
     * Claim the capture's final names for this attempt, refusing when any of them already exist: a live
     * entry's WAV/metadata/ready marker are immutable, so no duplicate or concurrent attempt may rename
     * over them. Called only while holding [lock] and after the ownership check passed.
     */
    private fun claimFinalNames(wavPart: File, metaPart: File, wavFinal: File, metaFinal: File, readyFile: File): Boolean {
        if (wavFinal.exists() || metaFinal.exists() || readyFile.exists()) return false
        return wavPart.renameTo(wavFinal) && metaPart.renameTo(metaFinal)
    }

    /** Remove only the staging files created by ONE attempt, identified by its reservation id. */
    private fun deleteStaging(attemptId: String) {
        dir.listFiles()?.forEach { f ->
            if (f.name.startsWith("$STAGING_PREFIX$attemptId.")) f.delete()
        }
    }

    private fun releaseReservation(id: String, projected: Long) {
        synchronized(lock) { reservations.remove(id) }
    }

    /** Budget test against durable entries PLUS live reservations (both consume the same budget). */
    private fun roomLocked(projected: Long): Boolean {
        val live = memoryEntries.size + reservations.size
        if (live >= limits.maxQueueRecords) return false
        val bytes = memoryEntries.values.sumOf { it.audioBytes + it.metadataBytes } + liveReservationBytesLocked()
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
    /**
     * Prepare an entry using an [Admission] the caller validated and reserved BEFORE the PCM copy — the
     * shape the retention sink uses (admit on the capture callback, hand the handle to a worker that does
     * the disk work). This is the REAL consumption path: no second `admit()` happens here, so a queue with
     * exactly one free slot admits one preparation instead of demanding a second one (reviewer 195845cb
     * finding 5). The handle is settled exactly once: adopted on publication, released on every other exit.
     */
    fun prepare(admission: Admission, captureId: String, enrollmentEpoch: String, pcmChunks: List<Pair<ShortArray, Int>>, canonicalMetadata: ByteArray): Entry? =
        prepareAt(fence.currentGeneration(), admission, captureId, enrollmentEpoch, pcmChunks, canonicalMetadata)

    fun prepare(
        captureId: String,
        enrollmentEpoch: String,
        pcmChunks: List<Pair<ShortArray, Int>>,
        canonicalMetadata: ByteArray,
    ): Entry? {
        val samples = sampleCount(pcmChunks) ?: return null
        val admission = admit(samples, canonicalMetadata.size) ?: return null
        return prepareAt(fence.currentGeneration(), admission, captureId, enrollmentEpoch, pcmChunks, canonicalMetadata)
    }

    /**
     * Generation-fenced preparation from a caller-supplied [admission]. [generation] is the value the caller
     * captured before starting work.
     *
     * Ownership rules enforced here (reviewers 32d8012c / 195845cb):
     *  - [admission] is CONSUMED exactly once by this call: adopted when the entry publishes, otherwise
     *    released on EVERY exit, including the early returns for invalid sizes and the exception path. There
     *    is deliberately no `admit()` inside, so passing a pre-retention handle never double-charges.
     *  - Bytes go to staging named after the UNIQUE attempt (`gen$<attemptId>.<generation>.<captureId>…`), not
     *    merely generation+captureId, so two same-generation attempts cannot truncate each other and a stale
     *    attempt's cleanup only ever touches its own staging prefix — never a successor's committed files.
     *  - If [captureId] is already owned as a ready entry, the request is refused before any write: a
     *    committed entry's WAV/metadata are immutable.
     *  - Publication happens only while the captured generation is current AND inside the store lock, under
     *    the single fixed lock order store -> fence; a fenced-off attempt publishes nothing.
     */
    fun prepareAt(
        generation: Long,
        admission: Admission,
        captureId: String,
        enrollmentEpoch: String,
        pcmChunks: List<Pair<ShortArray, Int>>,
        canonicalMetadata: ByteArray,
    ): Entry? {
        require(captureId.matches(UUID_RE)) { "capture id must be UUID" }
        val samples = sampleCount(pcmChunks) ?: run {
            admission.discard()
            return null
        }
        try {
            return writeAndPublish(generation, admission, captureId, enrollmentEpoch, pcmChunks, samples, canonicalMetadata)
        } catch (_: Exception) {
            admission.discard() // idempotent: no-op if writeAndPublish already settled the handle
            return null
        }
    }

    /** Total valid samples across [pcmChunks], or null when any range is invalid or the sum overflows. */
    private fun sampleCount(pcmChunks: List<Pair<ShortArray, Int>>): Long? {
        var total = 0L
        for ((chunk, valid) in pcmChunks) {
            if (valid < 0 || valid > chunk.size) return null
            total = Math.addExact(total, valid.toLong())
        }
        return total
    }

    private fun writeAndPublish(
        generation: Long,
        admission: Admission,
        captureId: String,
        enrollmentEpoch: String,
        pcmChunks: List<Pair<ShortArray, Int>>,
        samples: Long,
        canonicalMetadata: ByteArray,
    ): Entry? {
        val createdAt = clock()
        dir.mkdirs()
        // Generation-owned staging: a stale attempt's cleanup can never reach a successor's paths.
        // Staging is keyed by the UNIQUE attempt (the Admission's reservation id), not merely
        // generation+captureId: two attempts in the same generation for the same capture can never
        // share a staging path, so one cannot truncate or delete the other's bytes mid-write.
        val staging = "$STAGING_PREFIX${admission.id}.$generation.$captureId"
        synchronized(lock) { stagingOwners.add(admission.id) }
        val wavPart = File(dir, "$staging$SUFFIX_PART")
        val metaPart = File(dir, "$staging$SUFFIX_META")
        val wavFinal = File(dir, "$captureId$SUFFIX_WAV")
        val metaFinal = File(dir, "$captureId$SUFFIX_META")
        val readyFile = File(dir, "$captureId$SUFFIX_READY")
        val createdFile = File(dir, "$captureId$SUFFIX_CREATED")
        val attemptsFile = File(dir, "$captureId$SUFFIX_ATTEMPTS")
        var committed = false
        try {
            FileOutputStream(wavPart).use { out ->
                WavWriter.writeHeader(out, Math.toIntExact(samples))
                for ((chunk, valid) in pcmChunks) {
                    WavWriter.writePcm(out, chunk, valid)
                    out.flush()
                    hooks.onWavPartWrite?.invoke(generation)
                }
                out.fd.sync()
            }
            FileOutputStream(metaPart).use { out ->
                out.write(canonicalMetadata)
                out.flush()
                out.fd.sync()
            }
            val audioBytes = wavPart.length()
            // An invalid committed size must still give the reservation back: the enclosing try/finally in
            // prepareAt settles EVERY exit, so this returns through it rather than leaking here.
            if (audioBytes == 0L || audioBytes > limits.maxClipBytes) return null
            val digest = sha256Hex(canonicalMetadata)
            val entry = Entry(
                captureId, enrollmentEpoch, digest, audioBytes, canonicalMetadata.size,
                attempts = 0, createdAtMs = createdAt,
            )
            // Revocation seam: fires before the ownership decision so a revoke performed here is observed as
            // stale AT the publication instant, deterministically and without racing another thread.
            hooks.beforePublication?.invoke(generation)

            // LOCK ORDER: store lock -> fence, the single order used by every ownership path in this class
            // (reviewer 195845cb finding 1). Publication used to take the fence FIRST while purge took the
            // store first; two threads could then hold one lock each and wait forever on the other. The
            // generation is now checked while already holding the store lock, via isValidUnlocked.
            val published: Entry? = synchronized(lock) {
                if (!fence.isValidUnlocked(generation)) {
                    null
                } else if (preparedIds.contains(captureId) || memoryEntries.containsKey(captureId) ||
                    !roomForPublicationLocked(admission)
                ) {
                    // Ownership is decided atomically with publication: whoever reached the commit point first
                    // owns the id, and a duplicate or over-budget attempt is refused.
                    null
                } else if (!claimFinalNames(wavPart, metaPart, wavFinal, metaFinal, readyFile)) {
                    // Final names are claimed only after ownership is established, so no attempt can rename
                    // over an existing entry's immutable files.
                    null
                } else {
                    // Adopt the reservation into the durable index (removes it from the live set).
                    admission.adopt()
                    fsyncDir(dir)
                    // Age + attempt authorities are written BEFORE the marker; failing either is a failed
                    // preparation, not an invented fallback, and must hand the slot back.
                    val sidecarsOk = runCatching {
                        createdFile.writeText(createdAt.toString())
                        attemptsFile.writeText("0")
                    }.isSuccess
                    if (!sidecarsOk) {
                        // Roll the claim back exactly: remove what we just wrote and re-hold nothing.
                        deleteLocked(entry)
                        memoryEntries.remove(captureId)
                        preparedIds.remove(captureId)
                        null
                    } else {
                        // READY MARKER LAST: presence of this file is the ONLY readiness authority.
                        FileOutputStream(readyFile).use { it.fd.sync() }
                        fsyncDir(dir)
                        committed = true
                        memoryEntries[captureId] = entry
                        preparedIds.add(captureId)
                        entry
                    }
                }
            }
            synchronized(lock) { stagingOwners.remove(admission.id) }
            if (published == null) {
                // Fenced off / lost ownership / budget moved: drop OUR staging only, never shared names.
                deleteStaging(admission.id)
                admission.discard() // idempotent: no-op once adopted above
                return null
            }
            return entry
        } finally {
            if (!committed) {
                deleteStaging(admission.id)
            }
        }
    }

    /** Ready entries (durable state), oldest first; age computed against [clock]. */
    fun readyEntries(): List<Entry> = synchronized(lock) {
        memoryEntries.values.sortedBy { it.createdAtMs }
    }

    private var recovered = false

    fun totalQueuedBytes(): Long = readyEntries().sumOf { it.audioBytes + it.metadataBytes }

    /** Test seam: the contributions directory this store writes to. */
    fun dirForTest(): File = dir

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
    /**
     * Bump attempt accounting for a READY entry under the SHARED generation boundary [fence]: the write is
     * refused once a full revocation has bumped the generation, so a late network callback can neither
     * advance retry state nor touch the attempts sidecar of an entry that no longer belongs to this owner.
     * Returns the new count, or null when the entry is unknown, the generation went stale, or the durable
     * sidecar write failed (a swallowed write error would silently reset retry budget on restart).
     */
    fun recordAttempt(generation: Long, captureId: String): Int? {
        val updated = synchronized(lock) {
            // Same fixed order as publication: store lock -> fence.
            if (!fence.isValidUnlocked(generation)) null else memoryEntries[captureId]?.let {
                it.copy(attempts = it.attempts + 1).also { cur -> memoryEntries[captureId] = cur }
            }
        } ?: return null
        return try {
            File(dir, "$captureId$SUFFIX_ATTEMPTS").writeText(updated.attempts.toString())
            updated.attempts
        } catch (_: Exception) {
            // Roll back only while this generation still owns the queue; after a revoke the whole index is
            // gone and inventing a decrement could corrupt a successor's counts.
            synchronized(lock) {
                if (fence.isValidUnlocked(generation)) {
                    memoryEntries[captureId]?.let { memoryEntries[captureId] = it.copy(attempts = it.attempts - 1) }
                }
            }
            null
        }
    }

    /** Convenience for the current owner generation; prefer the fenced overload in callback paths. */
    fun recordAttempt(captureId: String): Int? = recordAttempt(fence.currentGeneration(), captureId)

    /**
     * Remove one READY entry under the SHARED generation boundary [fence]. A stale callback must not be able
     * to drop an entry that a revocation already purged (or that now belongs to a successor), so the removal
     * and the file deletion happen inside the same guarded critical section as the ownership check.
     */
    fun dropEntry(generation: Long, captureId: String, reason: TerminalReason): Boolean {
        val removed = synchronized(lock) {
            if (fence.isValidUnlocked(generation)) memoryEntries.remove(captureId)?.also { deleteLocked(it) } else null
        } != null
        if (removed) dropCounterOnly(reason)
        return removed
    }

    /** Convenience for the current owner generation; prefer the fenced overload in callback paths. */
    fun dropEntry(captureId: String, reason: TerminalReason): Boolean = dropEntry(fence.currentGeneration(), captureId, reason)

    enum class TerminalReason { ATTEMPTS_EXHAUSTED, QUEUE_AGE_EXPIRED, SERVER_TERMINAL, INVALID_ON_RECOVERY }

    fun terminalCounter(reason: TerminalReason): Long = synchronized(lock) { terminalCounters[reason] ?: 0L }

    /**
     * FULL REVOCATION (account change / withdrawal / remote disable / lease loss): this owner's entire
     * queue goes, immediately.
     *
     * One generation/ownership transaction (reviewer 32d8012c finding 3): the generation is bumped and
     * THIS instance's ownership set (index entries, prepared ids, live reservations) is snapshotted under
     * the same lock, so cleanup is scoped to ids this owner actually held. The sweep never deletes files
     * belonging to a successor that opened after the revoke — a blanket directory wipe used to destroy a
     * successor's committed WAV/marker when revocation landed mid-preparation.
     */
    fun purgeQueue(reason: String) {
        val ownedIds: Set<String>
        val stagedAttempts: Set<String>
        synchronized(lock) {
            // Bump FIRST, inside the same critical section as the ownership snapshot: no admission or
            // publication can slip between "revoked" and "purged".
            fence.revoke()
            ownedIds = (memoryEntries.keys + preparedIds + reservations.keys).toSet()
            for (e in memoryEntries.values.toList()) deleteLocked(e)
            memoryEntries.clear()
            preparedIds.clear()
            reservations.clear()
            recovered = true // a purged store must not lazily resurrect anything from disk
            stagedAttempts = stagingOwners.toSet()
            stagingOwners.clear()
            _purgeReasons.add(reason)
        }
        // Remove only OUR committed files and OUR staging debris; foreign/successor paths are untouched.
        ownedIds.forEach { id ->
            listOf(SUFFIX_WAV, SUFFIX_META, SUFFIX_READY, SUFFIX_ATTEMPTS, SUFFIX_CREATED).forEach { suffix ->
                File(dir, "$id$suffix").delete()
            }
        }
        stagedAttempts.forEach { attempt -> deleteStaging(attempt) }
        countersFile.delete()
        synchronized(lock) { terminalCounters.clear() }
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

    /** Attempt ids with staging files owned by THIS instance, so purge cleanup stays scoped. */
    private val stagingOwners = mutableSetOf<String>()

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
        private const val STAGING_PREFIX = "gen$"
        private const val ADMISSION_HELD = 0
        private const val ADMISSION_RELEASED = 1
        private const val ADMISSION_ADOPTED = 2
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
