package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import java.util.UUID

/**
 * THE IMMUTABLE PUBLICATION SNAPSHOT (Batch B, reviewer be6bb62e #1): built under the sink
 * lock at the SINGLE seal operation — whether sealed by early completion (Executed/bleed
 * terminal, cause [CAUSE_TERMINAL]) or by the bounded outcome wait (cause
 * [CAUSE_OUTCOME_WAIT_EXPIRED]) — and handed to the REAL publication path. Async publication
 * reads ONLY this; the mutable [RetainedCandidate] is never dispatched, and every later
 * observation/terminal on the capture is refused through the same boundary (sealed flag).
 *
 * The cause keeps the stage schemas honest: a [CAUSE_OUTCOME_WAIT_EXPIRED] capture's
 * unresolved stages are typed by the GENUINE expired wait; an early-completed capture keeps
 * the pre-Batch-B unresolved typing (its wait did not expire — absence there is not an
 * expiry fact).
 */
class SealedCapture(
    val captureId: UUID,
    val context: CaptureContext,
    val pcm: ShortArray,
    val sampleRateHz: Int,
    val wakeState: SegmentObservation.WakeState,
    val outcome: CollectionOutcome?,
    val stageHistory: List<CollectionOutcome>,
    val translation: TranslationProvenance?,
    val executionOutcome: ExecutionOutcome?,
    val allowanceMetadataCap: Int?,
    val cause: String,
) {
    companion object {
        const val CAUSE_TERMINAL = "terminal"
        const val CAUSE_OUTCOME_WAIT_EXPIRED = "outcome_wait_expired"
    }
}
