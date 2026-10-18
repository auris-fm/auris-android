package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import java.util.UUID

/**
 * Observes the ACTUAL segment path — start / incremental / cancel / completed — from a single
 * serialized pipeline thread (the engine's processing coroutine). The context is frozen
 * SYNCHRONOUSLY at START before any async work, immutable for the whole capture, and every
 * later event — including outcomes that arrive after a cancel or after the capture was already
 * closed — is judged against that frozen context.
 */
class SegmentObserver(
    private val gate: CollectionGate,
    private val sink: SegmentSink,
) {
    /** Frozen context of the capture currently open on this observer; null between captures. */
    private var current: CaptureContext? = null
    private var retainedForCurrent = false

    /** Monotonic capture-session incarnation supplied by the pipeline owner at each START. */
    fun speechStarted(captureSession: Long, routeClass: String): UUID {
        val context = CaptureContext(UUID.randomUUID(), captureSession, routeClass)
        current = context
        // The gate check runs BEFORE anything else could allocate PCM for this capture.
        retainedForCurrent = gate.accepts(captureSession, routeClass)
        if (retainedForCurrent) {
            retainedForCurrent = sink.started(SegmentObservation.Started(context))
        }
        return context.captureId
    }

    fun speechIncremental(samplesSinceLastAccounting: Int) {
        val context = current ?: return
        if (retainedForCurrent) {
            sink.incremental(SegmentObservation.Incremental(context, samplesSinceLastAccounting, true))
        }
    }

    fun wakeCompleted(
        pcm: ShortArray,
        sampleRateHz: Int,
        speechOnsetSample: Int,
        speechEndSample: Int,
        wakeState: SegmentObservation.WakeState,
    ) {
        val context = current ?: return
        if (retainedForCurrent) {
            sink.completed(
                SegmentObservation.Completed(context, pcm, sampleRateHz, speechOnsetSample, speechEndSample, wakeState),
            )
        }
        close()
    }

    fun cancelled(reason: String) {
        val context = current ?: return
        if (retainedForCurrent) {
            sink.cancelled(SegmentObservation.Cancelled(context, reason))
        }
        close()
    }

    /** True when [captureId] still belongs to an open capture on this observer. */
    fun isOpen(captureId: UUID): Boolean = current?.captureId == captureId

    private fun close() {
        current = null
        retainedForCurrent = false
    }
}
