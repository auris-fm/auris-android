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
    override fun started(observation: SegmentObservation.Started): Boolean {
        if (!budget.reserve(FIRST_CHUNK_BYTES)) return false
        synchronized(lock) {
            openPrefix = OpenPrefix(observation.context, mutableListOf()).apply { chargedBytes = FIRST_CHUNK_BYTES }
        }
        return true
    }

    /** Appends REAL samples: reserve the delta BEFORE copying it in; refuse growth beyond caps. */
    override fun incremental(observation: SegmentObservation.Incremental, samples: ShortArray) {
        if (samples.isEmpty()) return
        val needed = samples.size.toLong() * BYTES_PER_SAMPLE
        val retentionEnders = mutableListOf<OpenPrefix>()
        val prefix = synchronized(lock) { openPrefix?.takeIf { it.context == observation.context } } ?: return
        if (!budget.reserve(needed)) {
            retentionEnders.clear()
            endPrefixWithTerminal(prefix, "budget-exhausted")
            return
        }
        synchronized(lock) {
            val still = openPrefix?.takeIf { it.context == observation.context } ?: run {
                budget.release(needed)
                return
            }
            val clipBytes = still.chargedBytes + needed
            if (clipBytes > maxBytesPerClip) {
                budget.release(needed)
                val released = still.chargedBytes
                still.buffer.clear()
                still.chargedBytes = 0L
                openPrefix = null
                budget.release(released)
                retentionEnders += still
            } else {
                still.buffer.add(samples.copyOf()) // bounded copy UNDER reservation
                still.chargedBytes = clipBytes
                candidates.find { it.context == observation.context && it.pcm == null }
                    ?.let { it.incrementalSamplesAccounted += samples.size }
            }
        }
        if (retentionEnders.isNotEmpty()) logLoss("partial-discard:retention-cap")
        retentionEnders.forEach { it.context.let { c -> onRetentionEnded?.invoke(c) } }
    }

    /** Adopts the PRODUCTION frames when no prefix grew; otherwise finalizes the retained prefix. */
    override fun completed(observation: SegmentObservation.Completed): Boolean {
        val prefix = synchronized(lock) { openPrefix?.takeIf { it.context == observation.context } }
        val candidate = RetainedCandidate(observation.context)
        if (prefix != null) {
            val flat = flatten(prefix.buffer)
            // Prefix already charged; adopt whichever is larger — the VAD's closed segment is
            // authoritative, so release the prefix charge and take the full-segment charge once.
            val fullBytes = observation.pcm.size.toLong() * BYTES_PER_SAMPLE
            // Re-check the aggregate for the FINAL size before adopting: release the prefix
            // reservation first, then reserve exactly the closed clip. Completion never keeps a
            // second copy of bytes the prefix already owned — no double count.
            val prefixCharge = prefix.chargedBytes
            budget.release(prefixCharge)
            prefix.chargedBytes = 0L
            if (!budget.reserve(fullBytes)) {
                logLoss("refused-handoff:budget")
                return false
            }
            candidate.bytesCharged = fullBytes
            candidate.pcm = observation.pcm
            candidate.sampleRateHz = observation.sampleRateHz
            candidate.wakeState = observation.wakeState
            candidate.incrementalSamplesAccounted = flat.size.toLong()
            releasePrefixOnlyBuffer(prefix)
            synchronized(lock) {
                if (openPrefix === prefix) openPrefix = null
                candidates.addLast(candidate)
            }
            return true
        }
        val bytes = observation.pcm.size.toLong() * BYTES_PER_SAMPLE
        if (bytes > maxBytesPerClip || !budget.reserve(bytes)) {
            logLoss(if (bytes > maxBytesPerClip) "refused-handoff:clip-cap" else "refused-handoff:budget")
            return false
        }
        candidate.pcm = observation.pcm
        candidate.sampleRateHz = observation.sampleRateHz
        candidate.wakeState = observation.wakeState
        candidate.bytesCharged = bytes
        synchronized(lock) { candidates.addLast(candidate) }
        return true
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

    private fun releasePrefixOnlyBuffer(prefix: OpenPrefix) {
        // Completion path: the CHARGE transfers to the candidate; only the buffer view is dropped.
        prefix.buffer.clear()
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
