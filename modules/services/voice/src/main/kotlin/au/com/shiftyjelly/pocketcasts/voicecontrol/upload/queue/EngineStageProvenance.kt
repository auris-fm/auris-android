package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ExecutionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SealedCapture
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObservation
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.TranslationProvenance
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageEntry
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageState

/**
 * ENGINE STAGE PROVENANCE (task73 composition): derives the record's stage entries from the
 * values the candidate ACTUALLY observed — never synthesized beyond what is known. Every
 * mapping is deliberately conservative:
 *  - wake: the candidate's REAL detector decision ([SegmentObservation.WakeState]).
 *  - bleed: the candidate does not carry a bleed decision → `unknown` (never invented).
 *  - asr: completed with the transcript ONLY when the [CollectionOutcome.Recognized] join
 *    carried one; otherwise `not_run/provider_error`.
 *  - translation: the candidate does not carry translation output → `not_run/no_text` unless
 *    a transcript exists, in which case it is `not_run/source_language_target_language`
 *    (translation result, if any, is server-side and not observable here).
 *  - routing: completed with the category ONLY on the [CollectionOutcome.Routed] join;
 *    otherwise `not_run/no_candidates`.
 *  - execution: the [CollectionOutcome.Executed] join is the DISPATCH observation, explicitly
 *    UNVERIFIED as ground truth (spec: not a label) → `unknown` until real execution evidence
 *    exists. It must never be recorded as `completed`.
 */
object EngineStageProvenance {

    fun stagesFor(sealed: SealedCapture): List<Pair<StageName, StageEntry>> {
        // HISTORY, not [outcome] (reviewer 54635caa): the terminal Executed join overwrites
        // [outcome]; the Recognized/Routed joins are read from the sealed join history.
        val candidate = sealed // stage reads below use the snapshot's observed fields only
        val history = sealed.stageHistory + listOfNotNull(sealed.outcome)
        val recognized = history.filterIsInstance<CollectionOutcome.Recognized>().firstOrNull()
        val routed = history.filterIsInstance<CollectionOutcome.Routed>().firstOrNull()
        // BATCH B (reviewer be6bb62e #1/#4): only a capture whose wait GENUINELY EXPIRED
        // (cause outcome_wait_expired) types its unresolved stages by the expired wait. An
        // early-completed capture (cause terminal) keeps the pre-Batch-B
        // `observation_unavailable` typing — its absence is not an expiry fact, and known
        // no-ASR/empty/skip facts are preserved either way.
        val unresolvedReason =
            if (sealed.cause == SealedCapture.CAUSE_OUTCOME_WAIT_EXPIRED) {
                RecordNode.Str(SealedCapture.CAUSE_OUTCOME_WAIT_EXPIRED)
            } else {
                RecordNode.Str("observation_unavailable")
            }
        return listOf(
            StageName.Wake to when (candidate.wakeState) {
                SegmentObservation.WakeState.Detected ->
                    StageEntry(StageState.Completed, mapOf("decision" to RecordNode.Str("detected")))

                // SCHEMA VOCABULARY (found by the bleed delivery control): a wake DECISION is
                // `detected` or `rejected`; `no_wake_detected` is a REASON, not a decision, and the
                // recovery validator refused the pair. A detector that ran and did not fire is a
                // completed check with decision `rejected` — the same shape bleed uses.
                SegmentObservation.WakeState.NotDetected ->
                    StageEntry(StageState.Completed, mapOf("decision" to RecordNode.Str("rejected")))

                // The observer KNOWS the detector threw, so this is a known failure — never unknown.
                // Recording it as observation_unavailable would launder a known outcome into an
                // unavailable observation (reviewer 6d68ac4a, spec 15:46Z).
                SegmentObservation.WakeState.DetectorFailed ->
                    StageEntry(StageState.Failed, mapOf("reason" to RecordNode.Str("detector_error")))
            },
            // WIRED PRODUCTION FACT (spec audit): VoiceAsrEngine.transcribeSegment joins
            // NotCollected(captureId, "bleed") when UtteranceFilter's correlator rejects the
            // segment. That join IS the bleed observation - a completed check with a rejected
            // decision - so it must reach the owning schema instead of laundering a known
            // production fact into an unavailable observation.
            StageName.Bleed to if (
                (candidate.outcome as? CollectionOutcome.NotCollected)?.reason == "bleed" ||
                sealed.stageHistory.any {
                    (it as? CollectionOutcome.NotCollected)?.reason == "bleed"
                }
            ) {
                StageEntry(StageState.Completed, mapOf("decision" to RecordNode.Str("overlap")))
            } else {
                StageEntry(StageState.Unknown, mapOf("reason" to unresolvedReason))
            },
            StageName.Asr to when {
                recognized != null ->
                    StageEntry(StageState.Completed, mapOf("text" to RecordNode.Str(recognized.transcript)))

                // BATCH B: an expired-wait record's ASR stage carries the genuine wait fact,
                // not a provider error (early-completed records keep the pre-Batch-B typing).
                sealed.cause == SealedCapture.CAUSE_OUTCOME_WAIT_EXPIRED ->
                    StageEntry(StageState.Unknown, mapOf("reason" to unresolvedReason))

                else -> StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("provider_error")))
            },
            // TRANSLATION: only OBSERVED truth (reviewer 8288ac15). A produced result is
            // completed with the observed target language; no observation at all is UNKNOWN —
            // a missing observation never justifies an invented not_run reason.
            // BOTH schema fields, from SEPARATE observations (reviewer 57719ad2): translated_text
            // is the result text itself, target_language the language it was produced in. A
            // produced result missing either observed half is not a completable record.
            // delta736d3180 + BATCH B: translation unobserved at finalization is an honest
            // unknown; for a sealed record the reason is the expired wait itself (see
            // [translationStage] for the full observed-truth mapping).
            StageName.Translation to translationStage(sealed.translation, unresolvedReason),
            StageName.Routing to when {
                routed != null ->
                    StageEntry(StageState.Completed, mapOf("predicted_intent" to RecordNode.Str(routed.intentCategory)))

                sealed.cause == SealedCapture.CAUSE_OUTCOME_WAIT_EXPIRED ->
                    StageEntry(StageState.Unknown, mapOf("reason" to unresolvedReason))

                else -> StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_candidates")))
            },
            // THE EXECUTOR'S OWN RESULT when it actually reported one (spec audit, action-result
            // channel): the outcome is what the executor returned/threw, never an inference. A
            // dispatch with no reported result is still an honest unknown — dispatch alone does
            // not prove execution (delta736d3180).
            StageName.Execution to when (val reported = candidate.executionOutcome) {
                null, ExecutionOutcome.NotObserved ->
                    StageEntry(StageState.Unknown, mapOf("reason" to unresolvedReason))

                ExecutionOutcome.Success -> StageEntry(StageState.Completed, mapOf("outcome" to RecordNode.Str("success")))

                // Schema vocabulary (StageReasons.Execution): action_failed / client_refused.
                ExecutionOutcome.Failure -> StageEntry(StageState.Failed, mapOf("reason" to RecordNode.Str("action_failed")))

                ExecutionOutcome.Refused -> StageEntry(StageState.Failed, mapOf("reason" to RecordNode.Str("client_refused")))

                ExecutionOutcome.Cancelled -> StageEntry(StageState.Cancelled, mapOf("reason" to RecordNode.Str("cancelled_by_user")))
            },
        )
    }

    /** TRANSLATION (reviewer 57719ad2 semantics, unchanged): only OBSERVED truth — a produced
     *  result is completed with BOTH observed halves; a present-but-empty observation is
     *  `not_run/no_text`; no observation at all is UNKNOWN with the sealed cause's reason. */
    private fun translationStage(
        translation: TranslationProvenance?,
        unresolvedReason: RecordNode.Str,
    ): StageEntry = when {
        translation != null && translation.producedResult &&
            translation.translatedText != null && translation.targetLanguage != null ->
            StageEntry(
                StageState.Completed,
                mapOf(
                    "translated_text" to RecordNode.Str(translation.translatedText),
                    "target_language" to RecordNode.Str(translation.targetLanguage),
                ),
            )

        translation != null -> StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_text")))

        else -> StageEntry(StageState.Unknown, mapOf("reason" to unresolvedReason))
    }
}
