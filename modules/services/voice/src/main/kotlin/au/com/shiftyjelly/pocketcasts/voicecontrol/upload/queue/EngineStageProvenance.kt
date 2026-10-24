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
                candidate.stageOutcomes.any {
                    (it as? CollectionOutcome.NotCollected)?.reason == "bleed"
                }
            ) {
                StageEntry(StageState.Completed, mapOf("decision" to RecordNode.Str("overlap")))
            } else {
                StageEntry(StageState.Unknown, mapOf("reason" to RecordNode.Str("observation_unavailable")))
            },
            StageName.Asr to if (recognized != null) {
                StageEntry(StageState.Completed, mapOf("text" to RecordNode.Str(recognized.transcript)))
            } else {
                StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("provider_error")))
            },
            // TRANSLATION: only OBSERVED truth (reviewer 8288ac15). A produced result is
            // completed with the observed target language; no observation at all is UNKNOWN —
            // a missing observation never justifies an invented not_run reason.
            // BOTH schema fields, from SEPARATE observations (reviewer 57719ad2): translated_text
            // is the result text itself, target_language the language it was produced in. A
            // produced result missing either observed half is not a completable record.
            StageName.Translation to when {
                candidate.translation?.producedResult == true &&
                    candidate.translation?.translatedText != null &&
                    candidate.translation?.targetLanguage != null ->
                    StageEntry(
                        StageState.Completed,
                        mapOf(
                            "translated_text" to RecordNode.Str(candidate.translation!!.translatedText!!),
                            "target_language" to RecordNode.Str(candidate.translation!!.targetLanguage!!),
                        ),
                    )

                candidate.translation != null -> StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_text")))

                // delta736d3180: translation unobserved at finalization — honest unknown, now
                // schema-valid.
                else -> StageEntry(StageState.Unknown, mapOf("reason" to RecordNode.Str("observation_unavailable")))
            },
            StageName.Routing to if (routed != null) {
                StageEntry(StageState.Completed, mapOf("predicted_intent" to RecordNode.Str(routed.intentCategory)))
            } else {
                StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_candidates")))
            },
            // UNVERIFIED by design: dispatch observed, execution result never asserted here.
            // delta736d3180: honest unknown with the enumerated reason the schema requires.
            StageName.Execution to StageEntry(StageState.Unknown, mapOf("reason" to RecordNode.Str("observation_unavailable"))),
        )
    }
}
