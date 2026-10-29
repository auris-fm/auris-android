package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ContributionRetentionSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SealedCapture
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageEntry
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName
import java.util.UUID
import kotlinx.coroutines.Job

/**
 * THE ENGINE→PRODUCER PUBLISH TRIGGER (task73 final wiring): the ONE guarded bridge between
 * the live pipeline and the durable contribution store. It is the only production caller of
 * [ContributionCaptureProducer.publish] for engine-produced captures, so every record beside
 * a retained WAV was written through the producer's validated path — no ad-hoc assembly.
 *
 * GUARDS, in order:
 *  1. Fires only on a TERMINAL join the sink forwards with retained PCM: Executed, and (spec +
 *     reviewer 1e4295a1) the terminal bleed rejection — a provenance record is the only durable
 *     witness that the correlator decided. Routing/recognition alone never publish.
 *  2. Requires the authority FROZEN at capture start (account + session + epoch) AND the ONE
 *     COHERENT live identity read ([coherentLiveIdentity], taken under the owner transaction)
 *     to match the frozen tuple on ALL THREE fields. Logout, A→B account move, session-only
 *     move and epoch rotation each refuse the capture record-less.
 *  3. Requires OBSERVED model provenance: the frozen [RetainedCandidate.context.asrModel] must
 *     be a Named ref — a capture with no observed ASR model is not publishable (honest absence,
 *     never a fixed label).
 *  4. The producer independently re-reads the live identity and refuses on disagreement — a
 *     refusal ends retention with NO record.
 *
 * Retention ends EXACTLY ONCE on every path: the publish body's `finally`, dispatch rejection
 * (synchronous throw), AND Job-bound cleanup — if the dispatching scope cancels the job before
 * or during the body, [Job.invokeOnCompletion] releases the charge (reviewer 54635caa: a
 * cancelled `scope.launch` never runs its body, so a `finally` inside the body is not enough).
 * [ContributionRetentionSink.releaseRetained] is idempotent, so the paths cannot double-release.
 */
class RetentionPublishTrigger(
    private val sink: ContributionRetentionSink,
    private val producer: ContributionCaptureProducer,
    /** The models that did NOT vary per capture (fixed app deployments: vad/wake/router). */
    private val fixedModels: List<Pair<String, ModelRef>>,
    /** Stage outcomes for THIS sealed capture, supplied by the pipeline at publish time. */
    private val stagesProvider: (SealedCapture) -> List<Pair<StageName, StageEntry>>,
    /**
     * THE ATOMIC PUBLICATION SECTION (reviewer 8288ac15): [block] runs ENTIRELY inside ONE owner
     * transaction — validation of the frozen tuple AND the producer's own live reads — so no
     * identity transition can interleave between the check and the commit. The trigger passes
     * the frozen tuple as the IMMUTABLE publication owner: [block] returns false WITHOUT any
     * reservation when the live coherent identity does not match it; it commits only when it does.
     */
    private val publishUnderOwner: ((FrozenOwner, () -> Boolean) -> Boolean)? = null,
    /** Dispatch body; returns the Job (or null for synchronous test dispatch). */
    private val dispatch: (() -> Unit) -> Job?,
) {

    /** One coherent identity read: all three fields from ONE owner-transaction snapshot. */
    data class LiveIdentity(val account: String, val session: Long, val enrollmentEpoch: String?)

    /** THE immutable publication owner: the tuple FROZEN at capture start. */
    data class FrozenOwner(val account: String, val session: Long, val enrollmentEpoch: String)

    /** Attach this trigger to the sink. Calling twice is refused (one owner, one trigger). */
    fun attach() {
        check(sink.onReadyForPublication == null) { "a publish trigger is already attached" }
        sink.onReadyForPublication = ::onCandidate
    }

    private fun onCandidate(sealed: SealedCapture) {
        val id = sealed.captureId
        // REJECTION/CANCEL-BEFORE-START: if the dispatch itself throws synchronously (rejected
        // executor, closed scope), the body never runs — release HERE and surface the failure.
        val job = try {
            dispatch { publishBody(sealed, id) }
        } catch (t: Throwable) {
            sink.releaseRetained(id)
            throw t
        }
        if (job == null) return
        if (job.isCancelled) {
            // Cancel-before-start: the body will never run; release NOW.
            sink.releaseRetained(id)
            return
        }
        // Cancel/failure DURING the body: the body's finally may itself be cut short; bind the
        // release to Job completion (idempotent with the finally path).
        job.invokeOnCompletion { cause -> if (cause != null) sink.releaseRetained(id) }
    }

    private fun publishBody(sealed: SealedCapture, id: UUID) {
        try {
            val issued = sealed.context.issuedAuthority
            val pcm = sealed.pcm
            // GUARD 1: the capture arrives ALREADY SEALED (reviewer be6bb62e #1 — ONE seal
            // operation for both completion paths; the sink dispatches only sealed snapshots).
            // Every guard below (frozen identity, consent, eligibility, caps) applies unchanged.
            if (sealed.pcm.isEmpty()) return
            // GUARD 2: the frozen tuple IS the publication owner; validation and the producer's
            // live reads happen inside ONE owner transaction (no check-then-use window).
            if (issued == null || issued.enrollmentEpoch == null) return
            val frozen = FrozenOwner(
                account = issued.accountName ?: return,
                session = issued.sessionGeneration,
                enrollmentEpoch = issued.enrollmentEpoch,
            )
            // GUARD 3 (FAIL-CLOSED): the FROZEN allowance metadata cap, carried by the immutable
            // candidate since admission. Missing metadata authority never means unconstrained
            // (reviewer fde5349e): a capture that was never allowance-admitted refuses here,
            // before any queue admission or write.
            val metadataCapBytes = sealed.allowanceMetadataCap ?: return
            // GUARD 4: observed per-capture model provenance is record truth. A Named ref is
            // required for EARLY-COMPLETED records (execution implies observed ASR). An
            // outcome-wait-expired record may carry NO observed ASR model (the wait expired
            // before one joined): that absence is honest and the ASR stage is typed by its
            // cause — publication with ModelRef.None, never fabricated provenance (reviewer
            // 399cb2e6 #1: retaining legitimate no-ASR outcomes cannot be deferred).
            val observedAsr = sealed.context.asrModel as? ModelRef.Named
            if (observedAsr == null && sealed.cause != SealedCapture.CAUSE_OUTCOME_WAIT_EXPIRED) return
            val asrModel = observedAsr ?: ModelRef.None
            // TRANSLATION provenance comes from the OBSERVED record on the sealed snapshot,
            // never from the frozen context (which cannot know whether translation ran).
            val translationModel = sealed.translation?.model ?: ModelRef.None
            val chunks = listOf(pcm to pcm.size)
            val commit = {
                producer.publish(
                    id.toString(),
                    frozen.enrollmentEpoch,
                    sealed.sampleRateHz,
                    chunks,
                    fixedModels + listOf("asr" to asrModel, "translation" to translationModel),
                    stagesProvider(sealed),
                    metadataCapBytes = metadataCapBytes,
                )
                true
            }
            // ATOMIC section: identity validation + reservation/prepare in the SAME acquisition.
            // A missing binding FAILS CLOSED; false = no matching live owner: record-less.
            val section = publishUnderOwner ?: return
            section(frozen) { commit() }
        } finally {
            sink.releaseRetained(id)
        }
    }
}
