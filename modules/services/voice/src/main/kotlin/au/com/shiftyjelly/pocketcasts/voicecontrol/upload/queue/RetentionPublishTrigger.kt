package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ContributionRetentionSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.RetainedCandidate
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageEntry
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName
import java.util.UUID

/**
 * THE ENGINE→PRODUCER PUBLISH TRIGGER (task73 final wiring): the ONE guarded bridge between
 * the live pipeline and the durable contribution store. It is the only production caller of
 * [ContributionCaptureProducer.publish] for engine-produced captures, so every record beside
 * a retained WAV was written through the producer's validated path — no ad-hoc assembly.
 *
 * GUARDS, in order:
 *  1. Fires only on the TERMINAL [CollectionOutcome.Executed] join (the sink only invokes the
 *     hook for that outcome with retained PCM) — routing/recognition alone never publish.
 *  2. Requires the authority FROZEN at capture start (`context.issuedAuthority`) with a
 *     non-null account AND enrollment epoch. A capture captured before any account existed is
 *     DISCARDED (record-less), never published with a guessed identity.
 *  3. The producer independently re-reads the LIVE admitted identity and refuses (null) when
 *     it disagrees — the frozen authority authorizes the trigger, the live identity authorizes
 *     the record. A refusal ends retention with NO record.
 *
 * The publish attempt always ends retention exactly once ([ContributionRetentionSink.releaseRetained])
 * whether it produced a prepared entry or was refused, so retained charges can never pin.
 * Dispatch is injected: production hands a coroutine scope so the synchronous outcome join on
 * the pipeline thread never blocks on audio encoding or disk I/O.
 */
class RetentionPublishTrigger(
    private val sink: ContributionRetentionSink,
    private val producer: ContributionCaptureProducer,
    /** The models that actually ran for engine captures (bound provenance). */
    private val models: List<Pair<String, ModelRef>>,
    /** Stage outcomes for THIS candidate, supplied by the pipeline at publish time. */
    private val stagesProvider: (RetainedCandidate) -> List<Pair<StageName, StageEntry>>,
    /** Dispatch body; defaults to synchronous for tests, production passes a worker scope. */
    private val dispatch: (() -> Unit) -> Unit = { it() },
) {

    /** Attach this trigger to the sink. Calling twice is refused (one owner, one trigger). */
    fun attach() {
        check(sink.onReadyForPublication == null) { "a publish trigger is already attached" }
        sink.onReadyForPublication = ::onCandidate
    }

    private fun onCandidate(candidate: RetainedCandidate) {
        val id = candidate.context.captureId
        // REJECTION/CANCEL-BEFORE-START (reviewer e2fe2056): if the dispatch itself throws
        // synchronously (rejected executor, closed scope), the body's finally never runs - the
        // release happens HERE instead. If the body is ACCEPTED but its scope is cancelled
        // before execution, the candidate is still in the sink's deque, so the ordinary loss
        // paths (purgeAllForRevocation on account move/withdrawal) release it; this trigger
        // therefore requires a scope that is not cancelled during ordinary operation.
        try {
            dispatch { publishBody(candidate, id) }
        } catch (t: Throwable) {
            sink.releaseRetained(id)
            throw t
        }
    }

    private fun publishBody(candidate: RetainedCandidate, id: UUID) {
        try {
            val issued = candidate.context.issuedAuthority
            val pcm = candidate.pcm
            // GUARD 1+2: terminal executed outcome with retained audio and FROZEN full identity.
            if (candidate.outcome !is CollectionOutcome.Executed || pcm == null) return
            if (issued?.accountName == null || issued.enrollmentEpoch == null) return
            val chunks = listOf(pcm to pcm.size)
            producer.publish(
                id.toString(),
                issued.enrollmentEpoch,
                candidate.sampleRateHz,
                chunks,
                models,
                stagesProvider(candidate),
            )
            // null return = store/producer refused (budget, epoch, live identity): the capture
            // ends record-less by design. Either way retention ends EXACTLY ONCE below.
        } finally {
            sink.releaseRetained(id)
        }
    }
}
