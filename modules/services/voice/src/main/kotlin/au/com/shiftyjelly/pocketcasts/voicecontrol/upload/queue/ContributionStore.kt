package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.CanonicalJson
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordValidator
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
     * The single shared ownership boundary: one monitor holding BOTH the queue state and the revocation
     * generation. Defaults to a fresh [QueueOwnership]; the controller and the account-loss watcher MUST be
     * given the same instance, because a fence over a different monitor would let an authority validate a
     * generation that a concurrent revocation is free to change underneath it (reviewer ee26880c finding 1).
     */
    internal val ownership: QueueOwnership = QueueOwnership(),
    /**
     * Shared generation boundary for revocation, always backed by [ownership] — there is no independently
     * locked fence to invert against.
     */
    val fence: QueueFence = QueueFence(ownership),
    /** Deterministic interleaving seams for tests; production uses [StoreHooks.NONE]. */
    private val hooks: StoreHooks = StoreHooks.NONE,
    /**
     * Directory-entry durability. Injected so tests can OBSERVE that it runs and that a failure is not
     * swallowed; production uses the real fsync. This replaced a no-op stub that left the ready
     * marker's durability unsubstantiated (review f003b49f finding 2).
     */
    private val directorySync: DirectorySync = DirectorySync.REAL,
    /**
     * File-CONTENT durability, injected for the same reason as [directorySync]: a directory force
     * persists a name, not what the file contains, so sidecars written with writeText were durable in
     * name only.
     */
    private val fileSync: FileSync = FileSync.REAL,
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

    // Reservation for one entry, claimed atomically against the queue's count and byte budgets
    // BEFORE any PCM copy or disk write. A reservation that is never committed MUST be released via
    // Admission.discard; a committed one is consumed when the entry is published. This is what makes
    // concurrent preparations unable to over-admit into the same remaining slot.

    /**
     * Registry of live reservations, keyed by attempt id. A handle is only valid in the store that created
     * it: transfer consults this map, so a foreign handle (another ContributionStore instance over the same
     * directory) is refused before any write (reviewer ee26880c finding 2).
     */
    private val ownedAdmissions = mutableMapOf<String, Admission>()

    inner class Admission internal constructor(
        /** Reservation key; also the unique attempt identity used to name staging. */
        val id: String,
        override val projectedBytes: Long,
        /** Sample count this reservation was sized for at admission time. */
        override val reservedSamples: Long,
    ) : ReservationHandle {
        override val attemptId: String get() = id

        /**
         * Single-use settlement under the SHARED monitor. Legal transitions:
         * HELD -> WRITING -> ADOPTED, or HELD/WRITING -> RELEASED. Reuse of an already WRITING/RELEASED/
         * ADOPTED handle is refused, so a concurrently reused handle cannot share one attempt's staging
         * (reviewer ee26880c finding 2).
         */
        internal var state = ADMISSION_HELD

        override fun transferToWriting(requiredSamples: Long, requiredMetadataBytes: Int): Boolean = synchronized(lock) {
            // Single-winner: only a HELD handle can transfer, and `state` is guarded by this monitor, so
            // a concurrently reused handle loses here instead of sharing one attempt's staging.
            if (state != ADMISSION_HELD) return false
            // Foreign handle: it is not in THIS store's live reservation table.
            if (ownedAdmissions[id] !== this) return false
            // Undersized/mismatched: the reservation must cover exactly what this attempt writes.
            val expected = WavWriter.fileBytes(requiredSamples) + requiredMetadataBytes
            if (reservedSamples != requiredSamples || projectedBytes != expected) return false
            state = ADMISSION_WRITING
            true
        }

        /** Give the budget back (idempotent once settled). */
        override fun release() = discard()

        /** Hand the budget to the published entry (only legal from WRITING or HELD). */
        override fun adopt() {
            synchronized(lock) {
                if (state != ADMISSION_WRITING && state != ADMISSION_HELD) return
                state = ADMISSION_ADOPTED
                reservations.remove(id)
                ownedAdmissions.remove(id)
            }
        }

        /** Release the reserved slot/bytes when preparation is refused, fails, or is fenced off. Idempotent. */
        fun discard() {
            synchronized(lock) {
                if (state == ADMISSION_RELEASED || state == ADMISSION_ADOPTED) return
                state = ADMISSION_RELEASED
                reservations.remove(id)
                ownedAdmissions.remove(id)
            }
        }

        /** True while this admission still holds its reservation. */
        fun isHeld(): Boolean = synchronized(lock) { state == ADMISSION_HELD && reservations.containsKey(id) }

        /** Test seam: the settlement state (0 held, 1 released, 2 adopted, 3 writing). */
        fun stateForTest(): Int = synchronized(lock) { state }
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
        return reserve(sampleCount, projected)
    }

    /** Byte-projection used by tests/callers that need the same arithmetic the cap checks use. */
    fun projectedBytesFor(sampleCount: Long, metadataBytes: Int): Long = WavWriter.fileBytes(sampleCount) + metadataBytes

    /**
     * Ownership + budget transfer for one preparation attempt, executed inside the caller's ownership
     * transaction. Returns false unless [admission] was created by THIS store, is still HELD, and is sized
     * exactly for the samples/metadata about to be written (reviewer ee26880c finding 2).
     */
    private fun claimLocked(
        admission: Admission,
        requiredSamples: Long,
        requiredMetadataBytes: Int,
    ): Boolean {
        if (ownedAdmissions[admission.id] !== admission) return false // foreign or already settled here
        if (!admission.transferToWriting(requiredSamples, requiredMetadataBytes)) return false
        stagingOwners.add(admission.attemptId)
        return true
    }

    /** Claim count+bytes atomically; returns null when the queue cannot hold another entry. */
    private fun reserve(sampleCount: Long, projected: Long): Admission? {
        val id = java.util.UUID.randomUUID().toString()
        return synchronized(lock) {
            if (!roomLocked(projected)) {
                null
            } else {
                reservations[id] = projected
                Admission(id, projected, sampleCount).also { ownedAdmissions[id] = it }
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

    /**
     * Delete one attempt's staging files, identified by its reservation id. Deliberately takes NO lock: callers either already hold the shared
     * monitor (revokeAndPurge) or act on an attempt they exclusively own. A nested `synchronized(lock)` here
     * would RE-ENTER the monitor - Java monitors are count-owned, so an external revocation could slip in
     * between two sections of one transaction and observe the ownership boundary as free.
     */
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

    // Prepares one entry. Returns the ready Entry, or null when refused (caps), fenced, or
    // interrupted.
    //
    // Publication rule: the in-memory index is written ONLY after the `.ready` marker commit point
    // has been reached on disk. An interrupted preparation therefore never appears in
    // readyEntries and leaves at most .part/.meta debris for recover() to sweep.
    //
    // Sequence: write <id>.wav.part -> fsync -> write <id>.meta -> fsync -> rename part to final
    // name -> write sidecars -> write <id>.ready LAST (the commit point) -> publish in memory.

    /**
     * Prepare an entry using an [Admission] the caller validated and reserved BEFORE the PCM copy — the
     * shape the retention sink uses (admit on the capture callback, hand the handle to a worker that does
     * the disk work). This is the REAL consumption path: no second `admit()` happens here, so a queue with
     * exactly one free slot admits one preparation instead of demanding a second one (reviewer 195845cb
     * finding 5). The handle is settled exactly once: adopted on publication, released on every other exit.
     */
    fun prepare(admission: Admission, captureId: String, enrollmentEpoch: String, pcmChunks: List<Pair<ShortArray, Int>>, canonicalMetadata: ByteArray): Entry? = prepareAt(fence.currentGeneration(), admission, captureId, enrollmentEpoch, pcmChunks, canonicalMetadata)

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
        // ONE ownership transaction covers validation, staging claim, writing and publication/settlement
        // (reviewer ee26880c findings 2 and 3): the handle is validated against THIS store's reservation table
        // and transferred HELD -> WRITING before any byte is written, and every exit — invalid id, bad sample
        // range, clock/mkdir failure, refused publication — settles it exactly once.
        val samples = try {
            if (!captureId.matches(UUID_RE)) {
                admission.discard()
                return null
            }
            sampleCount(pcmChunks)
        } catch (_: Throwable) {
            null
        } ?: run {
            admission.discard()
            return null
        }
        val requiredBytes = WavWriter.fileBytes(samples) + canonicalMetadata.size
        // Validate + transfer under the shared monitor so a foreign/discarded/adopted/undersized/reused
        // handle cannot reach the write path at all.
        val claimed = synchronized(lock) {
            if (!ownership.isValidUnderLock(generation)) {
                null
            } else {
                claimLocked(admission, samples, canonicalMetadata.size)
            }
        }
        if (claimed != true) {
            admission.discard()
            return null
        }
        val published = try {
            writeAndPublish(generation, admission, captureId, enrollmentEpoch, pcmChunks, samples, canonicalMetadata)
        } catch (uncertain: PublicationUncertain) {
            // Re-thrown, NOT converted to null. The spec (ecbe84dd) names this exact mistake: a generic
            // error handler must not erase the difference between "not published" and "may have been
            // published". Returning null here would tell the caller the slot is free and invite it to
            // mint a new capture id for an entry a restart may still find intact.
            throw uncertain
        } catch (_: Throwable) {
            null
        }
        val settled = synchronized(lock) {
            // writeAndPublish commits inside its own section; re-checking ownership here keeps "is this still
            // ours?" atomic with releasing the staging claim, instead of trusting a verdict from earlier.
            if (published != null && !ownership.isValidUnderLock(generation)) {
                false
            } else {
                stagingOwners.remove(admission.attemptId)
                true
            }
        }
        if (!settled) {
            deleteStaging(admission.attemptId)
            admission.discard()
            return null
        }
        if (published == null) return null
        admission.adopt()
        return published
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
        // Creating the entry directory adds a NAME to the parent. Syncing only the new directory does
        // not make that name durable, so a crash could leave a directory nothing can find. Barrier the
        // parent at creation, once.
        // The parent barrier runs on EVERY preparation, not only when mkdirs created the directory.
        // `mkdirs()` returning false means "already exists", which does NOT mean the namespace is
        // durable: a previous attempt may have created the directory and then failed to sync its
        // parent. Gating on the mkdir result let that earlier failure be skipped forever, leaving the
        // directory name undurable while every later preparation reported success.
        dir.mkdirs()
        dir.parentFile?.let { parent -> syncDirectoryOrFail(parent) }
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
            }
            fileSync.sync(wavPart)
            FileOutputStream(metaPart).use { out ->
                out.write(canonicalMetadata)
                out.flush()
            }
            fileSync.sync(metaPart)
            val audioBytes = wavPart.length()
            // An invalid committed size must still give the reservation back: the enclosing try/finally in
            // prepareAt settles EVERY exit, so this returns through it rather than leaking here.
            if (audioBytes == 0L || audioBytes > limits.maxClipBytes) return null
            val digest = sha256Hex(canonicalMetadata)
            val digestFile = File(dir, "$captureId$SUFFIX_DIGEST")
            FileOutputStream(digestFile).use { out ->
                out.write(digest.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            fileSync.sync(digestFile)
            val entry = Entry(
                captureId,
                enrollmentEpoch,
                digest,
                audioBytes,
                canonicalMetadata.size,
                attempts = 0,
                createdAtMs = createdAt,
            )
            // ONE ownership transaction for the whole validate-then-mutate section: the shared monitor is held
            // from the ownership verdict through the commit point, so nothing can slip between checking the
            // generation and writing state. (Reviewer ee26880c finding 1: peeking at the generation and then
            // releasing before mutating was not a transaction; renaming the lock order was insufficient.)
            val published: Entry? = synchronized(lock) {
                // The seam runs INSIDE the section but BEFORE the ownership verdict, so a revocation
                // attempted from here is observed by the very check that gates the commit point
                // (reviewer ee26880c findings 1 and 4). Firing it after validation lets an attempt that
                // revokes in its own hook publish anyway.
                hooks.beforePublication?.invoke(generation)
                if (!ownership.isValidUnderLock(generation)) {
                    null
                } else if (preparedIds.contains(captureId) || memoryEntries.containsKey(captureId) ||
                    !roomForPublicationLocked(admission)
                ) {
                    // Ownership is decided atomically with publication: whoever reached the commit point
                    // first owns the id, and a duplicate or over-budget attempt is refused.
                    null
                } else if (!claimFinalNames(wavPart, metaPart, wavFinal, metaFinal, readyFile)) {
                    // Final names are claimed only after ownership is established, so no attempt can rename
                    // over an existing entry's immutable files.
                    null
                } else {
                    // Adopt the reservation into the durable index (removes it from the live set).
                    admission.adopt()
                    syncDirectoryOrFail(dir)
                    // Age + attempt authorities are written BEFORE the marker; failing either is a failed
                    // preparation, not an invented fallback, and must hand the slot back.
                    // Age and attempt authority are read back on recovery, so their CONTENTS must be
                    // durable before the marker can claim the entry is ready. A directory force only
                    // makes the name durable.
                    val sidecarsOk = runCatching {
                        createdFile.writeText(createdAt.toString())
                        fileSync.sync(createdFile)
                        attemptsFile.writeText("0")
                        fileSync.sync(attemptsFile)
                    }.isSuccess
                    if (!sidecarsOk) {
                        // Roll the claim back exactly: remove what we just wrote and re-hold nothing.
                        deleteLocked(entry)
                        memoryEntries.remove(captureId)
                        preparedIds.remove(captureId)
                        null
                    } else {
                        // READY MARKER LAST: presence of this file is the ONLY readiness authority.
                        //
                        // The uncertain boundary starts at the CREATE, not at the directory sync. The
                        // moment the marker file exists it may be visible after a crash, so a failure
                        // in ANY later step (its own content sync, the directory sync) leaves an
                        // ambiguous transition. Drawing the boundary one operation late was exactly
                        // the defect: a marker whose content sync failed was collapsed to null even
                        // though the transition may have occurred.
                        try {
                            FileOutputStream(readyFile).close()
                            fileSync.sync(readyFile)
                            syncDirectoryOrFail(dir)
                        } catch (transitionFailure: java.io.IOException) {
                            committed = true
                            // Recorded as OWNED before throwing. The entry never reached memoryEntries, so
                            // without this the owner's accounting would not contain it and a purge could
                            // leave the user's audio behind. Ownership is about "this owner created these
                            // files", not "this owner can read them back".
                            synchronized(lock) { uncertainPreparedIds += captureId }
                            throw PublicationUncertain(
                                "ready marker may exist but its durability could not be confirmed; publication uncertain",
                                transitionFailure,
                            )
                        }
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

    // Bump attempt accounting for a READY entry. Returns the new count, or null when the entry is not
    // in the index (purged/unknown) OR the durable sidecar write failed - a swallowed write error would
    // silently reset retry budget on the next restart, so failure is reported instead of invented.

    /**
     * Bump attempt accounting for a READY entry under the SHARED generation boundary [fence]: the write is
     * refused once a full revocation has bumped the generation, so a late network callback can neither
     * advance retry state nor touch the attempts sidecar of an entry that no longer belongs to this owner.
     * Returns the new count, or null when the entry is unknown, the generation went stale, or the durable
     * sidecar write failed (a swallowed write error would silently reset retry budget on restart).
     */
    fun recordAttempt(generation: Long, captureId: String): Int? {
        // ONE ownership transaction: validate, advance the index, and write the durable attempts sidecar all
        // happen while holding the shared monitor (reviewer ee26880c finding 4). The pre-fix shape validated
        // under the lock and then wrote outside it, so a revocation could land between the two and leave a
        // stale owner's bump attributed to the successor's entry.
        var sidecarFailure: Throwable? = null
        val result: Int? = synchronized(lock) {
            if (!ownership.isValidUnderLock(generation)) {
                null
            } else {
                val current = memoryEntries[captureId]
                if (current == null) {
                    null
                } else {
                    val updated = current.copy(attempts = current.attempts + 1)
                    memoryEntries[captureId] = updated
                    hooks.beforeAttemptWrite?.invoke(generation)
                    try {
                        File(dir, "$captureId$SUFFIX_ATTEMPTS").writeText(updated.attempts.toString())
                        updated.attempts
                    } catch (_: Throwable) {
                        // Roll back only while this generation still owns the queue; after a revoke the whole
                        // index is gone and inventing a decrement could corrupt a successor's counts.
                        memoryEntries[captureId] = current
                        sidecarFailure = IllegalStateException("attempts sidecar write failed")
                        null
                    }
                }
            }
        }
        sidecarFailure?.let { throw it }
        return result
    }

    /**
     * Remove one READY entry under the SHARED generation boundary [fence]. A stale callback must not be able
     * to drop an entry that a revocation already purged (or that now belongs to a successor), so the removal
     * and the file deletion happen inside the same guarded critical section as the ownership check.
     */
    fun dropEntry(generation: Long, captureId: String, reason: TerminalReason): Boolean {
        val removed = synchronized(lock) {
            if (ownership.isValidUnderLock(generation)) memoryEntries.remove(captureId)?.also { deleteLocked(it) } else null
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
    fun purgeQueue(reason: String) = revokeAndPurge(reason)

    /**
     * ONE ownership transaction for a full revocation (reviewer ee26880c finding 4): the generation bump,
     * the ownership snapshot, the index clear AND the deletion of this owner's committed files all happen
     * while holding the shared monitor. Previously the per-capture deletes ran after releasing it, so a
     * successor that published the same capture id in between had its fresh files deleted by our late cleanup.
     *
     * Deleting under the monitor is safe because publication also runs under it and refuses to claim names
     * that already exist: either we hold the lock and delete before any successor claims, or the successor
     * claims first and our scoped snapshot (ids we actually owned) simply does not include that id.
     */
    fun revokeAndPurge(reason: String) {
        synchronized(lock) {
            ownership.revokeUnderLock()
            // OWNERSHIP IS INDEPENDENT OF READINESS. An uncertain entry was removed from memoryEntries so
            // it could not be exposed as ready, but that must not drop it from the owner's accounting: an
            // account purge that skipped it would leave the user's audio on disk after they asked for
            // everything to be removed, and a later healthy recovery would resurrect it. The uncertain set
            // is unioned in HERE, before the snapshot, and cleared only after its files are deleted.
            val ownedIds = (memoryEntries.keys + uncertainPreparedIds).distinct()
            val stagedAttempts = stagingOwners.toList()
            // Delete only what THIS owner's index actually holds. deleteLocked removes each id as it goes, so
            // the later clear() is a no-op safety net rather than a second sweep.
            ownedIds.forEach { id -> memoryEntries[id]?.let { deleteLocked(it) } }
            memoryEntries.clear()
            preparedIds.clear()
            uncertainPreparedIds.clear()
            reservations.clear()
            // Settle our own live handles: their budget is gone with the queue, and leaving them HELD while
            // the table is empty would make a later discard()/adopt() dereference a missing entry.
            ownedAdmissions.values.toList().forEach { it.state = ADMISSION_RELEASED }
            ownedAdmissions.clear()
            recovered = true // a purged store must not lazily resurrect anything from disk
            ownedIds.forEach { id ->
                listOf(SUFFIX_WAV, SUFFIX_META, SUFFIX_READY, SUFFIX_ATTEMPTS, SUFFIX_CREATED, SUFFIX_DIGEST).forEach { suffix ->
                    File(dir, "$id$suffix").delete()
                }
            }
            stagedAttempts.forEach { attempt -> deleteStaging(attempt) }
            stagingOwners.clear()
            countersFile.delete()
            terminalCounters.clear()
            _purgeReasons.add(reason)
        }
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
        val readyIdsBefore = files.filter { it.name.endsWith(SUFFIX_READY) }.map { it.name.removeSuffix(SUFFIX_READY) }.toSet()
        // Unmarked FINAL debris: a .wav/.meta/.sha256 with no matching .ready marker is an
        // interrupted preparation that never reached its commit point. It must not survive recovery
        // (an .ready-marked entry is validated below instead).
        files.filter { f ->
            val suffix = listOf(SUFFIX_WAV, SUFFIX_META, SUFFIX_DIGEST).firstOrNull { f.name.endsWith(it) }
            suffix != null && f.name.removeSuffix(suffix) !in readyIdsBefore
        }.forEach { it.delete() }
        val readyIds = readyIdsBefore.toList()
        val keep = mutableListOf<Entry>()
        // Entries whose publication could not be confirmed durable. Kept out of the ready set, but NOT
        // deleted: uncertain is not rejected.
        val uncertainIds = mutableListOf<String>()
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
                File(dir, "$id$SUFFIX_DIGEST").delete()
                dropCounterOnly(TerminalReason.INVALID_ON_RECOVERY)
            } else {
                // STRUCTURAL validation is not durability confirmation. An entry may have been left by
                // a publication whose final sync failed (the uncertain outcome), so its files could be
                // present yet not durable. Before exposing it as ready, re-establish and confirm the
                // durable state: sync each file's contents, then the directory that names them.
                // A failure here means the entry is NOT confirmed durable, so it is not exposed.
                // Re-establish the SAME barriers publication relies on, not a subset:
                //  - every content file, INCLUDING the ready marker, because marker-content durability
                //    is now an explicit uncertain case at publication time and recovery must not be
                //    more trusting than the writer;
                //  - the queue directory that names them;
                //  - the queue directory's PARENT namespace, which is what makes the directory
                //    reachable at all (the same barrier publication performs at creation).
                val confirmed = runCatching {
                    listOf(
                        wav,
                        meta,
                        File(dir, "$id$SUFFIX_DIGEST"),
                        attemptsFile,
                        File(dir, "$id$SUFFIX_CREATED"),
                        File(dir, "$id$SUFFIX_READY"),
                    ).filter { it.isFile }.forEach { fileSync.sync(it) }
                    syncDirectoryOrFail(dir)
                    dir.parentFile?.let { parent -> syncDirectoryOrFail(parent) }
                }.isSuccess
                if (confirmed) {
                    keep += entry
                } else {
                    // Not exposed this run. The files are LEFT IN PLACE rather than deleted: the entry is
                    // an uncertain publication, and the spec forbids assuming cleanup succeeded or
                    // treating uncertainty as rejection. The next confirmation attempt decides.
                    uncertainIds += id
                }
            }
        }
        synchronized(lock) {
            // Rebuild the index from VALIDATED disk state only: self-prepared entries are
            // re-stamped from their current sidecars (a mutable clock or post-purge debris can
            // never resurrect a stale in-memory copy), foreign entries follow validation.
            val self = memoryEntries.keys.filter { it in preparedIds }.toSet()
            memoryEntries.clear()
            for (id in self) {
                // A self-prepared entry whose durability confirmation FAILED must be withheld from the
                // index exactly like a foreign one. Rebuilding from validate() alone would reinsert it
                // and make the confirmation above decorative for the very entries most likely to be
                // uncertain (this instance is the one that may have published them).
                if (id in uncertainIds) {
                    uncertainPreparedIds += id
                    continue
                }
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
        return try {
            // --- Sidecars are BOUNDED reads with strict semantics (no defaults that renew budgets/age) ---
            // A missing or malformed sidecar is a REFUSAL, never a default: defaulting attempts to 0
            // would hand back a spent retry budget, and defaulting created-at to mtime would let a
            // touched file silently renew its queue age.
            val attempts = readBoundedSidecar(attemptsFile, MAX_ATTEMPTS_SIDECAR_BYTES)
                ?.trim()?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val created = readBoundedSidecar(File(dir, "$id$SUFFIX_CREATED"), MAX_CREATED_SIDECAR_BYTES)
                ?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            // --- Mandatory digest identity: this on-disk format is unreleased, so there is NO legacy
            // fallback. An absent, malformed or mismatched .sha256 refuses the entry. ---
            val recordedDigest = readBoundedSidecar(File(dir, "$id$SUFFIX_DIGEST"), MAX_DIGEST_SIDECAR_BYTES)
                ?.trim() ?: return null
            if (!HEX_SHA256.matches(recordedDigest)) return null
            // --- Metadata: bounded read, then parsed with the canonical parser (which itself
            // rejects duplicate keys and non-finite numbers), NOT a regex. ---
            val metaBytes = readBoundedBytes(meta, limits.maxMetadataBytes) ?: return null
            val metaText = metaBytes.toString(Charsets.UTF_8)
            if (sha256Hex(metaBytes) != recordedDigest) return null
            // --- Canonical record authority: delegate to the SHARED decode-side validator. ---
            // Never re-derive schema rules here: a hand-rolled reader is a second, weaker authority
            // that silently accepts what the builder refuses (unknown fields, fractional integers,
            // empty epochs, inconsistent audio blocks).
            val parsed = CanonicalJson.parse(metaText)
            val valid = when (val result = RecordValidator.validate(parsed)) {
                is RecordValidator.Result.Rejected -> return null
                is RecordValidator.Result.Ok -> result.record
            }
            if (valid.captureId != id) return null
            val audio = valid.audio
            if (audio.byteCount != declared) return null
            // Audio digest binds the EXACT bytes: a digest authenticates whatever was written,
            // including a malformed WAV, so the header is validated separately below.
            val actualAudioSha = sha256FileBounded(wav, declared) ?: return null
            if (actualAudioSha != audio.sha256) return null
            // --- Structural WAV validation (cannot be established by the digest) ---
            val header = ByteArray(WavWriter.HEADER_BYTES)
            if (!readFully(wav, header)) return null
            val wavValid = WavHeaderValidator.validate(header, declared) ?: return null
            if (wavValid.sampleCount != audio.retainedSamples) return null
            Entry(id, valid.enrollmentEpoch, recordedDigest, declared, metaBytes.size, attempts, createdAtMs = created)
        } catch (_: Exception) {
            null
        }
    }

    /** Read a small sidecar with a hard byte bound; null when absent, oversized or unreadable. */
    private fun readBoundedSidecar(file: File, maxBytes: Int): String? {
        if (!file.isFile || file.length() > maxBytes) return null
        val bytes = readBoundedBytes(file, maxBytes) ?: return null
        return bytes.toString(Charsets.UTF_8)
    }

    /** Read at most [maxBytes] bytes; null if the file is larger (refused, never truncated). */
    private fun readBoundedBytes(file: File, maxBytes: Int): ByteArray? {
        if (!file.isFile || file.length() > maxBytes) return null
        val buf = ByteArray(maxBytes + 1)
        var total = 0
        file.inputStream().use { stream ->
            while (total < buf.size) {
                val n = stream.read(buf, total, buf.size - total)
                if (n < 0) break
                total += n
            }
        }
        return if (total > maxBytes) null else buf.copyOf(total)
    }

    private fun readFully(file: File, into: ByteArray): Boolean = file.inputStream().use { stream ->
        var total = 0
        while (total < into.size) {
            val n = stream.read(into, total, into.size - total)
            if (n < 0) return false
            total += n
        }
        true
    }

    /** Streamed SHA-256 over EXACTLY [expectedBytes]; null when the file length differs. */
    private fun sha256FileBounded(file: File, expectedBytes: Long): String? {
        if (file.length() != expectedBytes) return null
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var total = 0L
        file.inputStream().use { stream ->
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                if (total > expectedBytes) return null
                md.update(buf, 0, n)
            }
        }
        if (total != expectedBytes) return null
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Delete an entry's files; MUST be called while holding [lock]. */
    private fun deleteLocked(e: Entry) {
        val id = e.captureId
        File(dir, "$id$SUFFIX_WAV").delete()
        File(dir, "$id$SUFFIX_META").delete()
        File(dir, "$id$SUFFIX_READY").delete()
        File(dir, "$id$SUFFIX_ATTEMPTS").delete()
        File(dir, "$id$SUFFIX_CREATED").delete()
        File(dir, "$id$SUFFIX_DIGEST").delete()
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

    /**
     * Entries this instance prepared whose durability recovery could NOT re-confirm (spec ecbe84dd).
     *
     * They stay on disk and are withheld from the index until a later confirmation succeeds: uncertain
     * is not rejected, but it is also not ready.
     */
    private val uncertainPreparedIds = mutableSetOf<String>()
    private val reservations = LinkedHashMap<String, Long>()

    /** Attempt ids with staging files owned by THIS instance, so purge cleanup stays scoped. */
    private val stagingOwners = mutableSetOf<String>()

    // Lock and counter storage are declared BEFORE the init block that reads them: Kotlin runs
    // property initializers and init blocks top-to-bottom, so an init block placed above these
    // declarations would touch not-yet-initialized state (reviewer f003b49f finding 6).
    // THE one monitor: identical object across the index, reservations, staging registry and generation.
    private val lock: Any get() = ownership.lock
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

    /**
     * Directory-entry durability, never swallowed.
     *
     * This was a no-op stub because opening a stream on a directory is not portable.
     * [DirectorySync.REAL] uses FileChannel.force, which is verified to work on the target
     * platform. A failure means the entry is not known durable, so it propagates rather than
     * being reported as a successful publication.
     */
    private fun syncDirectoryOrFail(directory: File) = directorySync.sync(directory)

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
        private const val ADMISSION_WRITING = 3
        private const val SUFFIX_ATTEMPTS = ".attempts"
        private const val SAMPLE_RATE_HZ = 16_000L
        private const val SUFFIX_CREATED = ".created"
        private const val SUFFIX_DIGEST = ".sha256"
        private const val MAX_ATTEMPTS_SIDECAR_BYTES = 32
        private const val MAX_CREATED_SIDECAR_BYTES = 32
        private const val MAX_DIGEST_SIDECAR_BYTES = 128
        private val HEX_SHA256 = Regex("[0-9a-f]{64}")
        private val UUID_RE = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val EPOCH_RE = Regex("\"enrollment_epoch\"\\s*:\\s*\"[^\"]+\"")

        private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun le16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        private fun le32(b: ByteArray, at: Int): Long = (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)
    }
}
