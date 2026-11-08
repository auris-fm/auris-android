package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.LocalWithdrawalIntentStore
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.CanonicalJson
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordValidator
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.EnumMap
import timber.log.Timber

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
    internal val limits: QueueLimits,
    /**
     * THE DIRECTORY-OWNERSHIP EXCLUSION (spec c8f766fb). Defaults to a REAL OS lock; tests that deliberately
     * model a second owner pass an unheld one. A store whose ownership is not held is UNAVAILABLE: every
     * read that can admit work and every mutation refuses. There is no unguarded fallback.
     */
    private val directoryOwnership: DirectoryOwnership = FileDirectoryOwnership(File(root, DIR_NAME)),
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Full-jitter source for the retry policy: given the exclusive backoff bound in ms, returns the drawn
     * delay in [0, bound). Injected so tests pin the draw; production uses a uniform random generator.
     */
    internal val jitter: (Long) -> Long = { bound -> if (bound <= 0) 0 else kotlin.random.Random.Default.nextLong(bound) },
    /**
     * The single shared ownership boundary: one monitor holding BOTH the queue state and the revocation
     * generation. Defaults to a fresh [QueueOwnership]; the controller and the account-loss watcher MUST be
     * given the same instance, because a fence over a different monitor would let an authority validate a
     * generation that a concurrent revocation is free to change underneath it (reviewer ee26880c finding 1).
     */
    internal val ownership: QueueOwnership = QueueOwnership(),
    /**
     * THE DURABLE REVOCATION STATE (reviewer c764bea2, spec 777c18d5). A withdrawal of sharing is recorded
     * durably before the request leaves and survives process death, which makes it the right place to DERIVE
     * a pending purge rather than inventing a second marker file. [recover] consults it FIRST and finishes
     * the purge before anything can be validated, promoted or served.
     *
     * Null means "no durable revocation source is wired" (unit tests that are not about this), never
     * "sharing is on": a null source contributes no veto and no purge, exactly as before it existed.
     */
    private val withdrawalIntent: LocalWithdrawalIntentStore? = null,
    // Injection point for the disposal-intent barrier only. Defaults to the queue's barrier so production
    // behaviour is unchanged; tests override it to fault intent recording independently of the queue.
    private val intentDirectorySyncOverride: DirectorySync? = null,
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
     * marker's durability unsubstantiated (review f003b49f finding 2). The default is the JVM-runnable
     * FileChannel barrier so plain unit tests run without touching android.system.Os; the PRODUCTION
     * (android.system.Os) adapter is bound at the Android construction boundary (VoiceUploadModule),
     * per spec 64ce2de6. Tests keep injecting.
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
        /** Admitted ownership at capture start; null for epoch-only (older) admissions. */
        val enrollmentAccount: String? = null,
        val enrollmentSession: Long? = null,
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

    /** True when this instance validly owns the directory and no other owner can be mutating it. */
    fun isAvailable(): Boolean = acquireOwnership()

    /** Why this store is unavailable, for diagnosis. Null while available. */
    fun unavailableReason(): String? = directoryOwnership.unavailableReason()

    /**
     * Refuses when ownership is absent, and then runs [block] holding an OPERATION LEASE for its whole
     * duration. Every mutation and every read that can admit work goes through here.
     *
     * The lease is what makes [close] a real handover boundary (reviewer 7db01cd9 finding 1): checking
     * ownership once at entry fences new callers but not one already running, which could resume after a
     * successor owns the directory. [DirectoryOwnership.enter] registers the operation and [leave] releases
     * it, so close waits for this block to finish before it drops the OS lock.
     *
     * Not inline on purpose: an inline body would be copied into each caller, and the lease must be
     * released on every path including exceptions thrown out of [block].
     */
    private fun <T> guarded(unavailable: T, block: () -> T): T {
        if (!acquireOwnership() || !enterOperation()) {
            logGuardedRefusal()
            return unavailable
        }
        try {
            return block()
        } finally {
            leaveOperation()
        }
    }

    /**
     * Entry fence for every OTHER mutating surface (reviewer 7db01cd9 finding 2): an old valid in-memory
     * generation is not a directory lease. These paths already revalidate the generation, but generation
     * says "same owner in this process" and says nothing about whether a successor now owns the directory.
     *
     * Returns false when this instance must refuse. Re-entrant per thread so a fenced method may call
     * another fenced method (prepare -> discardCurrentPartials, for example) without deadlocking the lease.
     */
    private fun withLease(block: () -> Unit): Boolean {
        if ((leaseDepth.get() ?: 0) > 0) {
            block()
            return true
        }
        if (!acquireOwnership() || !enterOperation()) {
            logGuardedRefusal()
            return false
        }
        // NESTING COUNTS, never a bare flag: an inner fenced call must not reset the depth an outer one
        // still owns, or the outer operation stops being recognised as in-flight by close().
        leaseDepth.set((leaseDepth.get() ?: 0) + 1)
        try {
            block()
            return true
        } finally {
            leaseDepth.set((leaseDepth.get() ?: 1) - 1)
            leaveOperation()
        }
    }

    private val leaseDepth = ThreadLocal.withInitial { 0 }

    /** Like [guarded] but returns [unavailable] instead of running a block when the lease is refused. */
    private fun <T> withLeaseResult(unavailable: T, block: () -> T): T {
        if (!acquireOwnership() || !enterOperation()) {
            logGuardedRefusal()
            return unavailable
        }
        try {
            return block()
        } finally {
            leaveOperation()
        }
    }

    /** Operation-lease admission; false when this instance may not touch the directory. */
    private fun enterOperation(): Boolean = (directoryOwnership as? FileDirectoryOwnership)?.enter()
        ?: directoryOwnership.isHeld()

    private fun leaveOperation() {
        (directoryOwnership as? FileDirectoryOwnership)?.leave()
    }

    /**
     * Takes the directory lock on first real use. Idempotent; a closed or contested owner stays closed.
     */
    private fun acquireOwnership(): Boolean {
        (directoryOwnership as? FileDirectoryOwnership)?.acquire()
        return directoryOwnership.isHeld()
    }

    @Volatile
    private var guardedRefusals = 0L

    private fun logGuardedRefusal() {
        synchronized(lock) { guardedRefusals++ }
    }

    /** Count of operations refused for lack of directory ownership (test seam + diagnosis). */
    fun guardedRefusalsForTest(): Long = synchronized(lock) { guardedRefusals }

    /**
     * INVALIDATE FIRST, THEN RELEASE (spec c8f766fb): after this returns, a delayed callback into this
     * instance refuses instead of mutating a directory a new owner may hold.
     */
    fun close() = directoryOwnership.close()

    /**
     * Non-blocking teardown, for a future caller that must not block a lifecycle callback (spec c12c569f):
     * invalidates immediately so later callbacks refuse, and drains on [scope] because an in-flight
     * operation may be a network write. Returns a future that is false when ownership could not be
     * released yet.
     *
     * HONEST STATUS, TRACED to the actual owners (reviewer 6456b2a5):
     *
     * - THE UPLOAD OWNER is `UploadTrigger` (`VoiceUploadModule`), started once from
     *   `PocketCastsApplication.initializeOnAppStart` via `voiceUploadTrigger.start()`. It polls
     *   `assembly.poll()` in an endless loop, with an account-revocation watcher launched on the same scope.
     * - THE EXECUTOR is the app-wide `@ApplicationScope` CoroutineScope (`provideUploadTriggerScope`
     *   delegates to it). Its lifetime is the PROCESS, and nothing cancels it short of process death.
     * - THEREFORE there is no shutdown call site to attach to, and this method has NO production caller.
     *   Source retention is instead bounded by the ATTEMPT: every attempt settles its own descriptor in a
     *   `finally`, so no source outlives the call that owned it. An open source only survives past a close
     *   if that attempt is still running - which on process death the kernel reclaims with the process.
     *
     * This method stays as the contract a genuinely bounded teardown would use. Do NOT read its presence,
     * or the source lease's, as evidence that shutdown is wired: the store outliving the process, and the
     * attempt outliving nothing, is what actually closes descriptors here.
     */
    fun closeAsync(
        scope: java.util.concurrent.ExecutorService,
        onUnresolved: (String) -> Unit = {},
    ): java.util.concurrent.Future<Boolean> = directoryOwnership.closeAsync(scope, onUnresolved)

    /**
     * Reserve room for one entry BEFORE any PCM copy/disk work: deny-by-default, bounded, and atomic
     * across concurrent callers (the count and byte budgets are claimed under [lock], not merely read).
     * Returns null when the entry does not fit; the caller must [Admission.discard] on every path that
     * does not publish the entry.
     */
    fun admit(sampleCount: Long, metadataBytes: Int): Admission? = guarded(null) {
        if (sampleCount < 0 || metadataBytes < 0) {
            null
        } else if (durationMs(sampleCount) > limits.maxClipMs) {
            // Duration bound is independent of the byte bound: a long quiet clip can stay under
            // max_clip_bytes while still exceeding the specced max_clip_ms.
            null
        } else {
            val projected = WavWriter.fileBytes(sampleCount) + metadataBytes
            when {
                projected > limits.maxClipBytes -> null
                metadataBytes > limits.maxMetadataBytes -> null
                else -> reserve(sampleCount, projected)
            }
        }
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

    // The marker/disqualification reconciliation that admission depends on runs once per instance
    // before the first admission, not only at recovery/expiry (spec 7e9606b). It is COUNT-FREE (spec
    // a3ef694f): capacity is decided from complete entries on disk, so nothing here measures bytes.
    private var coverageEstablished = false

    /** Ownership-refusal accounting (spec 8adcfd01): durableCapture refusing for sidecar reasons. */
    var sidecarRefusals: Int = 0
        private set

    private fun establishCoverage() {
        if (coverageEstablished) return
        // A FRESH install has no directory yet; the counting listing below must see a real directory.
        dir.mkdirs()
        retryDisqualifiedCleanupLocked()
        coverageEstablished = true
    }

    /**
     * CLAIM OWNERSHIP OF EVERY RETAINED CAPTURE ON DISK, WITHOUT RE-ADMISSION (reviewer ec6ce881).
     *
     * Purge needs a list of what to DELETE, and it must come from the directory rather than from the index:
     * a freshly constructed store has seen nothing, so an index-driven purge would report success while
     * deleting zero captures and leave the user's audio on disk after they withdrew sharing. This is
     * ownership DISCOVERY, deliberately separate from CAPACITY accounting - it restores no byte or coverage
     * gate and admits nothing, so nothing discovered here can be served or committed. Callers hold the lock.
     */
    private fun discoverRetainedFromDiskLocked(): Boolean {
        val retained = retainedCaptureIdsLocked() ?: return false
        for (id in retained) {
            claimOwnedLocked(id, ready = false)
        }
        return true
    }

    /**
     * Every capture id that retains ANY of its entry files on disk - audio or sidecars (spec 9e6a2081).
     * Wider than the capacity count on purpose: capacity protects AUDIO, so it ignores orphan sidecars,
     * but a purge must remove them too or the withdrawal leaves debris behind.
     */
    // NULL means "could not read the directory", which is NOT the same as "nothing there" and must never
    // be collapsed into an empty set: a purge driven by a proven-empty listing records success while the
    // user's undiscovered recordings survive (reviewer f7700432). Uses the shared bounded listing so an
    // unreadable or oversized directory reports unknown instead of masquerading as empty.
    private fun retainedCaptureIdsLocked(): Set<String>? {
        val files = boundedRootListingOrNull() ?: return null
        return files.mapNotNullTo(mutableSetOf()) { f -> entryFileCaptureId(f.name) }
    }

    /** The capture id [name] belongs to, considering EVERY entry suffix, or null when it is not one. */
    private fun entryFileCaptureId(name: String): String? = ENTRY_SUFFIXES.firstNotNullOfOrNull { suffix ->
        name.removeSuffix(suffix).takeIf { name.endsWith(suffix) && it.matches(UUID_RE) }
    }

    /**
     * CAPACITY IS COUNTED, NOT MEASURED (spec a3ef694f). One record is one COMPLETE entry on disk: a WAV
     * whose matching metadata sidecar exists, under a UUID name. In-progress captures that only have a
     * `.wav.part` are NOT records yet, and no byte total is involved - a byte total could be made
     * "unknown" by a single unreadable file and would then freeze the whole feature.
     */
    // DISTINCT capture ids that STILL HOLD AUDIO (spec 33c1e27a ruling): complete entries and the audio half
    // of a failed deletion alike, because audio is what the bound exists to protect.
    //
    // A failed deletion leaves a partial entry behind (metadata gone, audio undeletable). That residue still
    // occupies the filesystem, so counting only complete PAIRS would let repeated failed deletes accumulate
    // unbounded audio under a 32-record bound.
    //
    // A SIDECAR-ONLY leftover never counts: orphan sidecars are exactly what wedged iOS, and they hold no
    // audio. The cleanup sweep removes them, counted and logged. Stray files are excluded for the same
    // reason they are not audio - staging writes are prefixed with their attempt (`gen<attemptId>...`) or
    // end in `.part`, so they never spell an entry name.
    // THE COUNT IS NOT A TRAVERSAL (spec a3ef694f, exhaustive-gate audit 2741a0ca). It reads the directory
    // and takes ids; nothing about it needs a byte budget, and the number it produces is what every other
    // decision compares against. If the count could go "unknown" from a listing bound, then a full queue
    // would report itself unknowable and stop serving the very clips whose removal reduces it - a deadlock
    // with no exit. It answers 0 only when the directory truly cannot be read, which admission treats as
    // "refuse new work", never as "stop uploading".
    private fun rootRecordCountLocked(): Int = dir.listFiles()?.mapNotNullTo(mutableSetOf()) { f -> audioEntryId(f.name) }?.size ?: 0

    /** The capture id whose AUDIO is in [name], or null when the file holds none. */
    private fun audioEntryId(name: String): String? = name.removeSuffix(SUFFIX_WAV).takeIf { name.endsWith(SUFFIX_WAV) && it.matches(UUID_RE) }

    /** Claim one record atomically; returns null when the queue cannot hold another entry. */
    private fun reserve(sampleCount: Long, projected: Long): Admission? {
        establishCoverage()
        val id = java.util.UUID.randomUUID().toString()
        return synchronized(lock) {
            if (!roomLocked()) {
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

    fun admitForTestOrNull(samples: Long, metadataBytes: Int): Admission? = admit(samples, metadataBytes)

    /** Test seam: current durable/in-memory retry boundaries. */
    fun retryBoundariesForTest(): Map<String, Long> = synchronized(lock) { nextRetryAtMs.toMap() }

    /** Test seam: the in-memory attempt count for one capture. */
    fun stateAttemptsForTest(captureId: String): Int = synchronized(lock) { memoryEntries[captureId]?.attempts ?: -1 }

    // True when generation is still the CURRENT owner AND the admitted attempt for captureId is still
    // attemptNumber - i.e. this completion belongs to the attempt the queue actually admitted. An old
    // completion after an epoch change, or a late response for a superseded attempt, settles nothing
    // (reviewer d1484829 finding 3).
    fun attemptIdentityHolds(generation: Long, captureId: String, attemptNumber: Int): Boolean = synchronized(lock) { attemptIdentityHoldsLocked(generation, captureId, attemptNumber) }

    private fun attemptIdentityHoldsLocked(generation: Long, captureId: String, attemptNumber: Int): Boolean = ownership.isValidUnderLock(generation) &&
        memoryEntries.containsKey(captureId) &&
        admittedGeneration[captureId] == generation &&
        memoryEntries[captureId]?.attempts == attemptNumber

    // The digest this queue recorded for captureId when it durably published the entry. Read from the
    // LIVE index, not from the caller's snapshot: a receipt validator must compare against what the queue
    // actually holds. Null when the queue holds no such entry.
    fun recordedDigest(captureId: String): String? = synchronized(lock) { memoryEntries[captureId]?.recordSha256 }

    /**
     * THE durable capture authority: serves the immutable record and audio for a capture id, bound to THIS
     * store's own published state, so no weaker parallel reader can exist (reviewer 0004628b, spec
     * 07ae8661). Every returned pair satisfies ALL of:
     * - the entry exists and is servable (not held-uncertain) and the epoch matches the entry;
     * - the metadata is read BOUNDED, its canonical bytes hash to the DIGEST THIS STORE WROTE at publish
     *   time (the .digest sibling agrees, so a replaced or rewritten record is refused);
     * - the WAV passes the header validator against the recorded byte count, so truncated or same-length
     *   damaged audio cannot ride a valid record.
     * Null means "this store cannot vouch for these bytes", which the assembly holds rather than sends.
     */
    fun durableCapture(captureId: String): DurableCapture? = withLeaseResult(null) {
        durableCaptureLocked(captureId)
    }

    /**
     * The body of [durableCapture], run while the caller holds the ownership lease.
     *
     * WHAT THE LEASE COVERS (reviewer c5a00f02): validation only - the bounded metadata read, the hashing of
     * the WAV, and the .digest comparison all happen while this store provably owns the directory, so a
     * successor cannot replace the bytes mid-check and a closed owner cannot vouch for someone else's
     * namespace.
     *
     * WHAT IT DOES NOT COVER, stated exactly (reviewer 54d0f185): a [DurableCapture] is a PATHNAME plus a
     * record, not pinned bytes. [DurableCapture.audio] outlives this call, and the transport re-opens that
     * path much later, in `ContributionUploadTransport.multipartFor` (`audio.inputStream()` inside
     * `writeTo`), OUTSIDE any store critical section - deliberately, because network work must not hold the
     * queue lock.
     *
     * Between validation and that open the path can be replaced by a new owner publishing at the same id,
     * and a control in the suite demonstrates it: validated bytes and bytes-at-transport-open can differ.
     * Validation therefore establishes what the store vouched for AT HANDOFF, not what the socket will
     * send. Ownership at handoff does NOT by itself prove the transmitted bytes are the validated ones.
     *
     * No byte-binding mechanism is implemented, and none of the obvious ones is proven here:
     * - re-hashing while sending detects a mismatch only AFTER unvalidated bytes may already have left
     *   the client (reviewer ac2d270c), so it cannot guarantee what was transmitted;
     * - opening the pathname pins a file identity, not immutable contents, while any writer can reach
     *   that inode.
     * A defensible fix binds validation and transmission to the same stable bytes (an owned immutable
     * snapshot whose no-in-place-mutation lifetime is proven, validated before sending and retained until
     * transport finishes). That is a contract choice under Spec review, deliberately not decided here.
     */
    private fun durableCaptureLocked(captureId: String): DurableCapture? = synchronized(lock) {
        val entry = memoryEntries[captureId] ?: return null
        if (heldUncertainLocked(captureId)) return null
        val audio = File(dir, "$captureId$SUFFIX_WAV")
        val meta = File(dir, "$captureId$SUFFIX_META")
        if (!audio.isFile || !meta.isFile) return null
        if (audio.length() != entry.audioBytes) return null
        // BOUNDED, EXACT metadata read: read exactly the recorded number of bytes from a regular file and
        // confirm nothing more follows, so a growing or replaced file cannot exceed the observed bound
        // (reviewer 31b75e03).
        val metadataBytes = readExactlyBounded(meta, entry.metadataBytes.toLong(), MAX_METADATA_READ_BYTES) ?: return null
        val digest = try {
            sha256Hex(metadataBytes)
        } catch (io: Throwable) {
            return null
        }
        // The record is exactly the bytes this store published: the in-memory entry AND the durable
        // .digest sibling must both agree, so a swapped-in valid record of another capture is refused.
        if (digest != entry.recordSha256) return null
        // The .digest sibling is read with the same bounded exact-read primitive: 64 hex characters,
        // nothing more.
        val digestSibling = readExactlyBounded(File(dir, "$captureId$SUFFIX_DIGEST"), 64L, 64L)
            ?.toString(Charsets.UTF_8) ?: return null
        if (digestSibling != digest) return null
        val node = try {
            au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.CanonicalJson.parse(metadataBytes.toString(Charsets.UTF_8))
        } catch (io: Throwable) {
            return null
        }
        val record = node as? au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode.Obj ?: return null
        // STREAMED AUDIO BINDING: the header check alone lets a same-length PCM mutation through, so the
        // FULL payload is hashed while streaming and compared to the sha256 the record itself declares.
        val declaredAudioSha = audioShaOf(record) ?: return null
        // BYTE BINDING (spec ee31a3cc): OPEN THE DESCRIPTOR FIRST, then hash THAT descriptor. A path-based
        // hash would validate bytes that a successor owner could replace between validation and the
        // transport's later open; hashing the descriptor we then hand down makes the validated bytes and
        // the transmitted bytes the same file description. The descriptor is rewound to 0 on success.
        // OWNERSHIP TRANSFER (reviewer 9a47123e): from the moment the descriptor opens, THIS function owns
        // it. Hand-written closes on each refusal path missed the exceptional ones, so the whole
        // validation runs in one try, and any failure - including header validation and an unexpected
        // exception - closes the descriptor here. Ownership leaves only on the success return.
        // SOURCE LEASE BEFORE THE OPEN (reviewer 7c2f30e3 finding 2): the operation lease ends when this
        // function returns, and the descriptor outlives it. Taking the source lease FIRST means a refused
        // lease creates NO descriptor, and a granted one keeps the directory counted as in use until the
        // descriptor actually closes - which is the real lifetime of the retained bytes.
        val sourceLease = directoryOwnership.acquireSourceLease()
            ?: return null // ownership unavailable: do not open a source nothing protects
        // GLOBAL SOURCE SLOT, BEFORE THE OPEN: a refused slot must not create a descriptor at all.
        if (!tryAcquireSourceSlot()) {
            runCatching { sourceLease.close() }
            return null
        }
        val source = try {
            java.io.FileInputStream(audio)
        } catch (io: Throwable) {
            runCatching { sourceLease.close() }
            releaseSourceSlot()
            return null
        }
        openedProbe.get()?.set(source)
        val owned = try {
            validateOpenedAudioLocked(captureId, entry, record, source, sourceLease)
        } catch (failure: Throwable) {
            runCatching { source.close() }
            runCatching { sourceLease.close() }
            releaseSourceSlot()
            return null
        } ?: run {
            runCatching { source.close() }
            runCatching { sourceLease.close() }
            releaseSourceSlot()
            return null
        }
        // REGISTER EVERY OPEN SOURCE (reviewer 7c2f30e3): the file is normally still linked here, so a
        // pin-if-unlinked-once decision would never fire. Registration is what makes a LATER purge/unlink
        // accountable. The charge is dropped only at descriptor close - never on cancellation, which does
        // not free an open inode.
        registerOpenSourceLocked(captureId, owned.audio)
        DurableCapture(owned.record, audio, entry.enrollmentEpoch, owned.ownerAccount, owned.ownerSession, owned.audio)
    }

    /**
     * TEST SEAM. Run `durableCapture` and also report the descriptor it opened, so a control can observe
     * whether a refusal CLOSED it. Reviewer 9a47123e was right that an indirect count proves nothing; this
     * exposes the exact object whose fate is in question.
     */
    internal data class RefusingDescriptorProbe(val capture: DurableCapture?, val descriptor: java.io.FileInputStream?)

    internal fun durableCaptureRefusingDescriptorForTest(captureId: String): RefusingDescriptorProbe = withLeaseResult(RefusingDescriptorProbe(null, null)) {
        synchronized(lock) {
            val opened = java.util.concurrent.atomic.AtomicReference<java.io.FileInputStream?>(null)
            val probe = object : Any() {}
            openedProbe.set(opened)
            try {
                RefusingDescriptorProbe(durableCaptureLocked(captureId), opened.get())
            } finally {
                openedProbe.remove()
            }
        }
    }

    /** Thread-local sink the open site publishes its descriptor to, used only by the test seam above. */
    private val openedProbe = ThreadLocal<java.util.concurrent.atomic.AtomicReference<java.io.FileInputStream?>>()

    /** The validated outcome of one opened descriptor, before it is handed down as a [DurableCapture]. */
    private class OpenedAudio(
        val record: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode.Obj,
        val audio: OwnedAudio,
        val ownerAccount: String?,
        val ownerSession: Long?,
    )

    /**
     * Validate the ALREADY-OPEN descriptor [source] against the record and sidecars.
     *
     * Returns null on any refusal. Ownership of [source] passes to the CALLER in every case: on success it
     * is wrapped in the returned [OpenedAudio], and the caller closes it on refusal. Nothing here closes the
     * descriptor, so there is exactly one place that decides its fate (reviewer 9a47123e).
     */
    private fun validateOpenedAudioLocked(
        captureId: String,
        entry: Entry,
        record: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode.Obj,
        source: java.io.FileInputStream,
        sourceLease: AutoCloseable,
    ): OpenedAudio? {
        val declaredAudioSha = audioShaOf(record) ?: return null
        val actualAudioSha = sha256HexBounded(source, limits.maxClipBytes) ?: return null
        if (actualAudioSha != declaredAudioSha) return null
        source.channel.position(0)
        // The header is read from the SAME descriptor, so it cannot come from a different file than the
        // payload just hashed.
        val header = ByteArray(au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.WavWriter.HEADER_BYTES)
        var read = 0
        while (read < header.size) {
            val n = source.read(header, read, header.size - read)
            if (n < 0) return null
            read += n
        }
        if (WavHeaderValidator.validate(header, source.channel.size()) == null) return null
        val owner = readOwnerSidecar(captureId) ?: run {
            sidecarRefusals++
            return null
        }
        if (owner.epoch != entry.enrollmentEpoch) {
            sidecarRefusals++
            return null
        }
        source.channel.position(0)
        // The source lease travels INSIDE the owned source, so the directory cannot be released before the
        // descriptor closes - and nothing can forget to release it.
        // The slot is released by the SAME handle that closes the descriptor, so the bound tracks real open
        // descriptors rather than attempts (reviewer 6456b2a5 finding 3).
        val owned = OwnedAudio(source, source.channel.size(), actualAudioSha, sourceLease, onDescriptorClosed = ::releaseSourceSlot)
        return OpenedAudio(record, owned, owner.account, owner.session)
    }

    /**
     * Read EXACTLY [exactBytes] from a regular file, refusing if the file is shorter, longer, larger than
     * [capBytes], or grows past the bound while being read. No unbounded read anywhere on this path.
     */
    private fun readExactlyBounded(file: File, exactBytes: Long, capBytes: Long): ByteArray? {
        if (exactBytes <= 0 || exactBytes > capBytes) return null
        if (!file.isFile || file.length() != exactBytes) return null
        return try {
            file.inputStream().use { input ->
                val out = ByteArray(exactBytes.toInt())
                var read = 0
                while (read < out.size) {
                    val n = input.read(out, read, out.size - read)
                    if (n <= 0) break
                    if (n <= 0) break
                    read += n
                }
                // Nothing may follow the exact byte count.
                if (read != out.size || input.read() != -1) null else out
            }
        } catch (io: Throwable) {
            null
        }
    }

    /**
     * Stream the WHOLE audio payload through SHA-256, refusing anything past [maxBytes] DURING the read.
     * Returns null on oversize or read failure - a payload that cannot be fully digested is not vouched.
     */
    private fun sha256HexBounded(file: File, maxBytes: Long): String? {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var total = 0L
        return try {
            file.inputStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) return null
                    digest.update(buffer, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (io: Throwable) {
            null
        }
    }

    /**
     * Stream the WHOLE payload of an ALREADY-OPEN descriptor through SHA-256, refusing anything past
     * [maxBytes] DURING the read, then REWIND it to byte 0 so the same descriptor can be read again.
     *
     * This is the byte-binding primitive (spec ee31a3cc): the digest is computed from the very file
     * description the transport will later read, so no replacement of the pathname can insert different
     * bytes between validation and transmission. A path-based hash cannot make that claim.
     */
    private fun sha256HexBounded(source: java.io.InputStream, maxBytes: Long): String? {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var total = 0L
        return try {
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = source.read(buffer)
                if (n < 0) break
                total += n
                if (total > maxBytes) return null
                digest.update(buffer, 0, n)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (io: Throwable) {
            null
        }
    }

    /** The audio sha256 the record itself declares, as written at capture time. */
    private fun audioShaOf(record: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode.Obj): String? {
        val audio = record.fields["audio"] as? au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode.Obj ?: return null
        val sha = audio.fields["sha256"] as? au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode.Str ?: return null
        return sha.value.takeIf { it.length == 64 }
    }

    /** A capture THIS STORE vouches for: validated record bytes, matching audio, the owning identity. */
    class DurableCapture(
        val record: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode.Obj,
        /**
         * The pathname this capture was published at. Kept for diagnostics only - it is NOT what the
         * transport reads. Reading [audio] would re-open a pathname a successor can replace; the bytes to
         * transmit come from [owned] (spec ee31a3cc).
         */
        val audio: File,
        val enrollmentEpoch: String,
        /** Admitted account/session from the owner sidecar; null for older epoch-only admissions. */
        val enrollmentAccount: String? = null,
        val enrollmentSession: Long? = null,
        /**
         * The validated bytes as an OPEN DESCRIPTOR, hashed by reading this same descriptor. Null when the
         * store produced the capture without a bound source; the transport must refuse such a capture
         * rather than fall back to the path, because a pathname cannot bind validation to transmission.
         */
        val owned: OwnedAudio? = null,
    )

    /**
     * Serialize the owner sidecar through an ESCAPING JSON writer (reviewer 30b1c817): the account
     * name is arbitrary text and must never be able to forge sidecar structure.
     */
    private fun ownerSidecarJson(captureId: String, account: String?, session: Long?, epoch: String): String {
        val buffer = okio.Buffer()
        val writer = com.squareup.moshi.JsonWriter.of(buffer)
        writer.beginObject()
        writer.name("capture_id").value(captureId)
        writer.name("account").value(account)
        writer.name("session").value(session)
        writer.name("epoch").value(epoch)
        writer.endObject()
        writer.close()
        return buffer.readUtf8()
    }

    /**
     * STRICT parse of the owner sidecar (reviewer 30b1c817): a real JSON parser with EXACT required
     * schema - object with exactly account(string, nonempty)/session(integer)/epoch(string), no
     * duplicates, no unknown keys, no trailing content. Null on ANY deviation; the caller fails closed.
     */
    private fun parseOwnerSidecar(text: String, expectedCaptureId: String): OwnerRecord? {
        val reader = com.squareup.moshi.JsonReader.of(okio.Buffer().writeUtf8(text))
        try {
            var captureId: String? = null
            var account: String? = null
            var session: Long? = null
            var epoch: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "capture_id" -> {
                        if (captureId != null) return null // duplicate key
                        // TOKEN TYPE: moshi coerces numbers to strings unless checked (reviewer 2b67e016).
                        if (reader.peek() != com.squareup.moshi.JsonReader.Token.STRING) return null
                        captureId = reader.nextString()
                    }

                    "account" -> {
                        if (account != null) return null // duplicate key
                        if (reader.peek() != com.squareup.moshi.JsonReader.Token.STRING) return null // numeric account refused
                        account = reader.nextString().ifEmpty { return null }
                    }

                    "session" -> {
                        if (session != null) return null // duplicate key
                        if (reader.peek() != com.squareup.moshi.JsonReader.Token.NUMBER) return null // string-encoded session refused
                        session = reader.nextLong()
                    }

                    "epoch" -> {
                        if (epoch != null) return null // duplicate key
                        if (reader.peek() != com.squareup.moshi.JsonReader.Token.STRING) return null
                        epoch = reader.nextString()
                    }

                    else -> return null // unknown key
                }
            }
            reader.endObject()
            // Trailing content after the closing brace is a deviation, not decor (strict EOF check).
            if (reader.peek() != com.squareup.moshi.JsonReader.Token.END_DOCUMENT) return null
            // BINDING: the sidecar belongs to exactly THIS capture (reviewer 2b67e016) - a sidecar
            // swapped between captures is a corruption, never a serveable identity.
            if (captureId != expectedCaptureId) return null
            account ?: return null
            session ?: return null
            epoch ?: return null
            return OwnerRecord(account, session, epoch)
        } catch (_: Exception) {
            return null
        } finally {
            reader.close()
        }
    }

    /** The full sidecar record; epoch is kept so the entry binding can be VERIFIED, not discarded. */
    private data class OwnerRecord(val account: String, val session: Long, val epoch: String)

    /**
     * STRICT bounded variable-length read of the admitted-ownership sidecar (reviewer 0081c95f):
     * the file is ordinary JSON of ANY length up to the cap - the read demands the file's ACTUAL
     * size, never a fixed width, and the parser is exact (no trailing junk, both fields present).
     * Null means absent OR corrupt; the caller fails closed.
     */
    private fun readOwnerSidecar(captureId: String): OwnerRecord? {
        val f = File(dir, "$captureId$SUFFIX_OWNER")
        if (!f.isFile) return null
        // The cap holds DURING the read (spec 8adcfd01): read at most CAP+1 bytes and refuse when a
        // byte beyond the cap exists - a file growing concurrently cannot smuggle oversize content.
        val cap = 512
        val bytes = ByteArray(cap + 1)
        val read = try {
            java.io.FileInputStream(f).use { input ->
                var total = 0
                while (total < bytes.size) {
                    val n = input.read(bytes, total, bytes.size - total)
                    if (n < 0) break
                    total += n
                }
                total
            }
        } catch (_: java.io.IOException) {
            return null // removed/unreadable after isFile: refuse explicitly, never throw through
        }
        // A full CAP+1 read means the file is at or beyond the cap: refuse either way.
        if (read == 0 || read > cap) return null
        return parseOwnerSidecar(String(bytes, 0, read, Charsets.UTF_8), captureId)
    }

    // The identity of the attempt most recently ADMITTED for captureId: the generation it was admitted
    // under plus the durable attempt count recorded for it. A completion (accept, failure, cancellation)
    // may only settle state for the attempt it actually belongs to - an OLD completion must never borrow
    // the successor's authority via currentGeneration (reviewer d1484829). Null when no attempt is
    // currently admitted, or when its admission could not be durably recorded.
    fun admittedAttemptForTest(captureId: String): AdmittedAttempt? = synchronized(lock) {
        val entry = memoryEntries[captureId] ?: return null
        AdmittedAttempt(admittedGeneration[captureId] ?: return null, captureId, entry.attempts)
    }

    data class AdmittedAttempt(val generation: Long, val captureId: String, val attemptNumber: Int)

    // The IMMUTABLE token for the attempt pickup just admitted (reviewer 20a5440b): the count is never
    // re-read afterwards, so a caller cannot capture a successor's identity.
    fun pickupAttemptToken(generation: Long, captureId: String, nowMs: Long = clock()): AdmittedAttempt? = withLeaseResult(null) {
        pickupAttemptTokenGuarded(generation, captureId, nowMs)
    }

    private fun pickupAttemptTokenGuarded(generation: Long, captureId: String, nowMs: Long): AdmittedAttempt? = synchronized(lock) {
        // SPEC a7f6ce81: the unknown-footprint hold is removed from the ATTEMPT path. Attempt
        // accounting does not depend on byte coverage, and withholding attempts is what made an
        // unknown footprint self-sustaining (uploads shrink it). Ownership is still validated.
        if (!ownership.isValidUnderLock(generation)) {
            null
        } else {
            val entry = memoryEntries[captureId]
            if (entry == null || !retryEligibleLocked(captureId, entry, nowMs)) {
                null
            } else {
                recordAttempt(generation, captureId)?.let { count ->
                    admittedGeneration[captureId] = generation
                    AdmittedAttempt(generation, captureId, count)
                }
            }
        }
    }

    // Settlement is VALIDATE AND MUTATE TOGETHER in ONE ownership transaction, keyed on that token. A
    // generation-only write would let a same-generation successor admitted between validation and
    // mutation receive an OLD attempt's delay (reviewer 20a5440b). Null = token no longer owns the
    // queue; NOTHING was mutated.
    fun scheduleRetryForAdmittedAttempt(token: AdmittedAttempt, retryAfterS: Long? = null): Long? = withLeaseResult(null) {
        scheduleRetryForAdmittedAttemptGuarded(token, retryAfterS)
    }

    private fun scheduleRetryForAdmittedAttemptGuarded(token: AdmittedAttempt, retryAfterS: Long?): Long? = synchronized(lock) {
        // (spec f6a8f380) ONE owner transaction validates and mutates; no currentGeneration fallback.
        // A REVOKED epoch still allows disposal when the token still owns the attempt slot: the capture
        // was retained, no successor attempt was admitted, and only ownership retired. A successor
        // admission flips the admitted generation/count and blocks the old token permanently.
        if (!tokenStillOwnsAttemptLocked(token)) {
            null
        } else {
            scheduleRetryAfterFailureLocked(token.generation, token.captureId, retryAfterS = retryAfterS, allowRevokedDisposal = true)
        }
    }

    // True when this token is still THE admitted attempt for the capture: either under a current epoch
    // (normal settlement) or after a revocation with no successor admitted (revocation disposal).
    // A successor was admitted for this capture after the token's attempt: any attempt number ABOVE
    // the token's (or a different admitting generation) means the token is dead for good.
    private fun attemptSupersededLocked(captureId: String, token: AdmittedAttempt): Boolean = (memoryEntries[captureId]?.attempts ?: -1) > token.attemptNumber ||
        admittedGeneration[captureId] != token.generation

    private fun tokenStillOwnsAttemptLocked(token: AdmittedAttempt): Boolean = memoryEntries.containsKey(token.captureId) &&
        admittedGeneration[token.captureId] == token.generation &&
        memoryEntries[token.captureId]?.attempts == token.attemptNumber &&
        (ownership.isValidUnderLock(token.generation) || !attemptSupersededLocked(token.captureId, token))

    // Same contract for a validated acceptance or terminal disposal: drop only if this exact attempt
    // still owns the entry AND its epoch is STILL VALID - a retired token never releases (spec
    // 442bfbd7); retry-boundary disposal is the only revoked-epoch write.
    fun releaseForAdmittedAttempt(
        token: AdmittedAttempt,
        reason: TerminalReason = TerminalReason.UPLOAD_ACCEPTED,
    ): Boolean = withLeaseResult(false) {
        // THIS DELETES (deleteLocked), so it needs the same lifetime fence as every other mutation
        // (found by the entry-point audit around reviewer e09f4cfc). Without it a stale ACCEPTED/TERMINAL
        // ack delivered to a closed owner deleted the capture out of a successor's directory.
        val removed = synchronized(lock) {
            if (!attemptIdentityHoldsLocked(token.generation, token.captureId, token.attemptNumber)) {
                null
            } else {
                memoryEntries.remove(token.captureId)?.also { deleteLocked(it, reason.name) }
            }
        } != null
        // MONITORING (review 3c0f31d3, gap 2): the truthful file-level outcome is logged INSIDE
        // deleteLocked with this caller's fixed reason; `removed` here is only the index transition.
        if (removed) dropCounterOnly(reason)
        removed
    }

    private val admittedGeneration = mutableMapOf<String, Long>()

    /** Test seam: how many records' worth of budget live reservations currently occupy. */
    fun reservedRecordsForTest(): Int = synchronized(lock) { reservations.size }

    /**
     * Settlement bookkeeping is BOUNDED: a reservation lives in [reservations] only while it is HELD, and
     * both discard() and adopt() remove it under the store lock. There are no tombstone entries to accumulate.
     */
    private fun liveReservationBytesLocked(): Long = reservations.values.sumOf { it }

    /**
     * THE single definition of what this owner already occupies, in RECORDS (spec a3ef694f, reviewer
     * 5e92bba3 finding 1). Both admission paths call it, so they cannot disagree.
     *
     * Capacity is the COMPLETE-ENTRY COUNT ON DISK, never an in-memory tally. The map is bookkeeping
     * about work in flight; disk is the truth about what is retained. A tally that disagrees with disk
     * is the defect this whole change exists to remove: stale or debris charges would fill the queue
     * while real files went uncounted. The ONLY memory added is live in-flight reservations, which
     * hold a slot for a capture that has been admitted but has not yet written its `.meta`. [exclude]
     * drops one reservation, for re-checking an attempt that already holds it.
     */
    private fun ownerRecordsLocked(exclude: String? = null): Int = rootRecordCountLocked() + reservations.keys.count { it != exclude }

    /** Re-check the record budget at publication time (a purge/expiry may have moved it since admission). */
    private fun roomForPublicationLocked(admission: Admission): Boolean = ownerRecordsLocked(exclude = admission.id) < limits.maxQueueRecords

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
    // A .part file that will not delete still occupies the queue. Charge the bytes under the name the file
    // implies so they are owned rather than invisible; a later sweep retries the delete.
    // Delete ONE file and settle its accounting as a single owner transition.
    //
    // Everything happens under the ownership monitor: the delete, the measurement of what survived, and the
    // charge or its release. The previous version was called from outside the lock by discardCurrentPartials
    // and the recovery sweep, so deletion and accounting could interleave with a competing owner holding the
    // monitor - reintroducing through these paths the same race the main transitions were fixed for
    // (reviewer 78c775dc).
    // Removes a `.wav.part` staging leftover and keeps its bytes accounted.
    //
    // THIS DELETE USED TO BE UNCOUNTED. `recoverLocked` runs this sweep before it knows which captures
    // are ready, so it is the one sweep that can touch files belonging to a committed entry, and it
    // incremented a terminal counter only when a delete FAILED. On 2026-11-07 the queue was found empty
    // with `terminal-counters.txt` byte-identical, which is exactly what an uncounted delete leaves
    // behind (spec bfe91857). Every successful removal is now counted and logged by name.
    private fun File.deleteOrChargeOnFailure() {
        val file = this
        val id = file.name.removeSuffix(SUFFIX_PART)
        synchronized(lock) {
            // The boolean result is NOT evidence: identity comes from the stat below. The delete is still
            // attempted because it is the operation that removes the bytes.
            file.delete()
            // ABSENCE IS CHECKED THE SAME WAY MEASUREMENT CHECKS IT. `!exists()` returns false for an
            // unreadable or permission-denied path, so a FAILED delete could look like a successful one and
            // free retained bytes. Only a no-follow stat that reports NoSuchFileException proves absence.
            val absence = absenceOf(file)
            when {
                absence == Absence.PRESENT -> {
                    // The file survived the delete. It is still OCCUPIED SPACE on the device, so its
                    // capture must keep its record (spec a3ef694f: capacity is counted, not measured).
                    claimOwnedLocked(id, ready = false)
                }

                absence == Absence.ABSENT -> {
                    // REVERSE TRANSITION. The no-follow stat proves THIS exact path is gone; delete()'s
                    // boolean adds no identity proof, and false here usually means an earlier or competing
                    // sweep already removed it. A retry over an already-absent path must RELEASE the charge,
                    // not poison admission with a permanent unmeasured mark (reviewer 567c0b94).
                    ownedCharges.remove(id)
                    // Absence is proven here, and `delete()` is the operation that produced it. Counted
                    // so that a staging sweep can never again empty bytes without leaving a trace.
                    dropCounterOnly(TerminalReason.RECOVERY_STAGING_REMOVED)
                    Timber.tag(QUEUE_TAG).i(
                        "voice-upload remove: file=%s reason=recovery-staging-removed",
                        file.name,
                    )
                }

                else -> {
                    // UNKNOWN: inability to stat. The charge is NEVER freed on a failure to look, so a
                    // staging leftover whose absence cannot be proven keeps its record.
                }
            }
        }
    }

    private enum class Absence { ABSENT, PRESENT, UNKNOWN }

    // Absence that distinguishes "not there" from "could not look", using the same no-follow stat as
    // measurement. Only NoSuchFileException proves absence.
    private fun absenceOf(file: File): Absence = hooks.settlementAbsenceOverride?.invoke(file)?.let { code ->
        when (code) {
            0 -> Absence.ABSENT
            1 -> Absence.PRESENT
            else -> Absence.UNKNOWN
        }
    } ?: run { absenceOfReal(file) }

    private fun absenceOfReal(file: File): Absence = try {
        java.nio.file.Files.readAttributes(
            file.toPath(),
            java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS,
        )
        Absence.PRESENT
    } catch (missing: java.nio.file.NoSuchFileException) {
        Absence.ABSENT
    } catch (failure: java.io.IOException) {
        Absence.UNKNOWN
    }

    private fun deleteStaging(attemptId: String) {
        // Bounded root traversal, like recovery: sweeping a partially-known listing is destructive, so an
        // unreadable listing defers the sweep entirely.
        val files = boundedRootListingOrNull() ?: return
        files.forEach { f ->
            // Checked, like every other deletion: a staging file that refuses to delete still occupies the
            // queue, and discarding the result left those bytes charged nowhere (reviewer e4342946).
            if (f.name.startsWith("$STAGING_PREFIX$attemptId.")) f.deleteOrChargeOnFailure()
        }
    }

    private fun releaseReservation(id: String, projected: Long) {
        synchronized(lock) { reservations.remove(id) }
    }

    /**
     * Record budget test against the durable entries on disk PLUS live reservations.
     */
    private fun roomLocked(): Boolean = ownerRecordsLocked() < limits.maxQueueRecords

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
    fun prepare(
        admission: Admission,
        captureId: String,
        enrollmentEpoch: String,
        pcmChunks: List<Pair<ShortArray, Int>>,
        canonicalMetadata: ByteArray,
        enrollmentAccount: String? = null,
        enrollmentSession: Long? = null,
    ): Entry? = prepareAt(fence.currentGeneration(), admission, captureId, enrollmentEpoch, pcmChunks, canonicalMetadata, enrollmentAccount, enrollmentSession)

    fun prepare(
        captureId: String,
        enrollmentEpoch: String,
        pcmChunks: List<Pair<ShortArray, Int>>,
        canonicalMetadata: ByteArray,
        enrollmentAccount: String? = null,
        enrollmentSession: Long? = null,
    ): Entry? {
        val samples = sampleCount(pcmChunks) ?: return null
        val admission = admit(samples, canonicalMetadata.size)
            ?: run {
                null
            }
            ?: return null
        return prepareAt(fence.currentGeneration(), admission, captureId, enrollmentEpoch, pcmChunks, canonicalMetadata, enrollmentAccount, enrollmentSession)
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
        enrollmentAccount: String? = null,
        enrollmentSession: Long? = null,
    ): Entry? = withLeaseResult(null) {
        prepareAtGuarded(generation, admission, captureId, enrollmentEpoch, pcmChunks, canonicalMetadata, enrollmentAccount, enrollmentSession)
    }

    private fun prepareAtGuarded(
        generation: Long,
        admission: Admission,
        captureId: String,
        enrollmentEpoch: String,
        pcmChunks: List<Pair<ShortArray, Int>>,
        canonicalMetadata: ByteArray,
        enrollmentAccount: String? = null,
        enrollmentSession: Long? = null,
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
            writeAndPublish(generation, admission, captureId, enrollmentEpoch, pcmChunks, samples, canonicalMetadata, enrollmentAccount, enrollmentSession)
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
        enrollmentAccount: String?,
        enrollmentSession: Long?,
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
            // Admitted ownership sidecar: the FULL admitted identity at capture start, so a
            // restarted queue still knows WHICH account/session admitted this capture. Missing
            // sidecar (older entries) = epoch-only admissions, and the identity check degrades to
            // the epoch path - never to "no check".
            if (enrollmentAccount != null || enrollmentSession != null) {
                val ownerJson = ownerSidecarJson(captureId, enrollmentAccount, enrollmentSession, enrollmentEpoch)
                val ownerFile = File(dir, "$captureId$SUFFIX_OWNER")
                FileOutputStream(ownerFile).use { out ->
                    out.write(ownerJson.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                fileSync.sync(ownerFile)
            }
            val entry = Entry(
                captureId,
                enrollmentEpoch,
                digest,
                audioBytes,
                canonicalMetadata.size,
                attempts = 0,
                createdAtMs = createdAt,
                enrollmentAccount = enrollmentAccount,
                enrollmentSession = enrollmentSession,
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
                val roomOk = roomForPublicationLocked(admission)
                if (!ownership.isValidUnderLock(generation)) {
                    null
                } else if (preparedIds.contains(captureId) || memoryEntries.containsKey(captureId) || !roomOk) {
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
                    // Ownership transfers here, so the owner charge is created here and nowhere else.
                    // It starts NOT ready: readiness is asserted only after the marker is durable, and a
                    // failure in between must leave an owned-but-uncertain capture rather than none.
                    ownedCharges[captureId] = OwnedCharge(ready = false)
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
                        deleteLocked(entry, "prepare-rollback")
                        memoryEntries.remove(captureId)
                        preparedIds.remove(captureId)
                        ownedCharges.remove(captureId)
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
                            // The charge already exists from adoption and stays NOT ready: exactly one
                            // owner entry per capture, so this path cannot double-count.
                            synchronized(lock) {
                                ownedCharges.getOrPut(captureId) {
                                    OwnedCharge(ready = false)
                                }.ready = false
                            }
                            throw PublicationUncertain(
                                "ready marker may exist but its durability could not be confirmed; publication uncertain",
                                transitionFailure,
                            )
                        }
                        committed = true
                        memoryEntries[captureId] = entry
                        preparedIds.add(captureId)
                        // Flip the EXISTING owner charge; never add a second one.
                        ownedCharges[captureId]?.ready = true
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
        // A capture with an unresolved record (retry boundary or attempt count) is NOT servable, even
        // though it is charged and indexed (reviewer 199ccdee).
        memoryEntries.values
            .filter { !heldUncertainLocked(it.captureId) }
            .sortedBy { it.createdAtMs }
    }

    private var recovered = false

    /**
     * Pinned-but-UNLINKED audio still occupying the device, keyed by the descriptor that holds it.
     *
     * Unlinking frees no bytes: an open inode keeps its charge until the descriptor closes. So purge can
     * legitimately report residue mid-upload, and capacity accounting must still count these bytes.
     *
     * COUNTED ONCE. While a capture is still linked, [readyEntries] already charges its bytes, so a pin is
     * counted here only once its file is gone. [pinnedUnlinkedBytes] enforces that, and the pin is removed
     * when the descriptor closes.
     */
    private val pinnedUnlinked = java.util.IdentityHashMap<OwnedAudio, Long>()

    /**
     * Bytes pinned by open descriptors whose file is no longer linked: retention that unlink did not free.
     * Closes when the descriptor does, never before.
     */
    fun pinnedUnlinkedBytes(): Long = synchronized(lock) { pinnedUnlinked.values.sum() }

    /**
     * The bytes this queue actually occupies: linked entry bytes plus pinned unlinked bytes. This is the
     * number capacity decisions should use; [totalQueuedBytes] alone under-reports during an upload whose
     * file has been removed but whose descriptor is still open.
     */
    fun totalRetainedBytes(): Long = totalQueuedBytes() + pinnedUnlinkedBytes()

    /**
     * TEST SEAM: close every source this store handed down, so a fixture that only needed a RECORD cannot
     * leave a descriptor (and its source lease) holding the directory.
     */
    fun settleSourcesForTest(): List<Boolean> = synchronized(lock) {
        // SETTLE, not close. close() is the lifecycle-notification path: it marks the source terminal and
        // lets whoever observes the drain perform the close, so a body writer still running at that moment
        // leaves the descriptor (and its PROCESS-WIDE source slot) open. Only settle() completes the
        // terminal transition and reports whether it did, which is what teardown must assert.
        openSources.keys.toList().map { owned -> runCatching { owned.settle(SOURCE_SETTLE_TIMEOUT_MS) }.getOrDefault(false) }
    }

    /** TEST SEAM: runs AFTER purge discovery, so a control can observe the promotion boundary itself. */
    @Volatile
    internal var onAfterPurgeDiscoveryForTest: (() -> Unit)? = null

    /** TEST SEAM: proves the settle callback actually ran, so a pin that never drops cannot pass. */
    internal val settleCallbacksForTest = java.util.concurrent.atomic.AtomicInteger(0)

    /** TEST SEAM: a hook that runs immediately BEFORE the pin decision, so the window is reachable. */
    internal var beforePinForTest: (() -> Unit)? = null

    /**
     * THE SOURCE SLOT, SHARED BY EVERY STORE IN THE PROCESS (reviewer 6456b2a5 finding 3; corrected after
     * reviewer 4e8592ba pointed out an instance field cannot bound the app).
     *
     * At most [MAX_OPEN_SOURCES] descriptors may be open at once ACROSS ALL STORES, and the slot is taken
     * BEFORE the open so a refused slot creates no descriptor at all. It is released only when the
     * descriptor actually closes - not when the handing call returns and not on cancellation - so the bound
     * is on REAL open descriptors rather than on attempts. The transport's semaphore bounds calls; this
     * bounds SOURCES, which outlive a call whenever a writer is parked.
     *
     * Why it is PROCESS-WIDE and not per instance: a store can be constructed more than once over different
     * directories (a successor after a handover, a diagnostics store, a test fixture), and descriptors from
     * all of them retain device bytes. A per-instance counter would let N stores hold N x the bound while
     * each looked correct. Every open descriptor retains its bytes after an unlink, so an unbounded source
     * population holds unbounded retained bytes while every individual queue looks idle.
     */
    private val openSourceSlots: java.util.concurrent.atomic.AtomicInteger get() = PROCESS_OPEN_SOURCE_SLOTS

    /** Take a source slot, or null when the global bound is reached. Pair with [releaseSourceSlot]. */
    private fun tryAcquireSourceSlot(): Boolean {
        while (true) {
            val current = openSourceSlots.get()
            if (current >= MAX_OPEN_SOURCES) return false
            if (openSourceSlots.compareAndSet(current, current + 1)) return true
        }
    }

    private fun releaseSourceSlot() {
        while (true) {
            val current = openSourceSlots.get()
            if (current <= 0) return
            if (openSourceSlots.compareAndSet(current, current - 1)) return
        }
    }

    /** TEST SEAM: how many source slots are currently held, to prove the bound is real and released. */
    fun openSourceSlotsForTest(): Int = openSourceSlots.get()

    /**
     * TEST SEAM: the capture ids whose descriptors this store was handed and has not seen closed.
     *
     * Lets a test's teardown assert it released everything, instead of erasing the bound's state: a reset
     * would leave descriptors, leases and pin charges alive while zeroing the counter that is supposed to
     * notice them (reviewer 2c7dc375). Empty means nothing leaked.
     */
    fun openCaptureIdsForTest(): List<String> = synchronized(lock) { openSources.values.toList() }

    /**
     * Every open source this store has handed down, with the capture it belongs to.
     *
     * REVIEWER 7c2f30e3: pinning only at handoff was wrong, because the WAV is normally still LINKED at
     * that moment, so no pin was ever registered and a later purge/unlink added nothing. The store now
     * registers EVERY open source at handoff and accounts its transition whenever the file goes away.
     */
    private val openSources = java.util.IdentityHashMap<OwnedAudio, String>()

    /**
     * Register [owned] as an open source for [captureId]. Called at EVERY handoff, linked or not, so a later
     * unlink can be accounted after the fact.
     */
    private fun registerOpenSourceLocked(captureId: String, owned: OwnedAudio) {
        if (openSources.containsKey(owned)) return
        openSources[owned] = captureId
        owned.onSettled {
            settleCallbacksForTest.incrementAndGet()
            synchronized(lock) {
                openSources.remove(owned)
                pinnedUnlinked.remove(owned)
            }
        }
        accountUnlinkLocked(captureId, owned)
    }

    /**
     * Account [owned]'s bytes against capacity IF its file is no longer linked. Idempotent and counted
     * once: while linked, [readyEntries] already charges these bytes, so the pin exists only for the
     * unlinked interval. Must be called again after any unlink (see [accountUnlinksLocked]).
     */
    private fun accountUnlinkLocked(captureId: String, owned: OwnedAudio) {
        if (!openSources.containsKey(owned)) return
        beforePinForTest?.invoke()
        val audio = File(dir, "$captureId$SUFFIX_WAV")
        if (audio.isFile) {
            // Still linked: the entry carries the charge, so there must be no pin (never count twice).
            pinnedUnlinked.remove(owned)
            return
        }
        val bytes = owned.byteCount
        if (bytes <= 0) return
        pinnedUnlinked[owned] = bytes
    }

    /** Re-account every open source after a deletion pass, so an upload that outlived its file still counts. */
    private fun accountUnlinksLocked() {
        if (openSources.isEmpty()) return
        for ((owned, captureId) in openSources.entries.toList()) {
            accountUnlinkLocked(captureId, owned)
        }
    }

    fun totalQueuedBytes(): Long = readyEntries().sumOf { it.audioBytes + it.metadataBytes }

    /** Test seam: the contributions directory this store writes to. */
    fun dirForTest(): File = dir

    /** Expiry removes ONLY expired entries; others are untouched. */
    fun expireOlderThan(): List<String> = guarded(emptyList()) {
        expireOlderThanGuarded()
    }

    private fun expireOlderThanGuarded(): List<String> {
        retryDisqualifiedCleanupLocked()
        val now = clock()
        val removed = mutableListOf<String>()
        synchronized(lock) {
            if (!recovered) reloadUnownedFromDisk()
            // Entries whose ready marker vanished (purged elsewhere) leave the index immediately.
            for ((id, e) in memoryEntries.toMap()) {
                if (id !in preparedIds && !File(dir, "$id$SUFFIX_READY").exists()) {
                    deleteLocked(e, "ready-marker-vanished")
                }
            }
            // A CAPTURE TIME THAT CANNOT BE EVALUATED NEVER AUTHORISES A DELETION. Untrusted ages are
            // already replaced at recovery (spec 3e33e306, [resetUntrustedAgeLocked]), so what can still
            // arrive here is a value that went bad after the reset. Such an entry is skipped - neither
            // expired nor served off a clock we cannot trust - and is NOT held: holding it would mark a
            // charged entry unready under a reason that says nothing about the recording. Zero/absent is
            // the only such case, because a future time yields a negative age.
            val expiredIds = memoryEntries.values
                .filter { entry -> entry.createdAtMs > 0 && entry.createdAtMs <= now }
                .filter { entry -> (now - entry.createdAtMs) / 1000L >= limits.maxQueueAgeSeconds }
                .map { it.captureId }
            for (id in expiredIds) {
                memoryEntries[id]?.let { deleteLocked(it, "queue-age-expired") }
                preparedIds.remove(id)
                removed += id
            }
            // HELD ENTRIES AGE LIKE EVERY OTHER ENTRY (reviewer 42801e42 item 2, spec bfe91857). A held
            // capture is CHARGED and on disk but deliberately absent from `memoryEntries`, so iterating
            // that map alone meant a held entry never expired: it would occupy the user's storage for
            // ever while the seven-day policy quietly did not apply to it. Expiry here deletes through
            // the same `deleteLocked` intent-first protocol; it never makes the entry servable.
            for ((id, createdAtMs) in heldCaptureAgesLocked()) {
                // ONLY captures this pass HELD. Every other capture is either in the index (expired above)
                // or was deliberately disqualified, and a disqualified capture must never be deleted
                // through a second path - it belongs to the intent-first cleanup, which confirms its
                // marker before destroying anything. Without this the same id expired twice.
                if (id !in heldIds) continue
                if (id in memoryEntries.keys) continue
                // An implausible age NEVER authorises a delete, held or not.
                if (createdAtMs <= 0 || createdAtMs > now) continue
                if ((now - createdAtMs) / 1000L < limits.maxQueueAgeSeconds) continue
                // deleteLocked only needs the id and the age; capacity is a record count, not bytes.
                deleteLocked(
                    Entry(
                        captureId = id,
                        enrollmentEpoch = "",
                        recordSha256 = "",
                        audioBytes = 0,
                        metadataBytes = 0,
                        attempts = 0,
                        createdAtMs = createdAtMs,
                    ),
                    "queue-age-expired-held",
                )
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
    // Entries whose retry-boundary persistence FAILED and whose durable state is therefore UNKNOWN.
    // While any exist, retryEligible fails CLOSED for them. The uncertainty is BACKED BY A DURABLE
    // INTENT FILE: a failed publication leaves the intent (tmp or renamed) on disk, so a FRESH OWNER
    // reconciles the barriers before eligibility instead of forgetting the boundary (reviewer 7433e6a9).
    private val retryBoundaryUncertain = mutableSetOf<String>()

    // Captures whose ATTEMPT-COUNT record is in an unresolved pending-intent state. While set, the count
    // is untrusted: no further attempt is recorded and the entry is not served (reviewer 199ccdee).
    private val attemptsRecordUncertain = mutableSetOf<String>()

    // Captures recovery HELD rather than served this run. They are charged and on disk but absent from
    // `memoryEntries`, so without this record no later pass could apply age policy to them.
    private val heldIds = mutableSetOf<String>()

    /**
     * Rewrite `.created` when its recorded value cannot be trusted, and return whether it did.
     *
     * Untrusted means absent/zero/malformed (an unsynced write lost on crash) or in the future (clock
     * skew). Both are properties of the BOOKKEEPING, not of the recording, so the recording is kept and
     * its age authority restarted rather than used as grounds for refusal. The rewrite is best effort: a
     * failed write leaves the old value, which the next recovery resets again - never a delete.
     */
    private fun resetUntrustedAgeLocked(id: String): AgeReset {
        val file = File(dir, "$id$SUFFIX_CREATED")
        val now = clock()
        val recorded = readBoundedSidecar(file, MAX_CREATED_SIDECAR_BYTES)?.trim()?.toLongOrNull()
        if (recorded != null && recorded > 0 && recorded <= now) return AgeReset.Trusted
        val written = runCatching {
            hooks.onCreatedAgeWrite?.invoke(id)
            file.writeText(now.toString())
            file.readText().trim().toLongOrNull() == now
        }.getOrDefault(false)
        // A FAILED WRITE IS ITS OWN OUTCOME, NOT A REASON TO JUDGE THE RECORDING (reviewer b796c5e9).
        // Returning a boolean that recovery ignored left the caller to run validation on the unresettable
        // age, and validation reporting null sent a complete recording into INVALID_ON_RECOVERY - the
        // deletion this helper exists to prevent.
        // THE FAILED AGE IS THE ACTUAL CLOCK READING, not a placeholder: it becomes the entry's
        // `createdAtMs` for this process, so it must be a real, plausible capture time.
        val outcome = if (written) AgeReset.Reset else AgeReset.Failed(now)
        dropCounterOnly(outcome.counter)
        Timber.tag(QUEUE_TAG).i(
            "voice-upload serve: capture=%s reason=%s recorded=%s",
            id,
            outcome.reason,
            recorded,
        )
        return outcome
    }

    /**
     * Capture time of every capture ON DISK that this owner holds, read from its own `.created` sidecar
     * rather than from the index, because a held entry is precisely the one the index does not have.
     * Bounded read; an unreadable or implausible value is reported as 0 so the caller cannot delete on it.
     */
    private fun heldCaptureAgesLocked(): Map<String, Long> {
        val held = (ownedCharges.keys + heldIds).filter { it !in memoryEntries.keys }.toSet()
        return held.mapNotNull { id ->
            val created = readBoundedSidecar(File(dir, "$id$SUFFIX_CREATED"), MAX_CREATED_SIDECAR_BYTES)
                ?.trim()?.toLongOrNull()?.takeIf { it > 0 }
            created?.let { id to it }
        }.toMap()
    }

    /** Ids recovery HELD this run, for expiry accounting. Cleared with the index transitions above. */
    private fun uncertainIdsSnapshot(): Set<String> = synchronized(lock) { heldIds.toSet() }

    /** The capture is held when ANY of its durable records is in an unresolved state. */
    private fun heldUncertainLocked(captureId: String): Boolean = captureId in retryBoundaryUncertain || captureId in attemptsRecordUncertain

    /**
     * ONE atomic/durable protocol for every writer of a sidecar record: temp file, contents sync, atomic
     * rename, record sync, directory sync. writeText alone could truncate a previously good record
     * (reviewer 7433e6a9). Returns false when ANY barrier fails; the record's durable state is unknown.
     */
    private fun publishRecordAtomicallyLocked(record: File, content: String): Boolean = try {
        val tmp = File(dir, record.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.flush()
        }
        fileSync.sync(tmp)
        if (!tmp.renameTo(record) || !record.exists()) throw java.io.IOException("rename failed")
        fileSync.sync(record)
        syncDirectoryOrFail(dir)
        tmp.delete()
        true
    } catch (failure: Throwable) {
        false
    }

    /**
     * Publish the durable retry-not-before boundary. The INTENT is the tmp file: it becomes visible the
     * moment it is written, and recovery reconciles its barriers (sync/rename/sync/dir) before granting
     * eligibility, so a failed publication can never shorten a received server minimum - a fresh owner
     * sees the intent and finishes it or fails closed (reviewer 7433e6a9).
     */
    private fun publishRetryBoundaryLocked(captureId: String, boundaryMs: Long) {
        val record = File(dir, "$captureId$SUFFIX_RETRY")
        if (!publishRecordAtomicallyLocked(record, boundaryMs.toString())) {
            // Durable state unknown; the intent (tmp or renamed) stays on disk for reconciliation.
            retryBoundaryUncertain += captureId
            nextRetryAtMs.remove(captureId)
            throw IllegalStateException("retry boundary publication uncertain")
        }
    }

    /**
     * Presence is read from the BOUNDED directory listing, NOT from exists(): a stat failure must look
     * UNKNOWN, never absent, and an over-bound root is an explicit failure, not an eager allocation
     * (reviewer 45b9d4de). Returns null when the directory cannot be enumerated or exceeds the bound.
     */
    // DISCOVERY, NOT SWEEPING (spec 2741a0ca). Nothing here deletes, so the traversal bound that protects
    // deletes has no business refusing it: it made every capture look like it had an unresolved intent, so
    // recovery held them all and served none - the same deadlock as the recovery listing, one call deeper.
    //
    // It stays UNBOUNDED but it is no longer a full listing of the root, which is what made it expensive
    // and what made partial coverage dangerous. Its only questions are about SPECIFIC names, and a direct
    // existence check answers those exactly at any directory size: presence is never a guess, and absence
    // is proven for the one name asked about rather than inferred from a truncated listing (reviewer
    // 45b9d4de kept intact - a failed listing still reports unknown, never absence).
    private fun visibleNamesLocked(): Set<String>? = try {
        dir.list()?.toHashSet() ?: throw MeasurementError("root unlistable")
    } catch (unknown: MeasurementError) {
        null
    }

    /**
     * Three-outcome reconciliation of a retry intent (reviewer 199ccdee): ABSENT (no intent visible ->
     * null, hold released), VALID (barriers reconciled, bounded read, strict positive parse -> boundary),
     * UNKNOWN (barrier failure, unreadable, oversized or malformed record -> hold RETAINED, throws).
     * Invalid EXISTING retry state must keep the hold - an unreadable boundary never defaults to
     * eligible-now.
     */
    private fun reconcileRetryIntentLocked(captureId: String): Long? {
        val names = visibleNamesLocked()
        if (names == null) {
            retryBoundaryUncertain += captureId
            throw IllegalStateException("retry intent presence unknown")
        }
        val tmpVisible = "$captureId$SUFFIX_RETRY.tmp" in names
        val recordVisible = "$captureId$SUFFIX_RETRY" in names
        if (!tmpVisible && !recordVisible) {
            retryBoundaryUncertain.remove(captureId)
            return null
        }
        val record = File(dir, "$captureId$SUFFIX_RETRY")
        val tmp = File(dir, "$captureId$SUFFIX_RETRY.tmp")
        return try {
            if (tmpVisible) {
                fileSync.sync(tmp)
                if (!tmp.renameTo(record) || !record.exists()) throw java.io.IOException("intent rename failed")
                tmp.delete()
            }
            fileSync.sync(record)
            syncDirectoryOrFail(dir)
            val text = readBoundedSidecar(record, MAX_ATTEMPTS_SIDECAR_BYTES)
                ?: throw java.io.IOException("retry record unreadable or oversized")
            val boundary = text.trim().toLongOrNull()?.takeIf { it > 0 }
                ?: throw java.io.IOException("retry record malformed")
            retryBoundaryUncertain.remove(captureId)
            boundary
        } catch (failure: Throwable) {
            retryBoundaryUncertain += captureId
            nextRetryAtMs.remove(captureId)
            throw IllegalStateException("retry intent reconciliation failed: ${failure.message}")
        }
    }

    /**
     * Same three-outcome protocol for a PENDING ATTEMPT-COUNT intent (reviewer 199ccdee): a pre-rename
     * failure leaves `.attempts.tmp` visible, and reading the OLDER count record would restore a smaller
     * attempt count and hand back spent retry budget. The intent is finished before the count is trusted;
     * failure retains the hold and throws. Returns the reconciled count, or null when no intent exists.
     */
    private fun reconcileAttemptsIntentLocked(captureId: String): Int? {
        val names = visibleNamesLocked()
        if (names == null) {
            attemptsRecordUncertain += captureId
            throw IllegalStateException("attempts intent presence unknown")
        }
        if ("$captureId$SUFFIX_ATTEMPTS.tmp" !in names) return null
        val record = File(dir, "$captureId$SUFFIX_ATTEMPTS")
        val tmp = File(dir, "$captureId$SUFFIX_ATTEMPTS.tmp")
        return try {
            fileSync.sync(tmp)
            if (!tmp.renameTo(record) || !record.exists()) throw java.io.IOException("intent rename failed")
            tmp.delete()
            fileSync.sync(record)
            syncDirectoryOrFail(dir)
            val text = readBoundedSidecar(record, MAX_ATTEMPTS_SIDECAR_BYTES)
                ?: throw java.io.IOException("attempts record unreadable or oversized")
            val count = text.trim().toIntOrNull()?.takeIf { it >= 0 }
                ?: throw java.io.IOException("attempts record malformed")
            attemptsRecordUncertain.remove(captureId)
            count
        } catch (failure: Throwable) {
            attemptsRecordUncertain += captureId
            throw IllegalStateException("attempts intent reconciliation failed: ${failure.message}")
        }
    }

    // In-memory retry scheduling. The ATTEMPT COUNT is durable (sidecar); the delay is recomputable from
    // it, so a restart legitimately re-enters the eligible set at the recomputed bound rather than
    // inventing a persisted clock (spec: resume from durable queue state).
    private val nextRetryAtMs = mutableMapOf<String, Long>()

    /**
     * Backoff BOUND (ms) for the given failed-attempt number: min(retry_max_s, retry_initial_s *
     * 2^(attempt-1)) * 1000. Capped exponential growth WITHOUT shifts: doubling saturates at retry_max_s,
     * so no attempt count (however large) can overflow into a negative or wrapped bound.
     */
    internal fun backoffBoundMs(attempt: Int): Long {
        require(attempt >= 1) { "backoff needs a FAILED attempt, got $attempt" }
        // The LOCAL cap applies BEFORE growth: initial=10s/max=2s must never exceed 2s at attempt 1
        // (reviewer e4820963).
        var boundS = minOf(limits.retryInitialS, limits.retryMaxS)
        repeat((attempt - 1).coerceAtMost(63)) {
            if (boundS < limits.retryMaxS) boundS = minOf(boundS * 2, limits.retryMaxS)
        }
        return boundS * 1000L
    }

    /**
     * Schedule the NEXT retry for an entry whose attempt just failed. ONE ownership transaction: the
     * generation, the coverage predicate and the entry are validated together, so a revoked generation or
     * an unknown-coverage hold refuses to schedule (the retry path cannot outrun the fence). Returns the
     * eligible-at epoch ms, or null when refused. A server Retry-After is a MINIMUM delay: it raises the
     * floor but never shortens the jittered backoff.
     */
    fun scheduleRetryAfterFailure(
        generation: Long,
        captureId: String,
        retryAfterS: Long? = null,
    ): Long? = withLeaseResult(null) {
        scheduleRetryAfterFailureGuarded(generation, captureId, retryAfterS)
    }

    private fun scheduleRetryAfterFailureGuarded(
        generation: Long,
        captureId: String,
        retryAfterS: Long? = null,
    ): Long? = synchronized(lock) { scheduleRetryAfterFailureLocked(generation, captureId, retryAfterS) }

    // Requires the caller to already hold the lock: validate-and-mutate must be indivisible (reviewer
    // 20a5440b) so a successor attempt cannot be admitted between the identity check and the write.
    private fun scheduleRetryAfterFailureLocked(
        generation: Long,
        captureId: String,
        retryAfterS: Long? = null,
        allowRevokedDisposal: Boolean = false,
    ): Long? {
        return if (!ownership.isValidUnderLock(generation) && !allowRevokedDisposal) {
            null
        } else if (captureId !in memoryEntries) {
            null
        } else {
            if (heldUncertainLocked(captureId)) {
                // A pending/failed boundary or count publication must be resolved before new retry state
                // is written over it (reviewer 199ccdee).
                throw IllegalStateException("retry state uncertain for $captureId")
            }
            val attempt = memoryEntries[captureId]!!.attempts
            if (attempt < 1) return null // no FAILED attempt to back off
            val bound = backoffBoundMs(attempt)
            val jittered = jitter(bound)
            // Saturating arithmetic: a huge server minimum must mean "never within representable time",
            // not a wrapped earlier/past retry.
            // Nonpositive Retry-After is ABSENT (a negative value must never wrap positive, reviewer
            // e4820963); a huge positive one saturates.
            val floorMs = when {
                retryAfterS == null || retryAfterS <= 0L -> 0L
                retryAfterS > Long.MAX_VALUE / 1000L -> Long.MAX_VALUE
                else -> retryAfterS * 1000L
            }
            val delay = maxOf(jittered, floorMs)
            val base = clock()
            val eligibleAt =
                if (base < 0 || delay >= Long.MAX_VALUE - base) {
                    Long.MAX_VALUE
                } else {
                    base + delay
                }
            // The not-before boundary is DURABLE in its own .retry record: a restart cannot reroll the
            // jitter or bypass a received server minimum, and a fresh owner reconciles the intent's
            // barriers before eligibility (reviewers 7433e6a9, 21176965).
            publishRetryBoundaryLocked(captureId, eligibleAt)
            nextRetryAtMs[captureId] = eligibleAt
            eligibleAt
        }
    }

    /**
     * READY entries whose backoff has elapsed and whose attempt budget is not spent, under the CURRENT
     * coverage: unknown accounting holds the whole retry path, and a revoked generation is handled by the
     * caller's fence (the ids returned are still only leaves for a guarded pickup).
     */
    fun retryEligible(nowMs: Long = clock()): List<String> = synchronized(lock) {
        // SPEC a7f6ce81 RULING: an unknown storage footprint must NEVER withhold uploads. Uploading
        // is what shrinks the footprint, so holding every attempt is the exact opposite of the
        // spec's intent - and because the same predicate also refused admission, a single
        // unmeasurable capture could freeze the whole feature until the next process restart.
        // Eligibility is therefore PER-ENTRY only; an unknown footprint is reported on the drain
        // line and priced conservatively, never used as a blanket refusal.
        memoryEntries
            .mapNotNull { (id, entry) -> if (retryEligibleLocked(id, entry, nowMs)) id else null }
            .sorted()
    }

    /** The COMPLETE per-entry retry eligibility predicate, shared by listing and atomic pickup. */
    private fun retryEligibleLocked(id: String, entry: Entry, nowMs: Long): Boolean {
        val charge = ownedCharges[id]
        if (charge?.ready != true) return false
        // NO ATTEMPT CAP (spec 05ff27d0): a retryable failure backs off exponentially to the 1-hour
        // bound and keeps retrying until acceptance or queue-age expiry. The old 8-attempt cap
        // permanently stranded recordings whose backoff outlived a server outage.
        if ((clock() - entry.createdAtMs) / 1000L >= limits.maxQueueAgeSeconds) return false
        // Absent means genuinely unscheduled: recovery loaded every durable boundary, and a publication
        // failure puts the id in the UNCERTAIN set instead of defaulting.
        if (heldUncertainLocked(id)) return false
        if ((nextRetryAtMs[id] ?: 0L) > nowMs) return false
        return true
    }

    // ATOMIC PICKUP (reviewer 1b926fa0, spec 8aa20001): inside ONE ownership transaction the caller
    // revalidates the COMPLETE current eligibility - readiness, the record budget, unresolved records and
    // the durable boundary, i.e. everything that gates retryEligible - and CONSUMES the attempt by
    // recording it durably. A gate callback or a concurrent poll that re-arms a boundary or changes
    // coverage between listing and pickup is caught HERE, not by the generation check alone. Returns the
    // durably recorded attempt count, or null when the pickup is refused (stale epoch, no longer
    // eligible, or the attempt record could not be persisted - never attempt on an unpersisted count).

    /**
     * LEASE a transport launch (reviewer 279de1d4 + 4d72e463). Runs ONE ownership transaction that
     * revalidates [generation] and, only if it still holds, marks `captureId` as launched under its own
     * cancellation flag. The returned [LaunchLease] is used OUTSIDE the monitor: the monitor is released
     * before the caller performs any network work, so an external revocation is never blocked, and
     * [LaunchLease.cancel] (driven by a revocation listener) is visible while the upload is in flight.
     * Returns null when the epoch already changed, i.e. the launch is refused before any request.
     */
    fun leaseTransportLaunch(generation: Long, captureId: String): LaunchLease? = withLeaseResult(null) {
        leaseTransportLaunchGuarded(generation, captureId)
    }

    private fun leaseTransportLaunchGuarded(generation: Long, captureId: String): LaunchLease? = ownership.transaction(
        object : (QueueOwnership.OwnershipTransaction) -> LaunchLease? {
            override fun invoke(tx: QueueOwnership.OwnershipTransaction): LaunchLease? = if (!tx.isValid(generation)) {
                null
            } else {
                // The M77b window: after the revalidation, before registration. Under the correct shape
                // this block runs in ONE transaction, so a revocation that lands here is caught by the
                // post-window re-check; a launch that moved the registration outside the transaction
                // would grant the lease unconditionally and start network work under the dead epoch.
                hooks.onLeaseWindow?.invoke(generation, captureId)
                if (!tx.isValid(generation)) {
                    null
                } else if (fenceRevokedDuringWindow(generation)) {
                    null
                } else {
                    // Register FIRST so a revocation triggered by the hook can cancel this very lease.
                    val entry = memoryEntries[captureId]
                    // FULL identity or NO launch (reviewer f4d85031 / spec 718c043c): an entry whose
                    // admitted ownership is unknown is never leased - a null-ownership lease would
                    // silently skip its account/session comparisons on revocation.
                    if (entry == null || entry.enrollmentAccount == null || entry.enrollmentSession == null) return null
                    val lease = LaunchLease(generation, captureId, entry.enrollmentAccount, entry.enrollmentSession, entry.enrollmentEpoch)
                    synchronized(launchLeasesLock) { launchLeases.add(lease) }
                    hooks.beforeTransportLaunch?.invoke(generation, captureId)
                    // The hook may have revoked: a cancelled lease is NOT a valid launch authorization.
                    if (lease.isCancelled()) {
                        synchronized(launchLeasesLock) { launchLeases.remove(lease) }
                        null
                    } else {
                        lease
                    }
                }
            }
        },
    )

    /** A launched attempt whose authorization may be withdrawn while it is in flight. */
    class LaunchLease internal constructor(
        val generation: Long,
        val captureId: String,
        /** The FULL admitted identity at attempt start - the revocation source compares THIS, never a readyEntries re-read. */
        val admittedAccount: String?,
        val admittedSession: Long?,
        val admittedEpoch: String,
    ) {
        private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * The transport hook for THIS lease. A blocked write cannot re-read a Boolean, so the lease also
         * carries an action that reaches into the live call and cancels it (reviewer d53d9c68 finding 1:
         * registered-but-never-cancelled is not cancellation).
         */
        @Volatile
        private var abort: (() -> Unit)? = null

        /** True once a revocation (or a newer owner) withdrew this launch's authorization. */
        fun isCancelled(): Boolean = cancelled.get()

        /** Binds the live call's cancellation to this lease; it fires on every later revocation. */
        internal fun bindAbort(action: () -> Unit) {
            abort = action
            // A revocation that landed between registration and binding must not be missed.
            if (cancelled.get()) runCatching { action() }
        }

        internal fun cancel() {
            cancelled.set(true)
            // Reach the CALL, not just the flag: this is what interrupts a blocked write.
            abort?.let { runCatching { it() } }
        }
    }

    /**
     * True when the epoch moved across the lease window. The window is inside the ownership transaction,
     * so a revocation cannot interleave except via the hook; this re-check is what makes "the launch is
     * refused when the epoch retired before registration" true rather than merely claimed (M77b).
     */
    private fun fenceRevokedDuringWindow(generation: Long): Boolean = !ownership.isValidUnderLock(generation)

    private val launchLeases = mutableListOf<LaunchLease>()
    private val launchLeasesLock = Any()

    /**
     * Withdraw every in-flight launch authorization and bump the generation in ONE section, so a
     * revocation both invalidates the epoch and makes already-launched uploads observe cancellation.
     */
    fun revokeAndCancelLaunches(reason: String) {
        // Delegates to revokeAndPurge, which takes its own lease; taking a second one here would be
        // nested for no gain.
        revokeAndPurge(reason)
        synchronized(launchLeasesLock) {
            launchLeases.forEach { it.cancel() }
            launchLeases.clear()
        }
    }

    /**
     * ACCOUNT-TRANSITION CANCELLATION (reviewer 0081c95f/30b1c817, open requirement): cancel ONLY the
     * live launch leases whose admitted identity no longer matches the LIVE account identity - an
     * account move must reach an ALREADY-REGISTERED in-flight call, not just future pre-registration
     * checks. Selection is by capture id against the caller-computed mismatch set; bytes are NOT
     * purged (a retired identity's capture is RETAINED by the ownership contract - it must simply
     * never complete a send). Returns the cancelled capture ids for control observability.
     */
    fun cancelLaunchesIdentityMismatch(
        liveAccount: String?,
        liveSession: Long?,
        liveEpoch: String?,
        reason: String,
    ): Set<String> = withLeaseResult(emptySet()) {
        cancelLaunchesIdentityMismatchGuarded(liveAccount, liveSession, liveEpoch, reason)
    }

    private fun cancelLaunchesIdentityMismatchGuarded(
        liveAccount: String?,
        liveSession: Long?,
        liveEpoch: String?,
        reason: String,
    ): Set<String> {
        val cancelledIds = mutableSetOf<String>()
        synchronized(launchLeasesLock) {
            val iterator = launchLeases.iterator()
            while (iterator.hasNext()) {
                val lease = iterator.next()
                // Compare the LEASE'S OWN admitted identity (reviewer ffca25e6) against the LIVE
                // authority - NOT a readyEntries re-read, whose coverage hold can return nothing
                // while live leases still exist. Any component mismatch cancels; null live
                // components (no live account/token) cancel everything attempted under one.
                val mismatched = when {
                    liveAccount == null || liveSession == null -> true
                    lease.admittedAccount != null && lease.admittedAccount != liveAccount -> true
                    lease.admittedSession != null && lease.admittedSession != liveSession -> true
                    lease.admittedEpoch != liveEpoch -> true
                    else -> false
                }
                if (mismatched) {
                    iterator.remove()
                    lease.cancel() // reaches the live CALL, not just a flag
                    cancelledIds.add(lease.captureId)
                }
            }
        }
        return cancelledIds
    }

    /**
     * Retire a lease once its upload finished, so a later revocation only cancels live attempts. A
     * REVOKED lease is left to be cleared wholesale by [revokeAndCancelLaunches].
     */
    fun releaseLaunchLease(lease: LaunchLease) {
        if (!withLease { releaseLaunchLeaseGuarded(lease) }) return
    }

    private fun releaseLaunchLeaseGuarded(lease: LaunchLease) {
        synchronized(launchLeasesLock) { if (!lease.isCancelled()) launchLeases.remove(lease) }
    }

    init {
        // A fence-level revocation must also cancel in-flight launches (reviewer 4d72e463).
        fence.pendingLaunchCancellation = { cancelLiveLaunchesLocked() }
    }

    /** Cancel every live launch lease. Called with the queue lock held from purge paths. */
    private fun cancelLiveLaunchesLocked() {
        synchronized(launchLeasesLock) {
            launchLeases.forEach { it.cancel() }
            launchLeases.clear()
        }
    }

    /** Test seam: how many launch leases are currently outstanding. */
    fun launchLeasesForTest(): Int = synchronized(launchLeasesLock) { launchLeases.size }

    fun pickupAttempt(generation: Long, captureId: String, nowMs: Long = clock()): Int? = withLeaseResult(null) {
        pickupAttemptGuarded(generation, captureId, nowMs)
    }

    private fun pickupAttemptGuarded(generation: Long, captureId: String, nowMs: Long): Int? = synchronized(lock) {
        // SPEC a7f6ce81: the unknown-footprint hold is removed from the ATTEMPT path. Attempt
        // accounting does not depend on byte coverage, and withholding attempts is what made an
        // unknown footprint self-sustaining (uploads shrink it). Ownership is still validated.
        if (!ownership.isValidUnderLock(generation)) {
            null
        } else {
            val entry = memoryEntries[captureId]
            if (entry == null || !retryEligibleLocked(captureId, entry, nowMs)) {
                null
            } else {
                recordAttempt(generation, captureId)?.let { count ->
                    admittedGeneration[captureId] = generation
                    count
                }
            }
        }
    }

    /**
     * Reconcile and read the durable not-before retry boundary from the .retry record. A visible but
     * unreconciled intent is finished here (temp sync, rename, record sync, directory sync); failure
     * marks the capture UNCERTAIN and throws, so eligibility cannot be granted on unconfirmed bytes.
     */
    private fun readRetryBoundaryLocked(id: String): Long? = reconcileRetryIntentLocked(id)

    fun recordAttempt(generation: Long, captureId: String): Int? = withLeaseResult(null) {
        recordAttemptGuarded(generation, captureId)
    }

    private fun recordAttemptGuarded(generation: Long, captureId: String): Int? {
        // ONE ownership transaction: validate, advance the index, and write the durable attempts sidecar all
        // happen while holding the shared monitor (reviewer ee26880c finding 4). The pre-fix shape validated
        // under the lock and then wrote outside it, so a revocation could land between the two and leave a
        // stale owner's bump attributed to the successor's entry.
        var sidecarFailure: Throwable? = null
        val result: Int? = synchronized(lock) {
            // Spec 896632f4: unknown coverage of ANY kind holds ATTEMPT accounting - a bump attributed
            // while root discovery or marker coverage is unknown could contradict an intent nobody has
            // seen yet. The gate is the complete owner-coverage predicate, so a held handle cannot
            // record an attempt through a root-scan failure either (reviewer 6cbb4efa).
            // SPEC a7f6ce81: no unknown-footprint hold here either - the durable attempts sidecar
            // must keep counting while the footprint heals, or eligibility drifts from accounting.
            if (!ownership.isValidUnderLock(generation)) {
                null
            } else {
                val reconciledCount: Int? = try {
                    reconcileAttemptsIntentLocked(captureId)
                } catch (uncertain: IllegalStateException) {
                    // ABSENCE and reconciliation FAILURE are distinct: failure must return from the
                    // ownership transaction BEFORE any increment or disk mutation - a later throw cannot
                    // undo the write (reviewer 1d29fa1e). The hold persists until recovery resolves.
                    sidecarFailure = uncertain
                    null
                }
                if (reconciledCount == null && sidecarFailure != null) {
                    null
                } else {
                    var current = memoryEntries[captureId]
                    if (reconciledCount != null && current != null) {
                        // The RECONCILED record is the trusted count: the in-memory copy may predate the
                        // pending increment, and re-deriving from it would republish the same count instead
                        // of advancing (reviewer 45b9d4de).
                        current = current.copy(attempts = reconciledCount)
                        memoryEntries[captureId] = current
                    }
                    if (current == null) {
                        null
                    } else {
                        val updated = current.copy(attempts = current.attempts + 1)
                        memoryEntries[captureId] = updated
                        hooks.beforeAttemptWrite?.invoke(generation)
                        try {
                            // The count record is written through the SAME atomic/durable protocol as the
                            // retry boundary - writeText could truncate a previously published record
                            // (reviewer 7433e6a9). The boundary lives in its own .retry record, so the
                            // count write can no longer erase a deadline.
                            if (!publishRecordAtomicallyLocked(File(dir, "$captureId$SUFFIX_ATTEMPTS"), updated.attempts.toString())) {
                                throw java.io.IOException("attempts record publication failed")
                            }
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
        }
        sidecarFailure?.let { throw it }
        return result
    }

    /**
     * Remove one READY entry under the SHARED generation boundary [fence]. A stale callback must not be able
     * to drop an entry that a revocation already purged (or that now belongs to a successor), so the removal
     * and the file deletion happen inside the same guarded critical section as the ownership check.
     */
    fun dropEntry(generation: Long, captureId: String, reason: TerminalReason): Boolean = withLeaseResult(false) {
        dropEntryGuarded(generation, captureId, reason)
    }

    private fun dropEntryGuarded(generation: Long, captureId: String, reason: TerminalReason): Boolean {
        val removed = synchronized(lock) {
            if (ownership.isValidUnderLock(generation)) memoryEntries.remove(captureId)?.also { deleteLocked(it, reason.name) } else null
        } != null
        if (removed) dropCounterOnly(reason)
        return removed
    }

    /** Convenience for the current owner generation; prefer the fenced overload in callback paths. */
    fun dropEntry(captureId: String, reason: TerminalReason): Boolean = dropEntry(fence.currentGeneration(), captureId, reason)

    enum class TerminalReason {
        ATTEMPTS_EXHAUSTED,
        QUEUE_AGE_EXPIRED,
        SERVER_TERMINAL,
        INVALID_ON_RECOVERY,
        UPLOAD_ACCEPTED,
        PERMANENT_REFUSAL,

        /** An entry DELETED by recovery's unmarked-debris sweep because it had no ready marker. */
        RECOVERY_DEBRIS_REMOVED,

        /** An entry WITHHELD by recovery (it exists but is not servable) and still on disk. */
        RECOVERY_HELD,

        /** A sidecar orphan removed by recovery's sweep: the entry it belonged to is gone. */
        RECOVERY_ORPHAN_SIDECAR_REMOVED,

        /** A disqualified capture whose leftover files were deleted by a cleanup retry. */
        DISQUALIFIED_CLEANED_UP,

        /**
         * A purge could not enumerate the directory, so it removed nothing and must be retried (reviewer
         * f7700432). Counted so an incomplete withdrawal is never silent.
         */
        PURGE_DEFERRED_UNKNOWN_LISTING,

        /**
         * THE WHOLE QUEUE WAS REVOKED (withdrawal, account change, remote disable). Cumulative and never
         * reset, because losing this counter is how a purge becomes invisible: the file that holds the
         * counters is itself deleted during the purge.
         */
        QUEUE_PURGED,

        /** An entry's `.created` was untrustworthy (absent/zero/future) and was reset to now. */
        AGE_RESET,

        /** The reset could not be written. The recording is served using `now` for this process only. */
        AGE_RESET_FAILED,

        /** A `.wav.part` staging leftover removed by recovery. Uncounted before spec bfe91857. */
        RECOVERY_STAGING_REMOVED,
    }

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
     * THE ORDINARY DRAIN OPPORTUNITY (reviewer 20f71675 finding 1, spec 777c18d5).
     *
     * [recover] runs ONCE per process by design (its debris sweep would unlink a live `.part` if it ran
     * per drain), so a withdrawal purge that deferred on an unreadable listing had no retry until the next
     * restart. A stop is not allowed to depend on a restart: while the durable intent says stop, this
     * finishes the purge at every drain, BEFORE anything is expired or polled.
     *
     * Purge-only by construction: it calls no recovery, no expiry and no sweep, so it cannot race a live
     * preparation. When there is nothing to do it costs one durable read and returns.
     *
     * @return true when the caller should NOT serve this pass (a stop is pending).
     */
    fun finishPendingPurgeIfStopped(): Boolean {
        if (withdrawalIntent?.collectionPermittedByLocalIntent() != false) return false
        // IF THE PERSISTED STOP APPLIES, THE DRAIN NEVER SERVES - FULL STOP (spec 6731209b).
        //
        // This must NOT depend on whether this pass completed the purge. My previous version returned the
        // pre-call purgeDeferred value, so the FIRST failed purge - where nothing was deferred yet - told
        // the caller to carry on into expiry and polling while a stop was in force. "Did the purge finish?"
        // is a question for logging; "may this pass serve?" has exactly one answer while the stop stands.
        revokeAndPurge("pending withdrawal intent (drain)")
        if (purgeDeferred) {
            Timber.i("voice-upload drain: reason=withdrawal-purge-still-pending, nothing served this pass")
        }
        return true
    }

    /**
     * LEASED PURGE (reviewer 72a35e19). [acquireOwnership] alone is a point-in-time check: the purge could
     * pass it, pause, and finish deleting after close() released the directory to a successor. The lease
     * keeps close()'s drain waiting for this whole mutation.
     */
    fun revokeAndPurge(reason: String) {
        if (!acquireOwnership() || !enterOperation()) {
            logGuardedRefusal()
            return
        }
        try {
            // REVOKE FIRST, THEN DISCOVER WITHOUT RE-ADMISSION (reviewer 4e8592ba, and the agreed
            // discovery-without-re-admission contract).
            //
            // revokeAndPurge deletes only what THIS store's index holds, and a freshly constructed store's
            // index is EMPTY - so a policy-disable observed at STARTUP purged nothing and a previous owner's
            // audio survived a disable. The fix must NOT be recoverLocked(): that routine PROMOTES entries
            // into the serving index (ready/retryable) and performs recovery mutations, which re-admits a
            // capture during the very withdrawal that is meant to dispose of it. Discovery here only
            // MEASURES and CHARGES what is on disk (establishCoverage), which is what the purge needs: an
            // ownership list to delete from, with nothing made servable.
            revokeAndPurgeLocked(reason, discoverBeforeDelete = true)
        } finally {
            leaveOperation()
        }
    }

    /**
     * LEASED RECOVERY (reviewer 72a35e19). Recovery enumerates and sweeps OUTSIDE the store monitor, so a
     * check-then-mutate hole here would let it delete in a directory a successor already owns.
     */
    fun recover(): List<Entry> {
        // PENDING PURGE FIRST (reviewer c764bea2, spec 777c18d5). The durable withdrawal intent is the
        // authority for STOPPING, and it survives process death - so a stop whose purge deferred is still
        // a stop. Consuming it HERE, before any validation or promotion, is what makes "the next ordinary
        // opportunity finishes the job" true: no pending capture is ever validated, promoted or served
        // on the way to the purge that disposes of it. A failed listing simply leaves the intent in place
        // and this store reports nothing, so the following opportunity tries again.
        if (withdrawalIntent?.collectionPermittedByLocalIntent() == false) {
            revokeAndPurge("pending withdrawal intent")
            return emptyList()
        }
        if (!acquireOwnership() || !enterOperation()) {
            logGuardedRefusal()
            return emptyList()
        }
        try {
            return recoverLocked()
        } finally {
            leaveOperation()
        }
    }

    /**
     * ONE ownership transaction for a full revocation (reviewer ee26880c finding 4): the generation bump,
     * the ownership snapshot, the index clear AND the deletion of this owner's committed files all happen
     * while holding the shared monitor. Previously the per-capture deletes ran after releasing it, so a
     * successor that published the same capture id in between had its fresh files deleted by our late cleanup.
     *
     * Deleting under the monitor is safe because publication also runs under it and refuses to claim names
     * that already exist: either we hold the lock and delete before any successor claims, or the successor
     * claims first and our scoped snapshot (ids we actually owned) simply does not include that id.
     *
     * Enforced by [revokeAndPurge], which holds the ownership lease around this body (reviewer 72a35e19).
     */
    private fun revokeAndPurgeLocked(reason: String, discoverBeforeDelete: Boolean = false) {
        // The public revokeAndPurge() holds the ownership lease around this body, so a closed owner can
        // never reach here (spec c8f766fb). Measured before that guard existed: a closed owner's late purge
        // ran to completion, which none of the other mutating surfaces allowed.
        hooks.onPurgeOrRecoverMutation?.invoke("purge")
        synchronized(lock) {
            ownership.revokeUnderLock()
            cancelLiveLaunchesLocked() // ONE authoritative transition: a purge also withdraws launches
            if (discoverBeforeDelete) {
                // DISCOVERY INSIDE THE REVOCATION, WITHOUT RE-ADMISSION (reviewer ec6ce881). This must
                // enumerate the DIRECTORY ITSELF: a freshly constructed store's index is empty, so a purge
                // driven by the index alone deletes nothing and the user's retained audio survives the
                // disable they asked for. Discovery CLAIMS ownership of every capture it finds so the
                // deletes below can reach it, but it never writes memoryEntries and never marks anything
                // ready or retryable - so no attempt can acquire a discovered capture during the purge
                // boundary. It also runs AFTER the generation advanced and launches were withdrawn, so a
                // capture a PUT is already streaming is covered by the revocation rather than by this call.
                if (!discoverRetainedFromDiskLocked()) {
                    // INCOMPLETE, NOT EMPTY (reviewer f7700432). An unreadable listing means we do not
                    // know what is on disk, so we cannot claim the audio is gone. Record the retained
                    // removal intent and retry at the next opportunity rather than reporting a completed
                    // purge over files we never enumerated. PURGE-only: no capacity or admission gate.
                    deferPurgeOnUnknownListingLocked(reason)
                    return@synchronized
                }
                // The discovered captures are now in ownedCharges; join them WITH the index below.
                // TEST SEAM: observes the state AFTER discovery, which is the boundary the contract is
                // about. onPurgeOrRecoverMutation fires BEFORE discovery, so it cannot see a promotion.
                onAfterPurgeDiscoveryForTest?.invoke()
            }
            // OWNERSHIP IS INDEPENDENT OF READINESS. An uncertain entry was removed from memoryEntries so
            // it could not be exposed as ready, but that must not drop it from the owner's accounting: an
            // account purge that skipped it would leave the user's audio on disk after they asked for
            // everything to be removed, and a later healthy recovery would resurrect it. The uncertain set
            // is unioned in HERE, before the snapshot, and cleared only after its files are deleted.
            val ownedIds = (memoryEntries.keys + ownedCharges.keys).distinct()
            val stagedAttempts = stagingOwners.toList()
            // Delete only what THIS owner's index actually holds. deleteLocked removes each id as it goes, so
            // the later clear() is a no-op safety net rather than a second sweep.
            // Deleting through deleteLocked is not usable here: it re-claims residue as an owned charge,
            // and a purge must leave no owned capture behind. Each delete is still CHECKED, and any
            // residue is recorded so a later sweep can retry rather than being forgotten.
            // CONFIRMED INTENT FIRST, for EVERY owned capture, before a single file is touched. Writing it
            // afterwards means a purge that dies mid-way leaves no record of what it was doing, and every
            // survivor it had not reached yet looks like ordinary data to the next healthy store.
            // EVERY ATTEMPT STARTS FROM THE CURRENT TRUTH (reviewer 20f71675 finding 2). Residue reported
            // by an earlier attempt is not evidence about THIS one: leaving it in place makes a capture that
            // was successfully removed keep being reported as surviving, which is the mirror of the
            // false-success bug (a false ALARM). _purgeDeferred is deliberately NOT reset here - until this
            // attempt confirms completion, the purge is still pending.
            _purgeResidue.clear()
            val intentConfirmed = ownedIds.associateWith { writeDisqualificationLocked(it) }
            val residueIds = mutableListOf<String>()
            ownedIds.forEach { id ->
                // No confirmed intent means no destruction: the capture stays owned and charged below.
                if (intentConfirmed[id] != true) {
                    residueIds += id
                    return@forEach
                }
                val gone = deleteAll(
                    id,
                    ENTRY_SUFFIXES,
                )
                if (!gone) residueIds += id
            }
            memoryEntries.clear()
            preparedIds.clear()
            reservations.clear()
            // Survivors keep a REAL, MEASURED charge. A list of ids is not byte accounting: the queue would
            // otherwise hold unmeasurable debris while reporting itself empty, and a successor owner would
            // admit new work straight over it.
            ownedCharges.keys.toList().forEach { ownedCharges.remove(it) }
            residueIds.forEach { id ->
                chargeRetainedLocked(id, ready = false)
            }
            // Settle our own live handles: their budget is gone with the queue, and leaving them HELD while
            // the table is empty would make a later discard()/adopt() dereference a missing entry.
            ownedAdmissions.values.toList().forEach { it.state = ADMISSION_RELEASED }
            ownedAdmissions.clear()
            recovered = true // a purged store must not lazily resurrect anything from disk
            // Retained residue after a purge is reported, not assumed away: a caller that treats a purge
            // as complete when files remain would tell the user their audio is gone while it is not.
            // COMPLETION IS RECONCILED, NOT INFERRED FROM ATTEMPTS (reviewer 20f71675 finding 2, spec
            // 777c18d5). Attempting every delete is not the same as removing every capture: a delete can
            // fail, a capture can be unreachable, and a file can be re-created. The purge is complete only
            // when the listing is READABLE and EMPTY of retained captures. Until then it stays deferred and
            // its residue stays reported - telling the user their audio is gone while some of it survives
            // is the exact failure this whole path exists to prevent.
            _purgeResidue.remove(PURGE_RESIDUE_UNKNOWN_LISTING)
            _purgeResidue.addAll(residueIds)
            val survivors = retainedCaptureIdsLocked()
            val stillPending = survivors == null || survivors.isNotEmpty()
            _purgeDeferred = stillPending
            if (survivors == null) {
                _purgeResidue.add(PURGE_RESIDUE_UNKNOWN_LISTING)
            } else {
                _purgeResidue.addAll(survivors.filterNot { it in _purgeResidue })
                if (survivors.isNotEmpty()) {
                    Timber.tag(QUEUE_TAG).w("voice-upload purge: INCOMPLETE, %d capture(s) survive", survivors.size)
                }
            }
            stagedAttempts.forEach { attempt -> deleteStaging(attempt) }
            stagingOwners.clear()
            // COMPLETION EVIDENCE ONLY ON A READABLE, EMPTY LISTING (reviewer 04207559 finding 2, spec
            // 6731209b). recordPurgeLocked / QUEUE_PURGED / _purgeReasons say "this purge HAPPENED". My
            // previous version emitted them even with survivors, so a failed deletion produced the same
            // durable witness as a successful one - the false success this path exists to prevent, written
            // in the one file that outlives the counters it is meant to explain. stillPending is the only
            // permission to skip them.
            if (!stillPending) {
                // A PURGE MUST BE VISIBLE AFTER IT HAS HAPPENED, and this is the one moment where the
                // counters file - the only witness - is itself destroyed. The purge record is therefore
                // CUMULATIVE and lives in its own file, appended BEFORE the counters are cleared, so a
                // device that is empty always says why it is empty.
                recordPurgeLocked(reason, ownedIds.size)
                // Also on the ordinary counter path so a caller can read it without parsing the purge file.
                // It is cumulative in the purge file because the clear() below wipes it from here.
                dropCounterOnly(TerminalReason.QUEUE_PURGED)
                countersFile.delete()
                terminalCounters.clear()
                _purgeReasons.add(reason)
            } else {
                Timber.tag(QUEUE_TAG).w(
                    "voice-upload purge: no completion evidence recorded, purge still pending reason=%s",
                    reason,
                )
            }
            // UNLINK ACCOUNTING (reviewer 7c2f30e3): the purge deleted files that an in-flight upload may
            // still hold open. Those bytes are NOT freed - the descriptor retains them - so they must be
            // counted against capacity until it closes. This purge does not route through deleteLocked, so
            // the accounting has to happen here explicitly.
            accountUnlinksLocked()
        }
    }

    private fun deferPurgeOnUnknownListingLocked(reason: String) {
        // Ownership was REVOKED above and stays revoked: nothing here becomes servable.
        //
        // DELIBERATELY NO _purgeReasons ADD (reviewer 20f71675 finding 2): that list is the evidence the
        // user's audio WAS purged, so appending here would claim a completed purge that never happened. A
        // deferred purge is reported through purgeDeferred, purgeResidue and its counter instead - the
        // pending intent itself is derived from the durable withdrawal marker, so it needs no separate
        // record to survive.
        _purgeDeferred = true
        _purgeResidue.clear()
        _purgeResidue.add(PURGE_RESIDUE_UNKNOWN_LISTING)
        Timber.tag(QUEUE_TAG).w(
            "voice-upload purge: INCOMPLETE, listing unreadable, retained captures not removed reason=%s",
            reason,
        )
        dropCounterOnly(TerminalReason.PURGE_DEFERRED_UNKNOWN_LISTING)
        hooks.onPurgeOrRecoverMutation?.invoke("purge-deferred")
    }

    /** True when the most recent purge could not enumerate the directory and removed nothing. */
    val purgeDeferred: Boolean get() = synchronized(lock) { _purgeDeferred }
    private var _purgeDeferred = false

    val purgeReasons: List<String> get() = synchronized(lock) { _purgeReasons.toList() }

    // Captures whose files SURVIVED a purge delete attempt. A purge that reports success while bytes
    // remain would tell the user their audio is gone while it is still on disk.
    val purgeResidue: List<String> get() = synchronized(lock) { _purgeResidue.toList() }
    private val _purgeResidue = mutableListOf<String>()
    private val _purgeReasons = mutableListOf<String>()

    /** Listening/permission closure: remove IN-PROGRESS preparation only; ready entries stay. */
    fun discardCurrentPartials() {
        if (!acquireOwnership() || !enterOperation()) {
            logGuardedRefusal()
            return
        }
        try {
            // Same bounded traversal as recovery (reviewer d1cce25b): an unreadable listing defers the sweep.
            val files = boundedRootListingOrNull() ?: return
            // Same checked deletion as the recovery sweep. Discarding the result here left surviving partials
            // invisible to accounting (reviewer e4342946).
            files.forEach { f -> if (f.name.endsWith(SUFFIX_PART)) f.deleteOrChargeOnFailure() }
        } finally {
            leaveOperation()
        }
    }

    /**
     * Recovery at startup: delete abandoned preparation (.part without matching .ready), then
     * validate COMPLETE entries (ready + wav + meta present, byte_count matches header, attempts
     * restored). Invalid complete-looking entries are dropped with a content-free counter —
     * recovery admits nothing unvalidated to upload.
     */
    // Recovery cannot know what the queue directory holds when its listing fails, so it must not run a
    // DESTRUCTIVE sweep over a partially-known listing. [boundedRootListingOrNull] returns null in that
    // case and every sweep/refusal decision reads the null directly - no state flag is involved, because
    // the hold clears on the very next successful list (spec a3ef694f removed the flags that could keep
    // admission unknown).
    private fun boundedRootListingOrNull(): List<File>? = try {
        boundedRootListing()
    } catch (unknown: MeasurementError) {
        Timber.tag(QUEUE_TAG).i("voice-upload listing: root-unreadable reason=%s", unknown.message)
        null
    }

    private fun boundedRootListing(): List<File> = boundedDirectoryListing(dir)

    /**
     * DISCOVERY ONLY - no traversal budget (reviewer a4a6b1 / spec 2741a0ca).
     *
     * Used where the operation is READ-ONLY or where refusing would make the queue unrecoverable: serving
     * discovery and the record count. Returns null only when the directory genuinely cannot be read.
     */
    private fun unboundedRootListingOrNull(): List<File>? = try {
        (dir.list() ?: throw MeasurementError("root unlistable")).map { File(dir, it) }
    } catch (unknown: MeasurementError) {
        Timber.tag(QUEUE_TAG).i("voice-upload listing: root-unreadable reason=%s", unknown.message)
        null
    } catch (failure: RuntimeException) {
        Timber.tag(QUEUE_TAG).i("voice-upload listing: root-unreadable reason=%s", failure.message)
        null
    }

    // ONE shared budget for a whole listing, so the bound is on the traversal and not on each call.
    private class MeasurementBudget(private var remaining: Int) {
        fun consume(): Boolean = --remaining >= 0
    }

    /**
     * THE TRAVERSAL BOUND IS SIZED FOR THE WORKLOAD IT NOW MEASURES (device finding, 2026-11-08).
     *
     * A fixed [MEASURE_MAX_ENTRIES] = 512 was correct when the listing fed a BYTE walk over a handful of
     * probes. Since spec a3ef694f the SAME helper feeds the record COUNT, and the directory holds up to
     * [ENTRY_SUFFIXES].size files per record. On a real device the queue reached 104 records - about 784
     * files - every listing aborted past 512, the count read "unknown", and admission saw `eligible=0`:
     * NOTHING uploaded, not even the 104 already on disk, and nothing could ever drain, so the queue could
     * never fall back under the bound either. The machine could not recover on its own.
     *
     * The bound still exists (an unbounded traversal of an adversarial directory is a real hazard), but it
     * is now derived from the record cap it has to hold, with headroom for transient files (`.part`,
     * staging, in-flight attempts) that are NOT records. Reusing a helper correctly is not the same as
     * reusing its constants correctly.
     */
    internal fun listingEntryBoundForTest(): Int = listingEntryBound()

    // THE BOUND IS AN ALLOCATION GUARD, NOT A QUEUE-SIZE POLICY (spec 2741a0ca, reviewer a4a6b1).
    //
    // It exists to stop one crash-restart loop from materialising a hostile directory into memory all at
    // once. It must therefore sit far above any queue this app can legitimately hold, and the earlier value
    // (512) did not: sized for a byte-measurement walk, it was reused for a per-file count and broke at
    // ~50 records. The device queue that exposed it held 104 records (~1,040 files) against a cap of 32,
    // i.e. an INHERITED over-cap queue, so a cap-derived bound alone would still have been unreadable.
    //
    // Crucially the FEEDBACK LOOP was the fatal part: an unreadable root refused SERVING, so encumbered
    // clips could never drain and the queue could never fall back under the bound. Nothing self-corrected.
    // Two independent things prevent that now: discovery no longer consults the bound at all, and the bound
    // itself is sized for an inherited queue. 4,096 entries is ~400 records at 10 files each - an order of
    // magnitude above the worst queue observed, and still a finite allocation.
    private fun listingEntryBound(): Int = maxOf(
        MEASURE_MAX_ENTRIES,
        INHERITED_QUEUE_RECORDS * ENTRY_SUFFIXES.size,
        limits.maxQueueRecords * ENTRY_SUFFIXES.size,
    )

    // The marker directory holds ONE file per withdrawn capture, not ten per record, so it needs its own
    // bound rather than the queue root's. It stays BOUNDED because this listing drives DELETION from a
    // possibly partial view (reviewer 3eec6e62): failure defers the retry, never guesses.
    private fun markerEntryBound(): Int = maxOf(MEASURE_MAX_ENTRIES, INHERITED_QUEUE_RECORDS * 2)

    private fun boundedDirectoryListing(directory: File, bound: Int = listingEntryBound()): List<File> {
        val budget = MeasurementBudget(bound)
        val found = mutableListOf<File>()
        try {
            java.nio.file.Files.newDirectoryStream(directory.toPath()).use { stream ->
                for (child in stream) {
                    if (!budget.consume()) throw MeasurementError("root exceeds $bound entries")
                    found += child.toFile()
                }
            }
        } catch (unknown: MeasurementError) {
            throw unknown
        } catch (failure: java.io.IOException) {
            throw MeasurementError("unreadable root $dir: ${failure.message}")
        } catch (failure: RuntimeException) {
            throw MeasurementError("directory discovery failed after ${found.size} entries: ${failure.message}")
        }
        return found
    }

    private fun recoverLocked(): List<Entry> {
        // The public recover() holds the ownership lease around this body, so a closed owner cannot sweep
        // a successor's directory.
        hooks.onPurgeOrRecoverMutation?.invoke("recover")
        dir.mkdirs()
        // SERVING DISCOVERY IS UNBOUNDED, THE SWEEP IS NOT (reviewer a4a6cmb1 / spec 2741a0ca).
        //
        // This used to return emptyList() when the bounded listing refused, so an over-bound directory
        // made recovery serve NOTHING: every clip on disk was invisible until the queue shrank, and the
        // queue could not shrink because nothing was served. The two duties are now separated. DISCOVERY
        // (which ids exist, and validating each one) has no traversal budget - it is a flat directory read
        // and its size is bounded by what is actually on disk. The DESTRUCTIVE SWEEP keeps the bound,
        // because deleting from a partially-known listing is the genuinely hazardous operation.
        val files = unboundedRootListingOrNull() ?: run {
            // The enumeration itself failed, so nothing is known about coverage: no serving and no sweep.
            Timber.tag(QUEUE_TAG).i("voice-upload recovery: root unreadable, nothing discovered this pass")
            return emptyList()
        }
        // The DESTRUCTIVE SWEEP keeps its own guard (reviewer c66fda06, 3eec6e62). An extensive root is
        // expensive to enumerate but perfectly safe to SERVE from; deleting from it is the operation that
        // must never run on a view it could not fully take. Serving and sweeping are decided separately.
        val sweepPermitted = files.size <= listingEntryBound()
        if (!sweepPermitted) {
            Timber.tag(QUEUE_TAG).i(
                "voice-upload recovery: sweep deferred, sweeps-not-permitted entries=%d bound=%d",
                files.size,
                listingEntryBound(),
            )
        }
        // 1) Debris sweep: .part files are NEVER ready; .ready without wav/meta is invalid.
        // The RESULT of delete() used to be discarded here, so staging bytes that survived occupied the
        // queue while being charged nowhere (reviewer c71672c0).
        if (sweepPermitted) {
            files.filter { it.name.endsWith(SUFFIX_PART) }.forEach { it.deleteOrChargeOnFailure() }
        }
        val readyIdsBefore = files.filter { it.name.endsWith(SUFFIX_READY) }.map { it.name.removeSuffix(SUFFIX_READY) }.toSet()
        // CANDIDATES ARE EVERY CAPTURE THAT HAS BOTH PRIMARY FILES, NOT JUST THE MARKED ONES
        // (reviewer 42801e42). Building candidates from `.ready` alone left a preserved markerless pair
        // invisible to the recovery state machine: never validated, indexed or charged, and its sidecars
        // were then removed by the orphan sweep below - a complete recording reduced to silent debris.
        // A markerless pair is now recovered on its CONTENT: complete and checksum-valid is servable, and
        // the marker is rewritten (spec bfe91857, clarified by spec 0d6ec2e3); a pair that FAILS
        // validation is held, counted and logged rather than quietly kept.
        val completePairs = files.filter { f -> f.name.endsWith(SUFFIX_WAV) }
            .map { it.name.removeSuffix(SUFFIX_WAV) }
            .filter { id -> id.matches(UUID_RE) && File(dir, "$id$SUFFIX_META").isFile }
            .toSet()
        // THE MARKER IS NOT THE ONLY WAY TO BE RECOVERABLE; CONTENT IS (spec 0d6ec2e3). A pair that is
        // complete and checksum-valid is validated, charged, indexed and served, and its marker is
        // rewritten. A pair that FAILS validation is held, counted and logged. Either way it must reach
        // the recovery state machine, which building candidates from `.ready` alone prevented.
        val markerlessPairs = completePairs - readyIdsBefore
        val readyIds = (readyIdsBefore + markerlessPairs).distinct()
        // Unmarked FINAL debris: a .wav/.meta/.sha256 with no matching .ready marker is an
        // interrupted preparation that never reached its commit point. It must not survive recovery
        // (an .ready-marked entry is validated below instead).
        files.filter { f ->
            val suffix = listOf(SUFFIX_WAV, SUFFIX_META, SUFFIX_DIGEST).firstOrNull { f.name.endsWith(it) }
            suffix != null && f.name.removeSuffix(suffix) !in readyIdsBefore && f.name.removeSuffix(suffix) !in completePairs
        }.groupBy { f ->
            // Grouped by CAPTURE, not visited file by file. A per-file sweep can delete the metadata of a
            // capture whose WAV refused to delete, leaving a residue that was charged for one file and then
            // silently changed shape underneath the charge. Each capture is handled once, as a whole.
            f.name.removeSuffix(listOf(SUFFIX_WAV, SUFFIX_META, SUFFIX_DIGEST).first { sfx -> f.name.endsWith(sfx) })
        }.forEach { (id, captureFiles) ->
            // A CAPTURE WITH COMPLETE AUDIO AND METADATA IS AN ENTRY, NOT DEBRIS - read the presence
            // check as "no ready marker" and a failed barrier becomes a deletion. This sweep used to be
            // exactly how the 2026-11-07 install lost entries whose marker was never written: the entry
            // was withheld, so no marker existed on disk, so the sweep classed it as an interrupted
            // preparation and deleted its audio and metadata, uncounted. Presence of BOTH primary files
            // is the bar for a live entry; only unpaired leftovers are debris.
            val audio = File(dir, "$id$SUFFIX_WAV")
            val metadata = File(dir, "$id$SUFFIX_META")
            if (audio.isFile && metadata.isFile) return@forEach
            val removed = captureFiles.count { file -> file.delete() || !file.exists() }
            val survivors = captureFiles.filter { it.exists() }
            if (survivors.isNotEmpty()) {
                // WHAT ACTUALLY SURVIVED STILL OCCUPIES THE QUEUE, so its capture keeps its record. The
                // record is counted, not measured (spec a3ef694f): one capture is one record, whatever
                // survived of it.
                chargeRetainedLocked(id, ready = false)
            }
            if (removed > 0) {
                // NO UNCOUNTED DELETE. This path can remove bytes a user recorded, so it must be
                // visible in the counters and in the log, with the reason, for ever the first question.
                repeat(removed) { dropCounterOnly(TerminalReason.RECOVERY_DEBRIS_REMOVED) }
                Timber.tag(QUEUE_TAG).i(
                    "voice-upload remove: capture=%s reason=recovery-debris-removed files=%d survivors=%d",
                    id,
                    removed,
                    survivors.size,
                )
            }
        }

        val keep = mutableListOf<Entry>()

        // Entries recovery HELD rather than served. Kept out of the ready set, but NOT deleted: a held
        // entry is still the user's recording. Every hold is counted and logged with its reason, so a
        // recording that never uploads is always explainable from the device (spec bfe91857 item 3).
        val uncertainIds = mutableListOf<String>()
        for (id in readyIds) {
            // A capture disqualified by a previous failed deletion is NEVER served again, whatever
            // survives on disk: the marker is durable, so intent outlives the process that formed it.
            if (isDisqualified(id)) {
                chargeRetainedLocked(id, ready = false)
                holdLocked(id, "disqualified-by-previous-failed-deletion")
                continue
            }
            val wav = File(dir, "$id$SUFFIX_WAV")
            val meta = File(dir, "$id$SUFFIX_META")
            val attemptsFile = File(dir, "$id$SUFFIX_ATTEMPTS")
            // Finish a PENDING attempt-count intent BEFORE validating: validate must read the record the
            // intent resolves to, not the pre-failure one (reviewer 199ccdee).
            try {
                reconcileAttemptsIntentLocked(id)
            } catch (uncertain: IllegalStateException) {
                // Unresolved count record: held this run. The intent file resolves it next time.
                uncertainIds += id
                chargeRetainedLocked(id, ready = false)
                holdLocked(id, "attempts-intent-unresolved")
                continue
            }
            // AN UNTRUSTWORTHY AGE IS NOT AN INVALID RECORDING (spec 3e33e306). `.created` is written
            // without a directory sync, so a crash on a runtime whose barrier fails can leave it empty or
            // containing a future timestamp. Neither says anything about whether the audio is valid, so
            // validating on it deleted complete recordings as INVALID_ON_RECOVERY. The age is RESET to now,
            // rewritten, counted and logged; the seven-day clock then runs from the reset.
            val ageReset = resetUntrustedAgeLocked(id)
            // An age whose rewrite FAILED carries no authority to judge the recording: recovery uses `now`
            // for this process only, serves it, and retries the rewrite at the next recovery (spec 82b379a6).
            val entry = validate(id, wav, meta, attemptsFile, ageOverrideMs = ageReset.overrideMs)
            // A MARKERLESS PAIR THAT FAILS VALIDATION IS HELD, NOT DELETED (spec 0d6ec2e3). The pair is the
            // user's recording: absence of its marker or of one authority sidecar is a reason not to
            // SERVE it, never a licence to destroy it. Only an entry that had a marker and now fails
            // validation takes the delete path below, because that is a committed entry gone bad rather
            // than a recoverable capture whose bookkeeping was interrupted.
            if (entry == null && id in markerlessPairs) {
                chargeRetainedLocked(id, ready = false)
                holdLocked(id, "markerless-pair-incomplete")
                continue
            }
            if (entry == null) {
                // Deleting an invalid entry can FAIL. The previous version dropped the files' existence
                // on the floor and only bumped a counter, so any bytes that survived the delete became
                // unowned: still on disk, still occupying the user's storage, counted by nothing.
                // ALL sidecars go with the entry (spec 05ff27d0 residue ruling): a deleted entry
                // leaves no `.created`/`.owner`/`.retry` residue - the 2026-11-07 install left 44
                // three-file residues because this list missed them.
                val removedFiles = ENTRY_SUFFIXES.map { File(dir, "$id$it") }.filter { it.exists() && it.delete() }
                val cleaned = ENTRY_SUFFIXES.none { File(dir, "$id$it").exists() }
                if (removedFiles.isNotEmpty()) {
                    Timber.tag(QUEUE_TAG).i(
                        "voice-upload remove: capture=%s reason=recovery-invalid files=%d",
                        id,
                        removedFiles.size,
                    )
                }
                if (!cleaned) {
                    // The pre-fix version measured wav.length() and meta.length(): primary files only, and
                    // `length()` on a survivor directory reports the entry size, not the retained subtree.
                    chargeRetainedLocked(id, ready = false)
                }
                dropCounterOnly(TerminalReason.INVALID_ON_RECOVERY)
                // MONITORING (spec 827c5962): recovery CAN delete, so it must say so. A recording that
                // vanishes across a relaunch is otherwise invisible; `cleaned=false` marks the survivor
                // case, where bytes remain and are merely not served.
                Timber.tag(QUEUE_TAG).i(
                    "voice-upload remove: capture=%s reason=invalid-on-recovery cleaned=%s",
                    id,
                    cleaned,
                )
            } else {
                // The entry is COMPLETE and passed every structural and checksum check, so it is
                // servable (spec bfe91857). Re-hardening is best effort and never decides this.
                rehardenRecoveredLocked(id)
                if (id in markerlessPairs) {
                    // SERVING A MARKERLESS PAIR MEANS RESTORING ITS AUTHORITY, not just its bytes. Without
                    // the marker the entry's own next recovery would have to re-derive it, and any later
                    // sweep would read it as an interrupted preparation again.
                    //
                    // A FAILED RESTORE MUST NOT BECOME A DELETION (reviewer 5507715b item 1). The entry
                    // would enter the index with no marker on disk, and the expiry pass's
                    // `ready-marker-vanished` branch deletes exactly that: a best-effort write turning
                    // into the permanent loss of a recording. So a failed restore withholds the entry
                    // instead - retained, charged, counted, never served - and returns nothing.
                    val restored = runCatching {
                        hooks.onReadyMarkerRestore?.invoke(id)
                        File(dir, "$id$SUFFIX_READY").writeBytes(ByteArray(0))
                        File(dir, "$id$SUFFIX_READY").isFile
                    }.getOrDefault(false)
                    if (!restored) {
                        chargeRetainedLocked(id, ready = false)
                        holdLocked(id, "marker-restore-failed")
                        continue
                    }
                    Timber.tag(QUEUE_TAG).i(
                        "voice-upload serve: capture=%s reason=markerless-pair-recovered markerRestored=true",
                        id,
                    )
                }
                synchronized(lock) {
                    ownedCharges[id] = OwnedCharge(ready = true)
                }
                keep += entry
            }
        }
        // Boundary-held ids survive past the rebuild block: the orphan-sidecar sweep must exclude them.
        val boundaryHeld = mutableListOf<String>()
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
                val wavEntry = File(dir, "$id$SUFFIX_WAV")
                val metaEntry = File(dir, "$id$SUFFIX_META")
                if (id in uncertainIds) {
                    // Already charged once above; keep it held. Nothing is added here.
                    ownedCharges[id]?.ready = false
                    continue
                }
                val wav = wavEntry
                val metaF = metaEntry
                validate(id, wav, metaF, File(dir, "$id$SUFFIX_ATTEMPTS"))?.let {
                    memoryEntries[id] = it
                    // RECONCILIATION adds no new charge: the capture already holds exactly one, and this
                    // only marks it ready. Adding a second charge here is what double-counted bytes and
                    // records for the life of the entry.
                    ownedCharges.getOrPut(id) { OwnedCharge(ready = false) }
                        .ready = true
                } ?: run {
                    preparedIds.remove(id)
                    // Deleting a ready marker demotes a committed entry. Counted and logged like every
                    // other removal, so a capture that stops being served can be traced to this line.
                    if (File(dir, "$id$SUFFIX_READY").delete()) {
                        dropCounterOnly(TerminalReason.RECOVERY_HELD)
                        Timber.tag(QUEUE_TAG).i("voice-upload hold: capture=%s reason=revalidation-failed", id)
                    }
                }
            }
            keep.forEach {
                // Readiness on the SINGLE existing charge; a missing charge is created once, never added to.
                val charge = ownedCharges.getOrPut(it.captureId) {
                    OwnedCharge(ready = false)
                }
                // Restore the durable retry boundary: a restart must not shorten a scheduled backoff
                // or a received server minimum by "forgetting" them.
                val boundary = try {
                    readRetryBoundaryLocked(it.captureId)
                } catch (uncertain: IllegalStateException) {
                    // The retry intent could not be reconciled: the capture is NOT ready and NOT exposed
                    // by recover() or readyEntries() this run (reviewer 199ccdee). The uncertainty
                    // persists so every serving/attempt entry point keeps failing closed.
                    boundaryHeld += it.captureId
                    null
                }
                boundary?.let { b -> nextRetryAtMs[it.captureId] = b }
                if (it.captureId !in boundaryHeld) {
                    memoryEntries[it.captureId] = it
                    charge.ready = true
                } else {
                    memoryEntries.remove(it.captureId)
                    charge.ready = false
                }
            }
            keep.removeAll { it.captureId in boundaryHeld }
            recovered = true
        }
        // Marker coverage is established in the COMMON eligibility path, not only at expiry: a fresh owner
        // recovers here, so an overflowing or unreadable marker directory must be discovered before
        // anything is served or admitted (reviewer 6cbb4efa). Running it LAST so the debris sweep measures
        // what recovery found BEFORE the reconcile deletes what it can - a sweep finding that later
        // reconciles away still records its measurement failure. Failure inside leaves the hold set.
        retryDisqualifiedCleanupLocked()
        // ORPHAN SIDECAR SWEEP (spec 05ff27d0, decision on the 2026-11-07 residues): sidecars whose
        // entry is gone are debris. Any known-suffix file whose id is neither indexed, self-prepared,
        // nor explicitly withheld this run belongs to no live entry and is swept. Uncertain and
        // boundary-held ids are EXCLUDED: uncertainty is never rejection.
        synchronized(lock) {
            // ONLY sidecar orphans here: wav/meta/digest debris (no .ready) is owned by the grouped
            // unmarked-debris sweep above, which measures and charges each capture ONCE as a whole;
            // sweeping those here would charge per file. Disqualified ids are excluded: their cleanup
            // runs through the namespace-confirmed disqualified path, never a bare delete.
            val sidecarOnly = listOf(SUFFIX_ATTEMPTS, SUFFIX_CREATED, SUFFIX_RETRY, SUFFIX_OWNER, SUFFIX_ATTEMPTS + ".tmp", SUFFIX_RETRY + ".tmp")
            files.filter { f ->
                val id = sidecarOnly.firstOrNull { f.name.endsWith(it) }?.let { f.name.removeSuffix(it) }
                id != null &&
                    id.matches(UUID_RE) &&
                    id !in memoryEntries.keys &&
                    id !in preparedIds &&
                    id !in uncertainIds &&
                    id !in boundaryHeld &&
                    !File(dir, "$id$SUFFIX_READY").exists() &&
                    // A COMPLETE PAIR IS AN ENTRY EVEN WITHOUT A MARKER (reviewer 42801e42): its `.created`
                    // / `.owner` / `.retry` sidecars are the entry's age, identity and retry authority.
                    // Sweeping them here reduced a recoverable recording to an orphan shell.
                    !(File(dir, "$id$SUFFIX_WAV").isFile && File(dir, "$id$SUFFIX_META").isFile && !isDisqualified(id)) &&
                    !isDisqualified(id)
            }.forEach { orphan ->
                val orphanId = sidecarOnly.firstOrNull { orphan.name.endsWith(it) }?.let { orphan.name.removeSuffix(it) }.orEmpty()
                // NO UNCOUNTED DELETE, same rule as the debris sweep: this path removes a user's sidecar
                // and used to do it silently.
                dropCounterOnly(TerminalReason.RECOVERY_ORPHAN_SIDECAR_REMOVED)
                Timber.tag(QUEUE_TAG).i(
                    "voice-upload remove: capture=%s file=%s reason=recovery-orphan-sidecar-removed",
                    orphanId,
                    orphan.name,
                )
                orphan.delete()
            }
        }
        // QUEUE-AGE EXPIRY (spec 05ff27d0): a recording not accepted within maxQueueAgeSeconds of its
        // capture is deleted. The production hook is HERE, in recovery, so the once-per-process
        // invariant (reviewer 032a8639) holds and expiry can never race a live preparation; the
        // eligibility path additionally withholds over-age entries mid-process.
        val expiredNow = expireOlderThanGuarded()
        keep.removeAll { it.captureId in expiredNow.toSet() }
        coverageEstablished = true
        return keep.sortedBy { it.createdAtMs }
    }

    private fun validate(id: String, wav: File, meta: File, attemptsFile: File, ageOverrideMs: Long? = null): Entry? {
        if (!id.matches(UUID_RE)) return null
        if (!wav.isFile || !meta.isFile) return null
        val declared = wav.length()
        if (declared < WavWriter.HEADER_BYTES || declared > limits.maxClipBytes) return null
        return try {
            // --- Sidecars are BOUNDED reads with strict semantics (no defaults that renew budgets/age) ---
            // A missing or malformed sidecar is a REFUSAL, never a default: defaulting attempts to 0
            // would hand back a spent retry budget, and defaulting created-at to mtime would let a
            // touched file silently renew its queue age.
            // The attempts sidecar is STRICTLY the count: the retry boundary lives in its own .retry
            // record with its own reconciliation. Any multi-token or malformed sidecar fails the entry
            // closed - never a silent default (reviewers e4820963, 7433e6a9).
            val attempts = readBoundedSidecar(attemptsFile, MAX_ATTEMPTS_SIDECAR_BYTES)
                ?.trim()?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
            // AGE IS NOT PART OF RECORD VALIDITY when recovery could not persist a reset (reviewer
            // b796c5e9): a non-null override replaces the sidecar for THIS process only, so an unwritable
            // `.created` cannot route a complete recording into invalid-record deletion. Every other
            // caller passes null and the strict read below applies unchanged.
            val created = if (ageOverrideMs != null) {
                ageOverrideMs
            } else {
                readBoundedSidecar(File(dir, "$id$SUFFIX_CREATED"), MAX_CREATED_SIDECAR_BYTES)
                    ?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            }
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
            // FULL identity after restart (reviewer f4d85031 / spec 718c043c): a PRESENT sidecar is
            // validated strictly - an INVALID record or an epoch disagreement refuses the entry
            // (corruption). An ABSENT sidecar (pre-sidecar durable entry) still RECOVERS with its
            // full retention semantics - durability never downgrades to deletion - but with a null
            // ownership; attemptUpload refuses to LEASE such an entry, so it can never be launched
            // under skipped comparisons. Incomplete identity is unsendable, not unpreserved.
            var ownerAccount: String? = null
            var ownerSession: Long? = null
            if (File(dir, "$id$SUFFIX_OWNER").isFile()) {
                val owner = readOwnerSidecar(id) ?: return null
                if (owner.epoch != valid.enrollmentEpoch) return null
                ownerAccount = owner.account
                ownerSession = owner.session
            }
            Entry(id, valid.enrollmentEpoch, recordedDigest, declared, metaBytes.size, attempts, createdAtMs = created, enrollmentAccount = ownerAccount, enrollmentSession = ownerSession)
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

    // Delete an entry's files; MUST be called while holding [lock].
    //
    // STRUCTURAL validation is not durability confirmation. An entry may have been left by a publication
    // whose final sync failed (the uncertain outcome), so its files could be present yet not durable.
    // Before exposing it as ready, re-establish and confirm the SAME barriers publication relies on:
    //  - every content file, INCLUDING the ready marker (marker-content durability is an explicit
    //    uncertain case at publication time, so a reader must not be more trusting than the writer);
    //  - the queue directory that names them;
    //  - the queue directory's PARENT namespace, which is what makes the directory reachable at all.
    // Reviewed 805f0828: reloadUnownedFromDisk used to promote marker+validate directly, a SECOND and
    // weaker load path than recovery. Both now call this one transition, so they cannot diverge.

    /**
     * RE-HARDENING, NOT A GATE (device incident 2026-11-07, spec bfe91857).
     *
     * Recovery used to confirm every recovered entry with [confirmDurableLocked], the full PUBLICATION
     * barrier: re-fsync each file, then the queue directory and its parent. That barrier's outcome
     * belongs to a process which has already exited, so on any runtime where it cannot run at this
     * moment EVERY valid, already-published clip was reported as "uncertain" and served to nobody - and
     * because a withheld entry never has a ready marker on disk, the unmarked-debris sweep then DELETED
     * its audio and metadata on that same restart. Silent, uncounted, and repeated forever.
     *
     * So recovery asks only what it can actually know now: are the files here, complete, and do they
     * pass their checksum ([validate] already decides that)? If so the entry is servable. The fsyncs are
     * re-run ONCE as hardening - a failure is logged and nothing else, because the bytes are already on
     * disk, and a file system that must be fsynced again to be believed was never what made the original
     * publication correct.
     *
     * Only an entry that is genuinely INCOMPLETE is withheld, and withholding is neither silent nor a
     * death sentence: the caller counts it ([TerminalReason.RECOVERY_HELD]) and logs its reason, and
     * queue-age expiry still applies to it.
     */
    private fun rehardenRecoveredLocked(id: String) {
        val failures = ENTRY_SUFFIXES.map { File(dir, "$id$it") }.filter { it.isFile }.mapNotNull { file ->
            runCatching { fileSync.sync(file) }.exceptionOrNull()
        } + listOfNotNull(
            runCatching { syncDirectoryOrFail(dir) }.exceptionOrNull(),
            dir.parentFile?.let { parent -> runCatching { syncDirectoryOrFail(parent) }.exceptionOrNull() },
        )
        if (failures.isNotEmpty()) {
            // Not a withholding decision: the entry is served either way. Recorded so a device that
            // can never re-harden is visible instead of inferable.
            Timber.tag(QUEUE_TAG).w(
                "voice-upload reharden: capture=%s failures=%d first=%s",
                id,
                failures.size,
                failures.first().toString(),
            )
        }
    }

    private fun confirmDurableLocked(id: String): Boolean = runCatching {
        ENTRY_SUFFIXES.map { File(dir, "$id$it") }
            .filter { it.isFile }
            .forEach { fileSync.sync(it) }
        syncDirectoryOrFail(dir)
        dir.parentFile?.let { parent -> syncDirectoryOrFail(parent) }
    }.isSuccess

    // THE single owner transition. Every path that makes a capture owned, ready, or gone runs through
    // here, so a caller cannot silently omit the accounting step (reviewer 4537e0fe). Three earlier
    // rounds each found a different caller that had: a fresh store's withheld entries, expiry/deletion,
    // and disk reload. One transition, one place to be correct.
    private fun claimOwnedLocked(captureId: String, ready: Boolean) {
        val existing = ownedCharges[captureId]
        if (existing == null) {
            ownedCharges[captureId] = OwnedCharge(ready)
        } else {
            existing.ready = ready
        }
    }

    // Delete EVERY listed file, attempting all of them. A short-circuiting `.all` stops at the first
    // failure and never tries the rest, so a failed WAV delete left a COMPLETE, still-valid entry on disk
    // (metadata + ready marker intact) which a restart would happily recover as ready. Disposal intent
    // must be durable and must not depend on which delete happened to fail first.
    private fun deleteAll(id: String, suffixes: List<String>): Boolean {
        var allGone = true
        suffixes.forEach { suffix ->
            val file = File(dir, "$id$suffix")
            if (file.exists() && !file.delete() && file.exists()) allGone = false
        }
        return allGone
    }

    // Permanent, content-free record that this capture must never be served again. Written BEFORE any
    // destructive step: an in-memory `ready = false` is not durable, so a crash or restart between the
    // failed delete and the next sweep would resurrect the entry as ready. The marker outlives the
    // process, so recovery reads it and refuses the entry no matter what survives on disk.
    // Confirmed intent is not "a file with that name exists". The marker's own content and every barrier
    // that makes it discoverable must have succeeded; a marker written but never synced may vanish on
    // crash, and a marker whose directory entry was not persisted may not be there when it is needed.
    // The presence check is therefore only a shortcut for a marker that was ALREADY fully confirmed.
    // Records disposal intent as BEST EFFORT, without requiring any barrier to succeed, and returns
    // whether the record is CONFIRMED durable. Splitting these matters: if intent were only recorded when
    // its barriers succeeded, a failing barrier would leave NO record at all and the capture would be
    // forgotten as ordinary debris - the user's request disappearing because the disk refused to co-operate.
    // The unconfirmed record is what keeps the capture on the retry list; the return value is what gates
    // destruction. Intent recorded is never intent acted upon without confirmation.
    private fun writeDisqualificationLocked(id: String): Boolean {
        val marker = disqualificationMarker(id)
        recordIntentBestEffort(marker)
        // The marker lives in the PARENT of the queue directory, not inside it. The whole reason it is
        // needed is that the queue directory may be precisely what is blocking deletion (read-only or
        // otherwise unwritable); writing intent into the blocked directory would fail in exactly the
        // situation it exists for. Disposal intent must be writable whenever cleanup is not.
        return runCatching {
            val parent = marker.parentFile
            parent?.mkdirs()
            marker.writeText(DISQUALIFIED_CONTENT)
            // EVERY barrier, each able to fail the transition on its own. Reporting success because the
            // file happens to be visible in this process's cache is exactly the mistake to avoid: the
            // caller acts on this answer by starting destructive work.
            fileSync.sync(marker)
            // The marker's OWN directory is barriered, through its OWN barrier rather than the queue's.
            // Reusing the queue's DirectorySync would let a queue-wide fault make disposal intent
            // unrecordable, which is precisely the situation the record exists for: intent must remain
            // recordable while the queue itself is unhealthy. Both are real fsyncs in production; they are
            // separate injection points because they answer different questions.
            parent?.let { intentDirectorySync.sync(it) }
            // The NAMING namespace, barriered on EVERY confirmation. Two ways of getting this wrong, both
            // of which I made: sampling "did the directory exist before?" is unreliable because recording
            // intent already created it, and treating visibility as durability is exactly the token
            // shortcut one level up. A directory's creation is persisted by the entry that names it, so
            // that entry's directory must be barriered unconditionally - not only when this call believes
            // it just created something.
            parent?.parentFile?.let { intentDirectorySync.sync(it) }
            root.let { intentDirectorySync.sync(it) }
            marker.exists() && marker.readText().trim() == DISQUALIFIED_CONTENT
        }.getOrDefault(false)
    }

    // Disposal intent recorded on a previous attempt must be RETRIED, not left to a caller that may never
    // come back. This runs on every sweep: if the files are finally deletable, they go now and the charge
    // is released with them; if not, the capture stays owned, charged, and disqualified.
    private fun retryDisqualifiedCleanupLocked() {
        // The ENTIRE transaction runs under the shared ownership monitor, not just its charge mutations.
        // Deleting files and removing the marker outside the monitor let late cleanup interleave with a
        // successor's publication on the same id: the successor could publish a new capture while this
        // sweep was still deleting the old one's files, or clear the marker the successor had just written.
        // File work and accounting are one atomic step or they are not serialized at all.
        synchronized(lock) {
            val markerDir = File(root, DISQUALIFIED_DIR)
            // The marker directory is enumerated with the same discipline as the queue root: lazy stream,
            // shared budget, error-explicit. The eager listFiles() materialized unboundedly, and `?: return`
            // treated ENUMERATION FAILURE as an empty directory - indistinguishable from genuine absence,
            // so a sweep over a partially-known marker set would silently skip intents it never saw
            // (reviewer 3eec6e62). A failure here marks root discovery unknown and defers the retry.
            // Absence vs failure, settled on the STAT, not the listing: a marker directory that does not
            // exist yet is GENUINE absence (no intents were ever recorded). Any other stat failure -
            // permission, I/O, a security manager - is a failure to look and takes the deferred-retry
            // path, exactly like a listing failure. The no-follow stat also verifies the entry IS a
            // directory before anything is opened (reviewer c571c932).
            val attributes = try {
                java.nio.file.Files.readAttributes(
                    markerDir.toPath(),
                    java.nio.file.attribute.BasicFileAttributes::class.java,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS,
                )
            } catch (missing: java.nio.file.NoSuchFileException) {
                // No marker directory at all: coverage is trivially complete.
                return
            } catch (failure: Exception) {
                // ANY other stat failure - IOException, SecurityException, a runtime failure thrown by the
                // filesystem layer - is neither absence nor coverage: defer. Normalized like the directory
                // iterator rather than escaping uncaught (reviewer 6cbb4efa). The intent stays on disk and
                // a later sweep retries it; capacity counting is unaffected (spec a3ef694f).
                Timber.tag(QUEUE_TAG).i("voice-upload markers: unreadable reason=stat-failed")
                return
            }
            if (!attributes.isDirectory) {
                // A file where the marker directory belongs is corrupted state, not an empty set: refuse to
                // act on it and leave the intents for a later sweep.
                Timber.tag(QUEUE_TAG).i("voice-upload markers: unreadable reason=not-a-directory")
                return
            }
            val ids = try {
                boundedDirectoryListing(markerDir, markerEntryBound()).map { it.name }
            } catch (unknown: MeasurementError) {
                // FAILURE IS NOT ABSENCE: the retry is deferred - no marker is acted on from partial
                // knowledge, and every recorded intent stays on disk to be retried by a later sweep.
                Timber.tag(QUEUE_TAG).i("voice-upload markers: unreadable reason=listing-failed")
                return
            }
            for (id in ids) {
                // A marker on disk does not prove its barriers succeeded: the record is written BEFORE the
                // syncs, so a crash or failure leaves content identical to a confirmed record. Every retry
                // therefore RE-RUNS the confirmation before destroying anything; if it fails again,
                // nothing is deleted and the capture keeps its charge - but the record REMAINS, so the
                // intent is not forgotten and a later sweep tries again.
                if (!writeDisqualificationLocked(id)) {
                    chargeRetainedLocked(id, ready = false)
                    continue
                }
                val gone = deleteAll(
                    id,
                    ENTRY_SUFFIXES,
                )
                if (gone) {
                    disqualificationMarker(id).delete()
                    ownedCharges.remove(id)
                    dropCounterOnly(TerminalReason.DISQUALIFIED_CLEANED_UP)
                    Timber.tag(QUEUE_TAG).i(
                        "voice-upload remove: capture=%s reason=disqualified-cleaned-up",
                        id,
                    )
                } else {
                    chargeRetainedLocked(id, ready = false)
                }
            }
        }
    }

    // The unconfirmed half: write the record so it is DISCOVERABLE by a later sweep, swallowing failures.
    // A failure here is reported by the caller's confirmation result, never by silently skipping the write.
    private fun recordIntentBestEffort(marker: File) {
        runCatching {
            marker.parentFile?.mkdirs()
            if (!marker.exists()) marker.writeText(DISQUALIFIED_CONTENT)
        }
    }

    private fun disqualificationMarker(id: String): File = File(File(root, DISQUALIFIED_DIR), id)

    private fun isDisqualified(id: String): Boolean = disqualificationMarker(id).exists()

    // Discard an owned capture and RELEASE ITS CHARGE ONLY IF THE BYTES ARE ACTUALLY GONE.

    /**
     * MONITORING (review 3c0f31d3, gap 2): index removal is NOT proof files were deleted - the
     * disqualification write or the file deletes themselves can fail and RETAIN the bytes. The truthful
     * outcome is therefore logged HERE, at the only place that knows it, with the caller's FIXED reason.
     * outcome=removed only when every file is gone; otherwise outcome=retained.
     */
    private fun deleteLocked(e: Entry, reason: String) {
        val id = e.captureId
        // 1) Durable intent FIRST, and destruction does NOT proceed without it. If the marker cannot be
        //    confirmed, deleting the files would destroy the only evidence that this capture was meant to
        //    go, so a crash could resurrect a half-deleted entry as valid. Retain and retry instead.
        if (!writeDisqualificationLocked(id)) {
            memoryEntries.remove(id)
            chargeRetainedLocked(id, ready = false)
            Timber.tag(QUEUE_TAG).i("voice-upload delete: capture=%s reason=%s outcome=retained", id, reason)
            return
        }
        // 2) Attempt every delete, regardless of earlier failures.
        val gone = deleteAll(id, ENTRY_SUFFIXES)
        memoryEntries.remove(id)
        Timber.tag(QUEUE_TAG).i(
            "voice-upload delete: capture=%s reason=%s outcome=%s",
            id,
            reason,
            if (gone) "removed" else "retained",
        )
        if (gone) {
            // Only now is the disposal itself complete; the marker goes last so intent survives a crash.
            disqualificationMarker(id).delete()
            ownedCharges.remove(id)
        } else {
            // Residual files stay OWNED and COUNTED; the capture is not ready, and a later cleanup retries.
            chargeRetainedLocked(id, ready = false)
        }
        // UNLINK ACCOUNTING (reviewer 7c2f30e3): a deletion may have removed the file of a capture whose
        // descriptor is still open. That upload keeps retaining those bytes, so account the transition
        // HERE rather than only at handoff.
        accountUnlinksLocked()
    }

    // A measurement that could not be completed. Distinct from a measured value on purpose: the java.io
    // APIs this store has (`File.exists`, `length`, `listFiles`) report absent/zero/null for paths that are
    // merely UNREADABLE, so treating their answer as authoritative would silently shrink the footprint
    // (reviewer cb212295, 7340fe2d).
    private class MeasurementError(message: String) : Exception(message)

    // How much retained storage a capture occupies, split into the two accounted channels: the primary
    // audio, the fixed sidecar list, and the disposal-intent record.
    //
    // Lazy and jointly bounded. `listFiles()` is NOT used: it materialises the whole directory before any
    // check could run, so the entry budget would be applied after the cost was already paid, and a
    // per-call constant would not bound the traversal at all. list() streams one name at a time and shares
    // ONE remaining-entry budget across the entire recursion.
    //
    // Any inability to account - overflow, unreadable directory, an entry that disappears mid-walk, a
    // negative length, or a non-regular file type - THROWS rather than substituting a number.
    // Measured size of one path. The FIRST act is a no-follow stat, never `exists()`: `exists()` swallows
    // the failure that distinguishes "absent" from "unreadable" and returns false for both, so it would
    // turn an unreadable path - or a dangling symlink - into a measured zero (reviewer c66fda06).
    //
    // The ONE production charge transition. Every caller that owns retained files goes through here, so
    // no entry path can claim a different amount than another (reviewer cb212295 finding 3).
    //
    // NO MEASUREMENT (spec a3ef694f): a capture is one RECORD whether it is 4 KB or 40 MB, so there is
    // nothing to count and nothing that can fail. Readiness is the caller's verdict and nothing else -
    // an entry is never withheld because an accounting step could not complete.
    private fun chargeRetainedLocked(id: String, ready: Boolean) {
        claimOwnedLocked(id, ready)
    }

    /**
     * Outcome of [resetUntrustedAgeLocked]. The age to use for THIS process is a value, never a sentinel:
     * an earlier version signalled "failed" with `-1L`, which `validate` stored as the entry's
     * `createdAtMs`, making the recording look older than the seven-day bound and stranding it - expiry
     * skips non-positive ages, so it could never even be reclaimed (reviewer 03aa9542).
     */
    private sealed interface AgeReset {
        // The sidecar already held a plausible time; use it.
        data object Trusted : AgeReset

        // The time was rewritten; the seven-day clock now runs from the written value.
        data object Reset : AgeReset

        // The rewrite failed; `nowMs` is this process's age and the next recovery retries the write.
        data class Failed(val nowMs: Long) : AgeReset

        val reason: String
            get() = when (this) {
                Trusted -> "age-trusted"
                Reset -> "age-reset"
                is Failed -> "age-reset-failed"
            }

        val counter: TerminalReason
            get() = when (this) {
                is Failed -> TerminalReason.AGE_RESET_FAILED
                else -> TerminalReason.AGE_RESET
            }

        // Age to use for this process, or null when the durable sidecar governs.
        val overrideMs: Long?
            get() = (this as? Failed)?.nowMs
    }

    /**
     * Record that recovery is HOLDING an entry instead of serving it. Never a delete: the entry stays on
     * disk, keeps its charge, and queue-age expiry still reaches it. Counted and logged so that an entry
     * which does not upload is diagnosable from the device rather than inferred.
     */
    private fun holdLocked(captureId: String, reason: String) {
        heldIds += captureId
        dropCounterOnly(TerminalReason.RECOVERY_HELD)
        Timber.tag(QUEUE_TAG).i("voice-upload hold: capture=%s reason=%s", captureId, reason)
    }

    /**
     * Cumulative purge witness. Deliberately NOT part of [terminalCounters]: those are cleared by the
     * purge itself, so a purge recorded only there would erase its own evidence. The count is the number
     * of captures the purge was responsible for, so "8 clips gone" is answerable from the device.
     */
    private fun recordPurgeLocked(reason: String, captures: Int) {
        // CUMULATIVE ACROSS PROCESSES (reviewer 5507715b item 3). The previous version kept the totals
        // only in memory, so the next process started from an empty map and OVERWROTE the file - the
        // witness of an earlier purge was destroyed by the next one, which defeats the entire point.
        loadPurgeCountersLocked()
        purgeCounters[reason] = (purgeCounters[reason] ?: 0L) + captures
        // A WRITE FAILURE IS REPORTED, NEVER SWALLOWED. This file is the only surface that survives a
        // purge; a silent failure would leave the device claiming nothing happened.
        val written = runCatching {
            purgeFile.writeText(purgeCounters.entries.joinToString(";") { "${it.key}=${it.value}" })
            purgeFile.isFile
        }.getOrElse { false }
        if (!written) {
            purgeWitnessFailures++
            Timber.tag(QUEUE_TAG).e(
                "voice-upload purge-witness: reason=%s captures=%d outcome=unwritten totals=%s",
                reason,
                captures,
                purgeCounters.entries.joinToString(";") { "${it.key}=${it.value}" },
            )
        } else {
            Timber.tag(QUEUE_TAG).i(
                "voice-upload purge: reason=%s captures=%d totals=%s",
                reason,
                captures,
                purgeCounters.entries.joinToString(";") { "${it.key}=${it.value}" },
            )
        }
    }

    /** Prior totals from disk, so a later process APPENDS to the record instead of replacing it. */
    private fun loadPurgeCountersLocked() {
        if (purgeCountersLoaded) return
        purgeCountersLoaded = true
        val text = runCatching { purgeFile.takeIf { it.isFile }?.readText() }.getOrNull() ?: return
        for (pair in text.split(";")) {
            val key = pair.substringBefore("=", "")
            val value = pair.substringAfter("=", "").toLongOrNull()
            if (key.isNotEmpty() && value != null) purgeCounters[key] = value
        }
    }

    /** How many purge records could not be written. Exposed so a lost witness is never invisible. */
    val purgeWitnessFailureCount: Int get() = synchronized(lock) { purgeWitnessFailures }

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
        // Adopting from a partially-known listing could resurrect entries a full listing would have
        // swept, so an unreadable listing defers the reload instead of guessing.
        val files = boundedRootListingOrNull() ?: return
        // Keyed off AUDIO, not the ready marker. A partially-failed deletion can remove the marker while
        // leaving the audio behind; keying off the marker made those residual bytes invisible to a
        // restarted store, so they occupied the queue without being charged anywhere. Anything holding
        // audio on disk is accounted for, ready or not.
        for (f in files.filter { it.name.endsWith(SUFFIX_WAV) }) {
            val id = f.name.removeSuffix(SUFFIX_WAV)
            if (id in preparedIds || memoryEntries.containsKey(id)) continue
            if (isDisqualified(id)) {
                chargeRetainedLocked(id, ready = false)
                continue
            }
            val ready = File(dir, "$id$SUFFIX_READY").exists()
            val wav = File(dir, "$id$SUFFIX_WAV")
            val meta = File(dir, "$id$SUFFIX_META")
            val attemptsFile = File(dir, "$id$SUFFIX_ATTEMPTS")
            if (!ready) {
                // Residual from an incomplete deletion: charged and NOT ready, so it is never served but
                // still bounds capacity until a later cleanup removes the bytes.
                chargeRetainedLocked(id, ready = false)
                continue
            }
            val adopted = validate(id, wav, meta, attemptsFile)
            if (adopted != null && confirmDurableLocked(id)) {
                // CHARGE FIRST, then publish to the in-memory index. The reverse order left a servable
                // entry behind when the measurement failed: `memoryEntries` still held it, so readiness
                // came from the index rather than from a measurement that succeeded (reviewer c66fda06).
                chargeRetainedLocked(id, ready = true)
                if (ownedCharges[id]?.ready == true) {
                    memoryEntries[id] = adopted
                } else {
                    memoryEntries.remove(id)
                }
            } else if (wav.exists() || meta.exists()) {
                // An entry that FAILED validation OR durability confirmation still holds bytes on disk.
                // Dropping it silently left those bytes occupying the queue uncharged. Charged, not
                // ready, not served - the next confirmation attempt decides.
                chargeRetainedLocked(id, ready = false)
            }
        }
    }

    private val memoryEntries = LinkedHashMap<String, Entry>()
    private val preparedIds = mutableSetOf<String>()

    /**
     * OWNER ACCOUNTING: one charge per immutable capture this instance owns, with readiness as STATE.
     *
     * Every capture this owner has ever published, withheld, or failed to confirm occupies exactly one
     * entry here, and that single entry is the ONLY source of its bytes/record charge. Readiness is a
     * state flag on that same entry, never a second map.
     *
     * Earlier versions kept readiness in [memoryEntries] and uncertainty in a separate id->sizes map.
     * Because the two could overlap, the same capture was charged twice after reconciliation, zero times
     * after a restart, and the record count disagreed between the two admission paths. One entry per
     * capture removes the possibility instead of patching each symptom.
     */
    private val ownedCharges = LinkedHashMap<String, OwnedCharge>()

    /**
     * One owner's claim on one capture. It records EXISTENCE and READINESS only (spec a3ef694f): capacity
     * is the number of records, so there are no byte fields to keep in sync or to fail to measure.
     */
    private class OwnedCharge(
        // True once the capture may be handed back as ready; false while it is uncertain.
        var ready: Boolean,
    )
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
    private val purgeFile get() = File(root, PURGE_COUNTERS_NAME)
    private val purgeCounters = mutableMapOf<String, Long>()
    private var purgeCountersLoaded = false
    private var purgeWitnessFailures = 0
    private val dir get() = File(root, DIR_NAME)

    /**
     * Directory-entry durability, never swallowed.
     *
     * This was a no-op stub because opening a stream on a directory is not portable. Production now
     * uses [DirectorySync.PRODUCTION] - the documented android.system.Os open(O_DIRECTORY)/fsync/close
     * surface - which has NO unsupported-runtime success fallback. A failure (errno or unsupported)
     * means the entry is not known durable, so it propagates rather than being reported as a
     * successful publication.
     */
    private fun syncDirectoryOrFail(directory: File) = directorySync.sync(directory)

    // Test seam: the ownership monitor, so a control can prove FILE WORK is serialized on it and not
    // merely the accounting mutations.
    fun ownerLockForTest(): Any = lock

    // Test seam: the records currently occupied by this owner (durable entries + live reservations).
    fun ownedRecordsForTest(): Int = synchronized(lock) { ownerRecordsLocked() }

    /** The record bound admission enforces (spec a3ef694f). */
    fun recordLimit(): Int = limits.maxQueueRecords

    fun ownedIdsForTest(): Set<String> = synchronized(lock) { ownedCharges.keys.toSet() }

    // Lock-free on purpose: the held-monitor control needs to observe the claim while holding the monitor.
    fun ownedIdsSnapshotForTest(): Set<String> = ownedCharges.keys.toSet()

    // Test seam: whether one capture currently holds an owner record.
    fun isChargedForTest(captureId: String): Boolean = synchronized(lock) { captureId in ownedCharges }

    /** Test seam: charge a capture with NO file behind it, to simulate a stale in-memory tally. */
    fun chargeRetainedForTest(captureId: String) = synchronized(lock) { claimOwnedLocked(captureId, ready = false) }

    /** Test seam: how many captures the in-memory tally believes are retained (capacity index ONLY). */
    fun chargedCountForTest(): Int = synchronized(lock) { ownedCharges.size }

    // Separate barrier for the DURABLE DISPOSAL INTENT record. Production uses the same real fsync; it is
    // a distinct injection point so a fault aimed at the queue cannot silently disable intent recording,
    // and so intent recording can itself be faulted on its own.
    // Defaults to the REAL barrier, NOT to `directorySync`. A fault aimed at the queue must not be able to
    // make disposal intent unrecordable: intent is most needed exactly when the queue is unhealthy. Tests
    // that want to fault intent recording pass the override explicitly.
    private val intentDirectorySync: DirectorySync = intentDirectorySyncOverride
        ?: DirectorySync.REAL

    companion object {
        /** Marks a purge that could not enumerate the directory. NOT a capture id. */
        const val PURGE_RESIDUE_UNKNOWN_LISTING = "unknown-listing"

        private const val DIR_NAME = "contributions"
        private const val COUNTERS_NAME = "terminal-counters.txt"

        // Cumulative across purges BY DESIGN: unlike terminal-counters.txt this file is never deleted by a
        // purge, because deleting it would destroy the only record that the purge happened.
        private const val PURGE_COUNTERS_NAME = "purge-counters.txt"
        private const val SUFFIX_WAV = ".recording.wav"
        private const val SUFFIX_META = ".metadata.json"
        private const val SUFFIX_READY = ".ready"
        private const val SUFFIX_PART = ".wav.part"

        /** Cap for reading a published metadata file back; the recorded size must fit under it. */
        private const val MAX_METADATA_READ_BYTES = 1L shl 20

        /**
         * The global source-slot bound (reviewer 6456b2a5 finding 3). Matches the transport's call bound:
         * at most this many descriptors may be open, so retained bytes cannot grow past a bounded number of
         * captures while the queue looks idle.
         */
        internal const val MAX_OPEN_SOURCES = 8

        /** Teardown settle deadline: long enough for a live writer to drain, short enough to fail fast. */
        private const val SOURCE_SETTLE_TIMEOUT_MS = 2_000L

        /**
         * TEST SEAM: process-wide slots currently held. Read WITHOUT constructing a store, because a store
         * created by a teardown would itself establish coverage and could mask the leak being asserted.
         */
        @JvmStatic
        fun processOpenSourceSlotsForTest(): Int = PROCESS_OPEN_SOURCE_SLOTS.get()

        /**
         * The PROCESS-WIDE source-slot count. Deliberately a companion-object field, not per instance: every
         * open descriptor anywhere in this process consumes device bytes, so one bound must cover them all.
         */
        private val PROCESS_OPEN_SOURCE_SLOTS = java.util.concurrent.atomic.AtomicInteger(0)

        private const val STAGING_PREFIX = "gen$"
        private const val ADMISSION_HELD = 0
        private const val ADMISSION_RELEASED = 1
        private const val ADMISSION_ADOPTED = 2
        private const val ADMISSION_WRITING = 3
        private const val SUFFIX_ATTEMPTS = ".attempts"

        // Durable retry-not-before boundary: its own file, published atomically, so the boundary survives
        // failed publications and restarts independently of the attempt count (reviewer 7433e6a9).
        private const val SUFFIX_RETRY = ".retry"
        private const val SAMPLE_RATE_HZ = 16_000L

        /** Admitted-ownership sidecar: {"account":...,"session":...,"epoch":...}. */
        const val SUFFIX_OWNER = ".owner"
        private const val SUFFIX_CREATED = ".created"

        /** Every file an entry can own. Deletion and residue sweeps use this ONE list. */
        private val ENTRY_SUFFIXES = listOf(
            SUFFIX_WAV,
            SUFFIX_META,
            SUFFIX_READY,
            SUFFIX_ATTEMPTS,
            SUFFIX_CREATED,
            SUFFIX_DIGEST,
            SUFFIX_RETRY,
            SUFFIX_OWNER,
            SUFFIX_ATTEMPTS + ".tmp",
            SUFFIX_RETRY + ".tmp",
        )
        private const val SUFFIX_DIGEST = ".sha256"

        // Everything a capture can leave on disk besides its primary audio and metadata. Kept as a fixed
        // list so measurement is bounded; a new artifact must be added here or it goes unaccounted.
        // Measurement bounds. Hitting either is an ERROR, never a silent truncation.
        private const val MEASURE_MAX_DEPTH = 4
        private const val MEASURE_MAX_ENTRIES = 512

        /**
         * The largest over-cap queue a build may inherit and still have to DRAIN rather than merely refuse.
         * The observed worst case was 104 records against a 32 cap; this leaves room above it so the same
         * failure cannot recur on a queue that is bigger still. At ~10 files per record this puts the
         * traversal bound around 5,000 entries: transient `.part`/staging residue is absorbed by that
         * headroom rather than needing a separate multiplier.
         */
        private const val INHERITED_QUEUE_RECORDS = 512
        private val SIDECAR_SUFFIXES = listOf(SUFFIX_META, SUFFIX_READY, SUFFIX_ATTEMPTS, SUFFIX_CREATED, SUFFIX_DIGEST, SUFFIX_RETRY)
        private const val DISQUALIFIED_DIR = "disqualified"

        // The marker's content is its confirmation token: presence alone is not durable intent.
        private const val DISQUALIFIED_CONTENT = "disqualified"
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

private const val QUEUE_TAG = "VoiceUploadQueue"
