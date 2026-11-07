package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoiceActionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackIntentExecutor
import au.com.shiftyjelly.pocketcasts.voicecontrol.service.VoiceIntentDispatcher
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionBudget
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CompletionHandoff
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ContributionRetentionSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.IssuedAuthority
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObserver
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.AudioBlock
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.CanonicalJson
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ContributionRecordBuilder
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * SYNTHETIC SETTLEMENT → STAGE-MAPPING → BUILT-RECORD decode control.
 *
 * BOUNDARY (spec db897b24, reviewer f75f9229): this is NOT the Service → dispatch → settlement →
 * publication composition. No Service is started, no dispatch is issued, and nothing is published;
 * the candidate is retained through a test seam and the invocation is test-opened. What it does
 * prove is the layer above the aggregate: the facts the dispatcher maps reach the engine's
 * settlement, and the EXECUTION stage is read back out of the BUILT (canonicalised) record — so a
 * dishonest mapping between aggregate and recorded bytes is caught here.
 */
class ExecutionStageDecodeControlTest {

    @Test
    fun `an auxiliary duck success with the primary unobserved records UNKNOWN execution`() {
        val harness = Harness()
        runBlocking {
            // REAL auxiliary fact (the duck succeeded) plus the primary's explicit unknown. The
            // auxiliary success must not be recorded as this turn's outcome.
            harness.dispatchCloudTurn(
                VoiceActionOutcome.AuxiliaryPerformed("duck"),
                VoiceActionOutcome.NotObserved,
            )
        }
        val execution = harness.builtExecutionStage()
        assertTrue(
            "an auxiliary duck success must record unknown execution, was ${execution["state"]}",
            execution["state"] == RecordNode.Str("unknown"),
        )
        assertTrue(
            "reason must be observation_unavailable, was ${execution["reason"]}",
            execution["reason"] == RecordNode.Str("observation_unavailable"),
        )
    }

    @Test
    fun `an agreed primary result records the completed execution stage`() {
        val harness = Harness()
        runBlocking { harness.dispatchCloudTurn(VoiceActionOutcome.Performed) }
        val execution = harness.builtExecutionStage()
        assertTrue("was ${execution["state"]}", execution["state"] == RecordNode.Str("completed"))
        assertTrue("was ${execution["outcome"]}", execution["outcome"] == RecordNode.Str("success"))
    }

    /**
     * SPEC 21b5d822, option (b): EVERY observed execution carries `outcome` from the wire vocabulary
     * `success`/`failure`/`refused`/`cancelled`; failed and cancelled stages KEEP their `reason`, and
     * an unobserved execution still omits `outcome` entirely.
     *
     * Before this, only `success` was recorded as `outcome`; the other three were recorded as
     * `reason` alone, so a consumer reading `outcome` saw nothing for a failed, refused or cancelled
     * action.
     *
     * SCOPE (reviewer eeb5345e): these read an IN-MEMORY canonical build. The candidate is retained
     * through a test seam and the invocation is test-opened; nothing is published and no durable
     * entry is read. What IS real is the dispatcher's own mapping and the engine's settlement. The
     * DURABLE shape is asserted separately in `EngineToProducerPublishTest`, which goes through the
     * trigger, producer and store to the on-disk metadata file.
     */
    @Test
    fun `a failed execution records outcome failure and keeps its reason`() {
        val harness = Harness()
        runBlocking { harness.dispatchCloudTurn(VoiceActionOutcome.Failed(reason = "boom")) }
        val execution = harness.builtExecutionStage()
        assertTrue("was ${execution["state"]}", execution["state"] == RecordNode.Str("failed"))
        assertTrue("outcome must be failure, was ${execution["outcome"]}", execution["outcome"] == RecordNode.Str("failure"))
        assertTrue("reason must be kept, was ${execution["reason"]}", execution["reason"] == RecordNode.Str("action_failed"))
    }

    @Test
    fun `a refused execution records outcome refused and keeps its reason`() {
        val harness = Harness()
        runBlocking { harness.dispatchCloudTurn(VoiceActionOutcome.Refused(reason = "nope")) }
        val execution = harness.builtExecutionStage()
        assertTrue("was ${execution["state"]}", execution["state"] == RecordNode.Str("failed"))
        assertTrue("outcome must be refused, was ${execution["outcome"]}", execution["outcome"] == RecordNode.Str("refused"))
        assertTrue("reason must be kept, was ${execution["reason"]}", execution["reason"] == RecordNode.Str("client_refused"))
    }

    @Test
    fun `an unobserved execution still omits outcome`() {
        val harness = Harness()
        runBlocking { harness.dispatchCloudTurn(VoiceActionOutcome.NotObserved) }
        val execution = harness.builtExecutionStage()
        assertTrue("was ${execution["state"]}", execution["state"] == RecordNode.Str("unknown"))
        assertTrue("unknown must omit outcome, was ${execution["outcome"]}", execution["outcome"] == null)
    }

    /**
     * CANCELLED has no dispatcher mapping — [VoiceActionOutcome] has no cancelled variant — but the
     * engine accepts any `ExecutionOutcome` from its executor at [reportExecutionResult], so the
     * cancelled wire value is REACHABLE and must not go unrecorded. This drives that real engine
     * entry point rather than only the dispatcher, which is why it is a separate harness call.
     */
    @Test
    fun `a cancelled execution records outcome cancelled and keeps its reason`() {
        val harness = Harness()
        runBlocking { harness.dispatchCancelledTurn() }
        val execution = harness.builtExecutionStage()
        assertTrue("was ${execution["state"]}", execution["state"] == RecordNode.Str("cancelled"))
        assertTrue("outcome must be cancelled, was ${execution["outcome"]}", execution["outcome"] == RecordNode.Str("cancelled"))
        assertTrue("reason must be kept, was ${execution["reason"]}", execution["reason"] == RecordNode.Str("cancelled_by_user"))
    }

    /**
     * PARITY FIXTURE EMITTER (spec 2d5f764a). The canonical vector from `CanonicalRecordVectorRecipeTest`
     * carries no execution stage, so Cloud's parity check cannot see `observations.execution.outcome`
     * in it. This writes a record that DOES carry one, produced by the real dispatcher mapping from a
     * refused action, so the parity check sees `outcome:"refused"` beside `reason:"client_refused"`.
     *
     * It asserts the emitted bytes contain that shape before writing, so a fixture cannot be produced
     * that silently lacks the field it exists to demonstrate.
     */
    @Test
    fun `emit the parity record carrying a refused execution`() {
        val harness = Harness()
        runBlocking { harness.dispatchCloudTurn(VoiceActionOutcome.Refused(reason = "nope")) }
        val json = harness.builtRecordJson()
        assertTrue("the emitted record must carry outcome refused", json.contains("\"outcome\":\"refused\""))
        assertTrue("the emitted record must keep reason client_refused", json.contains("\"reason\":\"client_refused\""))

        val dir = java.io.File("/tmp/p1run/parity_execution_outcome")
        dir.mkdirs()
        java.io.File(dir, "record.json").writeText(json)
        java.io.File(dir, "sha256.txt").writeText(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(json.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) },
        )
    }

    /** The built record is the assertion target: nothing here reads the pre-build stage list. */
    private class Harness {
        private val authority = {
            au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision(false, true)
        }
        val sink = ContributionRetentionSink(CollectionBudget(1L shl 16))
        private val observer = SegmentObserver(
            CollectionGate { false },
            sink,
            authority,
            issueAuthority = { IssuedAuthority("a@b.c", 1L, 1L, "epoch-1", "disclosure-1") },
            handoff = CompletionHandoff { context, onset, end, wake, provider, scope, live, measurement ->
                scope.completeUnderOwner(context, onset, end, wake, provider, wakeMeasurement = measurement)
            },
        )
        private val executor = mock<VoicePlaybackIntentExecutor>()

        /**
         * CANCELLED comes from no [VoiceActionOutcome]; it is reported through the same real engine
         * entry point the dispatcher uses, which accepts any `ExecutionOutcome`.
         */
        suspend fun dispatchCancelledTurn() {
            val captureId = UUID.randomUUID()
            sink.retainCandidateForTest(captureId)
            val engine = newEngine(authority, sink, observer)
            val token = engine.openInvocationForTest(captureId, id = 100L)
            engine.reportExecutionResult(
                token,
                au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ExecutionOutcome.Cancelled,
            )
            engine.settleInvocation(token)
        }

        suspend fun dispatchCloudTurn(vararg facts: VoiceActionOutcome): String {
            val captureId = UUID.randomUUID()
            sink.retainCandidateForTest(captureId)
            val engine = newEngine(authority, sink, observer)
            val token = engine.openInvocationForTest(captureId, id = 99L)
            // The dispatcher's mapping is the ONLY translator between a reported fact and the
            // engine's observation vocabulary; using it keeps this honest to production.
            val dispatcher = VoiceIntentDispatcher()
            facts.forEach { fact ->
                dispatcher.executionOutcomeOf(fact)?.let { engine.reportExecutionResult(token, it) }
            }
            engine.settleInvocation(token)
            return captureId.toString()
        }

        // The whole canonical record, serialized: the bytes Cloud's intake would receive.
        fun builtRecordJson(): String {
            val record = buildRecord()
            return CanonicalJson.serialize(record)
        }

        // The Execution stage's own fields, decoded from the SERIALIZED record: the bytes that
        // would be published, not the in-memory entry the builder was handed.
        fun builtExecutionStage(): Map<String, RecordNode> {
            val parsed = CanonicalJson.parse(CanonicalJson.serialize(buildRecord()))
            val observations = (parsed as RecordNode.Obj).fields["observations"] as RecordNode.Obj
            val execution = observations.fields["execution"] as RecordNode.Obj
            return execution.fields
        }

        private fun buildRecord(): au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode {
            val live = sink.retainedSnapshot().single()
            // BATCH B: the provenance mapper now reads the immutable SealedCapture; a live
            // candidate is snapshotted with the early-completion cause (pre-Batch-B typing).
            val candidate = sink.sealedSnapshotForTest(live.context.captureId)!!
            val builder = ContributionRecordBuilder(
                captureId = candidate.context.captureId.toString(),
                enrollmentEpoch = "epoch-1",
                captureSessionId = candidate.context.captureSession.toString(),
            )
            MODEL_KEYS.forEach { builder.model(it, ModelRef.None) }
            EngineStageProvenance.stagesFor(candidate).forEach { (name, entry) -> builder.stage(name, entry) }
            builder.wakeRequired(true)
            val record = builder.build(
                platform = "android",
                appVersion = "1.0",
                build = "1",
                audio = audioBlockFor(live),
            )
            return record
        }
    }
}

private val MODEL_KEYS = listOf("vad", "wakeword", "asr", "translation", "router")

private fun newEngine(
    authority: () -> au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision,
    sink: ContributionRetentionSink,
    observer: SegmentObserver,
) = au.com.shiftyjelly.pocketcasts.voicecontrol.engine.VoiceAsrEngine(
    voiceAudioProcessor = mock(),
    utteranceFilter = mock(),
    intentRecognizer = mock(),
    wakeWordDetector = mock(),
    gracePeriodSignal = mock(),
    audioFeedbackRenderer = mock(),
    translationStage = mock(),
    collectionAuthority = authority,
    retentionSink = sink,
    segmentObserver = observer,
    context = mock(),
)

private fun audioBlockFor(candidate: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.RetainedCandidate): AudioBlock = AudioBlock(
    sha256 = "0".repeat(64),
    // The canonical record requires non-empty audio; the WAV header counts toward byte_count.
    byteCount = 44L + (candidate.pcm?.size ?: 0) * 2L,
    sampleRateHz = 16000,
    retainedSamples = (candidate.pcm?.size ?: 0).toLong().coerceAtLeast(1L),
    originalFormat = "pcm",
    conversionVersion = "1",
    truncated = false,
    droppedSpans = emptyList(),
)
