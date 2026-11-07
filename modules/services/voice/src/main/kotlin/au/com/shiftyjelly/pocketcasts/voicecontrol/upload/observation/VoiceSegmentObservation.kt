package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import java.util.UUID

/**
 * Immutable capture context, FROZEN SYNCHRONOUSLY at the segment START on the pipeline thread
 * (plan Task 3, spec dc4cb5af). The SAME object value is handed to the wake detector, ASR,
 * routing and execution call sites so outcomes join by issued identity, never by "whatever
 * capture is current" at outcome time.
 */
data class CaptureContext(
    val captureId: UUID,
    val captureSession: Long,
    val routeClass: String,
    /**
     * Authority FROZEN at start (account/session/credential + enrollment epoch/disclosure).
     * The completion handoff revalidates THIS value inside the owner transaction; nothing here
     * is ever refreshed from a newer projection or a later account.
     */
    val issuedAuthority: IssuedAuthority? = null,
    /**
     * THE ASR backend actually selected for THIS capture, frozen at issuance (reviewer
     * 54635caa/spec: per-capture backend provenance is record truth). [ModelRef.None] is the
     * honest absence — a capture with no observed ASR model is not publishable.
     */
    val asrModel: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef =
        au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef.None,
    /**
     * The TRANSLATION provenance OBSERVED for this capture (reviewer 8288ac15): null when no
     * translation ran or its identity was not observed. Never claimed when unobserved.
     */
    val translation: TranslationProvenance? = null,
    /**
     * THE REAL GRACE STATE FROZEN AT SEGMENT START (spec phase 2): `true` = the wake word was
     * required for this utterance; `false` = the follow-up grace window was active. This is the
     * record's REQUIRED `wake_required` fact, frozen synchronously with the rest of the context.
     */
    val wakeRequired: Boolean,
)

/** What the translation stage actually did for one capture. */
data class TranslationProvenance(
    val model: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef,
    /** True only when a translated result was actually produced for this capture. */
    val producedResult: Boolean,
    /** The OBSERVED translated text (the result itself), when a result exists. */
    val translatedText: String? = null,
    /** The target language the result is in (observed), when a result exists. */
    val targetLanguage: String?,
    /**
     * P1 (reviewer 7033f4d9): a KNOWN skip of the separate translation stage, in the existing
     * owning vocabulary (backend_handles_translation / source_language_unavailable /
     * translator_unavailable / source_language_target_language). A skip is a known fact, not
     * honest-unknown; capability alone never asserts a produced result.
     */
    val skipReason: String? = null,
    /**
     * P1 (reviewer be375ea1): the stage RAN and failed / returned invalid output — a known
     * FAILURE fact (state failed), never laundered into a skip or unknown.
     */
    val failedWith: String? = null,
)

/**
 * One observed step of the ACTUAL segment path. The observer subscribes to the same
 * [au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceSegmenterResult] stream the wake/bleed
 * pipeline already emits — reuse of the segment source, never a parallel capture — so virtual
 * padding and playback-reference PCM are excluded by construction. Every event carries the
 * immutable context ISSUED at start; events for closed or unknown captures are refused.
 */
sealed interface SegmentObservation {
    val context: CaptureContext

    /** VAD reported speech onset: the moment the context is frozen. Zero audio retained. */

    data class Admission(
        override val context: CaptureContext,
        val producerSamples: Int,
        /**
         * The SELECTED prepaid metadata bound, FROZEN at admission (reviewer fde5349e): it travels
         * with the capture into the retained candidate, so persistence reads immutable frozen
         * state. NULL refuses publication fail-closed - missing metadata authority never means
         * unconstrained. Positive fixtures pass an explicit bound.
         */
        val allowanceMetadataCapBytes: Int?,
        // NOTE ON THE NAME (core c74d7e30): the allowance no longer exists, but this field still
        // carries a REAL constraint — the per-clip metadata ceiling (ContributionCeilings
        // .MAX_METADATA_BYTES), which the contract keeps. It is renamed nowhere because the value
        // and its null-means-refuse check are unchanged; only its source moved from a provisioned
        // batch to a compiled constant. Retained deliberately rather than silently renamed, so this
        // diff does not touch the persistence path beyond removing the allowance.
    ) : SegmentObservation

    data class Started(
        override val context: CaptureContext,
        /** The production first-chunk array; the sink ADOPTS it (no upload-side copy). Its
         * CAPACITY may exceed the drained VALID range — the whole allocation stays charged until
         * the buffer is dropped (reviewer 79b2b5c9 b1/b3); audio readers must slice to
         * [validSamples]. */
        val firstChunk: ShortArray,
        /** Samples actually drained into [firstChunk] (distinctive-partial-copy proof). */
        val validSamples: Int = firstChunk.size,
        /** The frozen allowance metadata cap (same fail-closed contract as Admission). Direct-sink
         * starts (joined-path controls) freeze it here; null refuses publication. */
        val allowanceMetadataCapBytes: Int? = null,
    ) : SegmentObservation

    /** Incremental accounting of REAL produced samples, bounded by collector ownership. */
    data class Incremental(
        override val context: CaptureContext,
        /** Samples the production segment producer emitted since the last accounting point. */
        val samplesSinceLastAccounting: Int,
        /** True only while an owning collector accepts retained audio for this capture. */
        val ownershipAccepted: Boolean,
    ) : SegmentObservation

    /** Wake-word observation state of THIS capture, from the production detector result. */
    enum class WakeState { Detected, NotDetected, DetectorFailed }

    /**
     * The MEASURED wake score/threshold for THIS capture (spec phase 2: recorded whenever they
     * were measured). A null component = that value was not measured (e.g. a NaN threshold);
     * never synthesized.
     */
    data class WakeMeasurement(val score: Double?, val threshold: Double?)

    /**
     * VAD closed the segment with its audio. [pcm] is the PRODUCTION frame list handed over
     * without an upload-side copy: the sink either adopts it (retention) or drops it, and the
     * pipeline's own float conversion proceeds independently.
     */
    data class Completed(
        override val context: CaptureContext,
        /** The production VAD frames exactly as emitted — no upload-side flatten/copy. */
        val frames: List<ShortArray>,
        val sampleRateHz: Int,
        val speechOnsetSample: Int,
        val speechEndSample: Int,
        val wakeState: WakeState,
        val wakeMeasurement: WakeMeasurement? = null,
    ) : SegmentObservation {
        override fun equals(other: Any?): Boolean = this === other || (
            other is Completed && context == other.context &&
                frames.size == other.frames.size &&
                sampleRateHz == other.sampleRateHz &&
                speechOnsetSample == other.speechOnsetSample && speechEndSample == other.speechEndSample &&
                wakeState == other.wakeState
            )

        override fun hashCode(): Int = 31 * context.hashCode() + frames.size
    }

    /** The segment was cancelled/rejected before completion: partials die with it. */
    data class Cancelled(
        override val context: CaptureContext,
        val reason: String,
    ) : SegmentObservation
}

/** Live authority consulted at EVERY retention decision — start admission AND revalidation. */
data class AuthorityDecision(
    val collectionPermitted: Boolean,
    val fullRevocation: Boolean,
    /**
     * WHY collection is refused, as a FIXED token evaluated at the decision site itself. Null when
     * permitted. Never carries account or epoch contents - it names the failing clause only.
     */
    val refusalReason: AdmissionRefusalReason? = null,
) {
    val shouldRetainNewWork: Boolean get() = collectionPermitted && !fullRevocation
}

/** The distinct clauses the admission decision can fail on. Deliberately closed and small. */
enum class AdmissionRefusalReason {
    /** No signed-in account session. */
    NO_SESSION,

    /** The account has no durably saved opt-in (nothing ever saved, other account's, or withdrawn). */
    NO_SAVED_CHOICE,

    /** The user's durable local stop (or a failed stop write) vetoes. */
    LOCAL_STOP,
}

/**
 * The collection gate consulted at the segment START — BEFORE any upload-side allocation — and
 * again immediately before the completion handoff. A refusal here means zero upload-owned bytes:
 * no copy exists yet at that point in the pipeline.
 */
fun interface CollectionGate {
    fun accepts(authority: AuthorityDecision): Boolean
}

/** Sink for the observations of one capture; implemented by the retention owner. */
interface SegmentSink {
    /**
     * Pre-allocation admission: reserve EXACTLY [SegmentObservation.Admission.producerSamples]'s
     * bytes against the aggregate budget BEFORE the producer copies anything out, and register a
     * PER-ALLOCATION reservation token carrying that capacity (reviewer 23cae70e b3/b4). The
     * token — not any global counter — is what commit/release consume. Null = refused.
     */
    fun admit(observation: SegmentObservation.Admission): ReservationToken? = null

    /**
     * Receives the admitted-and-drained first chunk plus its token; transfers the USED range's
     * charge into the prefix and releases ONLY the token's unused capacity. A refusal or throw
     * leaves the caller responsible for releaseInitial (finally-path), which returns the token's
     * full charge exactly once.
     */
    fun adopted(observation: SegmentObservation.Started, token: ReservationToken?): Boolean

    /** Producer drop for an initial allocation that never transferred (refusal/throw path). */
    fun releaseInitial(context: CaptureContext, token: ReservationToken?) = Unit

    /** Returns true while the sink retains incremental audio for this capture. */
    fun started(observation: SegmentObservation.Started): Boolean

    /** True when the sink currently holds an OPEN prefix owned by [context]. */
    fun hasOpenPrefix(context: CaptureContext): Boolean = false

    /** Real produced samples appended under ownership; the sink owns bounded retention. */
    fun incremental(observation: SegmentObservation.Incremental, samples: ShortArray)

    /** Reserves [samples] delta bytes WITHOUT allocating; null when caps/budget refuse.
     * The returned handle is a PER-ALLOCATION token — commit/release address it, not any global. */
    fun reserveDelta(context: CaptureContext, samples: Int): ReservationToken? = null

    /** Transfers the reservation identified by [token] into owned retention: ADOPTS the original
     * [drained] array's valid range [0, copied) — no second copy — and releases only the token's
     * unused capacity. Returns true when adopted; false when refused (revoked/unknown token), in
     * which case the caller MUST still releaseDelta(token) when it drops the array. */
    fun commitDelta(context: CaptureContext, drained: ShortArray, copied: Int, token: ReservationToken?): Boolean = false

    /** Returns THAT token's remaining charge after a failed/refused/abandoned copy-out. */
    fun releaseDelta(context: CaptureContext, token: ReservationToken?) = Unit

    /**
     * Receives the PRODUCTION frames for adoption. Returning false declines ownership; the
     * observer then retains nothing for this capture.
     */
    fun completed(observation: SegmentObservation.Completed): Boolean
    fun cancelled(observation: SegmentObservation.Cancelled)
}

/**
 * Per-allocation reservation handle (reviewer 23cae70e b3): identifies ONE reserved capacity
 * window. Commit/release operations address THIS token; charges can never cross captures or
 * allocations through a shared counter.
 */
class ReservationToken constructor(
    internal val captureId: java.util.UUID,
    internal val id: Long,
    internal var bytes: Long,
    /** The allowance metadata cap FROZEN on the ADMISSION token (capture-owned; null = none). */
    internal val allowanceMetadataCapBytes: Int? = null,
)

/** Runs a block inside the collection authority transaction (composition supplies the fence). */
interface FenceGate {
    fun <R> gate(block: () -> R): R
}

val IdentityFenceGate: FenceGate = object : FenceGate {
    override fun <R> gate(block: () -> R): R = block()
}
