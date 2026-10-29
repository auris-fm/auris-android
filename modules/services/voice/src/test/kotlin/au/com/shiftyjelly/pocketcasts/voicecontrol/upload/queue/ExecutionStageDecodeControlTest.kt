package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoiceActionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackIntentExecutor
import au.com.shiftyjelly.pocketcasts.voicecontrol.service.VoiceIntentDispatcher
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance.PrepaidTestAllowance
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
            handoff = CompletionHandoff { context, onset, end, wake, provider, scope, live ->
                scope.completeUnderOwner(context, onset, end, wake, provider)
            },
            allowance = PrepaidTestAllowance(),
        )
        private val executor = mock<VoicePlaybackIntentExecutor>()

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

        /** Decoded from the SERIALIZED record: the bytes that would be published. */
        // The Execution stage's own recorded fields, read out of the canonicalised record.
        fun builtExecutionStage(): Map<String, RecordNode> {
            val candidate = sink.retainedSnapshot().single()
            val builder = ContributionRecordBuilder(
                captureId = candidate.context.captureId.toString(),
                enrollmentEpoch = "epoch-1",
                captureSessionId = candidate.context.captureSession.toString(),
            )
            MODEL_KEYS.forEach { builder.model(it, ModelRef.None) }
            EngineStageProvenance.stagesFor(candidate).forEach { (name, entry) -> builder.stage(name, entry) }
            val record = builder.build(
                platform = "android",
                appVersion = "1.0",
                build = "1",
                audio = audioBlockFor(candidate),
            )
            // Round-trip through canonical JSON so the assertion reads the recorded bytes, not the
            // in-memory entry the builder was handed.
            val parsed = CanonicalJson.parse(CanonicalJson.serialize(record))
            // The record stores stages under "observations", one object per stage name, with the
            // state and the stage's own fields flat inside it.
            val observations = (parsed as RecordNode.Obj).fields["observations"] as RecordNode.Obj
            val execution = observations.fields["execution"] as RecordNode.Obj
            return execution.fields
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
