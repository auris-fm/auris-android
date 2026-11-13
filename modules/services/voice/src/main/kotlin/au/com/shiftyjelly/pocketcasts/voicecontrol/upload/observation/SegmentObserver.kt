package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import java.util.UUID
import timber.log.Timber

/**
 * Observes the ACTUAL segment path — start / incremental / cancel / completed — under ONE
 * serialized lifecycle. Every event (pipeline events AND external revocation/closure signals)
 * is applied synchronously under a single lock, so an external invalidation can never interleave
 * between a pipeline decision and its handoff, and stop-cancel is not "outside" the collect loop:
 * both go through the same lock. The context is frozen SYNCHRONOUSLY at START before any
 * allocation or await; the SAME immutable context value travels to the detector, ASR, routing
 * and execution call sites, which report outcomes joined by issued capture ID.
 *
 * Exactly ONE terminal per open capture is defined and emitted before it exits the observer:
 * Completed (carrying DetectorFailed when the wake detector threw), or Cancelled. Late events
 * for a closed capture are refused — never joined onto a newer capture.
 */
class SegmentObserver(
    private val gate: CollectionGate,
    private val sink: SegmentSink,
    private val authority: () -> AuthorityDecision,
    /** Freezes the ORIGINAL authority at start; NO renewal source — reads current, once. */
    private val issueAuthority: () -> IssuedAuthority = { IssuedAuthority(null, null, null) },
    /**
     * One-transaction completion handoff (owner→observer→sink). Production composes a simple
     * inline handoff that validates the issued authority and completes under this observer; the
     * default refuses EVERY handoff (fail-closed), so an uncomposed observer retains nothing at
     * completion.
     */
    private val handoff: CompletionHandoff = RefuseAllCompletionHandoff,
    /** Test visibility only: observes each ADMITTED context (never used by production wiring). */
    private val onAdmittedForTest: ((CaptureContext) -> Unit)? = null,
) : ObserverHandoffScope {

    // All fields below are guarded by [lock]; external callers and the pipeline thread contend
    // on the same monitor, which serializes the lifecycle without a dispatcher dependency.
    private val lock = Any()
    private var open: CaptureContext? = null
    private var retainedForOpen = false
    private var admissionToken: ReservationToken? = null

    /** The open capture's per-clip ceiling (encoded-WAV bytes incl. 44-byte header). */
    private var openPrepaidClipBytes: Long = Long.MAX_VALUE

    /**
     * THE single retained/reserved-byte authority for the open capture (reviewer 55433b71):
     * sink-side charged bytes INCLUDING outstanding (not yet committed/released) reservations.
     * Every growth decision checks THIS, never an engine-fed sample counter.
     */
    private var openChargedBytes: Long = 0L

    /** Outstanding reservations (token -> reserved bytes) not yet committed or released. */
    private val outstanding = java.util.HashMap<ReservationToken, Long>()

    private var cumulativeAccounted = 0
    private var totalRetainedBytes = 0L

    /** Cap on retained collection audio (per-clip bound of plan Task 5 enforced early). */
    var maxRetainedBytes: Long = DEFAULT_MAX_RETAINED_BYTES

    /**
     * P1 (reviewer 0cd7e39d): the sink owns retained state; the INVOKED backend/router identity
     * is stored beside the candidate (never on the issued context). A backend replaced between
     * the SpeechStarted selection and the actual invocation cannot misattribute: the invocation
     * note is what the record claims.
     */
    fun noteAsrInvocation(
        context: CaptureContext,
        ref: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef,
    ) {
        (sink as? ContributionRetentionSink)?.noteAsrInvocation(context.captureId, ref)
    }

    /** P1: identity of the router ACTUALLY invoked — loading a model is not routing. */
    fun noteRouterInvocation(
        context: CaptureContext,
        ref: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef,
    ) {
        (sink as? ContributionRetentionSink)?.noteRouterInvocation(context.captureId, ref)
    }

    /** Records the ACTUAL translation observation for a retained capture (null = none). */
    fun noteTranslation(
        context: CaptureContext,
        provenance: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.TranslationProvenance,
    ) {
        (sink as? ContributionRetentionSink)?.noteTranslation(context.captureId, provenance)
    }

    /**
     * Reports the EXECUTOR's actual result for a capture (spec audit). Same ownership rule as
     * [noteTranslation]: the sink owns retained state.
     */
    fun noteExecutionOutcome(
        context: CaptureContext,
        outcome: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ExecutionOutcome,
    ) = noteExecutionOutcome(context.captureId, outcome)

    /** Capture-addressed form: the invocation token carries the captureId directly. */
    fun noteExecutionOutcome(
        captureId: java.util.UUID,
        outcome: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ExecutionOutcome,
    ) {
        (sink as? ContributionRetentionSink)?.noteExecutionOutcome(captureId, outcome)
    }

    data class AdmissionHandle(
        val context: CaptureContext,
        val initialToken: ReservationToken?,
        /** The compiled per-clip ceiling — binds every allocation/growth of THIS capture. */
        val selectedMaxClipBytes: Long,
        /** The compiled metadata ceiling — frozen for the capture's persistence stage. */
        val selectedMaxMetadataBytes: Int,
    )

    fun speechStartedAdmitting(
        captureSession: Long,
        routeClass: String,
        producerSize: Int,
        asrModel: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef =
            au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef.None,
        /** Real grace state frozen at segment start (spec phase 2): true = wake word required. */
        wakeRequired: Boolean = true,
    ): AdmissionHandle? {
        // Refusals consume nothing: a refused gate must not reserve sink bytes or allocate an
        // engine buffer.
        val issued = issueAuthority() // read current, once
        val decision = authority() // ONE decision read: the reason comes from the SAME evaluation
        val permitted = gate.accepts(decision) // decision read before the monitor
        if (!permitted) {
            // Name the failing clause, not a generic gate-off (@spec 56184117): the fixed token comes
            // from the decision that was actually evaluated, one read, no second state read.
            val reason = decision.refusalReason?.name?.lowercase()?.replace('_', '-') ?: "unknown"
            Timber.i("voice-capture admission: declined (gate-off reason=%s)", reason)
            return null
        }
        // NO ALLOWANCE STEP HERE (core c74d7e30). Capture used to consult a prepaid allowance token
        // and fail closed when it was absent; that reserve-before-capture rule is removed, and the
        // daily limit is metered server-side at acceptance instead. Admission is now gated only by
        // consent/policy (above) and the compiled per-clip ceiling (below).
        return speechStartedAdmittingLocked(captureSession, routeClass, producerSize, issued, permitted, asrModel, wakeRequired)?.let {
            onAdmittedForTest?.invoke(it)
            Timber.i("voice-capture admission: accepted capture=%s", it.captureId)
            AdmissionHandle(it, admissionToken, ContributionCeilings.MAX_CLIP_BYTES, ContributionCeilings.MAX_METADATA_BYTES)
        }
    }

    private fun speechStartedAdmittingLocked(captureSession: Long, routeClass: String, producerSize: Int, issued: IssuedAuthority, permitted: Boolean, asrModel: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef, wakeRequired: Boolean): CaptureContext? {
        val context = CaptureContext(
            UUID.randomUUID(),
            captureSession,
            routeClass,
            issued,
            asrModel,
            // THE REAL GRACE STATE FROZEN AT SEGMENT START (spec phase 2), supplied by the
            // invocation call site that reads the live grace signal synchronously.
            wakeRequired = wakeRequired,
        )
        // INVALID SIZES refuse before any arithmetic or admission (reviewer fde5349e): a negative
        // producer size would create a negative open charge and REDUCE the sink budget reservation.
        // ZERO stays legal: the empty-prefix admission is a real, retained capture (deltas grow it).
        if (producerSize < 0) {
            Timber.i("voice-capture admission: declined (invalid-producer-size)")
            return null
        }
        // The PER-CLIP CEILING binds BEFORE any allocation. It is a compiled constant now rather
        // than a server-selected bound, but it still binds: spec §"Actual upload usage" keeps
        // "at most 60,000 ms and 1,920,044 WAV bytes" per clip and calls these ceilings "not a
        // reservation or a daily charge". 2 bytes/sample, same accounting the sink uses.
        // 44-byte WAV header INCLUDED: MAX_CLIP_BYTES is an encoded-WAV bound.
        if (producerSize.toLong() * 2L + 44L > ContributionCeilings.MAX_CLIP_BYTES) {
            Timber.i("voice-capture admission: declined (oversized)")
            return null
        }
        return synchronized(lock) {
            if (open != null || !permitted) {
                Timber.i("voice-capture admission: declined (capture-already-open)")
                return@speechStartedAdmittingLocked null
            }
            cumulativeAccounted = 0
            openPrepaidClipBytes = ContributionCeilings.MAX_CLIP_BYTES
            openChargedBytes = producerSize.toLong() * 2L // the sink reserves full capacity at admit
            outstanding.clear()
            // The metadata ceiling is FROZEN into the admission event itself; the sink stamps it
            // into the immutable retained candidate. No side map, no lookup race.
            val token = sink.admit(SegmentObservation.Admission(context, producerSize, ContributionCeilings.MAX_METADATA_BYTES))
            if (token == null) {
                Timber.i("voice-capture admission: declined (sink-refused)")
                return@speechStartedAdmittingLocked null
            }
            admissionToken = token // producer-held initial reservation until adopt/release
            open = context
            // THE CLIP STARTS FROM ZERO: an empty-prefix admission (producerSize == 0 — the
            // engine no longer drains the finished-utterance buffer at start) has no
            // adoptFirstChunk to open the sink prefix, so open an EMPTY one here (the admit token
            // carries the frozen metadata cap), settle the 0-byte token immediately, and mirror
            // retention from the prefix's existence. Without it the engine's delta commits at
            // SpeechEnded would see isRetaining()=false and the capture would fall to the
            // no-prefix completion, which publication refuses fail-closed (no frozen metadata
            // authority). Non-empty admissions keep the adopt/release protocol unchanged.
            if (producerSize == 0) {
                sink.started(SegmentObservation.Started(context, ShortArray(0), 0, token.allowanceMetadataCapBytes))
                sink.releaseInitial(context, token)
                admissionToken = null
                retainedForOpen = openPrefixExistsFor(context)
            }
            context
        }
    }

    // Transfers the drained first chunk into the reserved prefix using ITS admit token; a
    // refusal/throw path must call releaseInitialChunk so that token's charge leaves exactly once
    // (reviewer 23cae70e b4). [validSamples] = samples actually drained; array capacity may
    // exceed the valid range and stays fully charged while adopted (reviewer 79b2b5c9 b1/b3).
    // Returns the SINK's actual transfer result — never hard true on refusal.
    fun adoptFirstChunk(admission: AdmissionHandle, firstChunk: ShortArray, validSamples: Int = firstChunk.size): Boolean = synchronized(lock) {
        val context = admission.context
        if (open?.captureId != context.captureId) return false
        val token = admission.initialToken ?: return false
        if (validSamples < 0 || validSamples > firstChunk.size) return false
        // The sink adopts the WHOLE array capacity, which may exceed the initial reservation: any
        // such growth is bound-checked BEFORE adoption, never discovered after the charge moved.
        val capacityBytes = firstChunk.size.toLong() * 2L
        val reservedBytes = token.bytes
        if (capacityBytes != reservedBytes) {
            if (openChargedBytes - reservedBytes + capacityBytes + 44L > openPrepaidClipBytes) return false
        }
        val transferred = sink.adopted(SegmentObservation.Started(context, firstChunk, validSamples), token)
        if (transferred) {
            // The authority mirrors the sink's ACTUAL charge: full capacity, reservation settled.
            openChargedBytes += capacityBytes - reservedBytes
            admissionToken = null
            // Retention is live once the prefix exists (sink sets it on adoption); mirror for gates.
            retainedForOpen = openPrefixExistsFor(context)
        }
        transferred
    }

    /** True when the sink currently holds an OPEN prefix for this capture. */
    private fun openPrefixExistsFor(context: CaptureContext): Boolean = sink.hasOpenPrefix(context)

    /** Producer drop for an initial allocation that never transferred. Addressed by the HANDLE
     * from [Admission] — valid AFTER revocation closed the capture or a successor opened: the
     * sink releases THAT token, not "the current open capture's" (reviewer 79b2b5c9 b2). */
    fun releaseInitialChunk(admission: AdmissionHandle) {
        val token = admission.initialToken ?: return
        synchronized(lock) { if (admissionToken === token) admissionToken = null }
        sink.releaseInitial(admission.context, token)
    }

    fun speechStarted(captureSession: Long, routeClass: String, firstChunk: ShortArray): CaptureContext? {
        val admitted = speechStartedAdmitting(captureSession, routeClass, firstChunk.size) ?: return null
        if (!adoptFirstChunk(admitted, firstChunk)) releaseInitialChunk(admitted)
        return admitted.context
    }

    // --- Reserve-before-copy delta protocol (blocker b3): reservation is a budget admit that
    // allocates NOTHING; commit transfers the caller's drained array (already reserved) into the
    // owned prefix; release returns bytes when the copy failed or was refused. All three take the
    // observer monitor only. ---
    fun reserveDelta(context: CaptureContext, samples: Int): ReservationToken? = synchronized(lock) {
        reserveEnforcedLocked(context, samples)
    }

    /** THE enforced growth gate: Long arithmetic, WAV header included, outstanding bytes counted. */
    private fun reserveEnforcedLocked(context: CaptureContext, samples: Int): ReservationToken? {
        if (samples <= 0) return null
        if (open?.captureId != context.captureId) return null
        val addBytes = samples.toLong() * 2L
        // selectedMaxClipBytes is the encoded-WAV bound: PCM bytes + 44-byte header must fit.
        if (openChargedBytes + addBytes + 44L > openPrepaidClipBytes) {
            Timber.i("voice-capture skip: capture=%s over per-clip ceiling", context.captureId)
            return null
        }
        val token = sink.reserveDelta(context, samples) ?: return null
        outstanding[token] = addBytes
        openChargedBytes += addBytes
        return token
    }

    // Producer drop path: even when the capture is already closed/revoked, the sink MUST see the
    // release so a lease-held charge leaves the budget exactly once (reviewer 8273382e b4).
    // Advances accounting ONLY when the sink actually adopted (reviewer 23cae70e b2).
    fun commitDelta(context: CaptureContext, drained: ShortArray, copied: Int, token: ReservationToken?): Boolean = synchronized(lock) {
        // BOTH the engine path and the compatibility path commit through HERE, so the capacity
        // accounting has exactly one definition (reviewer 55433b71 follow-up).
        if (open?.captureId != context.captureId || token == null) {
            // Refused commit: the producer still owns the array; its charge leaves ONLY through
            // releaseDelta when the producer drops it — do not adopt or double-release here.
            return false
        }
        if (copied < 0 || copied > drained.size) return false
        val reservedBytes = outstanding[token] ?: return false // unknown/stale token: refuse
        // The sink adopts the WHOLE array capacity; growth beyond the reservation is enforced
        // against the per-clip ceiling BEFORE adoption, never discovered after the charge moved.
        val capacityBytes = drained.size.toLong() * 2L
        if (capacityBytes > reservedBytes) {
            if (openChargedBytes - reservedBytes + capacityBytes + 44L > openPrepaidClipBytes) return false
        }
        val adopted = sink.commitDelta(context, drained, copied, token)
        if (adopted) {
            // The authority mirrors the sink's ACTUAL retained charge: full capacity, reservation settled.
            openChargedBytes += capacityBytes - reservedBytes
            outstanding.remove(token)
            cumulativeAccounted += copied
        }
        adopted
    }

    fun releaseDelta(context: CaptureContext, token: ReservationToken?) = synchronized(lock) {
        openChargedBytes -= outstanding.remove(token) ?: 0L
        sink.releaseDelta(context, token)
    }

    /** READ-ONLY test seam: samples actually accounted (adopted+committed) for the open capture. */
    fun chargedSamplesForTest(): Int = synchronized(lock) { cumulativeAccounted }

    /** READ-ONLY test seam: THE charged-byte authority (incl. outstanding) for the open capture. */
    fun chargedBytesForTest(): Long = synchronized(lock) { openChargedBytes }

    fun speechIncremental(context: CaptureContext?, samples: ShortArray): Unit = synchronized(lock) {
        val current = open ?: return
        if (context == null || current.captureId != context.captureId) return // stale/unknown: refuse
        if (!retainedForOpen || samples.isEmpty()) return
        // The compatibility path shares THE enforced gate AND the shared capacity-accounting commit.
        val token = reserveEnforcedLocked(current, samples.size) ?: return
        try {
            commitDelta(current, samples, samples.size, token)
        } finally {
            // If commit refused/threw, THIS token's charge returns on producer drop.
            releaseDelta(current, token)
        }
    }

    // * VAD closed [context] (or the detector failed for it). [framesProvider] is invoked AT MOST
    // * ONCE and ONLY after the live revalidation passes — with a dead gate it is NEVER called, so
    // * zero upload-side bytes are allocated. It hands over the PRODUCTION frames plus sample rate.
    // Completion handoff DELEGATES to [CompletionHandoff]: production runs the whole sequence —
    // original-authority validation, live re-decision, frames materialization, sink adoption,
    // closure. The default refuses everything (fail-closed). No check-then-act seam exists at this
    // boundary anymore.

    fun wakeCompleted(
        context: CaptureContext?,
        speechOnsetSample: Int,
        speechEndSample: Int,
        wakeState: SegmentObservation.WakeState,
        wakeMeasurement: SegmentObservation.WakeMeasurement? = null,
        framesProvider: () -> Pair<List<ShortArray>, Int>,
    ): Unit {
        // [framesProvider] is wrapped SINGLE-SHOT (blocker b3): the first call drains and hands the
        // native buffer back; every later call returns EMPTY — no second copy-out can double-run.
        var drained = false
        val oneShot: () -> Pair<List<ShortArray>, Int> = {
            if (drained) {
                emptyList<ShortArray>() to 0
            } else {
                drained = true
                framesProvider()
            }
        }
        // Single consulted gate for the handoff: the LIVE authority decision.
        val liveAllowed = gate.accepts(authority())
        handoff.handoff(context, speechOnsetSample, speechEndSample, wakeState, oneShot, this, liveAllowed, wakeMeasurement)
    }

    /**
     * Observer-side completion body, invoked through [CompletionHandoff]. Late/unknown contexts are
     * refused without running [framesProvider]; the live decision is re-read; refusal allocates
     * nothing.
     */
    override fun completeUnderOwner(
        context: CaptureContext?,
        speechOnsetSample: Int,
        speechEndSample: Int,
        wakeState: SegmentObservation.WakeState,
        framesProvider: () -> Pair<List<ShortArray>, Int>,
        admit: Boolean,
        liveGateAllowed: Boolean,
        wakeMeasurement: SegmentObservation.WakeMeasurement?,
    ): Boolean = synchronized(lock) {
        val current = open ?: return false
        if (context != null && current.captureId != context.captureId) return false // late: refuse join
        if (context == null) {
            // No frozen context at all: the handoff cannot be authorized — terminate the OPEN
            // capture with exactly one terminal and release its prefix (blocker b2), never a
            // silent return that leaves live uncharged audio.
            sink.cancelled(SegmentObservation.Cancelled(current, "handoff-without-context"))
            closeLocked()
            return false
        }
        if (!admit) {
            // ORIGINAL-authority validation failed: FULL release (blocker b2) —
            // purge every collection byte (prefix AND ready candidates), close the capture, zero
            // retention; provider never runs. A refused handoff is a loss event, not a deferral.
            (sink as? RevocationAware)?.purgeAllForRevocation("handoff-authority-refused")
                ?: sink.cancelled(SegmentObservation.Cancelled(current, "handoff-authority-refused"))
            closeLocked()
            totalRetainedBytes = 0L
            return false
        }
        if (!liveGateAllowed) {
            // LIVE-gate refusal is ALSO a terminated handoff (blocker b2): provider never runs,
            // exactly one terminal closes the capture, prefix PCM + charge released via cancel.
            sink.cancelled(SegmentObservation.Cancelled(current, "handoff-gate-refused"))
            closeLocked()
            return false
        }
        val (frames, rate) = framesProvider()
        // FROZEN CLIP BOUND AT COMPLETION (reviewer be120bf2 #1): a refused growth/final-tail
        // reserveDelta leaves the capture open, and the completion drain can hand back the FULL
        // native segment. Enforce the frozen encoded-WAV bound HERE, before any flatten or
        // allocation, with Long totals (2 bytes/sample + the 44-byte header). Refusal terminates
        // collection cleanly - exactly one terminal, prefix and charge released, NO candidate -
        // while recognition itself continues untouched.
        val completionSamples = frames.sumOf { it.size.toLong() }
        if (completionSamples * 2L + 44L > openPrepaidClipBytes) {
            sink.cancelled(SegmentObservation.Cancelled(current, "handoff-clip-bound-refused"))
            closeLocked()
            totalRetainedBytes = 0L
            return false
        }
        val adopted = sink.completed(
            SegmentObservation.Completed(current, frames, rate, speechOnsetSample, speechEndSample, wakeState, wakeMeasurement),
        )
        if (adopted) totalRetainedBytes += frames.sumOf { it.size } * BYTES_PER_SAMPLE
        closeLocked()
        adopted
    }

    /** Terminal cancellation of the OPEN capture identified by [context] (late ones are no-ops). */
    fun cancelled(context: CaptureContext?, reason: String): Unit = synchronized(lock) {
        val current = open ?: return
        if (context != null && current.captureId != context.captureId) return // already terminal
        sink.cancelled(SegmentObservation.Cancelled(current, reason))
        closeLocked()
    }

    /** True when [captureId] is the CURRENT open capture: stale-guard before any refusal purge. */
    override fun ownsCapture(captureId: java.util.UUID): Boolean = synchronized(lock) { open?.captureId == captureId }

    /** Listening closure: discard ONLY the current partial; retained completions survive. */
    fun listeningClosed(reason: String): Unit = cancelled(null, reason)

    /**
     * External FULL REVOCATION signal (withdrawal/disable/expiry/account change): purges ALL
     * collection PCM including the open partial, immediately, under the same lifecycle lock.
     */
    fun revoked(reason: String): Boolean {
        // ONE decision covers decide → purge → close: loss fan-out enters here, and an in-flight
        // handoff completing on the same observer lock is serialized by the monitor below.
        // Loss causes purge on their OWN signal; expiry does not require the flag to have flipped.
        val decision = authority()
        if (!revocationCauses.contains(reason) && decision.collectionPermitted && !decision.fullRevocation) {
            return false
        }
        return synchronized(lock) {
            val purge = sink as? RevocationAware
            purge?.purgeAllForRevocation(reason)
            closeLocked()
            totalRetainedBytes = 0L
            true
        }
    }

    // True while the SINK still owns retention for the open capture (budget may have ended it).
    fun isRetaining(captureId: UUID): Boolean = synchronized(lock) { open?.captureId == captureId && retainedForOpen }

    /** The sink ended retention externally (budget/cap): stop forwarding without a new terminal. */
    fun noteRetentionEnded(context: CaptureContext): Unit = synchronized(lock) {
        if (open?.captureId == context.captureId) retainedForOpen = false
    }

    fun isOpen(captureId: UUID): Boolean = synchronized(lock) { open?.captureId == captureId }

    /** Bytes currently attributed to retained collection audio (test seam for the cap). */
    fun retainedBytes(): Long = synchronized(lock) { totalRetainedBytes }

    private fun closeLocked() {
        open = null
        retainedForOpen = false
    }

    companion object {
        const val DEFAULT_MAX_RETAINED_BYTES: Long = 8L * 1024 * 1024
        private const val BYTES_PER_SAMPLE = 2L

        /** Causes that MUST purge regardless of a live-but-stale-looking gate (loss-driven). */
        val revocationCauses = setOf(
            "account-change",
            "enrollment-change",
            "policy-disable",
            "lease-deadline",
            "policy-inactive",
        )
    }
}

/** Sinks that own retention clear everything on full revocation, partial buffers included. */
interface RevocationAware {
    fun purgeAllForRevocation(reason: String)
}

/**
 * Narrow observer surface used ONLY by the completion handoff. Implemented by [SegmentObserver].
 */
interface ObserverHandoffScope {
    /** True when the scope's observer currently holds THIS capture open (stale-guard first). */
    fun ownsCapture(captureId: java.util.UUID): Boolean

    /**
     * The handoff has ALREADY validated the ORIGINAL authority and evaluated the LIVE gate; those
     * decisions arrive as data ([admit], [liveGateAllowed]) — the observer never acquires any
     * authority lock upward. Runs [framesProvider] at most once, adopts into the sink, closes
     * the capture. Refusals emit exactly one terminal and release charges. Returns retention.
     */
    fun completeUnderOwner(
        context: CaptureContext?,
        speechOnsetSample: Int,
        speechEndSample: Int,
        wakeState: SegmentObservation.WakeState,
        framesProvider: () -> Pair<List<ShortArray>, Int>,
        admit: Boolean = true,
        liveGateAllowed: Boolean = true,
        wakeMeasurement: SegmentObservation.WakeMeasurement?,
    ): Boolean
}

/**
 * Completion handoff strategy: production composes a simple inline handoff; tests may inject an
 * inline executor. The observer calls this from its own wakeCompleted site and MUST be handed a
 * scope it can enter.
 */
fun interface CompletionHandoff {
    /**
     * Executes (or refuses) the handoff for [context]. Fail-closed implementations never invoke
     * [framesProvider] and never retain.
     */
    fun handoff(
        context: CaptureContext?,
        speechOnsetSample: Int,
        speechEndSample: Int,
        wakeState: SegmentObservation.WakeState,
        framesProvider: () -> Pair<List<ShortArray>, Int>,
        scope: ObserverHandoffScope,
        liveGateAllowed: Boolean,
        wakeMeasurement: SegmentObservation.WakeMeasurement?,
    )
}

/** Default when uncomposed: NO handoff ever retains. Allocation proof: provider not run. */
object RefuseAllCompletionHandoff : CompletionHandoff {
    override fun handoff(
        context: CaptureContext?,
        speechOnsetSample: Int,
        speechEndSample: Int,
        wakeState: SegmentObservation.WakeState,
        framesProvider: () -> Pair<List<ShortArray>, Int>,
        scope: ObserverHandoffScope,
        liveGateAllowed: Boolean,
        wakeMeasurement: SegmentObservation.WakeMeasurement?,
    ) = Unit
}
