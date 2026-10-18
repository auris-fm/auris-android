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

    private val lock = Any()
    private var openPrefix: OpenPrefix? = null
    private var pendingAdmissionBytes: Long = 0L
    private val candidates = ArrayDeque<RetainedCandidate>()
    private val _lossEvents = mutableListOf<String>()

    private class OpenPrefix(val context: CaptureContext, val buffer: MutableList<ShortArray>) {
        var chargedBytes: Long = 0L
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
            openPrefix = OpenPrefix(observation.context, mutableListOf(observation.firstChunk))
                .apply { chargedBytes = actualBytes }
        }
        return true
    }

    /** ADMIT reserves exact producer bytes BEFORE any native copy-out; refusal allocates nothing. */
    override fun admit(observation: SegmentObservation.Admission): Boolean {
        val bytes = observation.producerSamples.toLong() * BYTES_PER_SAMPLE
        // Empty prefix (size 0): still admitted — nothing allocated, nothing charged; the
        // capture retains from deltas onward under the same ownership.
        if (bytes > maxBytesPerClip || !budget.reserve(bytes)) return false
        synchronized(lock) { pendingAdmissionBytes = bytes }
        return true
    }

    /** Completes an admitted start: the drained array transfers in with NO new reservation. */
    override fun adopted(observation: SegmentObservation.Started) {
        val reserved = synchronized(lock) {
            val b = pendingAdmissionBytes
            pendingAdmissionBytes = 0L
            b
        }
        val actualBytes = observation.firstChunk.size.toLong() * BYTES_PER_SAMPLE
        // Reconcile exactly: producer shrank between counter-read and drain -> release the excess;
        // grew -> charge the difference too (still bounded by the clip cap).
        when {
            actualBytes < reserved -> budget.release(reserved - actualBytes)

            actualBytes > reserved -> if (!budget.reserve(actualBytes - reserved)) {
                // Over-growth beyond cap: end retention explicitly rather than exceed.
                endPrefixWithTerminal(OpenPrefix(observation.context, mutableListOf()).apply { chargedBytes = reserved }, "adoption-over-cap")
                return
            }
        }
        synchronized(lock) {
            openPrefix = OpenPrefix(observation.context, mutableListOf(observation.firstChunk))
                .apply { chargedBytes = actualBytes }
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

    /**
     * Completion handoff with ATOMIC charge transfer: the closed segment's exact bytes are
     * reserved BEFORE the prefix charge is released, so a failed reservation leaves the prefix
     * fully charged and owned (never an open, uncharged buffer). The per-clip cap applies to
     * BOTH paths. The retained copy happens only after its own reservation succeeds.
     */
    override fun completed(observation: SegmentObservation.Completed): Boolean {
        val fullSamples = observation.frames.sumOf { it.size }
        val fullBytes = fullSamples.toLong() * BYTES_PER_SAMPLE
        if (fullBytes > maxBytesPerClip) {
            logLoss("refused-handoff:clip-cap")
            return false
        }
        val prefix = synchronized(lock) { openPrefix?.takeIf { it.context == observation.context } }
        if (prefix != null) {
            // Reserve the FINAL size first; release the prefix charge only after success.
            val extra = (fullBytes - prefix.chargedBytes).coerceAtLeast(0L)
            if (extra > 0 && !budget.reserve(extra)) {
                logLoss("refused-handoff:budget")
                return false // prefix remains charged & owned; observer closes the capture separately
            }
            // Copy the production frames into ONE owned buffer under reservation, then transfer.
            val owned = flattenOwned(observation.frames, fullSamples)
            synchronized(lock) {
                if (openPrefix === prefix) {
                    prefix.buffer.clear()
                    if (prefix.chargedBytes > fullBytes) budget.release(prefix.chargedBytes - fullBytes)
                    prefix.chargedBytes = fullBytes
                    openPrefix = null
                }
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
        if (prefix != null) releasePrefix(prefix)
        synchronized(lock) { if (openPrefix === prefix) openPrefix = null }
        logLoss("partial-discard:${observation.reason}")
    }

    /** FULL REVOCATION: every retained byte (prefix + ready) is released immediately. */
    override fun purgeAllForRevocation(reason: String) {
        synchronized(lock) {
            openPrefix?.let { releasePrefix(it) }
            openPrefix = null
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
        synchronized(lock) {
            val candidate = candidates.find { it.context.captureId == outcome.captureId } ?: return false
            if (candidate.outcome is CollectionOutcome.Executed || candidate.outcome is CollectionOutcome.NotCollected) {
                return false // terminal outcome already recorded
            }
            candidate.outcome = outcome
            return true
        }
    }

    fun retainedCount(): Int = synchronized(lock) { candidates.size }

    fun retainedSnapshot(): List<RetainedCandidate> = synchronized(lock) { candidates.toList() }

    fun retainedBytes(): Long = synchronized(lock) { candidates.sumOf { it.bytesCharged } }

    /** Samples actually copied into the OPEN prefix buffer (proof of real retention). */
    fun prefixSamples(): Long = synchronized(lock) { openPrefix?.buffer?.sumOf { it.size }?.toLong() ?: 0L }

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
