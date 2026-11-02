package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.EngineStageProvenance
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageState
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 INVOCATION-PROVENANCE BRANCH CONTROLS (reviewer 7033f4d9 + be375ea1 + spec 1e79e5b4).
 *
 * Every control drives the REAL sink join/seal path (the same joins the engine now makes at
 * the actual function boundaries) and reads the REAL [EngineStageProvenance] mapping from the
 * sealed snapshot. The asserted facts:
 *  - result / empty / failure / cancellation / actual skip / replacement between selection and
 *    invocation are DISTINCT observable outcomes;
 *  - no stage entry ever invents a failure reason (provider_error/no_candidates) for a missing
 *    fact;
 *  - model identity is claimed only from invocation evidence, and a replacement between the
 *    SpeechStarted selection and the actual invocation cannot misattribute it.
 */
class InvocationProvenanceControlsTest {

    private fun context(id: UUID = UUID.randomUUID()) = CaptureContext(
        id,
        captureSession = 1L,
        routeClass = "Bluetooth",
        issuedAuthority = null,
        // The SpeechStarted SELECTION: deliberately DIFFERENT from every invocation identity
        // used below, so any control that reads it instead of the invocation note fails.
        asrModel = ModelRef.Named("selected-but-never-run", "0.0"),
    )

    private fun retain(sink: ContributionRetentionSink, ctx: CaptureContext, samples: Int = 1600) {
        val first = ShortArray(800) { i -> i.toShort() }
        assertTrue(sink.started(SegmentObservation.Started(ctx, first, validSamples = 800, allowanceMetadataCapBytes = 1 shl 20)))
        val rest = ShortArray(samples - 800) { i -> (i + 1).toShort() }
        sink.completed(
            SegmentObservation.Completed(ctx, frames = listOf(rest), sampleRateHz = 16000, speechOnsetSample = 0, speechEndSample = samples, wakeState = SegmentObservation.WakeState.Detected),
        )
    }

    private fun stagesOf(sink: ContributionRetentionSink, id: UUID) = EngineStageProvenance.stagesFor(sink.sealedSnapshotForTest(id)!!).toMap()

    private fun asrEntry(sink: ContributionRetentionSink, id: UUID) = stagesOf(sink, id).getValue(StageName.Asr)

    @Test
    fun `a recognized result carries the INVOKED model - not the selection`() {
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = context()
        retain(sink, ctx)
        // The engine freezes the INVOKED backend's verified identity at the transcribe call.
        assertTrue(sink.noteAsrInvocation(ctx.captureId, ModelRef.Named("sense-voice", "2024-07-17")))
        assertTrue(sink.joinOutcome(CollectionOutcome.Recognized(ctx.captureId, "skip this ad", "en")))

        val sealed = sink.sealedSnapshotForTest(ctx.captureId)!!
        assertEquals(ModelRef.Named("sense-voice", "2024-07-17"), sealed.asrInvocationModel)
        assertEquals(StageState.Completed, asrEntry(sink, ctx.captureId).state)
    }

    @Test
    fun `a genuinely empty recognition is completed empty_result and still carries the invoked model`() {
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = context()
        retain(sink, ctx)
        assertTrue(sink.noteAsrInvocation(ctx.captureId, ModelRef.Named("sense-voice", "2024-07-17")))
        // The engine joins NotCollected("empty") ONLY for a blank RAW result.
        assertTrue(sink.joinOutcome(CollectionOutcome.NotCollected(ctx.captureId, "empty")))

        val asr = asrEntry(sink, ctx.captureId)
        assertEquals(StageState.Completed, asr.state)
        assertEquals("empty_result", (asr.fields["reason"] as? au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode.Str)?.value)
        assertNull((asr.fields["text"]))
        // The record still claims the invoked model (invocation evidence is independent of result).
        assertEquals(ModelRef.Named("sense-voice", "2024-07-17"), sink.sealedSnapshotForTest(ctx.captureId)!!.asrInvocationModel)
    }

    @Test
    fun `an empty result WITHOUT an invocation claims no model and never empty_result`() {
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = context()
        retain(sink, ctx)
        assertTrue(sink.joinOutcome(CollectionOutcome.NotCollected(ctx.captureId, "empty")))
        val asr = asrEntry(sink, ctx.captureId)
        assertEquals(StageState.Unknown, asr.state)
        assertNull(sink.sealedSnapshotForTest(ctx.captureId)!!.asrInvocationModel)
    }

    @Test
    fun `a provider failure is a known failed terminal - not a wait-expiry unknown`() {
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        var published: SealedCapture? = null
        sink.onReadyForPublication = { published = it }
        val ctx = context()
        retain(sink, ctx)
        assertTrue(sink.noteAsrInvocation(ctx.captureId, ModelRef.Named("sense-voice", "2024-07-17")))
        assertTrue(sink.joinOutcome(CollectionOutcome.AsrFailed(ctx.captureId)))

        // Publishable terminal: sealed EARLY with cause terminal (the wait never owns it).
        assertNotNull(published)
        assertEquals(SealedCapture.CAUSE_TERMINAL, published!!.cause)
        assertEquals(StageState.Failed, asrEntry(sink, ctx.captureId).state)
        assertEquals("provider_error", (asrEntry(sink, ctx.captureId).fields["reason"] as? au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode.Str)?.value)
        // Sealed exactly-once: the deadline finds it sealed and refuses.
        assertNull(sink.sealExpiredOutcome(ctx.captureId, sink.currentPurgeGeneration()))
    }

    @Test
    fun `cancellation is a nonterminal known fact - the stream stays open and no publication fires`() {
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        var published: SealedCapture? = null
        sink.onReadyForPublication = { published = it }
        val ctx = context()
        retain(sink, ctx)
        assertTrue(sink.noteAsrInvocation(ctx.captureId, ModelRef.Named("sense-voice", "2024-07-17")))
        assertTrue(sink.joinOutcome(CollectionOutcome.AsrCancelled(ctx.captureId)))

        // NO early publication; the bounded wait still owns finalization.
        assertNull(published)
        assertEquals(StageState.Cancelled, asrEntry(sink, ctx.captureId).state)
        assertEquals("cancelled_by_user", (asrEntry(sink, ctx.captureId).fields["reason"] as? au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode.Str)?.value)
    }

    @Test
    fun `a backend replaced between selection and invocation claims the INVOKED identity`() {
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = context() // selection: selected-but-never-run 0.0
        retain(sink, ctx)
        assertTrue(sink.noteAsrInvocation(ctx.captureId, ModelRef.Named("canary-flash", "2.0.0")))
        assertTrue(sink.joinOutcome(CollectionOutcome.Recognized(ctx.captureId, "play news", "de")))

        val sealed = sink.sealedSnapshotForTest(ctx.captureId)!!
        assertEquals(ModelRef.Named("canary-flash", "2.0.0"), sealed.asrInvocationModel)
        // The selection value appears NOWHERE in the claimed models.
        assertTrue(sealed.context.asrModel != sealed.asrInvocationModel)
    }

    @Test
    fun `a wake-rejected capture is not_run rejected_by_wake - never provider_error`() {
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = context()
        retain(sink, ctx)
        assertTrue(sink.joinOutcome(CollectionOutcome.NotCollected(ctx.captureId, "rejected_by_wake")))
        val asr = asrEntry(sink, ctx.captureId)
        assertEquals(StageState.NotRun, asr.state)
        assertEquals("rejected_by_wake", (asr.fields["reason"] as? au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode.Str)?.value)
    }

    @Test
    fun `a bleed-rejected capture is not_run rejected_by_bleed beside the observed bleed decision`() {
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = context()
        retain(sink, ctx)
        // The engine joins NotCollected("bleed") when the correlator rejects the segment.
        assertTrue(sink.joinOutcome(CollectionOutcome.NotCollected(ctx.captureId, "bleed")))
        val asr = asrEntry(sink, ctx.captureId)
        assertEquals(StageState.NotRun, asr.state)
        assertEquals("rejected_by_bleed", (asr.fields["reason"] as? au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode.Str)?.value)
        // The bleed observation itself is the completed/overlap fact.
        val bleed = stagesOf(sink, ctx.captureId).getValue(StageName.Bleed)
        assertEquals(StageState.Completed, bleed.state)
        assertEquals("overlap", (bleed.fields["decision"] as? au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode.Str)?.value)
    }

    @Test
    fun `an unrouted capture records the recognizer's OWN decision - and missing facts stay unknown`() {
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = context()
        retain(sink, ctx)
        // The router RAN and reported its own reason.
        assertTrue(sink.noteRouterInvocation(ctx.captureId, ModelRef.Named("lfm", "1.4.2")))
        assertTrue(sink.joinOutcome(CollectionOutcome.RoutingNone(ctx.captureId, "no_candidates")))
        val routing = stagesOf(sink, ctx.captureId).getValue(StageName.Routing)
        assertEquals(StageState.NotRun, routing.state)
        assertEquals("no_candidates", (routing.fields["reason"] as? au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode.Str)?.value)
        assertEquals(ModelRef.Named("lfm", "1.4.2"), sink.sealedSnapshotForTest(ctx.captureId)!!.routerInvocationModel)

        // A capture where routing never ran: honest UNKNOWN — never an invented no_candidates.
        val sink2 = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx2 = context()
        retain(sink2, ctx2)
        assertTrue(sink2.joinOutcome(CollectionOutcome.Recognized(ctx2.captureId, "what time is it", "en")))
        val routing2 = stagesOf(sink2, ctx2.captureId).getValue(StageName.Routing)
        assertEquals(StageState.Unknown, routing2.state)
        assertNull(sink2.sealedSnapshotForTest(ctx2.captureId)!!.routerInvocationModel)
    }

    @Test
    fun `a non-vocabulary recognizer reason stays unknown - never minted into a reason`() {
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = context()
        retain(sink, ctx)
        assertTrue(sink.joinOutcome(CollectionOutcome.RoutingNone(ctx.captureId, "some_future_reason")))
        val routing = stagesOf(sink, ctx.captureId).getValue(StageName.Routing)
        assertEquals(StageState.Unknown, routing.state)
    }

    @Test
    fun `translation known skips are not_run facts and a run-and-fail is a known failure`() {
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = context()
        retain(sink, ctx)
        assertTrue(sink.joinOutcome(CollectionOutcome.Recognized(ctx.captureId, "播放音乐", "yue")))
        // Backend handled translation in the same call: the SEPARATE stage is a known skip.
        assertTrue(
            sink.noteTranslation(
                ctx.captureId,
                TranslationProvenance(model = ModelRef.None, producedResult = false, translatedText = null, targetLanguage = null, skipReason = "backend_handles_translation"),
            ),
        )
        val translation = stagesOf(sink, ctx.captureId).getValue(StageName.Translation)
        assertEquals(StageState.NotRun, translation.state)
        assertEquals("backend_handles_translation", (translation.fields["reason"] as? au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode.Str)?.value)

        // A stage that RAN and returned invalid output is a known FAILURE — not a skip/unknown.
        val sink2 = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx2 = context()
        retain(sink2, ctx2)
        assertTrue(sink2.joinOutcome(CollectionOutcome.Recognized(ctx2.captureId, "播放音乐", "yue")))
        assertTrue(
            sink2.noteTranslation(
                ctx2.captureId,
                TranslationProvenance(model = ModelRef.None, producedResult = false, translatedText = null, targetLanguage = null, failedWith = "provider_error"),
            ),
        )
        val translation2 = stagesOf(sink2, ctx2.captureId).getValue(StageName.Translation)
        assertEquals(StageState.Failed, translation2.state)
        assertEquals("provider_error", (translation2.fields["reason"] as? au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode.Str)?.value)
    }

    @Test
    fun `the engine's bleed-skip mapping is the spec pairing (whole-record validator control lives in ContributionRecordTest)`() {
        val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
        val ctx = context()
        retain(sink, ctx)
        assertTrue(sink.joinOutcome(CollectionOutcome.NotCollected(ctx.captureId, "bleed")))
        val stages = EngineStageProvenance.stagesFor(sink.sealedSnapshotForTest(ctx.captureId)!!)
        // The mapping produced the spec pairing: asr not_run/rejected_by_bleed beside the
        // bleed stage's own completed/overlap observation. The whole-record builder/recovery
        // positive and wrong-stage/state negatives are asserted through the REAL
        // RecordValidator in ContributionRecordTest `rejected_by_bleed round-trips...`.
        val asr = stages.toMap().getValue(StageName.Asr)
        assertEquals(StageState.NotRun, asr.state)
        assertEquals("rejected_by_bleed", (asr.fields["reason"] as? au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode.Str)?.value)
        val bleed = stages.toMap().getValue(StageName.Bleed)
        assertEquals(StageState.Completed, bleed.state)
        assertEquals("overlap", (bleed.fields["decision"] as? au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode.Str)?.value)
    }
}
