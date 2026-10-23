package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.RetainedCandidate
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObservation
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

    fun stagesFor(candidate: RetainedCandidate): List<Pair<StageName, StageEntry>> {
        // HISTORY, not [outcome] (reviewer 54635caa): the terminal Executed join overwrites
        // [outcome]; the Recognized/Routed joins are read from the retained join history.
        val history = candidate.stageOutcomes + listOfNotNull(candidate.outcome)
        val recognized = history.filterIsInstance<CollectionOutcome.Recognized>().firstOrNull()
        val routed = history.filterIsInstance<CollectionOutcome.Routed>().firstOrNull()
        return listOf(
            StageName.Wake to when (candidate.wakeState) {
                SegmentObservation.WakeState.Detected ->
                    StageEntry(StageState.Completed, mapOf("decision" to RecordNode.Str("detected")))

                SegmentObservation.WakeState.NotDetected ->
                    StageEntry(StageState.Completed, mapOf("decision" to RecordNode.Str("no_wake_detected")))

                SegmentObservation.WakeState.DetectorFailed -> StageEntry(StageState.Unknown)
            },
            StageName.Bleed to StageEntry(StageState.Unknown),
            StageName.Asr to if (recognized != null) {
                StageEntry(StageState.Completed, mapOf("text" to RecordNode.Str(recognized.transcript)))
            } else {
                StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("provider_error")))
            },
            // TRANSLATION: only OBSERVED truth (reviewer 8288ac15). A produced result is
            // completed with the observed target language; no observation at all is UNKNOWN —
            // a missing observation never justifies an invented not_run reason.
            StageName.Translation to when {
                candidate.translation?.producedResult == true -> {
                    val target = candidate.translation?.targetLanguage
                    StageEntry(
                        StageState.Completed,
                        if (target != null) mapOf("translated_text" to RecordNode.Str(target)) else emptyMap(),
                    )
                }

                candidate.translation != null -> StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_text")))

                else -> StageEntry(StageState.Unknown)
            },
            StageName.Routing to if (routed != null) {
                StageEntry(StageState.Completed, mapOf("predicted_intent" to RecordNode.Str(routed.intentCategory)))
            } else {
                StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_candidates")))
            },
            // UNVERIFIED by design: dispatch observed, execution result never asserted here.
            StageName.Execution to StageEntry(StageState.Unknown),
        )
    }
}
