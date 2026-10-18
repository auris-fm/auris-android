package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import java.util.UUID

/**
 * Immutable capture context, FROZEN SYNCHRONOUSLY at the segment START on the pipeline thread
 * (plan Task 3, spec dc4cb5af) and carried unchanged through incremental, completion, cancel
 * and every later outcome. Nothing re-reads mutable pipeline state after the freeze.
 */
data class CaptureContext(
    val captureId: UUID,
    val captureSession: Long,
    val routeClass: String,
)

/**
 * One observed step of the ACTUAL segment path. The observer subscribes to the same
 * [au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceSegmenterResult] stream the wake/bleed
 * pipeline already emits — reuse of the segment source, never a parallel capture — so virtual
 * padding and playback-reference PCM are excluded by construction.
 */
sealed interface SegmentObservation {
    val context: CaptureContext

    /** VAD reported speech onset: the moment the context is frozen. Zero audio retained. */
    data class Started(override val context: CaptureContext) : SegmentObservation

    /** Incremental accounting only; bounded by collector ownership, no buffer growth here. */
    data class Incremental(
        override val context: CaptureContext,
        /** Samples classified as speech since the last accounting point. */
        val samplesSinceLastAccounting: Int,
        /** True only while an owning collector accepts retained audio for this capture. */
        val ownershipAccepted: Boolean,
    ) : SegmentObservation

    /** Wake-word observation state of THIS capture, from the production detector result. */
    enum class WakeState { Detected, NotDetected, DetectorFailed }

    /** VAD closed the segment with its audio; the wake observation rides along. */
    data class Completed(
        override val context: CaptureContext,
        val pcm: ShortArray,
        val sampleRateHz: Int,
        val speechOnsetSample: Int,
        val speechEndSample: Int,
        val wakeState: WakeState,
    ) : SegmentObservation {
        override fun equals(other: Any?): Boolean = this === other || (
            other is Completed && context == other.context &&
                pcm.contentEquals(other.pcm) && sampleRateHz == other.sampleRateHz &&
                speechOnsetSample == other.speechOnsetSample && speechEndSample == other.speechEndSample &&
                wakeState == other.wakeState
            )

        override fun hashCode(): Int = 31 * context.hashCode() + pcm.contentHashCode()
    }

    /** The segment was cancelled/rejected before completion: partials die with it. */
    data class Cancelled(
        override val context: CaptureContext,
        val reason: String,
    ) : SegmentObservation
}

/**
 * The collection gate consulted EXACTLY ONCE per capture, synchronously at the segment START —
 * BEFORE any PCM allocation (plan Task 5's zero-retention boundary asserted here at Task 3):
 * when collection is off, the pipeline allocates nothing for upload purposes.
 */
fun interface CollectionGate {
    fun accepts(captureSession: Long, routeClass: String): Boolean
}

/** Sink for the observations of one capture; implemented by the future queue owner (Task 5). */
interface SegmentSink {
    /** Returns true while the sink retains incremental audio for this capture. */
    fun started(observation: SegmentObservation.Started): Boolean
    fun incremental(observation: SegmentObservation.Incremental)
    fun completed(observation: SegmentObservation.Completed)
    fun cancelled(observation: SegmentObservation.Cancelled)
}

/** Retains nothing: the default sink while collection is unassigned (Task 5 replaces it). */
object NoopSegmentSink : SegmentSink {
    override fun started(observation: SegmentObservation.Started): Boolean = false
    override fun incremental(observation: SegmentObservation.Incremental) = Unit
    override fun completed(observation: SegmentObservation.Completed) = Unit
    override fun cancelled(observation: SegmentObservation.Cancelled) = Unit
}
