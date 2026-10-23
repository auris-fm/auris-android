package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ContributionRetentionSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.RetainedCandidate
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
 *  1. Fires only on the TERMINAL [CollectionOutcome.Executed] join (the sink only invokes the
 *     hook for that outcome with retained PCM) — routing/recognition alone never publish.
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
    /** Stage outcomes for THIS candidate, supplied by the pipeline at publish time. */
    private val stagesProvider: (RetainedCandidate) -> List<Pair<StageName, StageEntry>>,
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

    private fun onCandidate(candidate: RetainedCandidate) {
        val id = candidate.context.captureId
        // REJECTION/CANCEL-BEFORE-START: if the dispatch itself throws synchronously (rejected
        // executor, closed scope), the body never runs — release HERE and surface the failure.
        val job = try {
            dispatch { publishBody(candidate, id) }
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

    private fun publishBody(candidate: RetainedCandidate, id: UUID) {
        try {
            val issued = candidate.context.issuedAuthority
            val pcm = candidate.pcm
            // GUARD 1: terminal executed outcome with retained audio.
            if (candidate.outcome !is CollectionOutcome.Executed || pcm == null) return
            // GUARD 2: the frozen tuple IS the publication owner; validation and the producer's
            // live reads happen inside ONE owner transaction (no check-then-use window).
            if (issued == null || issued.enrollmentEpoch == null) return
            val frozen = FrozenOwner(
                account = issued.accountName ?: return,
                session = issued.sessionGeneration,
                enrollmentEpoch = issued.enrollmentEpoch,
            )
            // GUARD 3: observed per-capture model provenance is record truth.
            val asrModel = candidate.context.asrModel as? ModelRef.Named ?: return
            // TRANSLATION provenance comes from the OBSERVED record on the candidate, never
            // from the frozen context (which cannot know whether translation ran).
            val translationModel = candidate.translation?.model ?: ModelRef.None
            val chunks = listOf(pcm to pcm.size)
            val commit = {
                producer.publish(
                    id.toString(),
                    frozen.enrollmentEpoch,
                    candidate.sampleRateHz,
                    chunks,
                    fixedModels + listOf("asr" to asrModel, "translation" to translationModel),
                    stagesProvider(candidate),
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
