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
    internal val limits: QueueLimits,
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

    // Marker AND root coverage must be established BEFORE the first admission, not only at
    // recovery/expiry: a newly constructed store over an EXISTING directory would otherwise admit
    // immediately over intents and bytes nobody has enumerated (reviewers f4436ced, 92911de9; required
    // precondition under spec 7e9606b). The scans are bounded and run once per instance - afterwards the
    // flags reflect what was found. The root pass only ACCOUNTS: it charges retained unowned captures it
    // finds; deletion stays the recovery sweep's job, so admission never destroys on partial knowledge.
    private var coverageEstablished = false

    /** Ownership-refusal accounting (spec 8adcfd01): durableCapture refusing for sidecar reasons. */
    var sidecarRefusals: Int = 0
        private set

    private fun establishCoverage() {
        if (coverageEstablished) return
        // A FRESH install has no directory yet; without this, the first listing would fail with
        // NoSuchFileException and permanently refuse admission on a perfectly empty queue (recover()
        // creates the directory the same way).
        dir.mkdirs()
        val files = try {
            boundedRootListing()
        } catch (unknown: MeasurementError) {
            synchronized(lock) {
                rootEnumerationUnknown = true
                measurementFailures++
            }
            coverageEstablished = true
            return
        }
        synchronized(lock) { rootEnumerationUnknown = false }
        // Account every on-disk capture this instance holds no charge for, grouped by derived capture id
        // but measured at the CONCRETE discovered paths. Deriving an id and charging canonical paths only
        // charged zero for raw or staging debris (reviewer 7a47fff8): the bytes live where they live.
        val suffixes = listOf(SUFFIX_READY, SUFFIX_PART, SUFFIX_ATTEMPTS, SUFFIX_RETRY, SUFFIX_WAV, SUFFIX_META, SUFFIX_DIGEST, SUFFIX_OWNER, SUFFIX_ATTEMPTS + ".tmp", SUFFIX_RETRY + ".tmp")
        synchronized(lock) {
            files.groupBy { f ->
                val hit = suffixes.firstOrNull { f.name.endsWith(it) }
                if (hit != null) {
                    f.name.removeSuffix(hit).removePrefix(STAGING_PREFIX).removeSuffix(SUFFIX_WAV).removeSuffix(".tmp")
                } else {
                    f.name
                }
            }.forEach { (id, captureFiles) ->
                if (id in ownedCharges) return@forEach
                try {
                    val budget = MeasurementBudget(MEASURE_MAX_ENTRIES)
                    var audio = 0L
                    var rest = 0L
                    for (file in captureFiles) {
                        val bytes = measuredPathBytes(file, budget, optional = false)
                        if (file.name.endsWith(SUFFIX_WAV) || file.name.endsWith(SUFFIX_PART)) {
                            audio = checkedAdd(audio, bytes)
                        } else {
                            rest = checkedAdd(rest, bytes)
                        }
                    }
                    if (rest < 0L || rest > Int.MAX_VALUE.toLong()) {
                        throw MeasurementError("retained footprint unrepresentable: $rest")
                    }
                    unmeasuredIds -= id
                    claimOwnedLocked(id, audio, rest.toInt(), ready = false)
                } catch (failure: MeasurementError) {
                    measurementFailures++
                    unmeasuredIds += id
                    // Small placeholder; the unknown state above is the refusal mechanism.
                    claimOwnedLocked(id, 0L, 0, ready = false)
                }
            }
        }
        retryDisqualifiedCleanupLocked()
        coverageEstablished = true
    }

    /** Claim count+bytes atomically; returns null when the queue cannot hold another entry. */
    private fun reserve(sampleCount: Long, projected: Long): Admission? {
        establishCoverage()
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
    fun durableCapture(captureId: String): DurableCapture? = synchronized(lock) {
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
        val actualAudioSha = try {
            sha256HexBounded(audio, limits.maxClipBytes) ?: return null
        } catch (io: Throwable) {
            return null
        }
        if (actualAudioSha != declaredAudioSha) return null
        // The audio must still be the WAV these bytes describe.
        val header = try {
            audio.inputStream().use { input ->
                val buffer = ByteArray(au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.WavWriter.HEADER_BYTES)
                var read = 0
                while (read < buffer.size) {
                    val n = input.read(buffer, read, buffer.size - read)
                    if (n < 0) return null
                    read += n
                }
                buffer
            }
        } catch (io: Throwable) {
            return null
        }
        if (WavHeaderValidator.validate(header, audio.length()) == null) return null
        // FAIL CLOSED (reviewer 0081c95f): the admitted ownership is REQUIRED. A missing or corrupt
        // sidecar refuses to serve the capture at all - it retains on disk but can never be sent,
        // because an unvouched identity must not silently degrade to an epoch-only check that a
        // removed or damaged sidecar could bypass.
        val owner = readOwnerSidecar(captureId) ?: run {
            sidecarRefusals++
            return null
        }
        DurableCapture(record, audio, entry.enrollmentEpoch, owner.first, owner.second)
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

    /** The audio sha256 the record itself declares, as written at capture time. */
    private fun audioShaOf(record: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode.Obj): String? {
        val audio = record.fields["audio"] as? au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode.Obj ?: return null
        val sha = audio.fields["sha256"] as? au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode.Str ?: return null
        return sha.value.takeIf { it.length == 64 }
    }

    /** A capture THIS STORE vouches for: validated record bytes, matching audio, the owning identity. */
    class DurableCapture(
        val record: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode.Obj,
        val audio: File,
        val enrollmentEpoch: String,
        /** Admitted account/session from the owner sidecar; null for older epoch-only admissions. */
        val enrollmentAccount: String? = null,
        val enrollmentSession: Long? = null,
    )

    /**
     * Serialize the owner sidecar through an ESCAPING JSON writer (reviewer 30b1c817): the account
     * name is arbitrary text and must never be able to forge sidecar structure.
     */
    private fun ownerSidecarJson(account: String?, session: Long?, epoch: String): String {
        val buffer = okio.Buffer()
        val writer = com.squareup.moshi.JsonWriter.of(buffer)
        writer.beginObject()
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
    private fun parseOwnerSidecar(text: String): Pair<String, Long>? {
        val reader = com.squareup.moshi.JsonReader.of(okio.Buffer().writeUtf8(text))
        try {
            var account: String? = null
            var session: Long? = null
            var sawEpoch = false
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "account" -> {
                        if (account != null) return null // duplicate key
                        account = reader.nextString().ifEmpty { return null }
                    }

                    "session" -> {
                        if (session != null) return null // duplicate key
                        session = reader.nextLong()
                    }

                    "epoch" -> {
                        if (sawEpoch) return null // duplicate key
                        sawEpoch = true
                        reader.nextString()
                    }

                    else -> return null // unknown key
                }
            }
            reader.endObject()
            // Trailing content after the closing brace is a deviation, not decor (strict EOF check).
            if (reader.peek() != com.squareup.moshi.JsonReader.Token.END_DOCUMENT) return null
            account ?: return null
            session ?: return null
            if (!sawEpoch) return null
            return account to session
        } catch (_: Exception) {
            return null
        } finally {
            reader.close()
        }
    }

    /**
     * STRICT bounded variable-length read of the admitted-ownership sidecar (reviewer 0081c95f):
     * the file is ordinary JSON of ANY length up to the cap - the read demands the file's ACTUAL
     * size, never a fixed width, and the parser is exact (no trailing junk, both fields present).
     * Null means absent OR corrupt; the caller fails closed.
     */
    private fun readOwnerSidecar(captureId: String): Pair<String, Long>? {
        val f = File(dir, "$captureId$SUFFIX_OWNER")
        if (!f.isFile) return null
        // The cap holds DURING the read (spec 8adcfd01): read at most CAP+1 bytes and refuse when a
        // byte beyond the cap exists - a file growing concurrently cannot smuggle oversize content.
        val cap = 512
        val bytes = ByteArray(cap + 1)
        var read = 0
        java.io.FileInputStream(f).use { input ->
            while (read < bytes.size) {
                val n = input.read(bytes, read, bytes.size - read)
                if (n < 0) break
                read += n
            }
        }
        // A full CAP+1 read means the file is at or beyond the cap: refuse either way.
        if (read == 0 || read > cap) return null
        return parseOwnerSidecar(String(bytes, 0, read, Charsets.UTF_8))
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
    fun pickupAttemptToken(generation: Long, captureId: String, nowMs: Long = clock()): AdmittedAttempt? = synchronized(lock) {
        if (unknownFootprintLocked() || !ownership.isValidUnderLock(generation)) {
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
    fun scheduleRetryForAdmittedAttempt(token: AdmittedAttempt, retryAfterS: Long? = null): Long? = synchronized(lock) {
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
    ): Boolean {
        val removed = synchronized(lock) {
            if (!attemptIdentityHoldsLocked(token.generation, token.captureId, token.attemptNumber)) {
                null
            } else {
                memoryEntries.remove(token.captureId)?.also { deleteLocked(it) }
            }
        } != null
        if (removed) dropCounterOnly(reason)
        return removed
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
     * THE single definition of what this owner already occupies. Both admission paths call it, so they
     * cannot disagree: owner charges (ready AND uncertain alike - the files are on disk either way) plus
     * any live reservation. [exclude] drops one reservation, for re-checking an attempt that already
     * holds it.
     */
    private fun ownerBytesLocked(exclude: String? = null): Long = ownedCharges.values.sumOf { it.bytes } +
        reservations.entries.filterNot { it.key == exclude }.sumOf { it.value }

    /** Records occupied = every owned capture (ready or uncertain) + live reservations. */
    private fun ownerRecordsLocked(exclude: String? = null): Int = ownedCharges.size + reservations.keys.count { it != exclude }

    /** Re-check budgets at publication time (a purge/expiry may have moved them since admission). */
    private fun roomForPublicationLocked(admission: Admission): Boolean = ownerRecordsLocked(exclude = admission.id) < limits.maxQueueRecords &&
        ownerBytesLocked(exclude = admission.id) + admission.projectedBytes <= limits.maxQueueBytes

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
                    // The file survived. Charge it where it actually is; measuring "$id$SUFFIX_WAV" for a
                    // staging name found nothing and charged zero, the blindness this check exists to remove.
                    val survivorBytes = try {
                        measuredPathBytes(file, MeasurementBudget(MEASURE_MAX_ENTRIES))
                    } catch (unknown: MeasurementError) {
                        measurementFailures++
                        unmeasuredIds += id
                        // Worst-case placeholder: the bytes could not be counted, so they are assumed to
                        // fill the whole budget until a successful measurement clears the unknown mark.
                        claimOwnedLocked(id, limits.maxQueueBytes, 0, ready = false)
                        return@synchronized
                    }
                    // A SUCCESSFUL measurement is itself a clearing event: an id that earlier could not be
                    // measured is now accounted, so refusing admission over it any longer would be refusing
                    // over bytes that ARE known (reviewer 7e09c62b - the comment claimed this and the code
                    // did not do it).
                    unmeasuredIds -= id
                    claimOwnedLocked(id, survivorBytes, 0, ready = false)
                }

                absence == Absence.ABSENT -> {
                    // REVERSE TRANSITION. The no-follow stat proves THIS exact path is gone; delete()'s
                    // boolean adds no identity proof, and false here usually means an earlier or competing
                    // sweep already removed it. A retry over an already-absent path must RELEASE the charge,
                    // not poison admission with a permanent unmeasured mark (reviewer 567c0b94).
                    ownedCharges.remove(id)
                    unmeasuredIds -= id
                }

                else -> {
                    // UNKNOWN: inability to stat. Never free the charge on a failure to look. It is
                    // recoverable: a LATER sweep that can stat the path either proves absence (this branch
                    // releases) or measures the survivor successfully, both of which clear the mark.
                    measurementFailures++
                    unmeasuredIds += id
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
        // Bounded root traversal, like recovery: sweeping a partially-known listing is destructive with
        // unknown coverage, so enumeration failure defers the sweep and refuses admission instead.
        val files = try {
            boundedRootListing()
        } catch (unknown: MeasurementError) {
            synchronized(lock) {
                rootEnumerationUnknown = true
                measurementFailures++
            }
            return
        }
        files.forEach { f ->
            // Checked, like every other deletion: a staging file that refuses to delete still occupies the
            // queue, and discarding the result left those bytes charged nowhere (reviewer e4342946).
            if (f.name.startsWith("$STAGING_PREFIX$attemptId.")) f.deleteOrChargeOnFailure()
        }
    }

    private fun releaseReservation(id: String, projected: Long) {
        synchronized(lock) { reservations.remove(id) }
    }

    /** Budget test against durable entries PLUS live reservations (both consume the same budget). */
    private fun roomLocked(projected: Long): Boolean {
        // Unknown usage refuses admission outright: admission is exactly where a made-up footprint would be
        // trusted to bound everything else.
        if (unknownFootprintLocked()) return false
        return ownerRecordsLocked() < limits.maxQueueRecords &&
            ownerBytesLocked() + projected <= limits.maxQueueBytes
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
    ): Entry? {
        val samples = sampleCount(pcmChunks) ?: return null
        val admission = admit(samples, canonicalMetadata.size)
            ?: run {
                null
            }
            ?: return null
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
                val ownerJson = ownerSidecarJson(enrollmentAccount, enrollmentSession, enrollmentEpoch)
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
                    ownedCharges[captureId] = OwnedCharge(entry.audioBytes, entry.metadataBytes, ready = false)
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
                                    OwnedCharge(entry.audioBytes, entry.metadataBytes, ready = false)
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
        // A failed recovery leaves memoryEntries intact from before the failure, but the queue DIRECTORY
        // could not be enumerated, so what is on disk can no longer be reconciled with what is in memory.
        // Serving from memory alone could hand out entries whose files were swept, changed or never
        // accounted while the root was unknown; eligibility is restored by a recovery that enumerates
        // successfully (reviewer d1cce25b).
        if (rootEnumerationUnknown || markerCoverageUnknown) {
            emptyList()
        } else {
            // A capture with an unresolved record (retry boundary or attempt count) is NOT servable,
            // even though it is charged and indexed (reviewer 199ccdee).
            memoryEntries.values
                .filter { !heldUncertainLocked(it.captureId) }
                .sortedBy { it.createdAtMs }
        }
    }

    private var recovered = false

    fun totalQueuedBytes(): Long = readyEntries().sumOf { it.audioBytes + it.metadataBytes }

    /** Test seam: the contributions directory this store writes to. */
    fun dirForTest(): File = dir

    /** Expiry removes ONLY expired entries; others are untouched. */
    fun expireOlderThan(): List<String> {
        retryDisqualifiedCleanupLocked()
        val now = clock()
        val removed = mutableListOf<String>()
        synchronized(lock) {
            if (!recovered) reloadUnownedFromDisk()
            // Entries whose ready marker vanished (purged elsewhere) leave the index immediately.
            for ((id, e) in memoryEntries.toMap()) {
                if (id !in preparedIds && !File(dir, "$id$SUFFIX_READY").exists()) {
                    deleteLocked(e)
                }
            }
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
    // Entries whose retry-boundary persistence FAILED and whose durable state is therefore UNKNOWN.
    // While any exist, retryEligible fails CLOSED for them. The uncertainty is BACKED BY A DURABLE
    // INTENT FILE: a failed publication leaves the intent (tmp or renamed) on disk, so a FRESH OWNER
    // reconciles the barriers before eligibility instead of forgetting the boundary (reviewer 7433e6a9).
    private val retryBoundaryUncertain = mutableSetOf<String>()

    // Captures whose ATTEMPT-COUNT record is in an unresolved pending-intent state. While set, the count
    // is untrusted: no further attempt is recorded and the entry is not served (reviewer 199ccdee).
    private val attemptsRecordUncertain = mutableSetOf<String>()

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
    private fun visibleNamesLocked(): Set<String>? = try {
        boundedDirectoryListing(dir).mapTo(HashSet()) { it.name }
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
    ): Long? = synchronized(lock) { scheduleRetryAfterFailureLocked(generation, captureId, retryAfterS) }

    // Requires the caller to already hold the lock: validate-and-mutate must be indivisible (reviewer
    // 20a5440b) so a successor attempt cannot be admitted between the identity check and the write.
    private fun scheduleRetryAfterFailureLocked(
        generation: Long,
        captureId: String,
        retryAfterS: Long? = null,
        allowRevokedDisposal: Boolean = false,
    ): Long? {
        return if (unknownFootprintLocked() || (!ownership.isValidUnderLock(generation) && !allowRevokedDisposal)) {
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
        if (unknownFootprintLocked()) {
            emptyList()
        } else {
            memoryEntries
                .mapNotNull { (id, entry) -> if (retryEligibleLocked(id, entry, nowMs)) id else null }
                .sorted()
        }
    }

    /** The COMPLETE per-entry retry eligibility predicate, shared by listing and atomic pickup. */
    private fun retryEligibleLocked(id: String, entry: Entry, nowMs: Long): Boolean {
        val charge = ownedCharges[id]
        if (charge?.ready != true) return false
        if (entry.attempts >= limits.maxUploadAttempts) return false
        // Absent means genuinely unscheduled: recovery loaded every durable boundary, and a publication
        // failure puts the id in the UNCERTAIN set instead of defaulting.
        if (heldUncertainLocked(id)) return false
        if ((nextRetryAtMs[id] ?: 0L) > nowMs) return false
        return true
    }

    // ATOMIC PICKUP (reviewer 1b926fa0, spec 8aa20001): inside ONE ownership transaction the caller
    // revalidates the COMPLETE current eligibility - readiness, coverage, budget, unresolved records and
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
    fun leaseTransportLaunch(generation: Long, captureId: String): LaunchLease? = ownership.transaction(
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
                    val lease = LaunchLease(generation, captureId)
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
    class LaunchLease internal constructor(val generation: Long, val captureId: String) {
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
        revokeAndPurge(reason)
        synchronized(launchLeasesLock) {
            launchLeases.forEach { it.cancel() }
            launchLeases.clear()
        }
    }

    /**
     * Retire a lease once its upload finished, so a later revocation only cancels live attempts. A
     * REVOKED lease is left to be cleared wholesale by [revokeAndCancelLaunches].
     */
    fun releaseLaunchLease(lease: LaunchLease) {
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

    fun pickupAttempt(generation: Long, captureId: String, nowMs: Long = clock()): Int? = synchronized(lock) {
        if (unknownFootprintLocked() || !ownership.isValidUnderLock(generation)) {
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

    fun recordAttempt(generation: Long, captureId: String): Int? {
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
            if (unknownFootprintLocked()) {
                null
            } else if (!ownership.isValidUnderLock(generation)) {
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
    fun dropEntry(generation: Long, captureId: String, reason: TerminalReason): Boolean {
        val removed = synchronized(lock) {
            if (ownership.isValidUnderLock(generation)) memoryEntries.remove(captureId)?.also { deleteLocked(it) } else null
        } != null
        if (removed) dropCounterOnly(reason)
        return removed
    }

    /** Convenience for the current owner generation; prefer the fenced overload in callback paths. */
    fun dropEntry(captureId: String, reason: TerminalReason): Boolean = dropEntry(fence.currentGeneration(), captureId, reason)

    enum class TerminalReason { ATTEMPTS_EXHAUSTED, QUEUE_AGE_EXPIRED, SERVER_TERMINAL, INVALID_ON_RECOVERY, UPLOAD_ACCEPTED }

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
            cancelLiveLaunchesLocked() // ONE authoritative transition: a purge also withdraws launches
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
                    listOf(SUFFIX_WAV, SUFFIX_META, SUFFIX_READY, SUFFIX_ATTEMPTS, SUFFIX_CREATED, SUFFIX_DIGEST, SUFFIX_RETRY, SUFFIX_ATTEMPTS + ".tmp", SUFFIX_RETRY + ".tmp"),
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
            residueIds.forEach { id -> _purgeResidue.add(id) }
            stagedAttempts.forEach { attempt -> deleteStaging(attempt) }
            stagingOwners.clear()
            countersFile.delete()
            terminalCounters.clear()
            _purgeReasons.add(reason)
        }
    }

    val purgeReasons: List<String> get() = synchronized(lock) { _purgeReasons.toList() }

    // Captures whose files SURVIVED a purge delete attempt. A purge that reports success while bytes
    // remain would tell the user their audio is gone while it is still on disk.
    val purgeResidue: List<String> get() = synchronized(lock) { _purgeResidue.toList() }
    private val _purgeResidue = mutableListOf<String>()
    private val _purgeReasons = mutableListOf<String>()

    /** Listening/permission closure: remove IN-PROGRESS preparation only; ready entries stay. */
    fun discardCurrentPartials() {
        // Same bounded traversal as recovery (reviewer d1cce25b): enumeration failure defers the sweep.
        val files = try {
            boundedRootListing()
        } catch (unknown: MeasurementError) {
            synchronized(lock) {
                rootEnumerationUnknown = true
                measurementFailures++
            }
            return
        }
        // Same checked deletion as the recovery sweep. Discarding the result here left surviving partials
        // invisible to accounting (reviewer e4342946).
        files.forEach { f -> if (f.name.endsWith(SUFFIX_PART)) f.deleteOrChargeOnFailure() }
    }

    /**
     * Recovery at startup: delete abandoned preparation (.part without matching .ready), then
     * validate COMPLETE entries (ready + wav + meta present, byte_count matches header, attempts
     * restored). Invalid complete-looking entries are dropped with a content-free counter —
     * recovery admits nothing unvalidated to upload.
     */
    // Set when root enumeration failed or exceeded its bound. Recovery cannot know what the queue
    // directory holds, so it must not run a DESTRUCTIVE sweep over a partially-known listing, and
    // accounting stays unknown - refusing admission - until a later recovery enumerates successfully.
    @Volatile
    private var rootEnumerationUnknown = false

    // Spec 896632f4 (relayed by reviewer cb236dd5): unknown DISPOSAL-MARKER coverage holds admission AND
    // serving/attempt for this owner. A marker can disqualify a retained survivor, so neither readiness
    // nor full accounting can be established while marker coverage is partially known. Ownership and
    // charges are preserved; the hold lifts only after bounded marker discovery succeeds and the recorded
    // intents are reconciled (the retry pass runs to completion over the fully enumerated set).
    // Starts false: a fresh INSTALL has no marker directory (genuine absence). The dangerous case - a
    // store instance over a directory whose markers were never enumerated - is covered by recover(),
    // which establishes coverage in the common eligibility path before anything is served or admitted.
    @Volatile
    private var markerCoverageUnknown = false

    // Root discovery with the same discipline as the measurement walk: a lazy directory stream, ONE shared
    // entry budget, error-explicit. The eager `dir.listFiles()` materialized the entire directory with no
    // bound, so a pathological queue root allocated without limit at every startup (reviewer c66fda06
    // series; the same defect already fixed inside the measurement walk).
    private fun boundedRootListing(): List<File> = boundedDirectoryListing(dir)

    private fun boundedDirectoryListing(directory: File): List<File> {
        val budget = MeasurementBudget(MEASURE_MAX_ENTRIES)
        val found = mutableListOf<File>()
        try {
            java.nio.file.Files.newDirectoryStream(directory.toPath()).use { stream ->
                for (child in stream) {
                    if (!budget.consume()) throw MeasurementError("root exceeds $MEASURE_MAX_ENTRIES entries")
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

    fun recover(): List<Entry> {
        dir.mkdirs()
        val files = try {
            boundedRootListing()
        } catch (unknown: MeasurementError) {
            synchronized(lock) {
                rootEnumerationUnknown = true
                measurementFailures++
            }
            // NO sweep, NO serving: deleting from a directory whose contents are only partially known is
            // destructive with unknown coverage, and nothing can be validated for serving. Accounting is
            // unknown, so admission is refused until a later recovery enumerates successfully.
            return emptyList()
        }
        synchronized(lock) { rootEnumerationUnknown = false }
        // 1) Debris sweep: .part files are NEVER ready; .ready without wav/meta is invalid.
        // The RESULT of delete() used to be discarded here, so staging bytes that survived occupied the
        // queue while being charged nowhere (reviewer c71672c0).
        files.filter { it.name.endsWith(SUFFIX_PART) }.forEach { it.deleteOrChargeOnFailure() }
        val readyIdsBefore = files.filter { it.name.endsWith(SUFFIX_READY) }.map { it.name.removeSuffix(SUFFIX_READY) }.toSet()
        // Unmarked FINAL debris: a .wav/.meta/.sha256 with no matching .ready marker is an
        // interrupted preparation that never reached its commit point. It must not survive recovery
        // (an .ready-marked entry is validated below instead).
        files.filter { f ->
            val suffix = listOf(SUFFIX_WAV, SUFFIX_META, SUFFIX_DIGEST).firstOrNull { f.name.endsWith(it) }
            suffix != null && f.name.removeSuffix(suffix) !in readyIdsBefore
        }.groupBy { f ->
            // Grouped by CAPTURE, not visited file by file. A per-file sweep can delete the metadata of a
            // capture whose WAV refused to delete, leaving a residue that was charged for one file and then
            // silently changed shape underneath the charge. Each capture is handled once, as a whole.
            f.name.removeSuffix(listOf(SUFFIX_WAV, SUFFIX_META, SUFFIX_DIGEST).first { sfx -> f.name.endsWith(sfx) })
        }.forEach { (id, captureFiles) ->
            val survivors = captureFiles.filter { file -> !file.delete() && file.exists() }
            if (survivors.isNotEmpty()) {
                // Measure the WHOLE capture AFTER the attempted deletes, so the charge matches what
                // actually survived rather than what was there before the sweep touched it.
                chargeRetainedLocked(id, ready = false)
            }
        }
        val readyIds = readyIdsBefore.toList()
        val keep = mutableListOf<Entry>()
        // Entries whose publication could not be confirmed durable. Kept out of the ready set, but NOT
        // deleted: uncertain is not rejected.
        val uncertainIds = mutableListOf<String>()
        for (id in readyIds) {
            // A capture disqualified by a previous failed deletion is NEVER served again, whatever
            // survives on disk: the marker is durable, so intent outlives the process that formed it.
            if (isDisqualified(id)) {
                chargeRetainedLocked(id, ready = false)
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
                // Unresolved count record: withheld this run exactly like an unconfirmed publication.
                uncertainIds += id
                chargeRetainedLocked(id, ready = false)
                continue
            }
            val entry = validate(id, wav, meta, attemptsFile)
            if (entry == null) {
                // Deleting an invalid entry can FAIL. The previous version dropped the files' existence
                // on the floor and only bumped a counter, so any bytes that survived the delete became
                // unowned: still on disk, still occupying the user's storage, counted by nothing.
                val cleaned = listOf(
                    wav,
                    meta,
                    attemptsFile,
                    File(dir, "$id$SUFFIX_READY"),
                    File(dir, "$id$SUFFIX_DIGEST"),
                ).all { !it.exists() || it.delete() || !it.exists() }
                if (!cleaned) {
                    // The pre-fix version measured wav.length() and meta.length(): primary files only, and
                    // `length()` on a survivor directory reports the entry size, not the retained subtree.
                    chargeRetainedLocked(id, ready = false)
                }
                dropCounterOnly(TerminalReason.INVALID_ON_RECOVERY)
            } else {
                val confirmed = confirmDurableLocked(id)
                // ONE charge for every validated entry, assigned BEFORE branching on confirmation.
                // Charging only the confirmed branch left a gap: a fresh store has an empty `self` set,
                // so its unconfirmable on-disk entries were never charged anywhere and new work was
                // admitted over them. Ownership does not depend on being able to hand the entry back.
                synchronized(lock) {
                    ownedCharges[id] = OwnedCharge(
                        entry.audioBytes,
                        entry.metadataBytes,
                        ready = confirmed,
                    )
                }
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
                val wavEntry = File(dir, "$id$SUFFIX_WAV")
                val metaEntry = File(dir, "$id$SUFFIX_META")
                if (id in uncertainIds) {
                    // Already charged once above; keep it uncertain. Nothing is added here.
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
                    ownedCharges.getOrPut(id) { OwnedCharge(it.audioBytes, it.metadataBytes, ready = false) }
                        .ready = true
                } ?: run {
                    preparedIds.remove(id)
                    File(dir, "$id$SUFFIX_READY").delete()
                }
            }
            val boundaryHeld = mutableListOf<String>()
            keep.forEach {
                // Readiness on the SINGLE existing charge; a missing charge is created once, never added to.
                val charge = ownedCharges.getOrPut(it.captureId) {
                    OwnedCharge(it.audioBytes, it.metadataBytes, ready = false)
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
        coverageEstablished = true
        // If marker discovery failed, the hold is set: recovery must not hand its caller ready entries
        // either - readyEntries() withholding them is not enough when the caller consumes this list
        // directly (reviewer f4436ced).
        if (markerCoverageUnknown) return emptyList()
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
            // The attempts sidecar is STRICTLY the count: the retry boundary lives in its own .retry
            // record with its own reconciliation. Any multi-token or malformed sidecar fails the entry
            // closed - never a silent default (reviewers e4820963, 7433e6a9).
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
    // STRUCTURAL validation is not durability confirmation. An entry may have been left by a publication
    // whose final sync failed (the uncertain outcome), so its files could be present yet not durable.
    // Before exposing it as ready, re-establish and confirm the SAME barriers publication relies on:
    //  - every content file, INCLUDING the ready marker (marker-content durability is an explicit
    //    uncertain case at publication time, so a reader must not be more trusting than the writer);
    //  - the queue directory that names them;
    //  - the queue directory's PARENT namespace, which is what makes the directory reachable at all.
    // Reviewed 805f0828: reloadUnownedFromDisk used to promote marker+validate directly, a SECOND and
    // weaker load path than recovery. Both now call this one transition, so they cannot diverge.
    private fun confirmDurableLocked(id: String): Boolean = runCatching {
        listOf(SUFFIX_WAV, SUFFIX_META, SUFFIX_DIGEST, SUFFIX_ATTEMPTS, SUFFIX_CREATED, SUFFIX_READY, SUFFIX_RETRY, SUFFIX_ATTEMPTS + ".tmp", SUFFIX_RETRY + ".tmp")
            .map { File(dir, "$id$it") }
            .filter { it.isFile }
            .forEach { fileSync.sync(it) }
        syncDirectoryOrFail(dir)
        dir.parentFile?.let { parent -> syncDirectoryOrFail(parent) }
    }.isSuccess

    // THE single owner transition. Every path that makes a capture owned, ready, or gone runs through
    // here, so a caller cannot silently omit the accounting step (reviewer 4537e0fe). Three earlier
    // rounds each found a different caller that had: a fresh store's withheld entries, expiry/deletion,
    // and disk reload. One transition, one place to be correct.
    private fun claimOwnedLocked(captureId: String, audioBytes: Long, metadataBytes: Int, ready: Boolean) {
        val existing = ownedCharges[captureId]
        if (existing == null) {
            ownedCharges[captureId] = OwnedCharge(audioBytes, metadataBytes, ready)
        } else {
            // NEVER MOVE THE CHARGE DOWN, and never keep first-seen sizes as authoritative. The debris
            // sweep visits files individually, so claiming the WAV first (metadata 0) and the metadata
            // later used to leave its size uncounted forever. Raising to the larger observation keeps
            // the bound conservative; a caller that knows the true footprint passes it directly and
            // replaces this anyway.
            existing.audioBytes = maxOf(existing.audioBytes, audioBytes)
            existing.metadataBytes = maxOf(existing.metadataBytes, metadataBytes)
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
                markerCoverageUnknown = false
                return
            } catch (failure: Exception) {
                // ANY other stat failure - IOException, SecurityException, a runtime failure thrown by the
                // filesystem layer - is neither absence nor coverage: hold, count, defer. Normalized like
                // the directory iterator rather than escaping uncaught (reviewer 6cbb4efa).
                markerCoverageUnknown = true
                measurementFailures++
                return
            }
            if (!attributes.isDirectory) {
                // A file where the marker directory belongs is corrupted state, not an empty set: hold,
                // refuse to act on it, and record the degradation.
                markerCoverageUnknown = true
                measurementFailures++
                return
            }
            val ids = try {
                boundedDirectoryListing(markerDir).map { it.name }
            } catch (unknown: MeasurementError) {
                // FAILURE IS NOT ABSENCE: the retry is deferred - no marker is acted on from partial
                // knowledge, every recorded intent stays on disk and is retried by a later sweep - and
                // per spec 896632f4 the unknown coverage holds admission AND serving/attempt until a
                // later sweep enumerates fully and reconciles the intents.
                markerCoverageUnknown = true
                measurementFailures++
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
                    listOf(SUFFIX_WAV, SUFFIX_META, SUFFIX_READY, SUFFIX_ATTEMPTS, SUFFIX_CREATED, SUFFIX_DIGEST, SUFFIX_RETRY, SUFFIX_ATTEMPTS + ".tmp", SUFFIX_RETRY + ".tmp"),
                )
                if (gone) {
                    disqualificationMarker(id).delete()
                    ownedCharges.remove(id)
                    // A CONFIRMED removal clears the unknown state too: the capture is gone, so it no
                    // longer holds accounting admission must refuse over (reviewer c66fda06).
                    unmeasuredIds -= id
                } else {
                    chargeRetainedLocked(id, ready = false)
                }
            }
            // Bounded marker discovery succeeded and every enumerated intent was reconciled - the pass
            // ran to completion, so the hold lifts (spec 896632f4).
            markerCoverageUnknown = false
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
    private fun deleteLocked(e: Entry) {
        val id = e.captureId
        // 1) Durable intent FIRST, and destruction does NOT proceed without it. If the marker cannot be
        //    confirmed, deleting the files would destroy the only evidence that this capture was meant to
        //    go, so a crash could resurrect a half-deleted entry as valid. Retain and retry instead.
        if (!writeDisqualificationLocked(id)) {
            memoryEntries.remove(id)
            chargeRetainedLocked(id, ready = false)
            return
        }
        // 2) Attempt every delete, regardless of earlier failures.
        val gone = deleteAll(
            id,
            listOf(SUFFIX_WAV, SUFFIX_META, SUFFIX_READY, SUFFIX_ATTEMPTS, SUFFIX_CREATED, SUFFIX_DIGEST, SUFFIX_RETRY, SUFFIX_ATTEMPTS + ".tmp", SUFFIX_RETRY + ".tmp"),
        )
        memoryEntries.remove(id)
        if (gone) {
            // Only now is the disposal itself complete; the marker goes last so intent survives a crash.
            disqualificationMarker(id).delete()
            ownedCharges.remove(id)
            unmeasuredIds -= id
        } else {
            // Residual bytes stay OWNED and CHARGED; the capture is not ready, and a later cleanup retries.
            chargeRetainedLocked(id, ready = false)
        }
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
    // [optional] says whether a genuinely missing path means zero. Only a TOP-LEVEL artifact the capture
    // may simply never have written is optional; a child that was already enumerated by its parent is not,
    // because it was seen and then could not be read.
    private fun measuredPathBytes(
        path: File,
        budget: MeasurementBudget,
        depth: Int = 0,
        optional: Boolean = true,
    ): Long {
        val attributes = pathAttributes(path, optional)
            ?: return 0L // genuinely absent, and absence is allowed to mean zero here
        return when {
            attributes.isRegularFile -> attributes.size()

            // Depth is threaded through: passing 0 on every call made the bound unreachable.
            attributes.isDirectory -> measuredDirectoryBytes(path, depth = depth, budget = budget)

            // Symlinks and devices are rejected, not followed: following them would let one capture's
            // apparent footprint point anywhere on the filesystem.
            else -> throw MeasurementError("unsupported entry type: $path")
        }
    }

    // no-follow stat. Missing is reported as null ONLY when the caller says the path is optional; every
    // other failure (permissions, I/O, a dangling symlink) is MeasurementError, i.e. unknown accounting.
    private fun pathAttributes(path: File, optional: Boolean): java.nio.file.attribute.BasicFileAttributes? = try {
        java.nio.file.Files.readAttributes(
            path.toPath(),
            java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS,
        )
    } catch (missing: java.nio.file.NoSuchFileException) {
        if (optional) {
            null
        } else {
            // The parent listed this name and it is gone now: that is a failure to account, not an absence.
            throw MeasurementError("enumerated child vanished: $path")
        }
    } catch (failure: java.io.IOException) {
        throw MeasurementError("unreadable path $path: ${failure.message}")
    }

    private fun measuredDirectoryBytes(directory: File, depth: Int, budget: MeasurementBudget): Long {
        if (depth >= MEASURE_MAX_DEPTH) throw MeasurementError("subtree deeper than $MEASURE_MAX_DEPTH")
        // A real lazy stream. `list()` and `listFiles()` both allocate the WHOLE directory eagerly, so a
        // budget checked after either has already paid the cost the bound exists to prevent.
        // The WHOLE lifetime is wrapped, not just the open: iteration can throw
        // DirectoryIteratorException and close can throw IOException, and either would escape as a raw
        // runtime/IO failure instead of becoming unknown accounting (reviewer e4c9a80d).
        var total = 0L
        var yielded = 0
        var yieldedCount = 0
        try {
            java.nio.file.Files.newDirectoryStream(directory.toPath()).use { stream ->
                for (child in stream) {
                    if (!budget.consume()) throw MeasurementError("traversal exceeds $MEASURE_MAX_ENTRIES entries")
                    // Injected AFTER a child was yielded: proves the failure is normalized mid-iteration.
                    hooks.onMeasurementEntry?.invoke(yielded++)
                    yieldedCount = yielded
                    total = checkedAdd(
                        total,
                        measuredPathBytes(
                            child.toFile(),
                            budget,
                            depth + 1,
                            optional = false, // enumerated: it existed a moment ago
                        ),
                    )
                }
            }
        } catch (unknown: MeasurementError) {
            throw unknown
        } catch (failure: java.io.IOException) {
            // Covers open, per-entry iteration and close, including DirectoryIteratorException.
            throw MeasurementError("unreadable directory $directory: ${failure.message}")
        } catch (failure: RuntimeException) {
            // Anything else raised while iterating or closing - including an injected fault after a child
            // was yielded - normalizes to unknown accounting rather than escaping as a raw failure.
            throw MeasurementError("measurement failed after $yieldedCount entries in $directory: ${failure.message}")
        }
        return total
    }

    // Checked accumulation: a wrapped sum is a SMALLER charge, i.e. an understated one. Refuse instead.
    private fun checkedAdd(left: Long, right: Long): Long = try {
        Math.addExact(left, right)
    } catch (overflow: ArithmeticException) {
        throw MeasurementError("footprint overflows: $left + $right")
    }

    // ONE shared budget for a whole measurement, so the bound is on the traversal and not on each call.
    private class MeasurementBudget(private var remaining: Int) {
        fun consume(): Boolean = --remaining >= 0
    }

    private fun retainedFootprintLocked(id: String): Pair<Long, Int> {
        // Deliberately not wrapped in runCatching: a failure must reach chargeRetainedLocked, which marks
        // the capture unmeasured and refuses admission. Substituting a guess here would hide the failure.
        val budget = MeasurementBudget(MEASURE_MAX_ENTRIES)
        // Top-level artifacts: a capture may legitimately never have written any one of them, so absence
        // is a real zero here. Everything below them is enumerated and must account for itself.
        val audio = measuredPathBytes(File(dir, "$id$SUFFIX_WAV"), budget, optional = true)
        var rest = 0L
        for (suffix in SIDECAR_SUFFIXES) {
            rest = checkedAdd(rest, measuredPathBytes(File(dir, "$id$suffix"), budget, optional = true))
        }
        rest = checkedAdd(rest, measuredPathBytes(disqualificationMarker(id), budget, optional = true))
        // No saturating clamp. `coerceIn(0, Int.MAX_VALUE)` made an oversized residue look SMALLER than it
        // is - an understated charge is worse than a refused one (reviewer e4c9a80d). A rest figure that
        // does not fit the metadata channel is unrepresentable, so it becomes unknown accounting.
        if (rest < 0L || rest > Int.MAX_VALUE.toLong()) {
            throw MeasurementError("sidecar footprint unrepresentable: $rest")
        }
        return audio to rest.toInt()
    }

    // Captures whose retained footprint could not be measured. While ANY exist the true owner usage is
    // UNKNOWN, so admission is refused: no per-capture worst case bounds arbitrary retained debris, and a
    // capture limit that happens to be small is not a queue-wide guarantee (reviewer e6eeabda, 7340fe2d).
    private val unmeasuredIds = mutableSetOf<String>()

    private fun unknownFootprintLocked(): Boolean = unmeasuredIds.isNotEmpty() || rootEnumerationUnknown || markerCoverageUnknown

    fun unknownFootprintForTest(): Boolean = synchronized(lock) { unknownFootprintLocked() }

    fun rootUnknownForTest(): Boolean = synchronized(lock) { rootEnumerationUnknown }

    fun unknownBreakdownForTest(): String = synchronized(lock) {
        "unmeasured=$unmeasuredIds rootUnknown=$rootEnumerationUnknown markerUnknown=$markerCoverageUnknown"
    }

    // How many charges had to fall back to the placeholder. Exposed for tests and diagnostics.
    var measurementFailures: Long = 0L
        private set

    // The ONE production charge transition. Every caller that owns retained bytes goes through here, so no
    // entry path can charge a narrower footprint than another (reviewer cb212295 finding 3).
    //
    // On a measurement failure the capture is recorded as UNMEASURED and charged the whole queue budget as
    // a placeholder, but the placeholder is not what protects the queue - the refusal in roomLocked is.
    // The capture is retained and NOT served, and each sweep retries the measurement, which is the only
    // way the unknown state clears.
    private fun chargeRetainedLocked(id: String, ready: Boolean) {
        val measurement = try {
            retainedFootprintLocked(id).also { unmeasuredIds -= id }
        } catch (failure: MeasurementError) {
            measurementFailures++
            unmeasuredIds += id
            // A SMALL placeholder, not the whole queue: the unknown state above is what refuses admission,
            // and a giant placeholder would ALSO trip the byte check, hiding whether the refusal rule works
            // at all (it masked the mutant in testing). The placeholder only exists so this capture is not
            // reported as zero bytes; it is not the mechanism that protects the queue.
            0L to 0
        }
        // An unmeasured capture is retained, never served: readiness is only ever set from a measurement
        // that succeeded.
        claimOwnedLocked(id, measurement.first, measurement.second, ready && id !in unmeasuredIds)
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
        val files = try {
            boundedRootListing()
        } catch (unknown: MeasurementError) {
            // Adopting from a partially-known listing could resurrect entries a full listing would have
            // swept, or miss bytes it would have charged. Unknown accounting refuses admission instead.
            rootEnumerationUnknown = true
            measurementFailures++
            return
        }
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

    /** One owner's charge for one immutable capture. Readiness is state, not identity. */
    private class OwnedCharge(
        var audioBytes: Long,
        var metadataBytes: Int,
        // True once the capture may be handed back as ready; false while it is uncertain.
        var ready: Boolean,
    ) {
        val bytes: Long get() = audioBytes + metadataBytes
    }
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

    // Test seam: the ownership monitor, so a control can prove FILE WORK is serialized on it and not
    // merely the accounting mutations.
    fun ownerLockForTest(): Any = lock

    // Test seam: total bytes currently charged to this owner.
    fun ownedBytesForTest(): Long = synchronized(lock) { ownerBytesLocked() }

    fun ownedIdsForTest(): Set<String> = synchronized(lock) { ownedCharges.keys.toSet() }

    // Lock-free on purpose: the held-monitor control needs to observe the charge while holding the monitor.
    fun ownedChargesSnapshotForTest(): Map<String, Long> = ownedCharges.mapValues { it.value.audioBytes }

    // Test seam: the exact bytes currently charged for a capture, so a control can assert the MEASURED
    // charge directly instead of inferring it through an admission probe whose budget has to straddle an
    // (often tiny) sidecar band.
    fun chargedBytesForTest(captureId: String): Long = synchronized(lock) { ownedCharges[captureId]?.bytes ?: 0L }

    // Separate barrier for the DURABLE DISPOSAL INTENT record. Production uses the same real fsync; it is
    // a distinct injection point so a fault aimed at the queue cannot silently disable intent recording,
    // and so intent recording can itself be faulted on its own.
    // Defaults to the REAL barrier, NOT to `directorySync`. A fault aimed at the queue must not be able to
    // make disposal intent unrecordable: intent is most needed exactly when the queue is unhealthy. Tests
    // that want to fault intent recording pass the override explicitly.
    private val intentDirectorySync: DirectorySync = intentDirectorySyncOverride ?: DirectorySync.REAL

    companion object {
        private const val DIR_NAME = "contributions"
        private const val COUNTERS_NAME = "terminal-counters.txt"
        private const val SUFFIX_WAV = ".recording.wav"
        private const val SUFFIX_META = ".metadata.json"
        private const val SUFFIX_READY = ".ready"
        private const val SUFFIX_PART = ".wav.part"

        /** Cap for reading a published metadata file back; the recorded size must fit under it. */
        private const val MAX_METADATA_READ_BYTES = 1L shl 20
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
        private const val SUFFIX_DIGEST = ".sha256"

        // Everything a capture can leave on disk besides its primary audio and metadata. Kept as a fixed
        // list so measurement is bounded; a new artifact must be added here or it goes unaccounted.
        // Measurement bounds. Hitting either is an ERROR, never a silent truncation.
        private const val MEASURE_MAX_DEPTH = 4
        private const val MEASURE_MAX_ENTRIES = 512
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
