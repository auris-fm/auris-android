package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import java.util.ArrayDeque
import java.util.UUID

/**
 * Why collection authority was lost. The three loss categories are DISTINCT by construction:
 * [FullRevocation] purges ALL collection PCM immediately; [ListeningClosure] discards only the
 * CURRENT partial and never resumes capture to complete an example; [Deferral] retains ready
 * entries untouched (a scheduling signal, not a deletion).
 */
sealed interface LossReason {
    val cause: String

    /** Withdrawal, policy-disable, lease expiry or account change: purge everything. */
    data class FullRevocation(override val cause: String) : LossReason

    /** Listening/permission closure: current partial dies, completed contributions survive. */
    data class ListeningClosure(override val cause: String) : LossReason

    /** Network loss or budget deferral: nothing is deleted; ready entries wait. */
    data class Deferral(override val cause: String) : LossReason
}

/** One terminal outcome of a capture, joined BY THE FROZEN CAPTURE ID — never guessed. */
sealed interface CollectionOutcome {
    val captureId: UUID

    /** Ordinary pipeline kept no contribution for this capture (bleed/drop/wake-only/no text). */
    data class NotCollected(override val captureId: UUID, val reason: String) : CollectionOutcome

    /** ASR produced a transcript for this capture; language is the ACTUAL detected value or null. */
    data class Recognized(
        override val captureId: UUID,
        val transcript: String,
        val detectedLanguage: String?,
    ) : CollectionOutcome

    /** Routing finished with an allowlisted category; unauthorized fields are excluded. */
    data class Routed(
        override val captureId: UUID,
        val intentCategory: String,
        val authorizedArgumentFields: Map<String, String>,
    ) : CollectionOutcome

    /**
     * Execution RESULT OBSERVED at the dispatch seam. This is NOT a success label and must never
     * become training ground truth on its own (spec: machine prediction, execution result and
     * explicit user correction are separate); until real execution evidence exists, consumers
     * must treat it as UNVERIFIED.
     */
    data class Executed(
        override val captureId: UUID,
        val dispatchedWithoutThrowing: Boolean,
    ) : CollectionOutcome
}

/** Retained candidate: owns a bounded PCM prefix and later stage outcomes joined by capture id. */
class RetainedCandidate internal constructor(val context: CaptureContext) {
    var pcm: ShortArray? = null
    var sampleRateHz: Int = 16000
    var wakeState: SegmentObservation.WakeState = SegmentObservation.WakeState.NotDetected
    var outcome: CollectionOutcome? = null

    /**
     * EVERY joined outcome in join order (reviewer 54635caa): stage provenance reads the
     * Recognized/Routed joins from THIS history, not from [outcome], which the terminal
     * Executed join overwrites. Terminal joins are still refused after Executed/NotCollected.
     */
    val stageOutcomes = mutableListOf<CollectionOutcome>()
    var incrementalSamplesAccounted: Long = 0L
    var bytesCharged: Long = 0L
}

/**
 * Retention owner between the observer and the future ContributionStore. Incremental deltas
 * APPEND REAL SAMPLES into a bounded prefix under aggregate budget control (actual retention,
 * not counters): reservation happens BEFORE allocation, growth re-reserves before each append,
 * cancellation/purge releases, completion transfers the prefix charge without double-counting.
 * The three loss categories stay distinct: revocation purges all charges; listening closure ends
 * only the open prefix; deferral changes nothing.
 */
class ContributionRetentionSink(
    private val budget: CollectionBudget,
    private val maxBytesPerClip: Long = SegmentObserver.DEFAULT_MAX_RETAINED_BYTES,
) : SegmentSink,
    RevocationAware {

    /** Invoked when the sink ends retention for a capture (budget/cap refusal), post-terminal. */
    var onRetentionEnded: ((CaptureContext) -> Unit)? = null

    /**
     * THE GUARDED PUBLISH HOOK (task73): invoked ONCE outside the sink lock when a retained
     * capture records its TERMINAL Executed outcome with retained PCM. The engine's execution
     * join is the only signal that reaches publication; Routed/Recognized-only captures never
     * fire it. The receiver owns the candidate snapshot (re-reads [RetainedCandidate.pcm],
     * finishes with [releaseRetained]); invocations are dispatched, never run under `lock`.
     */
    var onReadyForPublication: ((RetainedCandidate) -> Unit)? = null

    private val lock = Any()
    private var openPrefix: OpenPrefix? = null
    private val candidates = ArrayDeque<RetainedCandidate>()
    private val _lossEvents = mutableListOf<String>()

    private class OpenPrefix(val context: CaptureContext, val buffer: MutableList<ShortArray>) {
        var chargedBytes: Long = 0L

        /** Valid length per ADOPTED array (arrays may carry unused reserved capacity at the tail;
         * readers must slice to this length — reviewer 79b2b5c9 b1/b3: one array, FULL-CAPACITY
         * charge until dropped, valid range separate from size). */
        val validLengths = java.util.IdentityHashMap<ShortArray, Int>()

        fun samples(): Long = buffer.sumOf { (validLengths[it] ?: it.size).toLong() }
    }

    /** Content-free audit trail of loss decisions (cause names only, never audio/text). */
    val lossEvents: List<String> get() = synchronized(lock) { _lossEvents.toList() }

    /**
     * Opens a bounded prefix ONLY when the first-chunk reservation succeeds; a refused budget
     * means zero PCM allocated for this capture.
     */
    // Opens the owned prefix. The reviewer requires RESERVE ACTUAL BYTES BEFORE ALLOCATION:
    // the charge here is exactly the first chunk's bytes, and the caller hands that array over
    // to the sink (adopted, no copy) — so the reserved amount equals the allocated amount.
    override fun started(observation: SegmentObservation.Started): Boolean {
        val actualBytes = observation.firstChunk.size.toLong() * BYTES_PER_SAMPLE
        if (actualBytes > maxBytesPerClip || !budget.reserve(actualBytes)) return false
        synchronized(lock) {
            // ADOPT the production array: ownership transfers, zero extra allocation.
            openPrefix = OpenPrefix(observation.context, mutableListOf(observation.firstChunk)).apply {
                chargedBytes = actualBytes
                validLengths[observation.firstChunk] = observation.firstChunk.size
            }
        }
        return true
    }

    /** ADMIT reserves exact producer bytes BEFORE any native copy-out; refusal allocates nothing. */
    override fun admit(observation: SegmentObservation.Admission): ReservationToken? {
        val bytes = observation.producerSamples.toLong() * BYTES_PER_SAMPLE
        // Empty prefix (size 0): still admitted — nothing allocated, nothing charged; the
        // capture retains from deltas onward under the same ownership.
        if (bytes > maxBytesPerClip || !budget.reserve(bytes)) return null
        val token = ReservationToken(observation.context.captureId, nextReservationId(), bytes)
        synchronized(lock) {
            tokens[token.id] = token
            leases.getOrPut(token.captureId) { mutableListOf() }.add(ReservationLease(token.id, bytes))
        }
        return token
    }

    /** Completes an admitted start: THIS token's used range transfers in with NO new charge. */
    override fun hasOpenPrefix(context: CaptureContext): Boolean = synchronized(lock) {
        openPrefix?.context == context
    }

    override fun adopted(observation: SegmentObservation.Started, token: ReservationToken?): Boolean {
        if (token == null) return false
        val capacityBytes = observation.firstChunk.size.toLong() * BYTES_PER_SAMPLE
        // Reviewer 79b2b5c9 b1: the sink adopts the WHOLE ARRAY, so its full CAPACITY stays
        // charged while it is live — releasing "unused" bytes here would under-account memory the
        // prefix still pins. Valid audio = observation.validSamples (b3), stored separately.
        // Growth beyond the admitted reservation must be charged to close the gap; refusal ends
        // retention explicitly rather than exceed the cap.
        if (capacityBytes > token.bytes) {
            val extra = capacityBytes - token.bytes
            if (!budget.reserve(extra)) {
                endPrefixWithTerminal(OpenPrefix(observation.context, mutableListOf()).apply { chargedBytes = token.bytes }, "adoption-over-cap")
                dropTokenLocked(token)
                return false
            }
            token.bytes = capacityBytes
        }
        val validBytes = observation.validSamples.toLong() * BYTES_PER_SAMPLE
        synchronized(lock) {
            // Transfer ownership atomically: the token's FULL-CAPACITY charge moves into the
            // prefix; its lease disappears so no later release can double-return it.
            dropLeaseLocked(token.captureId, token.id)
            tokens.remove(token.id)
            openPrefix = OpenPrefix(observation.context, mutableListOf(observation.firstChunk)).apply {
                chargedBytes = token.bytes
                validLengths[observation.firstChunk] = observation.validSamples
            }
            // The drained-valid shortfall vs capacity remains charged WITH the array; when the
            // capture completes or purges, release uses chargedBytes (capacity), never valid audio.
            check(validBytes <= token.bytes) { "valid range exceeds charged capacity" }
        }
        return true
    }

    private fun dropTokenLocked(token: ReservationToken) {
        synchronized(lock) {
            if (token.bytes > 0) budget.release(token.bytes)
            token.bytes = 0L
            dropLeaseLocked(token.captureId, token.id)
            tokens.remove(token.id)
        }
    }

    /** Producer-drop path for an initial allocation that never transferred: releases THAT token. */
    override fun releaseInitial(context: CaptureContext, token: ReservationToken?) {
        if (token == null) return
        synchronized(lock) {
            val live = tokens[token.id] ?: return
            if (live.bytes > 0) budget.release(live.bytes)
            live.bytes = 0L
            dropLeaseLocked(live.captureId, live.id)
            tokens.remove(live.id)
        }
    }

    /** Appends REAL samples: reserve the delta BEFORE any copy; enforce per-clip + aggregate caps. */
    override fun incremental(observation: SegmentObservation.Incremental, samples: ShortArray) {
        if (samples.isEmpty()) return
        val needed = samples.size.toLong() * BYTES_PER_SAMPLE
        val prefix = synchronized(lock) { openPrefix?.takeIf { it.context == observation.context } } ?: return
        // Aggregate reservation FIRST — on failure nothing is copied and retention ends.
        if (!budget.reserve(needed)) {
            endPrefixWithTerminal(prefix, "budget-exhausted")
            return
        }
        var ended = false
        synchronized(lock) {
            val still = openPrefix?.takeIf { it.context == observation.context }
                ?: run {
                    budget.release(needed)
                    return
                }
            if (still.chargedBytes + needed > maxBytesPerClip) {
                budget.release(needed)
                ended = true
            } else {
                // Owned chunk: a bounded COPY under reservation (the caller's array stays theirs).
                still.buffer.add(samples.copyOf())
                still.chargedBytes += needed
            }
        }
        if (ended) endPrefixWithTerminal(prefix, "retention-cap")
    }

    // --- Reserve-before-copy delta protocol (blocker b3): reservation charges the budget but
    // allocates NOTHING; commit transfers the caller's drained array under the SAME charge;
    // release returns the charge when the copy-out failed. Growth beyond the per-clip cap ends
    // THIS capture with exactly one terminal and releases everything it owned. ---

    /**
     * Outstanding reservation leases (reviewer 8273382e b4): each lease is the budget charge for
     * an array the PRODUCER currently holds between reserve and commit/release. A revocation may
     * MARK a lease cancelled, but MUST NOT release its charge while the producer still holds the
     * allocated array — inflight live bytes stay charged until the producer's finally-path calls
     * releaseLease()/commit transfer ownership. Finished buffers purge immediately; leases do not.
     */
    private val leases = mutableMapOf<UUID, MutableList<ReservationLease>>()

    /** Total bytes still covered by outstanding (producer-held) leases for a capture. */
    private fun outstandingFor(captureId: UUID): Long = leases[captureId]?.sumOf { it.bytes } ?: 0L

    private class ReservationLease(val tokenId: Long, val bytes: Long, var cancelled: Boolean = false)

    /** Live reservation tokens by id (per-allocation; reviewer 23cae70e b3). */
    private val tokens = mutableMapOf<Long, ReservationToken>()
    private var reservationSeq = 0L
    private fun nextReservationId(): Long = synchronized(lock) { ++reservationSeq }

    override fun reserveDelta(context: CaptureContext, samples: Int): ReservationToken? {
        val bytes = samples.toLong() * BYTES_PER_SAMPLE
        val prefix = synchronized(lock) { openPrefix?.takeIf { it.context == context } } ?: return null
        // Per-clip cap accounts for THIS capture's outstanding allocations only (no global
        // pendingDeltaBytes assumption — reviewer 23cae70e b3).
        if (prefix.chargedBytes + outstandingFor(context.captureId) + bytes > maxBytesPerClip) return null
        if (!budget.reserve(bytes)) {
            endPrefixWithTerminal(prefix, "budget-exhausted")
            return null
        }
        val token = ReservationToken(context.captureId, nextReservationId(), bytes)
        synchronized(lock) {
            tokens[token.id] = token
            leases.getOrPut(context.captureId) { mutableListOf() }.add(ReservationLease(token.id, bytes))
        }
        return token
    }

    /** Returns true when the delta was adopted; false when refused (revoked or unknown token). */
    override fun commitDelta(context: CaptureContext, drained: ShortArray, copied: Int, token: ReservationToken?): Boolean {
        if (token == null || token.captureId != context.captureId) return false
        return synchronized(lock) {
            // Token validity FIRST: an already-consumed/released token commits NOTHING.
            val live = tokens[token.id] ?: return@synchronized false
            // Stale-commit guard: a lease marked cancelled by revocation may NOT transfer into
            // retained ownership. The producer still holds the array; its charge leaves ONLY via
            // releaseDelta when the producer drops it (reviewer 8273382e b4).
            val cancelled = leases[context.captureId]?.any { it.tokenId == token.id && it.cancelled } == true
            val prefix = openPrefix?.takeIf { it.context == context && !cancelled } ?: return@synchronized false
            // VALIDATE FIRST (reviewer b81c61df b2): the valid range must fit the array, and the
            // WHOLE capacity charge (token's own + any growth gap) must be secured BEFORE the
            // prefix is mutated. On refusal NOTHING is appended — the producer still owns the
            // array and its token charge leaves only via releaseDelta.
            if (copied < 0 || copied > drained.size) return@synchronized false
            val capacityBytes = drained.size.toLong() * BYTES_PER_SAMPLE
            if (capacityBytes > live.bytes) {
                // Caller handed a larger array than reserved: secure the gap or refuse cleanly.
                val extra = capacityBytes - live.bytes
                if (!budget.reserve(extra)) return@synchronized false
            }
            // Per-clip aggregate cap: prefix total after transfer must stay within the cap.
            if (prefix.chargedBytes + capacityBytes > maxBytesPerClip) {
                if (capacityBytes > live.bytes) budget.release(capacityBytes - live.bytes)
                endPrefixWithTerminal(prefix, "retention-cap")
                return@synchronized false
            }
            // ADOPT the ORIGINAL array and its valid range — no second array, ONE charge for the
            // WHOLE capacity (reviewer 79b2b5c9 b1): the array stays fully charged while live;
            // "unused" tail bytes are pinned memory, not free budget. Valid audio length is
            // stored separately so readers never see zero padding (b3).
            prefix.buffer.add(drained)
            prefix.validLengths[drained] = copied
            live.bytes = capacityBytes
            prefix.chargedBytes += live.bytes
            dropLeaseLocked(context.captureId, token.id)
            live.bytes = 0L
            tokens.remove(token.id)
            true
        }
    }

    override fun releaseDelta(context: CaptureContext, token: ReservationToken?) {
        if (token == null || token.captureId != context.captureId) return
        synchronized(lock) {
            // The producer DROPPED the allocated array: now (and only now) may THAT token's
            // remaining charge leave the budget — exactly once, never another capture's bytes.
            val live = tokens[token.id] ?: return@synchronized
            if (live.bytes > 0) budget.release(live.bytes)
            live.bytes = 0L
            dropLeaseLocked(context.captureId, token.id)
            tokens.remove(token.id)
        }
    }

    private fun dropLeaseLocked(captureId: java.util.UUID, tokenId: Long) {
        val list = leases[captureId] ?: return
        list.removeAll { it.tokenId == tokenId }
        if (list.isEmpty()) leases.remove(captureId)
    }

    /** Revocation marks OUTSTANDING leases cancelled; charges release on producer drop only. */
    fun markLeasesCancelled(captureId: java.util.UUID): Int = synchronized(lock) {
        val list = leases[captureId] ?: return@synchronized 0
        list.forEach { it.cancelled = true }
        list.size
    }

    /** Bytes still charged to producer-held arrays (inflight live audio, not yet dropped). */
    fun outstandingLeaseBytesForTest(): Long = synchronized(lock) { leases.values.sumOf { l -> l.sumOf { it.bytes } } }

    // Completion handoff with PEAK accounting (reviewer b81c61df b1): the FULL flattened output
    // is reserved while every prefix array is still live, so the budget carries both allocations
    // during flattening; the retired prefix charge releases exactly once after transfer. A
    // refused completion CANCELS the capture — the retained prefix releases completely and a
    // terminal is recorded; nothing stays pinned as an open, uncharged buffer. Per-clip cap
    // applies to both paths.
    override fun completed(observation: SegmentObservation.Completed): Boolean {
        val fullSamples = observation.frames.sumOf { it.size }
        val fullBytes = fullSamples.toLong() * BYTES_PER_SAMPLE
        if (fullBytes > maxBytesPerClip) {
            logLoss("refused-handoff:clip-cap")
            // Refusal must not leave the capture retained-open: cancel/release the prefix too.
            synchronized(lock) { openPrefix?.takeIf { it.context == observation.context } }?.let { endPrefixWithTerminal(it, "completion-refused:clip-cap") }
            return false
        }
        val prefix = synchronized(lock) { openPrefix?.takeIf { it.context == observation.context } }
        if (prefix != null) {
            // PEAK accounting (reviewer b81c61df b1): while flattening, the flattened output is a
            // NEW allocation and every retained prefix array is still live. The budget must carry
            // prefix + fullBytes simultaneously, so reserve the FULL output size up front — not
            // merely the delta over the prefix charge.
            if (!budget.reserve(fullBytes)) {
                logLoss("refused-handoff:budget")
                // Refusal CANCELS the capture: the retained prefix releases completely and the
                // producer's frames are dropped; nothing stays pinned as an orphan "open" buffer.
                endPrefixWithTerminal(prefix, "completion-refused")
                return false
            }
            // Copy the production frames into ONE owned buffer under reservation, then transfer.
            val owned = flattenOwned(observation.frames, fullSamples)
            val transferred = synchronized(lock) {
                if (openPrefix === prefix) {
                    val retired = prefix.chargedBytes
                    prefix.buffer.clear()
                    prefix.validLengths.clear()
                    prefix.chargedBytes = fullBytes
                    openPrefix = null
                    // Peak ends: the reserved fullBytes covers the new owned buffer; the retired
                    // prefix charge returns to the budget exactly once.
                    budget.release(retired)
                    true
                } else {
                    false
                }
            }
            if (!transferred) {
                // Prefix was closed concurrently (revoke/cancel already handled its release):
                // undo OUR reservation so no charge is orphaned; retain nothing.
                budget.release(fullBytes)
                return false
            }
            val candidate = RetainedCandidate(observation.context).apply {
                pcm = owned
                sampleRateHz = observation.sampleRateHz
                wakeState = observation.wakeState
                bytesCharged = fullBytes
                incrementalSamplesAccounted = fullSamples.toLong()
            }
            synchronized(lock) { candidates.addLast(candidate) }
            return true
        }
        // No open prefix (already closed by revoke/cancel): plain full-size reservation.
        if (!budget.reserve(fullBytes)) {
            logLoss("refused-handoff:budget")
            return false
        }
        val owned = flattenOwned(observation.frames, fullSamples)
        val candidate = RetainedCandidate(observation.context).apply {
            pcm = owned
            sampleRateHz = observation.sampleRateHz
            wakeState = observation.wakeState
            bytesCharged = fullBytes
        }
        synchronized(lock) { candidates.addLast(candidate) }
        return true
    }

    private fun flattenOwned(frames: List<ShortArray>, total: Int): ShortArray {
        val out = ShortArray(total)
        var offset = 0
        for (f in frames) {
            System.arraycopy(f, 0, out, offset, f.size)
            offset += f.size
        }
        return out
    }

    /** Cancellation releases the open prefix's charge completely — nothing survives it. */
    override fun cancelled(observation: SegmentObservation.Cancelled) {
        val prefix = synchronized(lock) { openPrefix?.takeIf { it.context == observation.context } }
        if (prefix != null) {
            // Producer-held lease bytes stay charged until the producer drops them; everything
            // else in the prefix releases now (reviewer 8273382e b4). Lease charges live in
            // pendingDeltaBytes, NOT in prefix.chargedBytes (commit transfers them), so the whole
            // finished-prefix charge is releasable immediately.
            releasePrefix(prefix)
            synchronized(lock) { if (openPrefix === prefix) openPrefix = null }
        }
        logLoss("partial-discard:${observation.reason}")
    }

    /** FULL REVOCATION: every retained byte (prefix + ready) is released immediately. */
    override fun purgeAllForRevocation(reason: String) {
        synchronized(lock) {
            openPrefix?.let { prefix ->
                // Finished-buffer bytes release immediately on revocation; the separate
                // outstanding-lease charge (pendingDeltaBytes) survives until the producer drops.
                releasePrefix(prefix)
                openPrefix = null
            }
            // Finished/retained buffers purge immediately; OUTSTANDING leases stay charged until
            // the producer's finally path releases them (reviewer 8273382e b4). Mark them so any
            // later commit is refused as cancelled.
            leases.values.flatten().forEach { it.cancelled = true }
            while (candidates.isNotEmpty()) {
                val c = candidates.pollFirst() ?: break
                budget.release(c.bytesCharged)
                c.pcm = null
            }
            _lossEvents.add("purge-all:$reason")
        }
    }

    /** NETWORK/BUDGET DEFERRAL: retained ready entries stay exactly as they are. */
    fun defer(reason: LossReason.Deferral) = logLoss("defer:${reason.cause}")

    /** Join an outcome by the FROZEN capture id; unknown/evicted ids are refused, never guessed. */
    fun joinOutcome(outcome: CollectionOutcome): Boolean {
        var fired: RetainedCandidate? = null
        synchronized(lock) {
            val candidate = candidates.find { it.context.captureId == outcome.captureId } ?: return false
            if (candidate.outcome is CollectionOutcome.Executed || candidate.outcome is CollectionOutcome.NotCollected) {
                return false // terminal outcome already recorded
            }
            candidate.outcome = outcome
            candidate.stageOutcomes.add(outcome)
            fired =
                if (outcome is CollectionOutcome.Executed && candidate.pcm != null) candidate else null
        }
        // HOOK OUTSIDE THE LOCK (dispatched, never re-entrant): receiver re-validates state.
        if (fired != null) onReadyForPublication?.invoke(fired)
        return true
    }

    /**
     * Ends retention for one capture after the publish attempt: releases the retained charge
     * EXACTLY ONCE, drops the PCM, and removes the candidate. Idempotent by capture id.
     */
    fun releaseRetained(captureId: UUID): Boolean {
        synchronized(lock) {
            val removed = candidates.firstOrNull { it.context.captureId == captureId } ?: return false
            candidates.remove(removed)
            budget.release(removed.bytesCharged)
            removed.bytesCharged = 0L
            removed.pcm = null
        }
        return true
    }

    fun retainedCount(): Int = synchronized(lock) { candidates.size }

    /** Test visibility: the live candidate for a retained capture, WITHOUT copying its PCM. */
    fun retainedCandidateForTest(captureId: UUID): RetainedCandidate? = synchronized(lock) { candidates.firstOrNull { it.context.captureId == captureId } }

    /** Test/diagnostic: total bytes currently charged to this sink across all captures. */
    fun totalChargedBytesForTest(): Long = synchronized(lock) {
        candidates.sumOf { it.bytesCharged } + (openPrefix?.chargedBytes ?: 0L) + tokens.values.sumOf { it.bytes }
    }

    fun retainedSnapshot(): List<RetainedCandidate> = synchronized(lock) { candidates.toList() }

    /** Charge currently carried by the OPEN prefix for [context] (0 when none/other). */
    fun prefixBytesChargedForTest(context: CaptureContext): Long = synchronized(lock) {
        openPrefix?.takeIf { it.context == context }?.chargedBytes ?: 0L
    }

    fun retainedBytes(): Long = synchronized(lock) { candidates.sumOf { it.bytesCharged } }

    /** Samples actually copied into the OPEN prefix buffer (proof of real retention). */
    fun prefixSamples(): Long = synchronized(lock) { openPrefix?.samples() ?: 0L }

    /** Bytes currently owned by the OPEN prefix (zero when none). */
    fun openPrefixBytes(): Long = synchronized(lock) { openPrefix?.chargedBytes ?: 0L }

    private fun endPrefixWithTerminal(prefix: OpenPrefix, reason: String) {
        releasePrefix(prefix)
        synchronized(lock) { if (openPrefix === prefix) openPrefix = null }
        logLoss("partial-discard:$reason")
        onRetentionEnded?.invoke(prefix.context)
    }

    private fun releasePrefix(prefix: OpenPrefix) {
        val bytes = prefix.chargedBytes
        prefix.buffer.clear()
        prefix.chargedBytes = 0L
        budget.release(bytes)
    }

    private fun logLoss(event: String) = synchronized(lock) { _lossEvents.add(event) }

    private fun flatten(frames: List<ShortArray>): ShortArray {
        val out = ShortArray(frames.sumOf { it.size })
        var offset = 0
        for (f in frames) {
            System.arraycopy(f, 0, out, offset, f.size)
            offset += f.size
        }
        return out
    }

    companion object {
        private const val BYTES_PER_SAMPLE = 2L
        private const val FIRST_CHUNK_BYTES = 4096L
    }
}
