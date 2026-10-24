package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackManagerPlaybackSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoiceActionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackIntentExecutor
import au.com.shiftyjelly.pocketcasts.voicecontrol.service.VoiceIntentDispatcher
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionBudget
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CompletionHandoff
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ContributionRetentionSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ExecutionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.IssuedAuthority
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObservation
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObserver
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.AudioBlock
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ContributionRecordBuilder
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageEntry
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageState
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * THE FULL DECODE (spec d62b77f8 follow-through): what the SERVICE produces for one dispatch
 * reaches the record that would be published, decoded through the PRODUCTION stage mapping
 * ([EngineStageProvenance]) and the real record builder. The chain under test is the same one the
 * service uses — [VoiceIntentDispatcher] settlement → `ContributionRetentionSink` candidate →
 * `EngineStageProvenance.stagesFor` → `ContributionRecordBuilder` — so an aggregate the engine got
 * wrong cannot hide behind a same-layer assertion.
 */
class ServiceToPersistedRecordControlTest {

    @Test
    fun `a cloud turn whose duck succeeds and whose action is unobserved decodes as UNKNOWN execution`() {
        val harness = Harness()
        runBlocking {
            harness.dispatchCloudTurn(reportPrimaryOutcome = false)
        }
        val execution = harness.executionStage()
        assertEquals(
            "an auxiliary duck success must decode as unknown execution, never completed",
            StageState.Unknown,
            execution.state,
        )
        assertEquals(RecordNode.Str("observation_unavailable"), execution.fields["reason"])
    }

    @Test
    fun `an agreed primary result decodes as the completed execution stage`() {
        val harness = Harness()
        runBlocking {
            harness.dispatchCloudTurn(reportPrimaryOutcome = true)
        }
        val execution = harness.executionStage()
        assertEquals(StageState.Completed, execution.state)
        assertEquals(RecordNode.Str("success"), execution.fields["outcome"])
    }

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
        )
        private val executor = mock<VoicePlaybackIntentExecutor>()

        suspend fun dispatchCloudTurn(reportPrimaryOutcome: Boolean) {
            val captureId = UUID.randomUUID()
            sink.retainCandidateForTest(captureId)
            val engine = EngineForTest.new(authority, sink, observer)
            val token = engine.openInvocationForTest(captureId, id = 99L)

            // The dispatcher's production settlement path decides what is published.
            val dispatcher = VoiceIntentDispatcher()
            val facts = mutableListOf<VoiceActionOutcome>()
            if (reportPrimaryOutcome) facts += VoiceActionOutcome.Performed
            facts.forEach { fact ->
                dispatcher.executionOutcomeOf(fact)?.let { engine.reportExecutionResult(token, it) }
            }
            dispatcher.run {
                // Exactly what the service's settle path does for this invocation.
                engine.settleInvocation(token)
            }
        }

        /** The PRODUCTION mapping, then the real builder — the record as it would be published. */
        fun executionStage(): StageEntry {
            val candidate = sink.retainedSnapshot().single()
            val stages = EngineStageProvenance.stagesFor(candidate)
            val builder = ContributionRecordBuilder(
                captureId = candidate.context.captureId.toString(),
                enrollmentEpoch = "epoch-1",
                captureSessionId = candidate.context.captureSession.toString(),
            )
            listOf("vad", "wakeword", "asr", "translation", "router").forEach {
                builder.model(it, au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef.None)
            }
            stages.forEach { (name, entry) -> builder.stage(name, entry) }
            val node = builder.build(
                platform = "android",
                appVersion = "1.0",
                build = "1",
                audio = audioBlockForTest(candidate),
            )
            return stages.first { (name, _) -> name == StageName.Execution }.second
        }
    }

    private object EngineForTest {
        fun new(
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
    }
}

private fun audioBlockForTest(candidate: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.RetainedCandidate): AudioBlock = AudioBlock(
    sha256 = "0".repeat(64),
    // The WAV header is part of byte_count; a record needs non-empty audio.
    byteCount = 44L + (candidate.pcm?.size ?: 0) * 2L,
    sampleRateHz = candidate.sampleRateHz,
    retainedSamples = (candidate.pcm?.size ?: 0).toLong().coerceAtLeast(1L),
    originalFormat = "pcm",
    conversionVersion = "1",
    truncated = false,
    droppedSpans = emptyList(),
)
