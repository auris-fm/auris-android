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

    /** Execution finished; success is recorded SEPARATELY from intent correctness. */
    data class Executed(
        override val captureId: UUID,
        val success: Boolean,
    ) : CollectionOutcome
}

/** Retained candidate awaiting record assembly (Task 4) and queue admission (Task 5). */
class RetainedCandidate internal constructor(val context: CaptureContext) {
    var pcm: ShortArray? = null
    var sampleRateHz: Int = 16000
    var wakeState: SegmentObservation.WakeState = SegmentObservation.WakeState.NotDetected
    var outcome: CollectionOutcome? = null
    var incrementalSamplesAccounted: Long = 0L
}

/**
 * Retention owner between the observer and the future ContributionStore. It ADOPTS the
 * production frame list handed at completion (no upload-side copy), enforces the byte cap at
 * retain time, keeps stage outcomes joined by capture ID, and implements the three distinct
 * loss categories. All state lives under one monitor shared in spirit with the observer's
 * lifecycle: revocation clears retained bytes immediately, deferral clears nothing.
 */
class ContributionRetentionSink(private val maxBytesPerClip: Long = SegmentObserver.DEFAULT_MAX_RETAINED_BYTES) :
    SegmentSink,
    RevocationAware {

    private val lock = Any()
    private val candidates = ArrayDeque<RetainedCandidate>()
    private val _lossEvents = mutableListOf<String>()

    /** Content-free audit trail of loss decisions (cause names only, never audio/text). */
    val lossEvents: List<String> get() = synchronized(lock) { _lossEvents.toList() }

    override fun started(observation: SegmentObservation.Started): Boolean = true

    override fun incremental(observation: SegmentObservation.Incremental) {
        synchronized(lock) {
            candidates.find { it.context == observation.context && it.pcm == null }?.let {
                it.incrementalSamplesAccounted += observation.samplesSinceLastAccounting
            }
        }
    }

    /** Adopts the PRODUCTION frames; declines (returns false) if the clip exceeds its cap. */
    override fun completed(observation: SegmentObservation.Completed): Boolean {
        val bytes = observation.pcm.size.toLong() * 2L
        if (bytes > maxBytesPerClip) return false // no adoption, no duplicate copy retained
        synchronized(lock) {
            val candidate = RetainedCandidate(observation.context).apply {
                pcm = observation.pcm // adopt the production array; the observer does not retain it
                sampleRateHz = observation.sampleRateHz
                wakeState = observation.wakeState
            }
            candidates.addLast(candidate)
        }
        return true
    }

    override fun cancelled(observation: SegmentObservation.Cancelled) {
        synchronized(lock) {
            // Only the open (not yet completed) capture had no candidate; cancellation means
            // nothing was adopted. Record the discard decision for the audit trail.
            _lossEvents.add("partial-discard:${observation.reason}")
        }
    }

    /** FULL REVOCATION: purge every retained byte immediately, partial included. */
    override fun purgeAllForRevocation(reason: String) {
        synchronized(lock) {
            while (candidates.isNotEmpty()) {
                candidates.pollFirst()?.let { it.pcm = null }
            }
            _lossEvents.add("purge-all:$reason")
        }
    }

    /** NETWORK/BUDGET DEFERRAL: retained ready entries stay exactly as they are. */
    fun defer(reason: LossReason.Deferral) {
        synchronized(lock) { _lossEvents.add("defer:${reason.cause}") }
    }

    /** LISTENING CLOSURE handled by the observer; completed contributions are NOT touched here. */
    fun noteListeningClosure(reason: LossReason.ListeningClosure) {
        synchronized(lock) { _lossEvents.add("closure-current-only:${reason.cause}") }
    }

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

    fun retainedBytes(): Long = synchronized(lock) { candidates.sumOf { (it.pcm?.size ?: 0) * 2L } }
}
